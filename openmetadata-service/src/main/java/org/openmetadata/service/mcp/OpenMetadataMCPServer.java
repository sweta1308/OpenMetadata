package org.openmetadata.service.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dropwizard.lifecycle.Managed;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.servlet.ServletHolder;
import org.openmetadata.common.utils.CommonUtil;
import org.openmetadata.schema.search.SearchRequest;
import org.openmetadata.service.Entity;
import org.openmetadata.service.config.MCPConfiguration;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.util.JsonUtils;
import reactor.core.publisher.Mono;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;

import static org.openmetadata.service.search.SearchUtil.mapEntityTypesToIndexNames;

/**
 * A simple implementation of MCP server that handles the Model Context Protocol
 * without external dependencies on Spring or the MCP SDK.
 */
@Slf4j
public class OpenMetadataMCPServer implements Managed {

  private final SearchRepository searchRepository;
  private final ExecutorService executorService;
  private final MCPConfiguration mcpConfig;
  private final ObjectMapper objectMapper;
  private final Map<String, McpServerSession> sessions = new ConcurrentHashMap<>();
  private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(
      1, 
      r -> {
        Thread t = new Thread(r, "mcp-heartbeat-scheduler");
        t.setDaemon(true);
        return t;
      }
  );
  private final Map<String, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
  private List<Map<String, Object>> cachedTools;
  private final Map<String, McpServerSession.RequestHandler<?>> cachedHandlers = new HashMap<>();
  private final ServletHolder servletHolder;

  public OpenMetadataMCPServer(MCPConfiguration mcpConfig) {
    this.mcpConfig = mcpConfig;
    this.searchRepository = Entity.getSearchRepository();
    this.executorService = Executors.newCachedThreadPool();
    this.objectMapper = JsonUtils.getObjectMapper();
    HttpServlet bridgeServlet = new MCPBridgeServlet();
    this.servletHolder = new ServletHolder(bridgeServlet);
    LOG.info("Initialized MCP server (using Servlet API)");
  }

  @Override
  public void start() {
    LOG.info("Starting MCP server with configuration: {}", mcpConfig);
    LOG.info("MCP Server name: {}, version: {}", mcpConfig.getMcpServerName(), mcpConfig.getMcpServerVersion());
    LOG.info("MCP Server path: {}", mcpConfig.getPath());
    try {
      LOG.info("Loading tool definitions...");
      cachedTools = loadToolDefinitions();
      if (cachedTools == null || cachedTools.isEmpty()) {
        LOG.error("No tool definitions were loaded!");
        throw new RuntimeException("Failed to load tool definitions");
      }
      LOG.info("Successfully loaded {} tool definitions", cachedTools.size());
      for (Map<String, Object> tool : cachedTools) {
        LOG.info("Tool available: {}", tool.get("name"));
      }
      LOG.info("Initializing request handlers...");
      initializeHandlers();
      if (cachedHandlers.isEmpty()) {
        LOG.error("No handlers were initialized!");
        throw new RuntimeException("Failed to initialize handlers");
      }
      LOG.info("Successfully initialized handlers: {}", cachedHandlers.keySet());
    } catch (Exception e) {
      LOG.error("Error during server startup", e);
      throw new RuntimeException("Failed to start MCP server", e);
    }
  }

  @Override
  public void stop() {
    LOG.info("Stopping MCP server");
    executorService.shutdown();
    for (Map.Entry<String, ScheduledFuture<?>> entry : heartbeatTasks.entrySet()) {
      entry.getValue().cancel(false);
      LOG.info("Canceled heartbeat task for session: {}", entry.getKey());
    }
    heartbeatTasks.clear();
    try {
      LOG.info("Shutting down heartbeat executor");
      heartbeatExecutor.shutdown();
      if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        LOG.warn("Heartbeat executor did not terminate in time, forcing shutdown");
        heartbeatExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      LOG.warn("Interrupted while waiting for heartbeat executor to shut down", e);
      Thread.currentThread().interrupt();
      heartbeatExecutor.shutdownNow();
    }
    for (McpServerSession session : sessions.values()) {
      session.closeGracefully().subscribe();
    }
    sessions.clear();
    LOG.info("MCP server stopped");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> loadToolDefinitions() throws IOException {
    try {
      String json = CommonUtil.getResourceAsStream(
              getClass().getClassLoader(), 
              "json/data/mcp/tools.json");
      LOG.info("Loaded tool definitions, content length: {}", json.length());
      LOG.info("Raw tools.json content: {}", json);
      
      JsonNode toolsJson = JsonUtils.readTree(json);
      JsonNode toolsArray = toolsJson.get("tools");

      if (toolsArray == null || !toolsArray.isArray()) {
        LOG.error("Invalid MCP tools file format. Expected 'tools' array.");
        return new ArrayList<>();
      }
      
      List<Map<String, Object>> tools = new ArrayList<>();
      for (JsonNode toolNode : toolsArray) {
        String name = toolNode.get("name").asText();
        Map<String, Object> toolDef = JsonUtils.convertValue(toolNode, Map.class);
        tools.add(toolDef);
        LOG.info("Tool found: {} with definition: {}", name, toolDef);
      }
      
      LOG.info("Found {} tool definitions", tools.size());
      return tools;
    } catch (Exception e) {
      LOG.error("Error loading tool definitions: {}", e.getMessage(), e);
      throw e;
    }
  }

  private void initializeHandlers() {
    LOG.info("Initializing request handlers");
    LOG.info("Registering handler for method: tools/list");
    cachedHandlers.put("tools/list", (exchange, params) -> {
      LOG.info("Starting tools/list handler");
      return Mono.<Object>defer(() -> {
        try {
          if (cachedTools == null) {
            String error = "No cached tools available - tools were not initialized at startup";
            LOG.error(error);
            return Mono.error(new RuntimeException(error));
          }
          LOG.info("Found {} cached tools", cachedTools.size());
          List<Map<String, Object>> toolsFormatted = new ArrayList<>();
          for (Map<String, Object> toolDef : cachedTools) {
            try {
              String name = (String) toolDef.get("name");
              String description = (String) toolDef.get("description");
              Object parameters = toolDef.get("parameters");
              
              LOG.info("Processing tool: name={}, description={}", name, description);
              
              Map<String, Object> formattedTool = new HashMap<>();
              formattedTool.put("name", name);
              formattedTool.put("description", description);
              formattedTool.put("parameters", parameters);
              
              toolsFormatted.add(formattedTool);
            } catch (Exception e) {
              LOG.error("Error processing tool definition: {}", toolDef, e);
            }
          }
          Map<String, Object> resultObj = new HashMap<>();
          resultObj.put("tools", toolsFormatted);
          
          LOG.info("Successfully formatted {} tools", toolsFormatted.size());
          return Mono.just(resultObj);
        } catch (Exception e) {
          LOG.error("Error in tools/list handler", e);
          return Mono.error(e);
        }
      })
      .doOnSubscribe(s -> LOG.info("tools/list handler subscribed"))
      .doOnNext(result -> LOG.info("Got result from handler: {}", result))
      .doOnSuccess(result -> LOG.info("tools/list handler completed successfully"))
      .doOnError(error -> LOG.error("tools/list handler failed with error: {}", error.getMessage(), error))
      .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    });
    
    cachedHandlers.put("executeFunction", (exchange, params) -> {
      return Mono.defer(() -> {
        try {
          Map<String, Object> paramsMap = objectMapper.convertValue(params, Map.class);
          String functionName = (String) paramsMap.get("name");
          Map<String, Object> functionParams = (Map<String, Object>) paramsMap.get("parameters");
          
          Object result;
          switch (functionName) {
            case "search_metadata":
              result = searchMetadata(functionParams);
              break;
            case "get_entity_details":
              result = getEntityDetails(functionParams);
              break;
            default:
              result = Map.of("error", "Unknown function: " + functionName);
              break;
          }
          
          return Mono.just(result);
        } catch (Exception e) {
          LOG.error("Error executing function", e);
          return Mono.error(e);
        }
      });
    });
  }

  private Map<String, Object> searchMetadata(Map<String, Object> params) {
    try {
      LOG.info("Executing searchMetadata with params: {}", params);
      String query = params.containsKey("query") ? (String) params.get("query") : "*";
      int limit = 10;
      if (params.containsKey("limit")) {
        Object limitObj = params.get("limit");
        if (limitObj instanceof Number) {
          limit = ((Number) limitObj).intValue();
        } else if (limitObj instanceof String) {
          limit = Integer.parseInt((String) limitObj);
        }
      }
      
      boolean includeDeleted = false;
      if (params.containsKey("include_deleted")) {
        Object deletedObj = params.get("include_deleted");
        if (deletedObj instanceof Boolean) {
          includeDeleted = (Boolean) deletedObj;
        } else if (deletedObj instanceof String) {
          includeDeleted = "true".equals(deletedObj);
        }
      }
      
      String entityType = params.containsKey("entity_type") ? (String) params.get("entity_type") : null;
      String index = (entityType != null && !entityType.isEmpty()) 
          ? mapEntityTypesToIndexNames(entityType) 
          : Entity.TABLE;

      LOG.info("Search query: {}, index: {}, limit: {}, includeDeleted: {}", 
          query, index, limit, includeDeleted);

      SearchRequest searchRequest = new SearchRequest()
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

  private Object getEntityDetails(Map<String, Object> params) {
    try {
      String entityType = (String) params.get("entity_type");
      String fqn = (String) params.get("fqn");
      
      LOG.info("Getting details for entity type: {}, FQN: {}", entityType, fqn);
      String fields = "*";
      Object entity = Entity.getEntityByName(entityType, fqn, fields, null);
      return entity;
    } catch (Exception e) {
      LOG.error("Error getting entity details", e);
      return Map.of("error", e.getMessage());
    }
  }


  private class MCPBridgeServlet extends HttpServlet {
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) 
            throws IOException {
      
      String initialRequestId = req.getHeader("X-Request-ID"); // Or generate one if needed
      if (initialRequestId == null) { initialRequestId = UUID.randomUUID().toString().substring(0, 8); }
      LOG.info("[{}] MCPBridgeServlet service() entered for: {} {}", 
          initialRequestId, req.getMethod(), req.getRequestURI());
      
      String path = req.getRequestURI();
      String method = req.getMethod();

      long startTime = System.currentTimeMillis();
      String requestId = UUID.randomUUID().toString().substring(0, 8);
      
      try {
        LOG.info("[{}] Received request: {} {} from {}", 
            requestId, 
            req.getMethod(), 
            req.getRequestURI(),
            req.getRemoteAddr());
        
        // Log headers for debugging
        java.util.Enumeration<String> headerNames = req.getHeaderNames();
        while (headerNames.hasMoreElements()) {
          String headerName = headerNames.nextElement();
          LOG.debug("[{}] Header: {} = {}", requestId, headerName, req.getHeader(headerName));
        }
        
        // Set content type explicitly for all responses
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        // Forward the request using the WebFlux transport
        if (req.getMethod().equals("GET") && path.endsWith("/sse")) {
          LOG.info("[{}] Handling SSE connection request", requestId);
          // Handle SSE connection with proper headers
          resp.setContentType("text/event-stream");
          resp.setCharacterEncoding("UTF-8");
          resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
          resp.setHeader("Pragma", "no-cache");
          resp.setHeader("Expires", "0");
          resp.setHeader("Connection", "keep-alive");
          
          resp.setHeader("Access-Control-Allow-Origin", "*");
          resp.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
          resp.setHeader("Access-Control-Allow-Headers", "*");
          
          resp.setStatus(HttpServletResponse.SC_OK);
          

          if (req.getMethod().equals("OPTIONS")) {
            LOG.info("[{}] Handling CORS preflight request", requestId);
            resp.setHeader("Access-Control-Max-Age", "86400"); // 24 hours
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().flush();
            return;
          }
          
          final AsyncContext asyncContext = req.startAsync();
          asyncContext.setTimeout(3600000); // 1 hour

          final String sessionId = UUID.randomUUID().toString();
          LOG.info("[{}] Created new SSE session: {}", requestId, sessionId);
          final AtomicBoolean closed = new AtomicBoolean(false);
          PrintWriter writer = resp.getWriter();
          String messageEndpoint = mcpConfig.getPath() + "/message?sessionId=" + sessionId;

          String serverHost = req.getServerName();
          int serverPort = req.getServerPort();
          String fullMessageEndpoint = "http://" + serverHost + ":" + serverPort + messageEndpoint;
          
          writer.write("event: endpoint\n");
          writer.write("data: " + fullMessageEndpoint + "\n\n");
          writer.flush();
          LOG.info("[{}] Sent initial endpoint event for session: {}", requestId, sessionId);
          LOG.info("[{}] Message endpoint URL: {}", requestId, fullMessageEndpoint);
          
          McpServerTransport transport = new McpServerTransport() {
              private final PrintWriter asyncWriter = asyncContext.getResponse().getWriter();
              
              @Override
              public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
                  if (closed.get()) {
                      LOG.warn("[{}] Attempted to send message on closed SSE connection: {}", sessionId, message);
                      return Mono.error(new IOException("SSE connection closed")); // Signal error if closed
                  }
                  
                  return Mono.fromCallable(() -> {
                      try {
                          String json = objectMapper.writeValueAsString(message);
                          LOG.info("[{}] Sending SSE message: {}", sessionId, json);
                          
                          synchronized (asyncWriter) {
                              asyncWriter.write("event: message\n");
                              asyncWriter.write("data: " + json + "\n\n");
                              asyncWriter.flush();
                              
                              if (asyncWriter.checkError()) {
                                  throw new IOException("PrintWriter error during SSE send");
                              }
                          }
                          return null;
                      } catch (Exception e) {
                          LOG.error("[{}] Error sending SSE message: {}", sessionId, e.getMessage());
                          throw e;
                      }
                  }).then();
              }
              
              @Override
              public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
                  return objectMapper.convertValue(data, typeRef);
              }
              
              @Override
              public Mono<Void> closeGracefully() {
                  closed.set(true);
                  return Mono.empty();
              }
              
              @Override
              public void close() {
                  closed.set(true);
              }
          };
          
          McpServerSession session = createSession(sessionId, transport);
          try {
              writer.write("event: heartbeat\n");
              writer.write("data: {\"timestamp\":" + System.currentTimeMillis() + "}\n\n");
              writer.flush();
              LOG.info("[{}] Sent initial heartbeat for session: {}", requestId, sessionId);
          } catch (Exception e) {
              LOG.error("[{}] Error sending initial heartbeat: {}", requestId, e.getMessage());
          }
          
          Thread heartbeatThread = new Thread(() -> {
              try {
                  Thread.currentThread().setName("mcp-heartbeat-" + sessionId.substring(0, 8));
                  LOG.info("Started heartbeat thread for session: {}", sessionId);
                  
                  int pingCount = 0;
                  long lastHeartbeatTime = System.currentTimeMillis();
                  
                  while (!Thread.currentThread().isInterrupted() && !closed.get()) {
                      try {
                          // Determine if we should send a heartbeat or just a ping
                          long now = System.currentTimeMillis();
                          boolean sendHeartbeat = (now - lastHeartbeatTime) >= 5000; // Every 5 seconds
                          
                          if (sendHeartbeat) {
                              // Send a proper heartbeat event
                              writer.write("event: heartbeat\n");
                              writer.write("data: {\"timestamp\":" + now + "}\n\n");
                              writer.flush();
                              lastHeartbeatTime = now;
                              pingCount = 0;
                              LOG.debug("Sent heartbeat for session: {}", sessionId);
                          } else {
                              // Send a comment ping
                              writer.write(": ping-" + (++pingCount) + "\n\n");
                              writer.flush();
                              LOG.debug("Sent ping for session: {}", sessionId);
                          }
                          
                          // Sleep before next ping/heartbeat
                          Thread.sleep(1000);
                      } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          LOG.info("Heartbeat thread interrupted for session: {}", sessionId);
                          break;
                      } catch (Exception e) {
                          if (e.getMessage() != null && 
                              (e.getMessage().contains("Broken pipe") || 
                               e.getMessage().contains("Connection reset"))) {
                              LOG.info("Client disconnected (detected in heartbeat): {}", sessionId);
                          } else {
                              LOG.error("Error in heartbeat thread: {}", e.getMessage(), e);
                          }
                          break;
                      }
                  }
                  
                  LOG.info("Heartbeat thread exiting for session: {}", sessionId);
                  
                  // Clean up resources
                  closed.set(true);
                  sessions.remove(sessionId);
                  
                  // Complete the async context
                  try {
                      asyncContext.complete();
                  } catch (Exception e) {
                      LOG.error("Error completing async context: {}", e.getMessage());
                  }
              } catch (Exception e) {
                  LOG.error("Unhandled error in heartbeat thread: {}", e.getMessage(), e);
              }
          });
          heartbeatThread.setDaemon(true);
          heartbeatThread.start();
          
          asyncContext.addListener(new AsyncListener() {
              @Override
              public void onComplete(AsyncEvent event) {
                  LOG.info("Async context completed for session: {}", sessionId);
                  cleanup();
              }
              
              @Override
              public void onTimeout(AsyncEvent event) {
                  LOG.info("Async context timed out for session: {}", sessionId);
                  cleanup();
              }
              
              @Override
              public void onError(AsyncEvent event) {
                  LOG.info("Async context error for session: {}: {}", 
                      sessionId, event.getThrowable() != null ? event.getThrowable().getMessage() : "unknown");
                  cleanup();
              }
              
              @Override
              public void onStartAsync(AsyncEvent event) {
              }
              
              private void cleanup() {
                  closed.set(true);
                  sessions.remove(sessionId);
                  heartbeatThread.interrupt();
                  LOG.info("Cleaned up resources for session: {}", sessionId);
              }
          });
          return;
        } else if (req.getMethod().equals("POST") && 
                  (path.contains("/message"))) {
          LOG.info("[{}] Entered POST /message handler for path: {}", requestId, path);
          resp.setContentType("application/json");

          resp.setHeader("Access-Control-Allow-Origin", "*");
          resp.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
          resp.setHeader("Access-Control-Allow-Headers", "*");
          
          if (req.getMethod().equals("OPTIONS")) {
              LOG.info("[{}] Handling CORS preflight request for /message", requestId);
              resp.setHeader("Access-Control-Max-Age", "86400"); // 24 hours
              resp.setStatus(HttpServletResponse.SC_OK);
              resp.getWriter().flush();
              return;
          }
          
          String sessionId = req.getParameter("sessionId");
          if (sessionId == null) {
            LOG.warn("[{}] No session ID provided in POST request", requestId);
            String[] pathParts = path.split("/");
            for (int i = 0; i < pathParts.length - 1; i++) {
              if (pathParts[i].equals("message") && i + 1 < pathParts.length) {
                sessionId = pathParts[i + 1];
                break;
              }
            }
            
            if (sessionId == null) {
              LOG.error("[{}] Cannot process JSON-RPC message without a session ID", requestId);
              resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
              resp.getWriter().write("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,\"message\":\"Session ID is required\"},\"id\":null}");
              resp.getWriter().flush();
              return;
            }
          }
          
          McpServerSession session = sessions.get(sessionId);
          if (session == null) {
            LOG.error("[{}] Session not found: {}", requestId, sessionId);
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,\"message\":\"Session not found\"},\"id\":null}");
            resp.getWriter().flush();
            return;
          }
          
          LOG.info("[{}] Processing JSON-RPC message for session: {}", requestId, sessionId);
          
          String requestBodyStr;
          StringBuilder requestBody = new StringBuilder();
          String line;
          try (BufferedReader reader = req.getReader()) {
              while ((line = reader.readLine()) != null) {
                  requestBody.append(line);
              }
          }
          requestBodyStr = requestBody.toString();
          try {
            McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, requestBodyStr);
            LOG.info("Processing message: {} (type: {})", message, message.getClass().getSimpleName());
            if (requestBodyStr.length() < 1000) {
              LOG.info("Raw request body: {}", requestBodyStr);
            } else {
              LOG.info("Raw request body (truncated): {}", requestBodyStr.substring(0, 1000));
            }
            
            if (message instanceof McpSchema.JSONRPCRequest request) {
              LOG.info("Passing request to session handler: method={}, id={}, available handlers={}", 
                  request.method(), 
                  request.id(),
                  createRequestHandlers().keySet());
              try {
                CompletableFuture<Void> future = new CompletableFuture<>();
                final PrintWriter responseWriter = resp.getWriter();
                
                LOG.info("About to handle request with method: {}", request.method());

                if ("tools/list".equals(request.method())) {
                  LOG.info("Handling tools/list method directly instead of through session");
                  try {
                    Map<String, Object> resultObj = new HashMap<>();
                    List<Map<String, Object>> formattedTools = new ArrayList<>();
                    
                    if (cachedTools != null) {
                      for (Map<String, Object> toolDef : cachedTools) {
                        try {
                          String name = (String) toolDef.get("name");
                          String description = (String) toolDef.get("description");
                          Object parameters = toolDef.get("parameters");
                          
                          Map<String, Object> formattedTool = new HashMap<>();
                          formattedTool.put("name", name);
                          formattedTool.put("description", description);
                          formattedTool.put("parameters", parameters);
                          
                          formattedTools.add(formattedTool);
                          LOG.info("Added tool to response: {}", name);
                        } catch (Exception e) {
                          LOG.error("Error processing tool definition: {}", toolDef, e);
                          // Continue processing other tools
                        }
                      }
                    } else {
                      LOG.warn("No cached tools available when handling tools/list directly");
                    }
                    
                    resultObj.put("tools", formattedTools);
                    
                    // Create JSON-RPC response
                    Map<String, Object> response = new HashMap<>();
                    response.put("jsonrpc", "2.0");
                    response.put("id", request.id());
                    response.put("result", resultObj);
                    
                    // Write response directly to HTTP response
                    String json = objectMapper.writeValueAsString(response);
                    resp.getWriter().write(json);
                    resp.getWriter().flush();
                    LOG.info("Sent direct tools/list response with {} tools", formattedTools.size());
                    
                    // Send 200 OK for the HTTP response
                    resp.setStatus(HttpServletResponse.SC_OK);
                    return;
                  } catch (Exception e) {
                    LOG.error("Error in direct tools/list handling", e);
                    handleError(resp, e);
                    return;
                  }
                } else if ("executeFunction".equals(request.method())) {
                  LOG.info("Handling executeFunction method directly instead of through session");
                  try {
                    Map<String, Object> paramsMap = objectMapper.convertValue(request.params(), Map.class);
                    String functionName = (String) paramsMap.get("name");
                    Map<String, Object> functionParams = (Map<String, Object>) paramsMap.get("parameters");
                    
                    LOG.info("Executing function: {} with params: {}", functionName, functionParams);
                    
                    Object result;
                    switch (functionName) {
                      case "search_metadata":
                        result = searchMetadata(functionParams);
                        break;
                      case "get_entity_details":
                        result = getEntityDetails(functionParams);
                        break;
                      default:
                        result = Map.of("error", "Unknown function: " + functionName);
                        break;
                    }
                    
                    // Create JSON-RPC response
                    Map<String, Object> response = new HashMap<>();
                    response.put("jsonrpc", "2.0");
                    response.put("id", request.id());
                    response.put("result", result);
                    
                    // Write response directly to HTTP response
                    String json = objectMapper.writeValueAsString(response);
                    resp.getWriter().write(json);
                    resp.getWriter().flush();
                    LOG.info("Sent direct executeFunction response for function: {}", functionName);
                    
                    // Send 200 OK for the HTTP response
                    resp.setStatus(HttpServletResponse.SC_OK);
                    return;
                  } catch (Exception e) {
                    LOG.error("Error in direct executeFunction handling", e);
                    handleError(resp, e);
                    return;
                  }
                }

                session.handle(request)
                    .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                    .doOnSubscribe(s -> LOG.info("Request handler subscribed for method: {}", request.method()))
                    .doOnNext(result -> LOG.info("Got result from handler: {}", result))
                    .doOnSuccess(result -> {
                        try {
                            LOG.info("Processing successful result: {}", result);
                            // Create JSON-RPC response
                            Map<String, Object> response = new HashMap<>();
                            response.put("jsonrpc", "2.0");
                            response.put("id", request.id());
                            response.put("result", result);
                            
                            // Write response directly to HTTP response
                            String json = objectMapper.writeValueAsString(response);
                            responseWriter.write(json);
                            responseWriter.flush();
                            LOG.info("Sent response: {}", json);
                            
                            future.complete(null);
                        } catch (Exception e) {
                            LOG.error("Error sending response", e);
                            future.completeExceptionally(e);
                        }
                    })
                    .doOnError(error -> {
                        LOG.error("Handler failed for method: {} with error: {}", request.method(), error.getMessage(), error);
                        future.completeExceptionally(error);
                    })
                    .doOnCancel(() -> LOG.info("Handler cancelled for method: {}", request.method()))
                    .doFinally(signalType -> LOG.info("Handler finally for method: {} with signal: {}", request.method(), signalType))
                    .subscribe();
                    
                try {
                  future.get(30, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                  LOG.error("Request timed out after 30 seconds for method: {}", request.method());
                  handleError(resp, new RuntimeException("Request timed out"));
                  return;
                }
                
                resp.setStatus(HttpServletResponse.SC_OK);
              } catch (Exception e) {
                LOG.error("Error handling request", e);
                handleError(resp, e);
              }
            } else {
              resp.setStatus(HttpServletResponse.SC_ACCEPTED);
            }
          } catch (Exception e) {
            LOG.error("Error processing message", e);
            handleError(resp, e);
          }
        } else if (req.getMethod().equals("GET") && path.contains("/oauth/authorize")) {
          LOG.info("[{}] Handling OAuth authorize request", requestId);
          
          String redirectUri = req.getParameter("redirect_uri");
          String state = req.getParameter("state");
          String clientId = req.getParameter("client_id");
          
          if (redirectUri == null || state == null) {
            LOG.error("[{}] Missing required OAuth parameters", requestId);
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing required OAuth parameters");
            return;
          }
          
          LOG.info("[{}] OAuth parameters: redirect_uri={}, state={}, client_id={}", 
              requestId, redirectUri, state, clientId);

          String code = UUID.randomUUID().toString();

          String location = redirectUri + "?code=" + code + "&state=" + state;
          LOG.info("[{}] Redirecting to: {}", requestId, location);
          
          resp.setStatus(HttpServletResponse.SC_FOUND);
          resp.setHeader("Location", location);
          resp.getWriter().flush();
        } else if (req.getMethod().equals("POST") && path.contains("/oauth/token")) {
          LOG.info("[{}] Handling OAuth token request", requestId);
          String requestBodyStr;
          StringBuilder requestBody = new StringBuilder();
          String line;
          try (BufferedReader reader = req.getReader()) {
              while ((line = reader.readLine()) != null) {
                  requestBody.append(line);
              }
          }
          requestBodyStr = requestBody.toString();
          
          LOG.info("[{}] Token request body: {}", requestId, requestBodyStr);
          
          Map<String, Object> tokenResponse = new HashMap<>();
          tokenResponse.put("access_token", UUID.randomUUID().toString());
          tokenResponse.put("token_type", "Bearer");
          tokenResponse.put("expires_in", 3600);
          tokenResponse.put("refresh_token", UUID.randomUUID().toString());
          String responseJson = objectMapper.writeValueAsString(tokenResponse);
          LOG.info("[{}] Sending token response: {}", requestId, responseJson);
          
          resp.setStatus(HttpServletResponse.SC_OK);
          resp.setContentType("application/json");
          resp.getWriter().write(responseJson);
          resp.getWriter().flush();
        } else if (req.getMethod().equals("GET") && path.contains("/wait-for-auth")) {
          LOG.info("[{}] Handling wait-for-auth request", requestId);
          Map<String, Object> authStatusResponse = new HashMap<>();
          authStatusResponse.put("status", "complete");
          authStatusResponse.put("token", UUID.randomUUID().toString());
          String responseJson = objectMapper.writeValueAsString(authStatusResponse);
          LOG.info("[{}] Sending auth status response: {}", requestId, responseJson);
          
          resp.setStatus(HttpServletResponse.SC_OK);
          resp.setContentType("application/json");
          resp.getWriter().write(responseJson);
          resp.getWriter().flush();
        } else {
          // Only allow GET /sse or POST /message (which is handled above)
          LOG.warn("[{}] Sending 404 for unexpected request: {} {}", initialRequestId, method, path);
          resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
      } catch (Exception e) {
        LOG.error("[{}] Error processing request: {}", requestId, e.getMessage(), e);
        resp.sendError(500, "Internal Server Error: " + e.getMessage());
      } finally {
        long endTime = System.currentTimeMillis();
        LOG.info("[{}] Request completed in {} ms", requestId, (endTime - startTime));
      }
    }

    private void handleError(HttpServletResponse resp, Throwable error) {
        try {
            Map<String, Object> errorObj = new HashMap<>();
            errorObj.put("code", McpSchema.ErrorCodes.INTERNAL_ERROR);
            errorObj.put("message", "Internal error: " + error.getMessage());

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("jsonrpc", "2.0");
            errorResponse.put("id", "error-" + UUID.randomUUID());
            errorResponse.put("error", errorObj);

            String errorJson = objectMapper.writeValueAsString(errorResponse);
            LOG.info("Sending error response: {}", errorJson);
            resp.getWriter().write(errorJson);
            resp.getWriter().flush();
            resp.flushBuffer();
        } catch (Exception e) {
            LOG.error("Failed to send error response", e);
        }
    }
  }

  private McpServerSession createSession(String sessionId, McpServerTransport transport) {
    LOG.info("Creating new session with ID: {}", sessionId);
    Map<String, McpServerSession.RequestHandler<?>> requestHandlers = createRequestHandlers();
    LOG.info("Created request handlers: {}", requestHandlers.keySet());
    McpServerSession session = new McpServerSession(
        sessionId,
        transport,
        createInitHandler(),
        createInitNotificationHandler(),
        requestHandlers,
        createNotificationHandlers()
    );
    
    sessions.put(sessionId, session);
    LOG.info("Created and stored new session with ID: {} and handlers: {}", sessionId, requestHandlers.keySet());
    return session;
  }

  /**
   * Creates the request handlers map.
   */
  private Map<String, McpServerSession.RequestHandler<?>> createRequestHandlers() {
    // Return the cached handlers instead of creating new ones
    LOG.info("Returning cached handlers: {}", cachedHandlers.keySet());
    return cachedHandlers;
  }


  private McpServerSession.InitRequestHandler createInitHandler() {
    return request -> {
      McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder()
          .tools(true)
          .build();
      McpSchema.Implementation serverInfo = new McpSchema.Implementation(
          mcpConfig.getMcpServerName(),
          mcpConfig.getMcpServerVersion()
      );
      
      McpSchema.InitializeResult result = new McpSchema.InitializeResult(
          "2024-11-05",  // Protocol version
          capabilities,   // Server capabilities
          serverInfo,     // Server info
          null            // Instructions (optional)
      );
      return Mono.just(result);
    };
  }
  

  private McpServerSession.InitNotificationHandler createInitNotificationHandler() {
    return () -> Mono.empty();
  }

  private Map<String, McpServerSession.NotificationHandler> createNotificationHandlers() {
    return new HashMap<>();
  }

  public ServletHolder getServletHolder() {
    LOG.info("Creating MCP servlet holder");
    MCPBridgeServlet servlet = new MCPBridgeServlet();
    ServletHolder servletHolder = new ServletHolder(servlet);
    servletHolder.setName("mcp-bridge");
    return servletHolder;
  }
}