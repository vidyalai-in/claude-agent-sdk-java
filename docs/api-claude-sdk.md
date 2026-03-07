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

## Session History Methods

### listSessions()

```java
public static List<SDKSessionInfo> listSessions()
```

List all sessions across all projects, sorted by most-recently-modified first. Reads from `~/.claude/projects/` without fully parsing JSONL files — only first and last 64 KB per file.

**Returns**: `List<SDKSessionInfo>` sorted by last-modified descending

### listSessions(Path directory)

```java
public static List<SDKSessionInfo> listSessions(Path directory)
```

List sessions for a specific project directory.

**Parameters**:
- `directory` - the project working directory to filter by

**Returns**: `List<SDKSessionInfo>`

### listSessions(Path directory, Integer limit, boolean includeWorktrees)

```java
public static List<SDKSessionInfo> listSessions(
    Path directory,
    Integer limit,
    boolean includeWorktrees
)
```

List sessions with full control.

**Parameters**:
- `directory` - project directory to filter by (null = all projects)
- `limit` - maximum sessions to return (null = no limit)
- `includeWorktrees` - whether to include git worktree directories

**Returns**: `List<SDKSessionInfo>`

### getSessionMessages(String sessionId)

```java
public static List<SessionMessage> getSessionMessages(String sessionId)
```

Return the full conversation messages for a session. Searches all project directories.

**Parameters**:
- `sessionId` - UUID of the session

**Returns**: `List<SessionMessage>` in conversation order

### getSessionMessages(String sessionId, Path directory)

```java
public static List<SessionMessage> getSessionMessages(
    String sessionId,
    Path directory
)
```

Return messages for a session in a specific project.

**Parameters**:
- `sessionId` - UUID of the session
- `directory` - project working directory to search in

**Returns**: `List<SessionMessage>`

### getSessionMessages(String sessionId, Path directory, Integer limit, int offset)

```java
public static List<SessionMessage> getSessionMessages(
    String sessionId,
    Path directory,
    Integer limit,
    int offset
)
```

Return messages with full control over filtering.

**Parameters**:
- `sessionId` - UUID of the session
- `directory` - project directory to search in (null = all projects)
- `limit` - maximum messages to return (null = no limit)
- `offset` - number of messages to skip from the start

**Returns**: `List<SessionMessage>`

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
- [Session History Guide](./feature-session-history.md)
