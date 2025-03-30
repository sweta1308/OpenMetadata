package org.openmetadata.service.resources.mcp;

import static org.openmetadata.service.search.SearchUtil.mapEntityTypesToIndexNames;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.StreamingOutput;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.common.utils.CommonUtil;
import org.openmetadata.schema.api.mcp.MCPToolDefinition;
import org.openmetadata.schema.api.mcp.ToolExecutionRequest;
import org.openmetadata.schema.api.mcp.ToolExecutionResponse;
import org.openmetadata.schema.search.SearchRequest;
import org.openmetadata.service.Entity;
import org.openmetadata.service.OpenMetadataApplicationConfig;
import org.openmetadata.service.config.MCPConfiguration;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.resources.Collection;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.socket.MCPWebSocketConfigurator;
import org.openmetadata.service.socket.MCPWebSocketManager;
import org.openmetadata.service.util.EntityUtil;
import org.openmetadata.service.util.JsonUtils;

@Slf4j
@Path("/v1/mcp")
@Tag(name = "Model Context Protocol", description = "APIs for integrating LLMs with OpenMetadata")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Collection(name = "mcp")
public class MCPResource {
  private final SearchRepository searchRepository;
  private final Map<String, MCPToolWithHandler> toolHandlers = new HashMap<>();
  private final Authorizer authorizer;
  private MCPConfiguration mcpConfiguration;

  private static final String MCP_TOOLS_FILE = ".*json/data/mcp/tools.json$";

  private static class MCPToolWithHandler {
    final MCPToolDefinition toolDefinition;
    final Function<JsonNode, JsonNode> handler;

    MCPToolWithHandler(MCPToolDefinition toolDefinition, Function<JsonNode, JsonNode> handler) {
      this.toolDefinition = toolDefinition;
      this.handler = handler;
    }
  }
  
  /**
   * MCP message types for JSON-RPC style communication
   */
  public enum MCPMessageType {
    INITIALIZE("initialize"),
    EXECUTE_FUNCTION("executeFunction"),
    FUNCTION_RESPONSE("functionResponse"),
    ERROR("error");
    
    private final String value;
    
    MCPMessageType(String value) {
      this.value = value;
    }
    
    public String getValue() {
      return value;
    }
  }
  
  /**
   * Data classes for MCP protocol messages
   */
  @Data
  @AllArgsConstructor
  public static class MCPMessage {
    private String type;
    private JsonNode data;
  }
  
  @Data
  public static class InitializeRequestData {
    private String clientIdentifier;
    private String clientVersion;
  }
  
  @Data
  public static class InitializeResponseData {
    private String serverIdentifier = "openmetadata-mcp";
    private String serverVersion = "1.0";
    private List<MCPToolDefinition> tools;
  }
  
  @Data
  public static class ExecuteFunctionRequestData {
    private String name;
    private JsonNode parameters;
  }
  
  @Data
  public static class FunctionResponseData {
    private String name;
    private JsonNode result;
  }
  
  @Data
  public static class ErrorData {
    private String message;
    private String code;
  }
  
  /**
   * JSON-RPC 2.0 classes
   */
  @Data
  public static class JsonRpcRequest {
    private String jsonrpc = "2.0";
    private String method;
    private JsonNode params;
    private String id;
  }
  
  @Data
  public static class JsonRpcResponse {
    private String jsonrpc = "2.0";
    private JsonNode result;
    private JsonNode error;
    private String id;
    
    public static JsonRpcResponse success(String id, JsonNode result) {
      JsonRpcResponse response = new JsonRpcResponse();
      response.setId(id);
      response.setResult(result);
      return response;
    }
    
    public static JsonRpcResponse error(String id, int code, String message) {
      JsonRpcResponse response = new JsonRpcResponse();
      response.setId(id);
      ObjectNode error = JsonUtils.getObjectNode();
      error.put("code", code);
      error.put("message", message);
      response.setError(error);
      return response;
    }
  }

  public MCPResource(Authorizer authorizer) {
    this.searchRepository = Entity.getSearchRepository();
    this.authorizer = authorizer;
    loadToolDefinitions();
  }

  public void initialize(OpenMetadataApplicationConfig config) throws IOException {
    loadToolDefinitions();
    if (config.getMcpConfiguration() != null && config.getMcpConfiguration().isEnabled()) {
      this.mcpConfiguration = config.getMcpConfiguration();
      MCPWebSocketConfigurator.initialize(this, this.mcpConfiguration);
      MCPWebSocketManager.MCPWebSocketManagerBuilder.build(this.mcpConfiguration);
      LOG.info("Initialized MCP resource with WebSocket support");
    }
  }

  private void loadToolDefinitions() {
    try {
      List<String> jsonDataFiles = EntityUtil.getJsonDataResources(MCP_TOOLS_FILE);
      if (!jsonDataFiles.isEmpty()) {
        String json =
            CommonUtil.getResourceAsStream(
                EntityRepository.class.getClassLoader(), jsonDataFiles.get(0));

        JsonNode toolsJson = JsonUtils.readTree(json);
        JsonNode toolsArray = toolsJson.get("tools");

        if (toolsArray == null || !toolsArray.isArray()) {
          LOG.error("Invalid MCP tools file format. Expected 'tools' array.");
          return;
        }

        for (JsonNode toolNode : toolsArray) {
          try {
            MCPToolDefinition toolDefinition =
                JsonUtils.convertValue(toolNode, MCPToolDefinition.class);
            switch (toolDefinition.getName()) {
              case "search_metadata":
                registerTool(toolDefinition, this::searchMetadata);
                break;
              case "get_entity_details":
                registerTool(toolDefinition, this::getEntityDetails);
                break;
              case "nlq_search":
                registerTool(toolDefinition, this::nlqSearch);
                break;
              case "advanced_search":
                registerTool(toolDefinition, this::advancedSearch);
                break;
              default:
                LOG.warn("Unknown tool definition: {}", toolDefinition.getName());
            }
          } catch (Exception e) {
            LOG.error("Error processing tool definition", e);
          }
        }

        LOG.info("Registered {} MCP tools", toolHandlers.size());
      }
    } catch (Exception e) {
      LOG.error("Error loading MCP tool definitions", e);
    }
  }

  private void registerTool(
      MCPToolDefinition toolDefinition, Function<JsonNode, JsonNode> handler) {
    toolHandlers.put(toolDefinition.getName(), new MCPToolWithHandler(toolDefinition, handler));
  }

  @GET
  @Path("/tools")
  @Operation(
      operationId = "getAvailableTools",
      summary = "Get available tools for LLM integration",
      description =
          "Get the schemas for all available tools that LLMs can use to interact with OpenMetadata",
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "Available tools",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = JsonNode.class)))
      })
  public Response getAvailableTools() {
    ObjectNode result = JsonUtils.getObjectNode();
    ArrayNode toolSchemas = JsonUtils.getArrayNode();

    for (MCPToolWithHandler handler : toolHandlers.values()) {
      toolSchemas.add(JsonUtils.valueToTree(handler.toolDefinition));
    }

    result.set("tools", toolSchemas);
    return Response.ok(result).build();
  }

  @POST
  @Path("/execute")
  @Operation(
      operationId = "executeTool",
      summary = "Execute a tool",
      description = "Execute a specific OpenMetadata tool with the provided parameters",
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "Tool execution result",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ToolExecutionResponse.class)))
      })
  public Response executeTool(
      @Context SecurityContext securityContext, ToolExecutionRequest request) {
    try {
      MCPToolWithHandler toolWithHandler = toolHandlers.get(request.getTool());
      if (toolWithHandler == null) {
        ToolExecutionResponse errorResponse = new ToolExecutionResponse();
        errorResponse.setStatus(ToolExecutionResponse.Status.ERROR);
        errorResponse.setMessage("Tool not found: " + request.getTool());
        return Response.status(Response.Status.BAD_REQUEST).entity(errorResponse).build();
      }

      JsonNode result =
          toolWithHandler.handler.apply(JsonUtils.valueToTree(request.getParameters()));

      ToolExecutionResponse response = new ToolExecutionResponse();
      response.setStatus(ToolExecutionResponse.Status.SUCCESS);

      response.setResult(result);

      return Response.ok(response).build();
    } catch (Exception e) {
      LOG.error("Error executing tool", e);
      ToolExecutionResponse errorResponse = new ToolExecutionResponse();
      errorResponse.setStatus(ToolExecutionResponse.Status.ERROR);
      errorResponse.setMessage(e.getMessage());
      return Response.status(Response.Status.BAD_REQUEST).entity(errorResponse).build();
    }
  }

  /**
   * Handle initialize request from MCP WebSocket.
   * 
   * @param data Request data
   * @return Response data as JsonNode
   */
  public JsonNode handleInitialize(JsonNode data) {
    try {
      InitializeRequestData initRequest = JsonUtils.convertValue(data, InitializeRequestData.class);
      LOG.info("Client connected: {} v{}", initRequest.getClientIdentifier(), initRequest.getClientVersion());
      
      // Prepare response with available tools
      InitializeResponseData responseData = new InitializeResponseData();
      responseData.setTools(toolHandlers.values().stream()
          .map(handler -> handler.toolDefinition)
          .toList());
      
      return JsonUtils.valueToTree(responseData);
    } catch (Exception e) {
      LOG.error("Error handling initialize request", e);
      ObjectNode error = JsonUtils.getObjectNode();
      error.put("error", e.getMessage());
      return error;
    }
  }
  
  /**
   * Handle execute function request from MCP WebSocket.
   * 
   * @param data Request data
   * @return Response data as JsonNode
   */
  public JsonNode handleExecuteFunction(JsonNode data) {
    try {
      ExecuteFunctionRequestData request = JsonUtils.convertValue(data, ExecuteFunctionRequestData.class);
      String toolName = request.getName();
      JsonNode parameters = request.getParameters();
      
      LOG.info("Executing function: {}", toolName);
      
      MCPToolWithHandler toolWithHandler = toolHandlers.get(toolName);
      if (toolWithHandler == null) {
        ObjectNode error = JsonUtils.getObjectNode();
        error.put("error", "Unknown tool: " + toolName);
        error.put("code", "UNKNOWN_TOOL");
        return error;
      }
      
      // Execute the tool
      JsonNode result = toolWithHandler.handler.apply(parameters);
      
      // Prepare response
      FunctionResponseData responseData = new FunctionResponseData();
      responseData.setName(toolName);
      responseData.setResult(result);
      
      return JsonUtils.valueToTree(responseData);
    } catch (Exception e) {
      LOG.error("Error handling execute function request", e);
      ObjectNode error = JsonUtils.getObjectNode();
      error.put("error", e.getMessage());
      error.put("code", "EXECUTION_ERROR");
      return error;
    }
  }

  private JsonNode searchMetadata(JsonNode params) {
    try {
      String query = params.has("query") ? params.get("query").asText() : "*";
      int limit = params.has("limit") ? params.get("limit").asInt() : 10;
      boolean includeDeleted =
          params.has("include_deleted") && params.get("include_deleted").asBoolean();
      String entityType = params.has("entity_type") ? params.get("entity_type").asText() : null;
      String index = entityType != null ? mapEntityTypesToIndexNames(entityType) : null;

      SearchRequest searchRequest =
          new SearchRequest()
              .withQuery(query)
              .withIndex(index)
              .withSize(limit)
              .withFrom(0)
              .withFetchSource(true)
              .withDeleted(includeDeleted);

      Response response = searchRepository.search(searchRequest, null);
      if (response.getEntity() instanceof String responseStr) {
        return JsonUtils.readTree(responseStr);
      } else {
        return JsonUtils.valueToTree(response.getEntity());
      }
    } catch (Exception e) {
      LOG.error("Error in searchMetadata", e);
      ObjectNode error = JsonUtils.getObjectNode();
      error.put("error", e.getMessage());
      return error;
    }
  }

  private JsonNode getEntityDetails(JsonNode params) {
    try {
      String entityType = params.get("entity_type").asText();
      String fqn = params.get("fqn").asText();
      EntityRepository<?> repository = Entity.getEntityRepository(entityType);
      Object entity = repository.getByName(null, fqn, null);

      return JsonUtils.valueToTree(entity);
    } catch (Exception e) {
      LOG.error("Error in getEntityDetails", e);
      ObjectNode error = JsonUtils.getObjectNode();
      error.put("error", e.getMessage());
      return error;
    }
  }

  private JsonNode nlqSearch(JsonNode params) {
    try {
      String query = params.get("query").asText();
      int limit = params.has("limit") ? params.get("limit").asInt() : 10;
      String entityType = params.has("entity_type") ? params.get("entity_type").asText() : "table";
      String index = mapEntityTypesToIndexNames(entityType);

      SearchRequest searchRequest =
          new SearchRequest()
              .withQuery(query)
              .withIndex(index)
              .withSize(limit)
              .withFrom(0)
              .withFetchSource(true);

      Response response = searchRepository.searchWithNLQ(searchRequest, null);
      if (response.getEntity() instanceof String responseStr) {
        return JsonUtils.readTree(responseStr);
      } else {
        return JsonUtils.valueToTree(response.getEntity());
      }
    } catch (Exception e) {
      LOG.error("Error in nlqSearch", e);
      ObjectNode error = JsonUtils.getObjectNode();
      error.put("error", e.getMessage());
      return error;
    }
  }

  private JsonNode advancedSearch(JsonNode params) {
    try {
      String query = params.get("query").asText();
      int limit = params.has("limit") ? params.get("limit").asInt() : 10;

      String entityType = params.has("entity_type") ? params.get("entity_type").asText() : null;
      String index = entityType != null ? mapEntityTypesToIndexNames(entityType) : null;
      String sortField = params.has("sort_field") ? params.get("sort_field").asText() : "_score";
      String sortOrder = params.has("sort_order") ? params.get("sort_order").asText() : "desc";

      String queryFilter = null;
      if (params.has("filters") && params.get("filters").isObject()) {
        StringBuilder filterBuilder = new StringBuilder();
        JsonNode filters = params.get("filters");
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = filters.fields();

        while (fields.hasNext()) {
          Map.Entry<String, JsonNode> entry = fields.next();
          if (filterBuilder.length() > 0) {
            filterBuilder.append(" AND ");
          }
          filterBuilder.append(entry.getKey()).append(":");

          if (entry.getValue().isTextual()) {
            filterBuilder.append(entry.getValue().asText());
          } else if (entry.getValue().isArray()) {
            filterBuilder.append("(");
            ArrayNode array = (ArrayNode) entry.getValue();
            for (int i = 0; i < array.size(); i++) {
              if (i > 0) {
                filterBuilder.append(" OR ");
              }
              filterBuilder.append(array.get(i).asText());
            }
            filterBuilder.append(")");
          } else {
            filterBuilder.append(entry.getValue().toString());
          }
        }

        queryFilter = filterBuilder.toString();
      }

      SearchRequest searchRequest =
          new SearchRequest()
              .withQuery(query)
              .withIndex(index)
              .withSize(limit)
              .withFrom(0)
              .withFetchSource(true)
              .withSortFieldParam(sortField)
              .withSortOrder(sortOrder)
              .withQueryFilter(queryFilter);

      Response response = searchRepository.search(searchRequest, null);
      if (response.getEntity() instanceof String responseStr) {
        return JsonUtils.readTree(responseStr);
      } else {
        return JsonUtils.valueToTree(response.getEntity());
      }
    } catch (Exception e) {
      LOG.error("Error in advancedSearch", e);
      ObjectNode error = JsonUtils.getObjectNode();
      error.put("error", e.getMessage());
      return error;
    }
  }
  
  /**
   * MCP Server-Sent Events endpoint that implements the JSON-RPC 2.0 protocol
   * for Model Context Protocol communication.
   */
  @GET
  @Path("/sse")
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @Operation(
      operationId = "connectMCP",
      summary = "Connect to MCP via Server-Sent Events",
      description = "Establishes a Server-Sent Events connection for MCP communication using JSON-RPC 2.0",
      responses = {
          @ApiResponse(
              responseCode = "200",
              description = "SSE Stream established",
              content = @Content(mediaType = MediaType.SERVER_SENT_EVENTS))
      })
  public Response connectMCP(@Context SecurityContext securityContext) {
    StreamingOutput stream = output -> {
      Writer writer = new BufferedWriter(new OutputStreamWriter(output));
      
      try {
        // Send initial connection established message
        sendSseEvent(writer, "connected", "Connection established");
        
        // Keep connection alive
        int count = 0;
        while (true) {
          Thread.sleep(30000); // Send heartbeat every 30 seconds
          sendSseEvent(writer, "heartbeat", "heartbeat-" + count++);
        }
      } catch (Exception e) {
        LOG.error("Error in SSE stream", e);
        sendSseEvent(writer, "error", "Stream error: " + e.getMessage());
      }
    };
    
    return Response.ok(stream).build();
  }
  
  /**
   * Process a JSON-RPC request and send the response via SSE
   */
  @OPTIONS
  @Path("/jsonrpc")
  public Response optionsJsonRpc() {
    return Response.ok()
        .header("Access-Control-Allow-Origin", "*")
        .header("Access-Control-Allow-Methods", "POST, OPTIONS")
        .header("Access-Control-Allow-Headers", "Content-Type, Authorization, Claude-Request-Id, Claude-SDK-Agent")
        .header("Access-Control-Allow-Credentials", "true")
        .header("Access-Control-Max-Age", "3600")
        .build();
  }
  
  @POST
  @Path("/jsonrpc")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      operationId = "processJsonRpc",
      summary = "Process a JSON-RPC 2.0 request",
      description = "Processes a JSON-RPC 2.0 request for the Model Context Protocol",
      responses = {
          @ApiResponse(
              responseCode = "200",
              description = "JSON-RPC response",
              content = @Content(mediaType = MediaType.APPLICATION_JSON))
      })
  public Response processJsonRpc(@Context SecurityContext securityContext, JsonRpcRequest request) {
    try {
      if (!"2.0".equals(request.getJsonrpc())) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(JsonRpcResponse.error(request.getId(), -32600, "Invalid JSON-RPC 2.0 request"))
            .build();
      }
      
      String method = request.getMethod();
      String id = request.getId();
      JsonNode params = request.getParams();
      
      // Handle different method types
      switch (method) {
        case "initialize":
          // Initialize request
          LOG.info("Processing initialize request with id: {}", id);
          LOG.info("Initialize request params: {}", params);
          
          ObjectNode result = JsonUtils.getObjectNode();
          
          // Set server info exactly as Claude expects
          ObjectNode serverInfo = JsonUtils.getObjectNode();
          serverInfo.put("name", "openmetadata-mcp");
          serverInfo.put("version", "1.0.0");
          result.set("serverInfo", serverInfo);
          
          // Set capabilities exactly as Claude expects
          ObjectNode capabilities = JsonUtils.getObjectNode();
          capabilities.put("search", true);
          result.set("capabilities", capabilities);
          
          // Set protocol version - use the one from request if available
          if (params != null && params.has("protocolVersion")) {
            String protocolVersion = params.get("protocolVersion").asText();
            result.put("protocolVersion", protocolVersion);
            LOG.info("Using protocol version from client: {}", protocolVersion);
          } else {
            // Use exactly the version Claude sent in the initialize message
            result.put("protocolVersion", "2024-11-05");
            LOG.info("Using default protocol version: 2024-11-05");
          }
          
          // Add available tools/functions - make sure format is exactly as Claude expects
          ArrayNode functions = JsonUtils.getArrayNode();
          toolHandlers.values().forEach(handler -> {
            MCPToolDefinition toolDef = handler.toolDefinition;
            ObjectNode functionNode = JsonUtils.getObjectNode();
            functionNode.put("name", toolDef.getName());
            functionNode.put("description", toolDef.getDescription());
            functionNode.set("parameters", JsonUtils.valueToTree(toolDef.getParameters()));
            functions.add(functionNode);
          });
          result.set("functions", functions);
          
          LOG.info("Initialize response prepared with {} functions", functions.size());
          LOG.info("Initialize response content: {}", result);
          
          JsonRpcResponse initResponse = JsonRpcResponse.success(id, result);
          LOG.info("Full initialize response JSON: {}", JsonUtils.valueToTree(initResponse));
          
          return Response.ok(initResponse)
              .header("Access-Control-Allow-Origin", "*")
              .header("Access-Control-Allow-Methods", "POST, OPTIONS") 
              .header("Access-Control-Allow-Headers", "Content-Type, Authorization, Claude-Request-Id, Claude-SDK-Agent")
              .header("Access-Control-Allow-Credentials", "true")
              .build();
          
        case "executeFunction":
          // Execute function request
          LOG.info("Processing executeFunction request with id: {}", id);
          
          if (params == null || !params.has("name") || !params.has("parameters")) {
            LOG.warn("Invalid executeFunction request - missing name or parameters: {}", params);
            return Response.ok(JsonRpcResponse.error(id, -32602, "Invalid params for executeFunction"))
                .header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Methods", "POST, OPTIONS")
                .header("Access-Control-Allow-Headers", "Content-Type, Authorization, Claude-Request-Id, Claude-SDK-Agent")
                .header("Access-Control-Allow-Credentials", "true")
                .build();
          }
          
          String functionName = params.get("name").asText();
          JsonNode functionParams = params.get("parameters");
          
          LOG.info("Executing function: {} with params: {}", functionName, functionParams);
          
          MCPToolWithHandler toolWithHandler = toolHandlers.get(functionName);
          if (toolWithHandler == null) {
            LOG.warn("Function not found: {}", functionName);
            return Response.ok(JsonRpcResponse.error(id, -32601, "Function not found: " + functionName))
                .header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Methods", "POST, OPTIONS")
                .header("Access-Control-Allow-Headers", "Content-Type, Authorization, Claude-Request-Id, Claude-SDK-Agent")
                .header("Access-Control-Allow-Credentials", "true")
                .build();
          }
          
          try {
            // Execute function with a quick timeout to avoid blocking
            JsonNode functionResult = toolWithHandler.handler.apply(functionParams);
            LOG.info("Function executed successfully: {} with result size: {}", 
                functionName, functionResult.toString().length());
            
            // Prepare result formatting like Claude expects
            ObjectNode result1 = JsonUtils.getObjectNode();
            result1.put("name", functionName);
            result1.set("content", functionResult);
            
            JsonRpcResponse response = JsonRpcResponse.success(id, result1);
            LOG.info("Sending function response for ID: {}", id);
            
            return Response.ok(response)
                .header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Methods", "POST, OPTIONS")
                .header("Access-Control-Allow-Headers", "Content-Type, Authorization, Claude-Request-Id, Claude-SDK-Agent")
                .header("Access-Control-Allow-Credentials", "true")
                .build();
          } catch (Exception e) {
            LOG.error("Error executing function: {}", e.getMessage(), e);
            ObjectNode errorResult = JsonUtils.getObjectNode();
            errorResult.put("name", functionName);
            ObjectNode errorContent = JsonUtils.getObjectNode();
            errorContent.put("error", e.getMessage());
            errorResult.set("content", errorContent);
            
            return Response.ok(JsonRpcResponse.success(id, errorResult))
                .header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Methods", "POST, OPTIONS") 
                .header("Access-Control-Allow-Headers", "Content-Type, Authorization, Claude-Request-Id, Claude-SDK-Agent")
                .header("Access-Control-Allow-Credentials", "true")
                .build();
          }
          
        case "tools/list":
          // List tools request
          LOG.info("Processing tools/list request with id: {}", id);
          
          ArrayNode toolsResult = JsonUtils.getArrayNode();
          toolHandlers.values().forEach(handler -> 
              toolsResult.add(JsonUtils.valueToTree(handler.toolDefinition)));
          
          LOG.info("Returning {} tools for tools/list request", toolsResult.size());
          LOG.info("Tools: {}", toolsResult);
          
          JsonRpcResponse toolsResponse = JsonRpcResponse.success(id, toolsResult);
          LOG.info("Full tools/list response: {}", JsonUtils.valueToTree(toolsResponse));
          
          return Response.ok(toolsResponse)
              .header("Access-Control-Allow-Origin", "*")
              .header("Access-Control-Allow-Methods", "POST, OPTIONS")
              .header("Access-Control-Allow-Headers", "Content-Type, Authorization, Claude-Request-Id, Claude-SDK-Agent")
              .header("Access-Control-Allow-Credentials", "true")
              .build();
          
        case "resources/list":
          // List resources request (empty for now)
          LOG.info("Processing resources/list request with id: {}", id);
          return Response.ok(JsonRpcResponse.success(id, JsonUtils.getArrayNode())).build();
          
        case "prompts/list":
          // List prompts request (empty for now)
          LOG.info("Processing prompts/list request with id: {}", id);
          return Response.ok(JsonRpcResponse.success(id, JsonUtils.getArrayNode())).build();
          
        case "notifications/initialized":
          // Client notification - just acknowledge
          LOG.info("Received notifications/initialized");
          return Response.ok(JsonRpcResponse.success(id, null)).build();
          
        case "notifications/cancelled":
          // Client cancellation notification - just acknowledge
          LOG.info("Received notifications/cancelled with id: {}", id);
          LOG.info("notifications/cancelled params: {}", params);
          
          // Check for request ID in the params
          if (params != null && params.has("requestId")) {
            LOG.info("Cancellation for requestId: {}", params.get("requestId").asText());
          }
          
          if (params != null && params.has("reason")) {
            LOG.info("Cancellation reason: {}", params.get("reason").asText());
          }
          
          // Make sure to return HTTP 200 OK even if there's no ID
          JsonRpcResponse response = JsonRpcResponse.success(id, null);
          LOG.info("Notification response: {}", JsonUtils.valueToTree(response));
          
          return Response.ok(response)
              .header("Access-Control-Allow-Origin", "*")
              .header("Access-Control-Allow-Methods", "POST, OPTIONS")
              .header("Access-Control-Allow-Headers", "Content-Type, Authorization, Claude-Request-Id, Claude-SDK-Agent")
              .header("Access-Control-Allow-Credentials", "true")
              .build();
          
        default:
          // Unknown method
          LOG.warn("Unknown method: {}", method);
          return Response.status(Response.Status.BAD_REQUEST)
              .entity(JsonRpcResponse.error(id, -32601, "Method not found: " + method))
              .build();
      }
    } catch (Exception e) {
      LOG.error("Error processing JSON-RPC request", e);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(JsonRpcResponse.error(request.getId(), -32603, "Internal error: " + e.getMessage()))
          .build();
    }
  }
  
  /**
   * Helper method to send an SSE event
   */
  private void sendSseEvent(Writer writer, String event, String data) throws IOException {
    writer.write("event: " + event + "\n");
    writer.write("data: " + data + "\n\n");
    writer.flush();
  }
}
