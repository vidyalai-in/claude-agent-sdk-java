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
public ClaudeSDKClient(ClaudeAgentOptions options, Transport transport)
```

With a non-null `transport`, the client uses it instead of spawning the CLI
subprocess. The SDK calls its `connect()`, writes prompts through `write()`, and
sends hooks, agents and the other initialize-request settings over the control
protocol. The CLI-flag options (model, cwd, permission mode, tools, and the
rest the subprocess transport turns into flags) are not applied to a custom
transport, and store-backed session resume is skipped.

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
public void query(String prompt)
```

Send a message. Returns as soon as the prompt is written — read the reply with
[`receiveResponse()`](#receiveresponse) or [`receiveMessages()`](#receivemessages).

With `verbatimPrompts(true)` on the options, every prompt this client writes —
from `connect(String)`, `sendMessage`, and both `query` forms — is marked
`client_composed: true`, so Claude Code delivers it as written (no `@path`
expansion, no slash-command dispatch). Streamed messages are stamped on a copy.

Equivalent to `query(prompt, "default")`.

### query(String prompt, String sessionId)

```java
public void query(String prompt, String sessionId)
```

Query a specific session.

### query(Iterator&lt;Map&lt;String, Object&gt;&gt; messageStream)

```java
public void query(Iterator<Map<String, Object>> messageStream)
public void query(Iterator<Map<String, Object>> messageStream, String sessionId)
```

Send raw message maps. Reach for this rather than `query(String)` when a message
needs fields the string form does not build — `origin` attribution, structured
content blocks, an explicit `uuid`:

```java
Map<String, Object> message = new HashMap<>();
message.put("type", "user");
message.put("message", Map.of("role", "user", "content", "Reply with exactly: one"));
message.put("parent_tool_use_id", null);
message.put("origin", Map.of("kind", "human"));   // attribute the turn

client.query(List.of(message).iterator());
for (Message msg : client.receiveResponse()) { /* ... */ }
```

`session_id` is filled in with `"default"` — or with `sessionId` on the
two-argument overload — on any message that omits it. The caller's maps are
copied rather than mutated when that field is added, so passing an immutable
map is safe.

Like `query(String)`, this leaves the CLI's stdin open, so it can be called
repeatedly across a session and mixed freely with the string overload.

> **Changed in v0.1.23.** This method previously handed the iterator to the
> internal one-shot streaming path, which closes stdin once the iterator is
> exhausted. That ended the session: the CLI exited, and the next `query()` or
> `sendMessage()` failed with `ProcessTransport is not ready for writing`. It
> now writes directly, matching the Python SDK. Callers that relied on the old
> behavior to terminate a session should call `disconnect()` (or use
> try-with-resources) instead.

The messages are written before the call returns, which keeps successive calls
in order. Drive a lazy or unbounded iterator from your own thread if you need to
read responses while it is still producing.

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

**Parameters**: `model` - Model name or alias (e.g. `"claude-sonnet-5"`, `"haiku"`), or `null` for the default. The CLI checks full model IDs against the API, so a retired ID fails; an alias is mapped to a current model.

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

Rewind tracked files to their state at a user message.

Requires `enableFileCheckpointing(true)` to track file changes, and
`extraArgs(Map.of("replay-user-messages", ""))` so that `UserMessage` objects in
the stream carry the `uuid` to rewind to.

**Parameters**: `userMessageId` - UUID of the user message to rewind to

### getMcpStatus()

```java
public McpStatusResponse getMcpStatus()
```

Get MCP server connection status.

**Returns**: `McpStatusResponse` - its `mcpServers()` lists one `McpServerStatus` per server

### reconnectMcpServer(String serverName)

```java
public void reconnectMcpServer(String serverName)
```

Retry connecting to an MCP server that failed to connect or was disconnected.

### toggleMcpServer(String serverName, boolean enabled)

```java
public void toggleMcpServer(String serverName, boolean enabled)
```

Enable or disable an MCP server. Disabling disconnects it and removes its tools
from the available set; enabling reconnects it and makes them available again.

### stopTask(String taskId)

```java
public void stopTask(String taskId)
```

Stop a running background task.

**Parameters**: `taskId` - The task ID from a `TaskStartedMessage`

After this returns, the CLI reports the task's end as a `TaskUpdatedMessage`
whose status is terminal (`"killed"` for a stopped task). A
`TaskNotificationMessage` with status `"stopped"` may follow, but is sometimes
suppressed, so treat a terminal status from either message as the end (see
`TaskUpdatedMessage.TERMINAL_TASK_STATUSES`).

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

Get server initialization info: available commands, output styles and server
capabilities.

**Returns**: `Map<String, Object>` - The info from the `initialize` response
(`null` only if the CLI returned none)

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
