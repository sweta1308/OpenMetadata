/*
 *  Copyright 2021 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dropwizard.lifecycle.Managed;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.servlet.ServletHolder;
import org.openmetadata.common.utils.CommonUtil;
import org.openmetadata.schema.api.mcp.MCPToolDefinition;
import org.openmetadata.schema.search.SearchRequest;
import org.openmetadata.service.Entity;
import org.openmetadata.service.config.MCPConfiguration;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.util.EntityUtil;
import org.openmetadata.service.util.JsonUtils;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.openmetadata.service.search.SearchUtil.mapEntityTypesToIndexNames;

@Slf4j
public class OpenMetadataMCPServer implements Managed {
  private static final String MCP_TOOLS_FILE = ".*json/data/mcp/tools.json$";

  private final SearchRepository searchRepository;
  private final Map<String, BiFunction<String, Map<String, Object>, Map<String, Object>>> toolHandlers = new HashMap<>();
  private final MCPConfiguration mcpConfiguration;
  @Getter private final List<MCPToolDefinition> toolDefinitions = new ArrayList<>();
  private final MCPJsonRpcServlet servlet;
  
    private final String serverName;
  private final String serverVersion;
  private final boolean searchEnabled = true;

  @Getter private ServletHolder servletHolder;

  public OpenMetadataMCPServer(MCPConfiguration mcpConfiguration) {
    this.mcpConfiguration = mcpConfiguration;
    this.searchRepository = Entity.getSearchRepository();
    
    this.serverName = mcpConfiguration.getMcpServerName();
    this.serverVersion = mcpConfiguration.getMcpServerVersion();
    
    loadToolDefinitions();
    
    this.servlet = new MCPJsonRpcServlet(this);
    this.servletHolder = new ServletHolder(servlet);
    
    LOG.info("Initialized MCP server with {} tools", toolHandlers.size());
  }

  @Override
  public void start() {
    LOG.info("Starting MCP server");
    // Pre-load tool definitions to ensure they're ready when we receive the first request
    if (toolDefinitions.isEmpty()) {
      loadToolDefinitions();
    }
    LOG.info("MCP server started with {} tools registered", toolHandlers.size());
  }

  @Override
  public void stop() {
    LOG.info("Stopping MCP server");
  }
  
  public String getName() {
    return serverName;
  }

  public String getVersion() {
    return serverVersion;
  }
  

  public boolean isSearchEnabled() {
    return searchEnabled;
  }
  

  public List<Map<String, Object>> getToolDefinitions() {
    List<Map<String, Object>> result = new ArrayList<>();
    for (MCPToolDefinition toolDef : toolDefinitions) {
      // Convert the entire MCPToolDefinition to a Map<String, Object>
      Map<String, Object> toolMap = JsonUtils.convertValue(toolDef, Map.class);
      result.add(toolMap);
    }
    return result;
  }
  

  public Map<String, Object> callTool(String callId, String functionName, Map<String, Object> parameters) {
    BiFunction<String, Map<String, Object>, Map<String, Object>> handler = toolHandlers.get(functionName);
    if (handler == null) {
      Map<String, Object> error = new HashMap<>();
      error.put("error", "Tool not found: " + functionName);
      return error;
    }
    
    try {
      return handler.apply(callId, parameters);
    } catch (Exception e) {
      LOG.error("Error executing tool: {} - {}", functionName, e.getMessage(), e);
      Map<String, Object> error = new HashMap<>();
      error.put("error", e.getMessage());
      return error;
    }
  }

  public void loadToolDefinitions() {
    try {
      // Clear existing definitions and handlers
      toolDefinitions.clear();
      toolHandlers.clear();
      
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
            
            toolDefinitions.add(toolDefinition);
            
            BiFunction<String, Map<String, Object>, Map<String, Object>> handler;
            switch (toolDefinition.getName()) {
              case "search_metadata":
                handler = this::searchMetadata;
                break;
              case "get_entity_details":
                handler = this::getEntityDetails;
                break;
              case "nlq_search":
                handler = this::nlqSearch;
                break;
              case "advanced_search":
                handler = this::advancedSearch;
                break;
              default:
                LOG.warn("Unknown tool definition: {}", toolDefinition.getName());
                continue;
            }
            
            toolHandlers.put(toolDefinition.getName(), handler);
            LOG.info("Registered MCP tool: {}", toolDefinition.getName());
            
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

  @SuppressWarnings("unchecked")
  private Map<String, Object> searchMetadata(String callId, Map<String, Object> params) {
    try {
      LOG.info("Executing searchMetadata with params: {}", params);
      
      String query = params.containsKey("query") ? (String) params.get("query") : "*";
      Integer limitObj = params.containsKey("limit") ? 
          (params.get("limit") instanceof Number ? ((Number) params.get("limit")).intValue() : 
           params.get("limit") instanceof String ? Integer.parseInt((String) params.get("limit")) : 10) : 10;
      int limit = limitObj != null ? limitObj : 10;
      
      boolean includeDeleted = params.containsKey("include_deleted") && 
          (params.get("include_deleted") instanceof Boolean ? (Boolean) params.get("include_deleted") : 
           "true".equals(params.get("include_deleted")));
           
      String entityType = params.containsKey("entity_type") ? (String) params.get("entity_type") : null;
      String index = (entityType != null && !entityType.isEmpty()) ? mapEntityTypesToIndexNames(entityType) : Entity.TABLE;

      LOG.info("Search query: {}, index: {}, limit: {}, includeDeleted: {}", 
          query, index, limit, includeDeleted);

      SearchRequest searchRequest =
          new SearchRequest()
              .withQuery(query)
              .withIndex(index)
              .withSize(limit)
              .withFrom(0)
              .withFetchSource(true)
              .withDeleted(includeDeleted);

      javax.ws.rs.core.Response response = searchRepository.search(searchRequest, null);
      
      if (response.getEntity() instanceof String responseStr) {
        LOG.info("Search returned string response");
        JsonNode jsonNode = JsonUtils.readTree(responseStr);
        return JsonUtils.convertValue(jsonNode, Map.class);
      } else {
        LOG.info("Search returned object response: {}", response.getEntity().getClass().getName());
        return JsonUtils.convertValue(response.getEntity(), Map.class);
      }
    } catch (Exception e) {
      LOG.error("Error in searchMetadata", e);
      Map<String, Object> error = new HashMap<>();
      error.put("error", e.getMessage());
      return error;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getEntityDetails(String callId, Map<String, Object> params) {
    try {
      String entityType = (String) params.get("entity_type");
      String fqn = (String) params.get("fqn");
      
      LOG.info("Getting entity details for type: {}, FQN: {}", entityType, fqn);
      
      if (entityType == null || entityType.isEmpty()) {
        throw new IllegalArgumentException("entity_type is required");
      }
      
      if (fqn == null || fqn.isEmpty()) {
        throw new IllegalArgumentException("fqn is required");
      }
      
      EntityRepository<?> repository = Entity.getEntityRepository(entityType);
      if (repository == null) {
        throw new IllegalArgumentException("Invalid entity type: " + entityType);
      }
      
      Object entity = repository.getByName(null, fqn, null);
      if (entity == null) {
        throw new IllegalArgumentException("Entity not found for FQN: " + fqn);
      }

      Map<String, Object> result = JsonUtils.convertValue(entity, Map.class);
      LOG.info("Successfully retrieved entity: {}", fqn);
      return result;
    } catch (Exception e) {
      LOG.error("Error in getEntityDetails", e);
      Map<String, Object> error = new HashMap<>();
      error.put("error", e.getMessage());
      return error;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> nlqSearch(String callId, Map<String, Object> params) {
    try {
      String query = (String) params.get("query");
      
      Integer limitObj = params.containsKey("limit") ? 
          (params.get("limit") instanceof Number ? ((Number) params.get("limit")).intValue() : 
           params.get("limit") instanceof String ? Integer.parseInt((String) params.get("limit")) : 10) : 10;
      int limit = limitObj != null ? limitObj : 10;
      
      String entityType = params.containsKey("entity_type") ? (String) params.get("entity_type") : "table";
      // Safety check for entity type
      if (entityType == null || entityType.isEmpty()) {
        entityType = "table";
      }
      String index = mapEntityTypesToIndexNames(entityType);

      SearchRequest searchRequest =
          new SearchRequest()
              .withQuery(query)
              .withIndex(index)
              .withSize(limit)
              .withFrom(0)
              .withFetchSource(true);

      javax.ws.rs.core.Response response = searchRepository.searchWithNLQ(searchRequest, null);
      
      if (response.getEntity() instanceof String responseStr) {
        JsonNode jsonNode = JsonUtils.readTree(responseStr);
        return JsonUtils.convertValue(jsonNode, Map.class);
      } else {
        return JsonUtils.convertValue(response.getEntity(), Map.class);
      }
    } catch (Exception e) {
      LOG.error("Error in nlqSearch", e);
      Map<String, Object> error = new HashMap<>();
      error.put("error", e.getMessage());
      return error;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> advancedSearch(String callId, Map<String, Object> params) {
    try {
      String query = (String) params.get("query");
      
      Integer limitObj = params.containsKey("limit") ? 
          (params.get("limit") instanceof Number ? ((Number) params.get("limit")).intValue() : 
           params.get("limit") instanceof String ? Integer.parseInt((String) params.get("limit")) : 10) : 10;
      int limit = limitObj != null ? limitObj : 10;

      String entityType = params.containsKey("entity_type") ? (String) params.get("entity_type") : null;
      String index = (entityType != null && !entityType.isEmpty()) ? mapEntityTypesToIndexNames(entityType) : null;
      
      String sortField = params.containsKey("sort_field") ? (String) params.get("sort_field") : "_score";
      String sortOrder = params.containsKey("sort_order") ? (String) params.get("sort_order") : "desc";
      
      LOG.info("Advanced search query: {}, index: {}, limit: {}, sortField: {}, sortOrder: {}", 
          query, index, limit, sortField, sortOrder);

      String queryFilter = null;
      if (params.containsKey("filters") && params.get("filters") instanceof Map) {
        StringBuilder filterBuilder = new StringBuilder();
        Map<String, Object> filters = (Map<String, Object>) params.get("filters");
        
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
          if (filterBuilder.length() > 0) {
            filterBuilder.append(" AND ");
          }
          filterBuilder.append(entry.getKey()).append(":");
          
          if (entry.getValue() instanceof String) {
            filterBuilder.append((String) entry.getValue());
          } else if (entry.getValue() instanceof List) {
            filterBuilder.append("(");
            List<Object> list = (List<Object>) entry.getValue();
            for (int i = 0; i < list.size(); i++) {
              if (i > 0) {
                filterBuilder.append(" OR ");
              }
              filterBuilder.append(list.get(i).toString());
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
      
      javax.ws.rs.core.Response response = searchRepository.search(searchRequest, null);
      
      if (response.getEntity() instanceof String responseStr) {
        JsonNode jsonNode = JsonUtils.readTree(responseStr);
        return JsonUtils.convertValue(jsonNode, Map.class);
      } else {
        return JsonUtils.convertValue(response.getEntity(), Map.class);
      }
    } catch (Exception e) {
      LOG.error("Error in advancedSearch", e);
      Map<String, Object> error = new HashMap<>();
      error.put("error", e.getMessage());
      return error;
    }
  }
  
  /**
   * Returns the HTTP servlet for the MCP server, which can be registered with a servlet container
   */
  public HttpServlet getServlet() {
    return servlet;
  }
  
  /**
   * Custom JSON-RPC 2.0 servlet for MCP protocol
   */
  @Slf4j
  private static class MCPJsonRpcServlet extends HttpServlet {
    private final OpenMetadataMCPServer mcpServer;
    private final ObjectMapper objectMapper;
    
    public MCPJsonRpcServlet(OpenMetadataMCPServer mcpServer) {
      this.mcpServer = mcpServer;
      this.objectMapper = JsonUtils.getObjectMapper();
    }
    
    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response) 
        throws ServletException, IOException {
      setCorsHeaders(response);
      response.setStatus(HttpServletResponse.SC_OK);
    }
    
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
        throws ServletException, IOException {
      
      response.setContentType("application/json");
      response.setCharacterEncoding("UTF-8");
      setCorsHeaders(response);
      
      try {
        JsonNode requestNode = objectMapper.readTree(request.getInputStream());
        String jsonrpc = requestNode.has("jsonrpc") ? requestNode.get("jsonrpc").asText() : null;
        String method = requestNode.has("method") ? requestNode.get("method").asText() : null;
        String id = requestNode.has("id") ? (requestNode.get("id").isTextual() ? 
            requestNode.get("id").asText() : requestNode.get("id").toString()) : null;
        JsonNode params = requestNode.has("params") ? requestNode.get("params") : null;
        
        LOG.info("Received JSON-RPC request: method={}, id={}, jsonrpc={}", method, id, jsonrpc);
        
        boolean isNotification = method != null && (method.startsWith("notifications/"));
        
        if (method == null || (id == null && !isNotification) || !"2.0".equals(jsonrpc)) {
          LOG.warn("Invalid JSON-RPC request: method={}, id={}, jsonrpc={}, isNotification={}", 
                   method, id, jsonrpc, isNotification);
          sendError(response, id, -32600, "Invalid JSON-RPC request");
          return;
        }
        
        JsonNode result;
        
        switch (method) {
          case "initialize":
            LOG.info("Processing initialize request with id: {}", id);
            result = handleInitialize(id, requestNode);
            break;
          case "executeFunction":
            LOG.info("Processing executeFunction request with id: {}", id);
            result = handleExecuteFunction(id, params);
            break;
          case "tools/list":
            LOG.info("Processing tools/list request with id: {}", id);
            result = handleToolsList();
            break;
          case "resources/list":
            LOG.info("Processing resources/list request with id: {}", id);
            result = objectMapper.createArrayNode();
            break;
          case "prompts/list":
            LOG.info("Processing prompts/list request with id: {}", id);
            result = objectMapper.createArrayNode();
            break;
          case "notifications/initialized":
            LOG.info("Received notifications/initialized with id: {}", id);
            result = objectMapper.nullNode();
            break;
          case "notifications/cancelled":
            LOG.info("Received notifications/cancelled notification with params: {}", params);
            result = objectMapper.nullNode();
            break;
          default:
            LOG.warn("Unknown method: {}", method);
            sendError(response, id, -32601, "Method not found: " + method);
            return;
        }
        
        // Send success response
        ObjectNode responseObj = objectMapper.createObjectNode();
        responseObj.put("jsonrpc", "2.0");
        responseObj.put("id", id);
        responseObj.set("result", result);
        
        String responseJson = objectMapper.writeValueAsString(responseObj);
        LOG.info("Sending JSON-RPC response for method '{}' with id '{}': {}", method, id, responseJson);
        
        // Important: Make sure to set the appropriate content headers
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        objectMapper.writeValue(response.getOutputStream(), responseObj);
        response.flushBuffer();
        LOG.info("Response sent successfully for method: {}", method);
        
      } catch (Exception e) {
        LOG.error("Error processing JSON-RPC request", e);
        sendError(response, null, -32700, "Parse error: " + e.getMessage());
      }
    }
    
    private void sendError(HttpServletResponse response, String id, int code, String message) 
        throws IOException {
      
      ObjectNode responseObj = objectMapper.createObjectNode();
      responseObj.put("jsonrpc", "2.0");
      if (id != null) {
        responseObj.put("id", id);
      } else {
        responseObj.putNull("id");
      }
      
      ObjectNode error = objectMapper.createObjectNode();
      error.put("code", code);
      error.put("message", message);
      
      responseObj.set("error", error);
      
      LOG.warn("Sending error response: {}", responseObj);
      objectMapper.writeValue(response.getOutputStream(), responseObj);
      response.flushBuffer();
    }
    
    @SuppressWarnings("unchecked")
    private JsonNode handleExecuteFunction(String id, JsonNode params) {
      try {
        LOG.info("executeFunction request with id: {}, params: {}", id, params);
        
        if (params == null || !params.has("name") || !params.has("parameters")) {
          throw new IllegalArgumentException("Invalid executeFunction params");
        }
        
        String functionName = params.get("name").asText();
        Map<String, Object> parameters = objectMapper.convertValue(
            params.get("parameters"), Map.class);
        
        LOG.info("Executing function '{}' with parameters: {}", functionName, parameters);
        Map<String, Object> result = mcpServer.callTool(id, functionName, parameters);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("name", functionName);

        if (result.containsKey("error")) {
          LOG.error("Function execution error: {}", result.get("error"));
          ObjectNode errorObj = objectMapper.createObjectNode();
          errorObj.put("error", String.valueOf(result.get("error")));
          response.set("content", errorObj);
        } else {
          response.set("content", objectMapper.valueToTree(result));
        }
        
        LOG.info("Function response for id {}: {}", id, response);
        return response;
      } catch (Exception e) {
        LOG.error("Error executing function for id {}: {}", id, e.getMessage(), e);
        ObjectNode response = objectMapper.createObjectNode();
        if (params != null && params.has("name")) {
          response.put("name", params.get("name").asText());
        } else {
          response.put("name", "unknown");
        }
        
        ObjectNode errorContent = objectMapper.createObjectNode();
        errorContent.put("error", e.getMessage());
        response.set("content", errorContent);
        
        return response;
      }
    }

    private JsonNode handleInitialize(String id, JsonNode requestNode) {
      try {
        // Ensure we have tools loaded
        if (mcpServer.getToolDefinitions().isEmpty()) {
          LOG.info("No tools loaded, reloading tool definitions");
          mcpServer.loadToolDefinitions();
        }
        
        ObjectNode result = objectMapper.createObjectNode();

        // Add server info
        ObjectNode serverInfo = objectMapper.createObjectNode();
        serverInfo.put("name", mcpServer.getName());
        serverInfo.put("version", mcpServer.getVersion());
        result.set("serverInfo", serverInfo);

        // Add capabilities exactly as Claude expects
        ObjectNode capabilities = objectMapper.createObjectNode();
        capabilities.put("search", mcpServer.isSearchEnabled());
        result.set("capabilities", capabilities);

        // Add protocol version - check if we have protocol version in the request
        if (requestNode != null && requestNode.has("params") && 
            requestNode.get("params").has("protocolVersion")) {
          String protocolVersion = requestNode.get("params").get("protocolVersion").asText();
          result.put("protocolVersion", protocolVersion);
          LOG.info("Using protocol version from client: {}", protocolVersion);
        } else {
          // Use exactly the version Claude sent in the initialize message
          result.put("protocolVersion", "2024-11-05");
          LOG.info("Using default protocol version: 2024-11-05");
        }
        
        LOG.info("Full initialize response: {}", result);

        ArrayNode functions = objectMapper.createArrayNode();
        for (Map<String, Object> toolDef : mcpServer.getToolDefinitions()) {
          ObjectNode functionNode = objectMapper.createObjectNode();
          functionNode.put("name", (String) toolDef.get("name"));
          functionNode.put("description", (String) toolDef.get("description"));

          ObjectNode parameters = objectMapper.createObjectNode();
          parameters.put("type", "object");

          ObjectNode properties = objectMapper.createObjectNode();
          Map<String, Object> paramsMap = (Map<String, Object>) toolDef.get("parameters");
          if (paramsMap.containsKey("properties")) {
            properties = (ObjectNode) JsonUtils.valueToTree(paramsMap.get("properties"));
          }
          parameters.set("properties", properties);

          ArrayNode required = objectMapper.createArrayNode();
          if (paramsMap.containsKey("required")) {
            Object reqObj = paramsMap.get("required");
            if (reqObj instanceof List) {
              for (Object item : (List<?>) reqObj) {
                required.add(item.toString());
              }
            } else if (reqObj != null) {
              required.add(reqObj.toString());
            }
          }
          parameters.set("required", required);

          functionNode.set("parameters", parameters);
          functions.add(functionNode);
        }
        result.set("functions", functions);

        LOG.info("Initialize response: {}", result);
        return result;
      } catch (Exception e) {
        LOG.error("Error handling initialize", e);
        ObjectNode error = objectMapper.createObjectNode();
        error.put("error", e.getMessage());
        return error;
      }
    }

    private JsonNode handleToolsList() {
      try {
        LOG.info("Handling tools/list request");
        ArrayNode toolsArray = objectMapper.createArrayNode();
        List<Map<String, Object>> toolDefs = mcpServer.getToolDefinitions();
        LOG.info("Got {} tool definitions", toolDefs.size());

        for (Map<String, Object> toolDef : toolDefs) {
          ObjectNode toolNode = objectMapper.createObjectNode();
          toolNode.put("name", (String) toolDef.get("name"));
          toolNode.put("description", (String) toolDef.get("description"));
          Map<String, Object> paramsMap = (Map<String, Object>) toolDef.get("parameters");
          ObjectNode parametersNode = objectMapper.createObjectNode();

          parametersNode.put("type", "object");

          ObjectNode propertiesNode = objectMapper.createObjectNode();
          if (paramsMap != null && paramsMap.containsKey("properties")) {
            Map<String, Object> props = (Map<String, Object>) paramsMap.get("properties");
            for (Map.Entry<String, Object> entry : props.entrySet()) {
              propertiesNode.set(entry.getKey(), JsonUtils.valueToTree(entry.getValue()));
            }
          }
          parametersNode.set("properties", propertiesNode);
          ArrayNode requiredArray = objectMapper.createArrayNode();
          if (paramsMap != null && paramsMap.containsKey("required")) {
            Object req = paramsMap.get("required");
            if (req instanceof List) {
              for (Object item : (List<?>) req) {
                requiredArray.add(item.toString());
              }
            } else if (req instanceof String) {
              requiredArray.add((String) req);
            }
          }
          parametersNode.set("required", requiredArray);
          toolNode.set("parameters", parametersNode);
          toolsArray.add(toolNode);
        }

        LOG.info("Returning {} tools in response", toolsArray.size());
        return toolsArray;
      } catch (Exception e) {
        LOG.error("Error in handleToolsList", e);
        return objectMapper.createArrayNode(); // Return empty array on error
      }
    }
    
    private void setCorsHeaders(HttpServletResponse response) {
      response.setHeader("Access-Control-Allow-Origin", "*");
      response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
      response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, Claude-Request-Id, Claude-SDK-Agent");
      response.setHeader("Access-Control-Max-Age", "3600");
      response.setHeader("Access-Control-Allow-Credentials", "true");
    }
  }
}