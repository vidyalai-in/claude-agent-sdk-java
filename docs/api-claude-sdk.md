# ClaudeSDK API Reference

Static facade for simple queries and client creation.

## Class Overview

```java
public final class ClaudeSDK
```

Utility class providing static methods for common SDK operations.

## Query Methods

### query(String prompt)

```java
public static List<Message> query(String prompt)
```

Execute query with default options.

**Returns**: `List<Message>`

### query(String prompt, ClaudeAgentOptions options)

```java
public static List<Message> query(
    String prompt,
    ClaudeAgentOptions options
)
```

Execute query with custom options.

**Parameters**:
- `prompt` - The prompt
- `options` - Configuration options

**Returns**: `List<Message>`

**Throws**:
- `IllegalArgumentException` - If canUseTool is set (requires streaming)
- `CLIConnectionException` - Connection failed
- `ProcessException` - CLI process failed

### query(Iterator<Map<String, Object>> messageStream, ClaudeAgentOptions options)

```java
public static List<Message> query(
    Iterator<Map<String, Object>> messageStream,
    ClaudeAgentOptions options
)
```

Execute streaming query with multiple messages.

**Parameters**:
- `messageStream` - Iterator of message dictionaries
- `options` - Configuration options

**Returns**: `List<Message>`

## Convenience Methods

### queryForText(String prompt, ClaudeAgentOptions options)

```java
public static String queryForText(
    String prompt,
    ClaudeAgentOptions options
)
```

Get only text content from assistant messages.

**Returns**: `String` - Combined text

### queryForResult(String prompt, ClaudeAgentOptions options)

```java
public static ResultMessage queryForResult(
    String prompt,
    ClaudeAgentOptions options
)
```

Get only the result message.

**Returns**: `ResultMessage` or null

## Client Factory Methods

### createClient()

```java
public static ClaudeSDKClient createClient()
```

Create client with default options.

**Returns**: `ClaudeSDKClient`

### createClient(ClaudeAgentOptions options)

```java
public static ClaudeSDKClient createClient(
    ClaudeAgentOptions options
)
```

Create client with custom options.

**Returns**: `ClaudeSDKClient`

## MCP Server Factory Methods

### createSdkMcpServer(String name, List<SdkMcpTool<?>> tools)

```java
public static McpSdkServerConfig createSdkMcpServer(
    String name,
    List<SdkMcpTool<?>> tools
)
```

Create SDK MCP server from tools list.

**Parameters**:
- `name` - Server name
- `tools` - List of tools

**Returns**: `McpSdkServerConfig`

### createSdkMcpServer(String name, String version, List<SdkMcpTool<?>> tools)

```java
public static McpSdkServerConfig createSdkMcpServer(
    String name,
    String version,
    List<SdkMcpTool<?>> tools
)
```

Create SDK MCP server with version.

### createSdkMcpServer(String name, Object instance)

```java
public static McpSdkMcpServer createSdkMcpServer(
    String name,
    Object instance
)
```

Create SDK MCP server from @Tool annotated methods.

**Parameters**:
- `name` - Server name
- `instance` - Object with @Tool methods

**Returns**: `McpSdkServerConfig`

## Version Method

### getVersion()

```java
public static String getVersion()
```

Get SDK version string.

**Returns**: Version (e.g., "0.1.3-SNAPSHOT")

## See Also
- [Simple Queries Guide](./feature-simple-queries.md)
- [MCP Servers Guide](./feature-mcp-servers.md)
