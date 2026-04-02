# ClaudeSDKClient API Reference

Interactive client for bidirectional conversations.

## Class Overview

```java
public class ClaudeSDKClient implements AutoCloseable
```

Client for stateful, interactive conversations with Claude.

## Constructor

```java
public ClaudeSDKClient()
public ClaudeSDKClient(ClaudeAgentOptions options)
```

## Connection Methods

### connect()

```java
public void connect() throws CLIConnectionException
```

Establish connection to Claude Code CLI.

**Thread Safety**: Thread-safe, idempotent

**Throws**: `IllegalStateException` if called after close()

### connect(String initialMessage)

```java
public void connect(String initialMessage) throws CLIConnectionException
```

Connect and send initial message.

### isConnected()

```java
public boolean isConnected()
```

Check if connected.

**Returns**: `boolean`

### disconnect() / close()

```java
public void disconnect()
public void close()
```

Close connection and cleanup resources.

**Thread Safety**: Thread-safe, idempotent

## Sending Messages

### sendMessage(String prompt)

```java
public void sendMessage(String prompt)
```

Send message and continue receiving.

### sendMessage(String prompt, String sessionId)

```java
public void sendMessage(String prompt, String sessionId)
```

Send message to specific session.

### query(String prompt)

```java
public List<Message> query(String prompt)
```

Send message and wait for complete response.

**Returns**: `List<Message>` - Messages until ResultMessage

### query(String prompt, String sessionId)

```java
public List<Message> query(String prompt, String sessionId)
```

Query specific session.

### query(Iterator<Map<String, Object>> messageStream)

```java
public List<Message> query(Iterator<Map<String, Object>> messageStream)
```

Send multiple messages as stream.

## Receiving Messages

### receiveMessages()

```java
public Iterator<Message> receiveMessages()
```

Get iterator over all messages (continuous).

**Thread Safety**: Thread-safe, but messages distributed across multiple iterators

**Returns**: `Iterator<Message>`

### receiveResponse()

```java
public Iterable<Message> receiveResponse()
```

Get messages until next ResultMessage (auto-closes).

**Thread Safety**: Thread-safe

**Returns**: `Iterable<Message>`

## Control Methods

### interrupt()

```java
public void interrupt()
```

Interrupt current execution.

**Thread Safety**: Thread-safe

### setModel(String model)

```java
public void setModel(String model)
```

Change AI model.

**Parameters**: `model` - Model name (e.g., "claude-opus-4-6")

### setPermissionMode(PermissionMode mode)

```java
public void setPermissionMode(PermissionMode mode)
```

Change permission mode.

**Parameters**: `mode` - New permission mode

### rewindFiles(String userMessageId)

```java
public void rewindFiles(String userMessageId)
```

Rewind files to state at user message (requires checkpointing).

**Parameters**: `userMessageId` - Message ID to rewind to

### getMcpStatus()

```java
public Map<String, Object> getMcpStatus()
```

Get MCP server connection status.

**Returns**: `Map<String, Object>` - Status information

### getContextUsage()

```java
public ContextUsageResponse getContextUsage()
```

Get a breakdown of current context window usage by category.

Returns the same data shown by the `/context` command in the CLI, including token counts per category, total usage, and detailed breakdowns of MCP tools, memory files, and agents.

**Returns**: `ContextUsageResponse` with fields:
- `categories` — List of `ContextUsageCategory` (name, tokens, color)
- `totalTokens` — Total tokens in the context window
- `maxTokens` — Effective context limit
- `percentage` — Percent of context used (0-100)
- `model` — Model name
- Plus optional fields: `autoCompactThreshold`, `memoryFiles`, `mcpTools`, `agents`, etc.

**Throws**: `CLIConnectionException` if not connected

### getServerInfo()

```java
public Map<String, Object> getServerInfo()
```

Get server initialization info.

**Returns**: `Map<String, Object>` - Server information

## Thread Safety

- **connect()**: Thread-safe, synchronized
- **send methods**: Thread-safe
- **receive methods**: Thread-safe, but share queue
- **control methods**: Thread-safe
- **close()**: Thread-safe, idempotent

## Resource Management

Always use try-with-resources:

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    // Use client
}
```

## See Also
- [Interactive Conversations Guide](./feature-interactive-conversations.md)
