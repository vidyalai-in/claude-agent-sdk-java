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

### getSessionInfo(String sessionId)

```java
@Nullable
public static SDKSessionInfo getSessionInfo(String sessionId)
```

Look up a single session by ID. Searches all project directories under `~/.claude/projects/`. No O(n) directory scan — reads only the target session file.

**Parameters**:
- `sessionId` - UUID of the session to look up

**Returns**: `SDKSessionInfo` for the session, or `null` if not found, is a sidechain session, or has no extractable summary

### getSessionInfo(String sessionId, Path directory)

```java
@Nullable
public static SDKSessionInfo getSessionInfo(
    String sessionId,
    Path directory
)
```

Look up a single session by ID within a specific project directory.

**Parameters**:
- `sessionId` - UUID of the session to look up
- `directory` - project working directory to search in

**Returns**: `SDKSessionInfo` or `null`

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

## Subagent Transcript Methods

When a session spawns subagents (via the `Task` tool or programmatic agent definitions), each subagent's transcript is written to `~/.claude/projects/<project>/<sessionId>/subagents/agent-<agentId>.jsonl`. These files may also live under nested directories such as `subagents/workflows/<runId>/`.

### listSubagents(String sessionId)

```java
public static List<String> listSubagents(String sessionId)
```

List subagent IDs for a session by scanning the session's `subagents/` directory across all project directories.

**Parameters**:
- `sessionId` - UUID of the parent session

**Returns**: `List<String>` of subagent IDs. Empty when the session is not found, the `sessionId` is not a valid UUID, or the session has no subagents.

### listSubagents(String sessionId, Path directory)

```java
public static List<String> listSubagents(String sessionId, Path directory)
```

List subagent IDs scoped to a specific project directory.

**Parameters**:
- `sessionId` - UUID of the parent session
- `directory` - Project working directory to find the session in

### getSubagentMessages(String sessionId, String agentId)

```java
public static List<SessionMessage> getSubagentMessages(
    String sessionId,
    String agentId
)
```

Read a subagent's user/assistant messages from its JSONL transcript. Walks `parentUuid` links to reconstruct the chain.

**Parameters**:
- `sessionId` - UUID of the parent session
- `agentId` - Subagent ID (as returned by `listSubagents`)

**Returns**: `List<SessionMessage>` in chronological order. Empty when the session or subagent is not found, the `sessionId` is not a valid UUID, or the transcript contains no user/assistant messages.

### getSubagentMessages(String sessionId, String agentId, Path directory)

```java
public static List<SessionMessage> getSubagentMessages(
    String sessionId,
    String agentId,
    Path directory
)
```

Read a subagent's messages scoped to a specific project directory.

### getSubagentMessages(String sessionId, String agentId, Path directory, Integer limit, int offset)

```java
public static List<SessionMessage> getSubagentMessages(
    String sessionId,
    String agentId,
    @Nullable Path directory,
    @Nullable Integer limit,
    int offset
)
```

Read subagent messages with full control over filtering and pagination.

**Parameters**:
- `sessionId` - UUID of the parent session
- `agentId` - Subagent ID
- `directory` - Project directory to search in (null = all projects)
- `limit` - Maximum messages to return (null or `0` = no limit)
- `offset` - Number of messages to skip from the start

## Session Mutation Methods

### renameSession(String sessionId, String title)

```java
public static void renameSession(
    String sessionId,
    String title
) throws IOException
```

Rename a session by appending a custom-title entry. Most recent rename wins. Searches all project directories.

**Parameters**:
- `sessionId` - UUID of the session to rename
- `title` - New session title (stripped of leading/trailing whitespace)

**Throws**:
- `IllegalArgumentException` - If `sessionId` is not a valid UUID or `title` is empty
- `FileNotFoundException` - If the session file cannot be found
- `IOException` - If the write fails

### renameSession(String sessionId, String title, Path directory)

```java
public static void renameSession(
    String sessionId,
    String title,
    Path directory
) throws IOException
```

Rename a session scoped to a specific project directory.

**Parameters**:
- `sessionId` - UUID of the session to rename
- `title` - New session title
- `directory` - Project working directory to search in

### tagSession(String sessionId, String tag)

```java
public static void tagSession(
    String sessionId,
    @Nullable String tag
) throws IOException
```

Tag a session. Pass `null` to clear an existing tag. Tags are Unicode-sanitized before storage. Searches all project directories.

**Parameters**:
- `sessionId` - UUID of the session to tag
- `tag` - Tag string, or `null` to clear. Must be non-empty after sanitization (unless `null`).

**Throws**:
- `IllegalArgumentException` - If `sessionId` is invalid or `tag` is empty after sanitization
- `FileNotFoundException` - If the session file cannot be found
- `IOException` - If the write fails

### tagSession(String sessionId, String tag, Path directory)

```java
public static void tagSession(
    String sessionId,
    @Nullable String tag,
    Path directory
) throws IOException
```

Tag a session scoped to a specific project directory.

**Parameters**:
- `sessionId` - UUID of the session to tag
- `tag` - Tag string, or `null` to clear
- `directory` - Project working directory to search in

### deleteSession(String sessionId)

```java
public static void deleteSession(String sessionId) throws IOException
```

Delete a session permanently by removing its JSONL file. Also recursively removes the sibling `<sessionId>/` directory containing subagent transcripts (if it exists). Soft-delete callers should use `tagSession(id, "__hidden")` and filter on listing instead.

**Parameters**:
- `sessionId` - UUID of the session to delete

**Throws**:
- `IllegalArgumentException` - If `sessionId` is not a valid UUID
- `FileNotFoundException` - If the session file cannot be found
- `IOException` - If the delete fails (subagent dir cleanup is best-effort and never fails the call)

### deleteSession(String sessionId, Path directory)

```java
public static void deleteSession(
    String sessionId,
    Path directory
) throws IOException
```

Delete a session scoped to a specific project directory.

### forkSession(String sessionId)

```java
public static ForkSessionResult forkSession(String sessionId) throws IOException
```

Fork a session into a new branch with fresh UUIDs.

**Returns**: `ForkSessionResult` containing the new session's UUID

**Throws**:
- `IllegalArgumentException` - If `sessionId` is not a valid UUID
- `FileNotFoundException` - If the session file cannot be found
- `IOException` - If the fork fails

### forkSession(String sessionId, Path directory)

```java
public static ForkSessionResult forkSession(
    String sessionId,
    Path directory
) throws IOException
```

Fork a session scoped to a specific project directory.

### forkSession(String sessionId, Path directory, String upToMessageId, String title)

```java
public static ForkSessionResult forkSession(
    String sessionId,
    @Nullable Path directory,
    @Nullable String upToMessageId,
    @Nullable String title
) throws IOException
```

Fork a session with optional truncation point and custom title.

**Parameters**:
- `sessionId` - UUID of the source session
- `directory` - Project directory (null searches all projects)
- `upToMessageId` - Slice transcript at this message UUID (inclusive); null copies all
- `title` - Custom title for the fork; null derives from original + " (fork)"

### listSessions(Path directory, Integer limit, int offset, boolean includeWorktrees)

```java
public static List<SDKSessionInfo> listSessions(
    Path directory,
    Integer limit,
    int offset,
    boolean includeWorktrees
)
```

List sessions with offset pagination support.

**Parameters**:
- `directory` - Project directory (null for all projects)
- `limit` - Maximum number of sessions to return
- `offset` - Number of sessions to skip (for pagination)
- `includeWorktrees` - Include sessions from git worktrees

## SessionStore-Backed Methods

These methods read/write sessions through a `SessionStore` adapter rather than the local `~/.claude/projects/` filesystem. See the [Session Store guide](./feature-session-store.md) for the full feature documentation.

### projectKeyForDirectory(Path directory)

```java
public static String projectKeyForDirectory(@Nullable Path directory)
```

Compute the `SessionStore` `project_key` for a directory using the same realpath + NFC normalization + djb2-hashed sanitization the CLI uses. Defaults to the current working directory when `directory == null`.

**Returns**: Sanitized project key string suitable for `SessionKey.projectKey()`.

### listSessionsFromStore(SessionStore, Path, Integer, int)

```java
public static List<SDKSessionInfo> listSessionsFromStore(
    SessionStore sessionStore,
    @Nullable Path directory,
    @Nullable Integer limit,
    int offset)
```

List sessions from a `SessionStore`. Uses the fast path when `store.implementsListSessionSummaries()` returns `true`; falls back to bounded-concurrency (16) per-session loads otherwise.

**Throws**: `IllegalStateException` if the store implements neither `listSessionSummaries()` nor `listSessions()`.

### getSessionInfoFromStore(SessionStore, String, Path)

```java
public static @Nullable SDKSessionInfo getSessionInfoFromStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory)
```

Read metadata for a single session from a store. Returns `null` for invalid UUIDs, missing sessions, sidechain sessions, or sessions with no extractable summary.

### getSessionMessagesFromStore(SessionStore, String, Path, Integer, int)

```java
public static List<SessionMessage> getSessionMessagesFromStore(
    SessionStore sessionStore, String sessionId,
    @Nullable Path directory, @Nullable Integer limit, int offset)
```

Read a session's full conversation transcript from a store. Returns an empty list for invalid UUIDs or missing sessions.

### listSubagentsFromStore(SessionStore, String, Path)

```java
public static List<String> listSubagentsFromStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory)
```

List subagent IDs for a session by enumerating store subkeys under `subagents/agent-<id>`.

**Throws**: `IllegalStateException` if the store doesn't implement `listSubkeys()`.

### getSubagentMessagesFromStore(SessionStore, String, String, Path, Integer, int)

```java
public static List<SessionMessage> getSubagentMessagesFromStore(
    SessionStore sessionStore, String sessionId, String agentId,
    @Nullable Path directory, @Nullable Integer limit, int offset)
```

Read a subagent's transcript from a store. Filters out synthetic `agent_metadata` entries.

### renameSessionViaStore(SessionStore, String, String, Path)

```java
public static void renameSessionViaStore(
    SessionStore sessionStore, String sessionId, String title,
    @Nullable Path directory)
```

Append a `custom-title` entry to the session in the store.

**Throws**: `IllegalArgumentException` if `sessionId` is not a valid UUID or `title` is empty/whitespace-only.

### tagSessionViaStore(SessionStore, String, String, Path)

```java
public static void tagSessionViaStore(
    SessionStore sessionStore, String sessionId, @Nullable String tag,
    @Nullable Path directory)
```

Append a `tag` entry. Pass `null` for `tag` to clear; tags are Unicode-sanitized before storing.

**Throws**: `IllegalArgumentException` for invalid UUID or empty-after-sanitization tag.

### deleteSessionViaStore(SessionStore, String, Path)

```java
public static void deleteSessionViaStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory)
```

Delete a session from the store. No-op if the store doesn't implement `delete()` (appropriate for WORM/append-only backends).

### forkSessionViaStore(SessionStore, String, Path, String, String)

```java
public static ForkSessionResult forkSessionViaStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory,
    @Nullable String upToMessageId, @Nullable String title) throws java.io.IOException
```

Fork a session into a new branch with fresh UUIDs via the store. Runs the same UUID-remap transform as the on-disk fork — a storage-layer copy is NOT sufficient.

**Throws**: `IllegalArgumentException` for invalid UUIDs; `FileNotFoundException` if the source session is not found in the store.

### importSessionToStore(String, SessionStore, Path)

```java
public static void importSessionToStore(
    String sessionId, SessionStore sessionStore, @Nullable Path directory)
    throws java.io.IOException
```

Replay a local on-disk session transcript into a `SessionStore`. Convenience overload using `includeSubagents=true` and the default batch size (`TranscriptMirrorBatcher.MAX_PENDING_ENTRIES = 500`).

### importSessionToStore(String, SessionStore, Path, boolean, int)

```java
public static void importSessionToStore(
    String sessionId, SessionStore sessionStore, @Nullable Path directory,
    boolean includeSubagents, int batchSize) throws java.io.IOException
```

Full version with explicit options.

**Parameters**:
- `includeSubagents` — recursively import `<sessionDir>/subagents/**/*.jsonl` and `.meta.json` sidecars
- `batchSize` — entries per `store.append()` call; values `≤ 0` use the default

**Throws**: `IllegalArgumentException` on invalid UUID; `NoSuchFileException` if the session file isn't found.

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
- [Session Store Guide](./feature-session-store.md)
