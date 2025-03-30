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
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.common.utils.CommonUtil;
import org.openmetadata.schema.api.mcp.MCPToolDefinition;
import org.openmetadata.schema.api.mcp.ToolExecutionRequest;
import org.openmetadata.schema.api.mcp.ToolExecutionResponse;
import org.openmetadata.schema.search.SearchRequest;
import org.openmetadata.service.Entity;
import org.openmetadata.service.OpenMetadataApplicationConfig;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.resources.Collection;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.security.Authorizer;
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

  private static final String MCP_TOOLS_FILE = ".*json/data/mcp/tools.json$";

  private static class MCPToolWithHandler {
    final MCPToolDefinition toolDefinition;
    final Function<JsonNode, JsonNode> handler;

    MCPToolWithHandler(MCPToolDefinition toolDefinition, Function<JsonNode, JsonNode> handler) {
      this.toolDefinition = toolDefinition;
      this.handler = handler;
    }
  }

  public MCPResource(Authorizer authorizer) {
    this.searchRepository = Entity.getSearchRepository();
    this.authorizer = authorizer;
    loadToolDefinitions();
  }

  public void initialize(OpenMetadataApplicationConfig config) throws IOException {
    loadToolDefinitions();
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
}
