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

package org.openmetadata.service.socket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.socket.engineio.server.EngineIoServer;
import io.socket.engineio.server.EngineIoServerOptions;
import io.socket.socketio.server.SocketIoNamespace;
import io.socket.socketio.server.SocketIoServer;
import io.socket.socketio.server.SocketIoSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.service.config.MCPConfiguration;
import org.openmetadata.service.resources.mcp.MCPResource;
import org.openmetadata.service.util.JsonUtils;

@Slf4j
public class MCPWebSocketManager {
  private static MCPWebSocketManager instance;
  @Getter private final EngineIoServer engineIoServer;
  @Getter private final SocketIoServer socketIoServer;
  public static final String MCP_INITIALIZE = "initialize";
  public static final String MCP_EXECUTE_FUNCTION = "executeFunction";
  public static final String MCP_FUNCTION_RESPONSE = "functionResponse";
  public static final String MCP_ERROR = "error";

  @Getter
  private final Map<String, SocketIoSocket> mcpSessions = new ConcurrentHashMap<>();

  private MCPWebSocketManager(EngineIoServerOptions eiOptions) {
    engineIoServer = new EngineIoServer(eiOptions);
    socketIoServer = new SocketIoServer(engineIoServer);
    initializeHandlers();
  }

  private void initializeHandlers() {
    SocketIoNamespace ns = socketIoServer.namespace("/");
    
    // On Connection
    ns.on(
        "connection",
        args -> {
          SocketIoSocket socket = (SocketIoSocket) args[0];
          List<String> remoteAddress = socket.getInitialHeaders().get("RemoteAddress");
          
          LOG.info(
              "MCP Client with socket ID: {} and remote address: {} connected",
              socket.getId(),
              remoteAddress);
          
          // Store the socket connection
          mcpSessions.put(socket.getId(), socket);
          
          // Handle disconnect
          socket.on(
              "disconnect",
              disconnectArgs -> {
                LOG.info(
                    "MCP Client with socket ID: {} and remote address: {} disconnected",
                    socket.getId(),
                    remoteAddress);
                mcpSessions.remove(socket.getId());
              });
          
          // Handle connection errors
          socket.on(
              "connect_error",
              errorArgs -> 
                  LOG.error(
                      "MCP connection error for socket ID: {} and remote address: {}",
                      socket.getId(),
                      remoteAddress));
          
          // Handle connection failures
          socket.on(
              "connect_failed",
              failArgs -> 
                  LOG.error(
                      "MCP connection failed for socket ID: {} and remote address: {}",
                      socket.getId(),
                      remoteAddress));
          
          // Initialize event handling
          setupEventHandlers(socket);
        });
    
    ns.on("error", args -> LOG.error("MCP WebSocket server connection error"));
  }
  
  private void setupEventHandlers(SocketIoSocket socket) {
    MCPResource mcpResource = MCPWebSocketConfigurator.getMcpResource();
    if (mcpResource == null) {
      LOG.error("MCP Resource not initialized. Unable to handle MCP events.");
      return;
    }

    // Handle initialize request
    socket.on(
        MCP_INITIALIZE,
        args -> {
          try {
            String messageJson = (String) args[0];
            JsonNode data = JsonUtils.readTree(messageJson);
            LOG.info("Received initialize request: {}", data);
            
            JsonNode response = mcpResource.handleInitialize(data);
            socket.send(MCP_INITIALIZE, JsonUtils.writeValueAsString(response));
          } catch (Exception e) {
            LOG.error("Error handling initialize request", e);
            sendErrorResponse(socket, "Error handling initialize request: " + e.getMessage(), "INITIALIZATION_ERROR");
          }
        });
    
    // Handle execute function request
    socket.on(
        MCP_EXECUTE_FUNCTION,
        args -> {
          try {
            String messageJson = (String) args[0];
            JsonNode data = JsonUtils.readTree(messageJson);
            LOG.debug("Received execute function request: {}", data);
            
            JsonNode response = mcpResource.handleExecuteFunction(data);
            socket.send(MCP_FUNCTION_RESPONSE, JsonUtils.writeValueAsString(response));
          } catch (Exception e) {
            LOG.error("Error handling execute function request", e);
            sendErrorResponse(socket, "Error handling execute function: " + e.getMessage(), "EXECUTION_ERROR");
          }
        });
  }
  
  private void sendErrorResponse(SocketIoSocket socket, String message, String code) {
    try {
      Map<String, Object> error = new HashMap<>();
      error.put("message", message);
      error.put("code", code);
      
      Map<String, Object> response = new HashMap<>();
      response.put("type", MCP_ERROR);
      response.put("data", error);
      
      socket.send(MCP_ERROR, JsonUtils.writeValueAsString(response));
    } catch (JsonProcessingException e) {
      LOG.error("Error sending error response", e);
    }
  }

  public static MCPWebSocketManager getInstance() {
    return instance;
  }

  public static class MCPWebSocketManagerBuilder {
    private MCPWebSocketManagerBuilder() {}

    public static void build(MCPConfiguration mcpConfig) {
      EngineIoServerOptions eioOptions = EngineIoServerOptions.newFromDefault();
      eioOptions.setAllowedCorsOrigins(null);
      eioOptions.setPingTimeout(mcpConfig.getPingTimeoutSeconds() * 1000);
      eioOptions.setPingInterval(mcpConfig.getPingTimeoutSeconds() * 500);
      
      instance = new MCPWebSocketManager(eioOptions);
    }
  }
}