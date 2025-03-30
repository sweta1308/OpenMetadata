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

package org.openmetadata.service.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MCPConfiguration {
  @JsonProperty("enabled")
  private boolean enabled = true;

  @JsonProperty("websocketEndpoint")
  @NotEmpty
  private String websocketEndpoint = "/v1/mcp/ws";
  
  @JsonProperty("allowedOrigins")
  private String allowedOrigins = "*";
  
  @JsonProperty("maxSessionIdleTimeMinutes")
  private int maxSessionIdleTimeMinutes = 30;
  
  @JsonProperty("maxTextMessageSize")
  private int maxTextMessageSize = 65536; // 64KB
  
  @JsonProperty("pingTimeoutSeconds")
  private int pingTimeoutSeconds = 60;
  
  @JsonProperty("jsonRpcEndpoint")
  private String jsonRpcEndpoint = "/v1/mcp/jsonrpc";
  
  // MCP SDK specific configuration
  @JsonProperty("mcpPort")
  private int mcpPort = 8586;
  
  @JsonProperty("mcpServerName")
  private String mcpServerName = "openmetadata-mcp";
  
  @JsonProperty("mcpServerVersion")
  private String mcpServerVersion = "1.0.0";
}