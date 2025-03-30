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

import org.openmetadata.service.config.MCPConfiguration;
import org.openmetadata.service.resources.mcp.MCPResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Helper class for MCP WebSocket endpoint.
 * This class helps configure MCP WebSocket functionality and facilitates access to the MCP resource.
 */
@Slf4j
public class MCPWebSocketConfigurator {
  private static MCPResource mcpResource;
  private static MCPConfiguration mcpConfiguration;
  
  /**
   * Initialize the configurator with the MCPResource instance.
   *
   * @param resource MCP resource instance
   * @param config MCP configuration
   */
  public static void initialize(MCPResource resource, MCPConfiguration config) {
    mcpResource = resource;
    mcpConfiguration = config;
    LOG.info("MCPWebSocketConfigurator initialized with configuration: {}", config);
  }
  
  /**
   * Get the MCP resource.
   *
   * @return MCP resource instance
   */
  public static MCPResource getMcpResource() {
    return mcpResource;
  }
  
  /**
   * Get the MCP configuration.
   * 
   * @return MCP configuration
   */
  public static MCPConfiguration getConfiguration() {
    return mcpConfiguration;
  }
}