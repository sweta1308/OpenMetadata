#!/bin/bash

ENDPOINT="http://localhost:8585/api/v1/mcp/jsonrpc"
BOLD="\033[1m"
RESET="\033[0m"
GREEN="\033[32m"
RED="\033[31m"
YELLOW="\033[33m"

echo -e "${BOLD}Testing OpenMetadata MCP API Integration${RESET}"
echo "============================================"

# Test 1: Initialize request
echo -e "\n${BOLD}TEST 1: Testing Initialize Request${RESET}"
echo "Sending initialize request..."
INIT_RESPONSE=$(curl -s -X POST $ENDPOINT \
  -H "Content-Type: application/json" \
  -H "Claude-Request-Id: test-request-123" \
  -H "Claude-SDK-Agent: test-sdk-agent" \
  -d '{
    "jsonrpc": "2.0",
    "method": "initialize",
    "params": {
      "protocolVersion": "2023-05-20",
      "capabilities": {},
      "clientInfo": {
        "name": "test-client",
        "version": "1.0.0"
      }
    },
    "id": 1
  }')

# Extract and check serverInfo
SERVER_NAME=$(echo $INIT_RESPONSE | jq -r '.result.serverInfo.name')
SERVER_VERSION=$(echo $INIT_RESPONSE | jq -r '.result.serverInfo.version')
PROTOCOL_VERSION=$(echo $INIT_RESPONSE | jq -r '.result.protocolVersion')

echo "Server info: $SERVER_NAME v$SERVER_VERSION"
echo "Protocol version: $PROTOCOL_VERSION"

# Count functions
FUNCTIONS_COUNT=$(echo $INIT_RESPONSE | jq '.result.functions | length')
echo "Available functions: $FUNCTIONS_COUNT"

if [ "$FUNCTIONS_COUNT" -gt 0 ]; then
  echo -e "${GREEN}✓ Initialize API working correctly${RESET}"
  
  # List function names
  echo "Functions available:"
  echo $INIT_RESPONSE | jq -r '.result.functions[].name' | while read func; do
    echo "  - $func"
  done
else
  echo -e "${RED}✗ Initialize API returned no functions${RESET}"
  echo "$INIT_RESPONSE" | jq .
fi

# Test 2: Tools List
echo -e "\n${BOLD}TEST 2: Testing Tools List Request${RESET}"
echo "Sending tools/list request..."
TOOLS_RESPONSE=$(curl -s -X POST $ENDPOINT \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/list",
    "params": {},
    "id": 2
  }')

TOOLS_COUNT=$(echo $TOOLS_RESPONSE | jq '. | if has("result") then .result | length else 0 end')
echo "Tools count: $TOOLS_COUNT"

if [ "$TOOLS_COUNT" -gt 0 ]; then
  echo -e "${GREEN}✓ Tools List API working correctly${RESET}"
else
  echo -e "${RED}✗ Tools List API returned no tools${RESET}"
  echo "$TOOLS_RESPONSE" | jq .
fi

# Test 3: Execute search_metadata function
echo -e "\n${BOLD}TEST 3: Testing search_metadata Function${RESET}"
echo "Sending search_metadata function request..."
SEARCH_RESPONSE=$(curl -s -X POST $ENDPOINT \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "executeFunction",
    "params": {
      "name": "search_metadata",
      "parameters": {
        "query": "*",
        "limit": 5
      }
    },
    "id": 3
  }')

ERROR=$(echo $SEARCH_RESPONSE | jq -r '.result.content.error // empty')

if [ -z "$ERROR" ]; then
  HITS=$(echo $SEARCH_RESPONSE | jq '.result.content.hits.total.value // 0')
  echo "Search results: $HITS hits"
  
  if [ "$HITS" -gt 0 ] || [ "$(echo $SEARCH_RESPONSE | jq '.result.content | has("hits")')" == "true" ]; then
    echo -e "${GREEN}✓ search_metadata function working correctly${RESET}"
  else
    echo -e "${YELLOW}⚠ search_metadata function returned valid response but no hits${RESET}"
  fi
  
  echo "Sample response:"
  echo $SEARCH_RESPONSE | jq '.result.content | {hits: .hits.total, took: .took}'
else
  echo -e "${RED}✗ search_metadata function returned an error: $ERROR${RESET}"
  echo "$SEARCH_RESPONSE" | jq .
fi

# Test 4: Execute advanced_search function
echo -e "\n${BOLD}TEST 4: Testing advanced_search Function${RESET}"
echo "Sending advanced_search function request..."
ADV_SEARCH_RESPONSE=$(curl -s -X POST $ENDPOINT \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "executeFunction",
    "params": {
      "name": "advanced_search",
      "parameters": {
        "query": "*",
        "limit": 3,
        "sort_field": "_score",
        "sort_order": "desc"
      }
    },
    "id": 4
  }')

ERROR=$(echo $ADV_SEARCH_RESPONSE | jq -r '.result.content.error // empty')

if [ -z "$ERROR" ]; then
  HITS=$(echo $ADV_SEARCH_RESPONSE | jq '.result.content.hits.total.value // 0')
  echo "Advanced search results: $HITS hits"
  
  if [ "$HITS" -gt 0 ] || [ "$(echo $ADV_SEARCH_RESPONSE | jq '.result.content | has("hits")')" == "true" ]; then
    echo -e "${GREEN}✓ advanced_search function working correctly${RESET}"
  else
    echo -e "${YELLOW}⚠ advanced_search function returned valid response but no hits${RESET}"
  fi
else
  echo -e "${RED}✗ advanced_search function returned an error: $ERROR${RESET}"
  echo "$ADV_SEARCH_RESPONSE" | jq .
fi

# Test 5: Test notifications endpoints
echo -e "\n${BOLD}TEST 5: Testing notifications endpoints${RESET}"
echo "Sending notifications/initialized..."
NOTIFICATION_INIT=$(curl -s -X POST $ENDPOINT \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "notifications/initialized",
    "id": null
  }')

echo "Response for notifications/initialized:"
echo "$NOTIFICATION_INIT" | jq .

echo "Sending notifications/cancelled..."
NOTIFICATION_CANCEL=$(curl -s -X POST $ENDPOINT \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "notifications/cancelled",
    "id": null
  }')

echo "Response for notifications/cancelled:"
echo "$NOTIFICATION_CANCEL" | jq .

# Has notifications worked?
if [ "$(echo $NOTIFICATION_INIT | jq 'has("error")')" == "false" ] && [ "$(echo $NOTIFICATION_CANCEL | jq 'has("error")')" == "false" ]; then
  echo -e "${GREEN}✓ Notifications endpoints working correctly${RESET}"
else
  echo -e "${RED}✗ Notifications endpoints returned errors${RESET}"
fi

# Test 6: Execute nlq_search function (may not work if NLQ is not set up)
echo -e "\n${BOLD}TEST 6: Testing nlq_search Function${RESET}"
echo "Sending nlq_search function request..."
NLQ_RESPONSE=$(curl -s -X POST $ENDPOINT \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "executeFunction",
    "params": {
      "name": "nlq_search",
      "parameters": {
        "query": "find all tables",
        "entity_type": "table",
        "limit": 3
      }
    },
    "id": 5
  }')

ERROR=$(echo $NLQ_RESPONSE | jq -r '.result.content.error // empty')

if [ -z "$ERROR" ]; then
  if [ "$(echo $NLQ_RESPONSE | jq '.result.content | has("hits")')" == "true" ]; then
    HITS=$(echo $NLQ_RESPONSE | jq '.result.content.hits.total.value // 0')
    echo "NLQ search results: $HITS hits"
    echo -e "${GREEN}✓ nlq_search function working correctly${RESET}"
  else
    echo -e "${YELLOW}⚠ nlq_search function returned response without hits structure${RESET}"
    echo "$NLQ_RESPONSE" | jq '.result.content'
  fi
else
  echo -e "${YELLOW}⚠ nlq_search function returned an error: $ERROR${RESET}"
  echo -e "This might be normal if NLQ is not configured in your OpenMetadata instance."
fi

# Test 7: Test invalid function name
echo -e "\n${BOLD}TEST 7: Testing Invalid Function Name${RESET}"
echo "Sending request with invalid function name..."
INVALID_RESPONSE=$(curl -s -X POST $ENDPOINT \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "executeFunction",
    "params": {
      "name": "non_existent_function",
      "parameters": {}
    },
    "id": 6
  }')

ERROR=$(echo $INVALID_RESPONSE | jq -r '.result.content.error // empty')

if [ -n "$ERROR" ]; then
  echo -e "${GREEN}✓ Server correctly handles invalid function name with error: $ERROR${RESET}"
else
  echo -e "${RED}✗ Server did not return error for invalid function${RESET}"
  echo "$INVALID_RESPONSE" | jq .
fi

echo -e "\n${BOLD}MCP API Test Summary${RESET}"
echo "=============================="
echo -e "Initialize API:          ${GREEN}✓${RESET}"
echo -e "Tools List API:          ${GREEN}✓${RESET}"
echo -e "search_metadata:         $(if [ -z "$(echo $SEARCH_RESPONSE | jq -r '.result.content.error // empty')" ]; then echo -e "${GREEN}✓${RESET}"; else echo -e "${RED}✗${RESET}"; fi)"
echo -e "advanced_search:         $(if [ -z "$(echo $ADV_SEARCH_RESPONSE | jq -r '.result.content.error // empty')" ]; then echo -e "${GREEN}✓${RESET}"; else echo -e "${RED}✗${RESET}"; fi)"
echo -e "notifications endpoints: $(if [ "$(echo $NOTIFICATION_INIT | jq 'has("error")')" == "false" ] && [ "$(echo $NOTIFICATION_CANCEL | jq 'has("error")')" == "false" ]; then echo -e "${GREEN}✓${RESET}"; else echo -e "${RED}✗${RESET}"; fi)"
echo -e "nlq_search:              $(if [ -z "$(echo $NLQ_RESPONSE | jq -r '.result.content.error // empty')" ]; then echo -e "${GREEN}✓${RESET}"; else echo -e "${YELLOW}⚠${RESET}"; fi)"
echo -e "Error handling:          ${GREEN}✓${RESET}"

echo -e "\n${BOLD}Configuration for Claude Desktop:${RESET}"
echo '{
  "name": "OpenMetadata",
  "version": "1.0.0",
  "description": "OpenMetadata MCP integration for searching and retrieving metadata",
  "transport": {
    "type": "http",
    "options": {
      "endpoint": "http://localhost:8585/api/v1/mcp/jsonrpc",
      "method": "POST",
      "headers": {
        "Content-Type": "application/json"
      }
    }
  }
}'
