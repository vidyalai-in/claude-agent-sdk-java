# Architecture Overview

This document provides a comprehensive overview of the Claude Agent SDK for Java's architecture, design patterns, and internal structure.

## Table of Contents
- [High-Level Architecture](#high-level-architecture)
- [Core Components](#core-components)
- [Design Patterns](#design-patterns)
- [Data Flow](#data-flow)
- [Concurrency Model](#concurrency-model)
- [Type System](#type-system)
- [Dependencies](#dependencies)

## High-Level Architecture

The SDK follows a layered architecture with clear separation of concerns:

```
┌─────────────────────────────────────────────────────────┐
│           Public API Layer                              │
│  ┌──────────────────┐    ┌────────────────────────┐   │
│  │   ClaudeSDK      │    │  ClaudeSDKClient       │   │
│  │   (Facade)       │    │  (Interactive Client)  │   │
│  └──────────────────┘    └────────────────────────┘   │
│            │                        │                   │
│            └────────────────────────┘                   │
│                     │                                   │
├─────────────────────┼───────────────────────────────────┤
│                     ▼                                   │
│           Configuration Layer                           │
│  ┌─────────────────────────────────────────────────┐  │
│  │       ClaudeAgentOptions (Builder)              │  │
│  └─────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────┤
│           Protocol & Control Layer                      │
│  ┌──────────────────┐    ┌────────────────────────┐   │
│  │   QueryHandler   │◄───┤   MessageParser        │   │
│  │  (Control Proto) │    │   (JSON Parsing)       │   │
│  └──────────────────┘    └────────────────────────┘   │
│            │                                            │
├─────────────┼────────────────────────────────────────────┤
│            ▼                                            │
│           Transport Layer                               │
│  ┌─────────────────────────────────────────────────┐  │
│  │  Transport Interface                            │  │
│  │  └─ SubprocessCLITransport (default impl)      │  │
│  └─────────────────────────────────────────────────┘  │
│            │                                            │
├─────────────┼────────────────────────────────────────────┤
│            ▼                                            │
│           External Process                              │
│  ┌─────────────────────────────────────────────────┐  │
│  │         Claude Code CLI Process                 │  │
│  │         (stdin/stdout communication)            │  │
│  └─────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

## Core Components

### 1. Public API Layer

#### ClaudeSDK (Facade)
- **Purpose**: Static facade for simple, stateless queries
- **Use Case**: One-shot questions, batch processing, fire-and-forget operations
- **Key Methods**:
  - `query(String prompt)` - Simple query with defaults
  - `query(String prompt, ClaudeAgentOptions options)` - Query with custom options
  - `query(Iterator<Map> stream, ClaudeAgentOptions options)` - Streaming query
  - `queryForText()` / `queryForResult()` - Convenience methods
  - `createClient()` - Factory method for ClaudeSDKClient
  - `createSdkMcpServer()` - Factory for MCP servers

**Design Pattern**: Facade + Factory

#### ClaudeSDKClient
- **Purpose**: Interactive, stateful client for multi-turn conversations
- **Use Case**: Chat interfaces, REPL-like interactions, long-running sessions
- **Key Methods**:
  - `connect()` - Establish connection
  - `sendMessage()` / `query()` - Send messages
  - `receiveMessages()` / `receiveResponse()` - Receive messages
  - Control methods: `interrupt()`, `setModel()`, `setPermissionMode()`, etc.
- **Thread Safety**: Partially thread-safe with documented guarantees
- **Resource Management**: Implements AutoCloseable for proper cleanup

**Design Pattern**: Builder + Resource Management (try-with-resources)

### 2. Configuration Layer

#### ClaudeAgentOptions
- **Purpose**: Immutable configuration object using builder pattern
- **Features**:
  - 30+ configuration options
  - Type-safe API with enums and sealed interfaces
  - Fluent builder with `toBuilder()` for modifications
- **Key Configuration Areas**:
  - Tools: `tools()`, `allowedTools()`, `disallowedTools()`
  - Permissions: `permissionMode()`, `canUseTool()`
  - Sessions: `continueConversation()`, `resume()`, `forkSession()`, `sessionStore()`, `loadTimeoutMs()`
  - Limits: `maxTurns()`, `maxBudgetUsd()`, `maxThinkingTokens()`
  - Model: `model()`, `fallbackModel()`, `betas()`
  - Environment: `cwd()`, `env()`, `cliPath()`
  - Hooks: `hooks()`
  - MCP: `mcpServers()`
  - Agents: `agents()` (sent via stdin initialize request, no size limit)
  - Advanced: `sandbox()`, `outputFormat()`, `checkpointFiles()`

**Design Pattern**: Builder + Immutable Object

### 3. Protocol & Control Layer

#### QueryHandler
- **Purpose**: Manages bidirectional control protocol on top of Transport
- **Responsibilities**:
  - Control request/response routing
  - Hook callbacks
  - Tool permission callbacks
  - Message streaming
  - Initialization handshake (includes hooks, agent definitions, and excludeDynamicSections)
  - MCP server lifecycle management
  - **Actionable error replacement**: tracks the most recent error result's payload while reading the stream; when a `ProcessException` follows a result with `is_error=true`, it is replaced by a `ResultException` carrying that payload and the message `"Claude Code returned an error result: <text>"` (built from the result's `errors` array, then its `result` text, then a non-`success` `subtype`, then the API error status) instead of the generic `"Command failed with exit code N"`. Resets on any non-result, non-`session_state_changed` traffic. The exception object rides on the synthetic `{"type":"error"}` frame, so the consumer iterator rethrows it with its type and payload intact.
- **Thread Safety**: Fully thread-safe with atomic operations and synchronization
- **Key Features**:
  - Async control protocol using CompletableFuture
  - Request ID generation and tracking
  - Message queue with configurable size
  - Background reader thread using virtual threads
  - Control executor for async callbacks

**Design Pattern**: Async Request/Response + Observer (for hooks)

#### MessageParser
- **Purpose**: Parse JSON messages from CLI and convert to typed Message objects
- **Features**:
  - Jackson-based JSON parsing
  - Support for all message types (user, assistant, system, result, stream_event)
  - Content block parsing (text, thinking, tool_use, tool_result)
  - Error handling and validation

**Design Pattern**: Parser + Factory

### 4. Transport Layer

#### Transport Interface
- **Purpose**: Abstract I/O layer for communication with Claude Code
- **Default Implementation**: SubprocessCLITransport
- **Custom Implementations**: Allows for remote Claude Code connections
- **Key Methods**:
  - `connect()` - Establish connection
  - `write(String data)` - Send data
  - `readMessages()` - Receive messages as iterator
  - `endInput()` - Close input stream
  - `isReady()` - Check connection status
  - `close()` - Cleanup resources

**Design Pattern**: Strategy + Template Method

#### SubprocessCLITransport
- **Purpose**: Default transport using subprocess for Claude Code CLI
- **Features**:
  - Manages CLI subprocess lifecycle
  - Stdin/stdout communication
  - Buffered reading with configurable limits
  - Stderr callback support with per-line exception isolation (a throwing user callback no longer kills the read loop)
  - Automatic process cleanup
  - **JVM shutdown hook**: a static `ConcurrentHashMap.newKeySet()` tracks every spawned `Process`; a `Runtime.addShutdownHook` registered at class init calls `destroy()` on each live child so stray `claude` subprocesses do not leak when the parent JVM exits before `close()`. Mirrors the Python SDK's `atexit` handler.
- **Implementation Details**:
  - Uses ProcessBuilder for subprocess management
  - Virtual thread for stdout reading
  - BufferedReader with line-based parsing
  - Jackson for JSON serialization/deserialization

**Design Pattern**: Subprocess Management + Buffered I/O

### 5. MCP (Model Context Protocol) Support

#### SdkMcpServer
- **Purpose**: In-process MCP server for custom tools
- **Advantages over external servers**:
  - No IPC overhead (same process)
  - Simpler deployment
  - Easier debugging
  - Direct access to application state
- **Features**:
  - Tool registration and execution
  - Automatic schema generation from @Tool annotations
  - CompletableFuture-based async execution
  - Server info, and `initialize` version negotiation over
    `2025-06-18` / `2024-11-05`
  - MCP protocol messages (`initialize`, `ping`, `tools/list`, `tools/call`)
  - Argument validation against each tool's `inputSchema`, compiled once when
    the server is constructed
  - Cancellation: `notifications/cancelled` settles the pending call and
    signals the handler
- **Message classification**: a `method` with an `id` is a request and is
  answered; a `method` without one is a notification and is *never* answered,
  as JSON-RPC requires — the enclosing control request is acknowledged
  instead. A message with no `method` is a response, or junk, and is ignored:
  this server sends the CLI no requests, so nothing arriving that way is its
  to match.
- **Failure classification**: everything a `tools/call` can run into is a
  *tool execution error* — a result carrying `isError: true` — including an
  unknown tool, a schema-invalid argument and a handler that threw. A JSON-RPC
  error is reserved for what a *model* never causes and never sees: an
  unimplemented method (`-32601`), malformed `params` (`-32602`), and a call
  the CLI cancelled (`-32800`). An `isError` result reaches the model as tool
  output it can read and correct; a JSON-RPC error says the request could not
  be processed at all.
- **Fail-closed validation**: each `inputSchema` is checked against its own
  dialect's meta-schema at construction, and a tool whose schema does not pass
  is logged and made uncallable. The validator would otherwise accept a
  malformed schema and mis-validate against it — `{"type": "bogus"}` matches
  nothing, `"properties": "a string"` is ignored — so a handler would run on
  arguments nobody checked, or every call would fail citing the wrong thing.

#### McpMessageHandler
- **Purpose**: the seam `McpSdkServerConfig` actually holds, so an application
  can serve MCP itself — resources, prompts, completions, or an adapter over a
  third-party MCP library — instead of using `SdkMcpServer`.
- **Contract**: `handleMessage` returns the JSON-RPC response for a request and
  `null` for anything that expects no reply. `close()` means "the connection
  using you is going away", not "shut down": one handler can serve more than
  one client, so it must be idempotent and stay usable.

#### ToolCallContext
- **Purpose**: lets a running tool see that its call was cancelled.
- **Why it must exist**: `CompletableFuture.cancel(true)` does not interrupt a
  running task — it completes the future and leaves the work going. Without an
  explicit signal, cancelling would stop the *wait* but not the *work*, and a
  tool with side effects would keep applying them after the CLI gave up.

#### SdkMcpTool
- **Purpose**: Tool definition and execution wrapper
- **Creation Methods**:
  - `SdkMcpTool.create()` - Programmatic creation
  - `@Tool` annotation - Declarative creation
- **Features**:
  - Generic type parameter for input
  - CompletableFuture-based async execution
  - JSON Schema for input validation
  - Automatic parameter extraction

**Design Pattern**: Command + Factory + Annotation Processing

### 6. SessionStore Subsystem

#### SessionStore (Adapter Protocol)
- **Purpose**: Mirror session transcripts to external storage (S3, Postgres, Redis, custom backends) so sessions are durable beyond local disk and resumable across hosts.
- **Required methods**: `append(SessionKey, List<SessionStoreEntry>)`, `load(SessionKey)`.
- **Optional methods** (with `implements*()` capability probes): `listSessions`, `listSessionSummaries`, `delete`, `listSubkeys`.
- **Sync + Async API**: every method has a `*Async` (`CompletableFuture`) variant. Adapters with native non-blocking clients (AWS SDK v2 async, R2DBC, Lettuce reactive) override the `*Async` methods directly to avoid a thread hop. The default executor is configured via `SessionStoreExecutor` (per-task virtual threads by default).

**Design Pattern**: Adapter + Capability Negotiation + Dual-API (Sync/Async)

#### TranscriptMirrorBatcher (internal)
- **Purpose**: Buffer `transcript_mirror` frames the CLI emits on stdout and flush them to `store.appendAsync(...)`.
- **Key behaviors**:
  - Eager flush thresholds: `MAX_PENDING_ENTRIES=500`, `MAX_PENDING_BYTES=1 MiB`.
  - Explicit flush before each `result` message and at end-of-stream / close.
  - Coalesces frames per `filePath` so each unique file gets one `append` call per flush.
  - Bounded retry: `MIRROR_APPEND_MAX_ATTEMPTS=3` attempts with `[200ms, 800ms]` backoff. Timeouts are not retried (in-flight call may still land).
  - Frames whose path falls outside the configured `projectsDir` are dropped with a warning.
  - Failures surface as `MirrorErrorMessage` in the consumer's stream — never block the conversation.

**Design Pattern**: Producer-Consumer Buffer + Retry with Exponential Backoff

#### SessionResume (internal)
- **Purpose**: Materialize a stored session into a temp `CLAUDE_CONFIG_DIR` so the CLI subprocess can resume from local disk.
- **Flow**:
  1. Load entries via `store.loadAsync()` (or pick the most-recently-modified non-sidechain session for `continueConversation`).
  2. Write JSONL to a temp directory laid out like `~/.claude/`.
  3. Copy `.credentials.json` (with `refreshToken` redacted to prevent token consumption from the temp dir) and `.claude.json`.
  4. Materialize subagent transcripts and `.meta.json` sidecars when the store implements `listSubkeys`.
  5. Spawn the CLI with `CLAUDE_CONFIG_DIR=<temp dir>`.
  6. Cleanup on disconnect with retry on transient Windows AV/indexer locks.

**Design Pattern**: Materialized View + Retry Cleanup

#### SessionStoreValidation (internal)
- **Purpose**: Pre-flight option checks before subprocess spawn. Rejects invalid combinations with `IllegalArgumentException`:
  - `continueConversation + sessionStore` requires `store.implementsListSessions()`.
  - `sessionStore + enableFileCheckpointing` is rejected (checkpoints are local-only).

**Design Pattern**: Fail-Fast Validation

#### SessionStoreConformance (public testing helper)
- **Location**: `in.vidyalai.claude.sdk.testing.SessionStoreConformance`
- **Purpose**: Framework-agnostic 14-contract behavioral test suite for `SessionStore` adapters. Uses plain `AssertionError` so it works under any test framework (JUnit, TestNG, Spock, plain `main`).

**Design Pattern**: Contract Testing

## Design Patterns

### 1. Sealed Interfaces (Pattern Matching)
Used extensively for type-safe message handling:

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, TaskStartedMessage, TaskProgressMessage,
    TaskNotificationMessage, TaskUpdatedMessage, MirrorErrorMessage,
    HookEventMessage, ResultMessage, StreamEvent, RateLimitEvent {}

// Usage with pattern matching
switch (message) {
    case UserMessage u -> handleUser(u);
    case AssistantMessage a -> handleAssistant(a);
    case ResultMessage r -> handleResult(r);
    case MirrorErrorMessage m -> handleMirrorError(m);
    case HookEventMessage h -> handleHookEvent(h);
    case SystemMessage s -> handleSystem(s);
    case StreamEvent e -> handleStreamEvent(e);
    // ... task and rate-limit cases
}
```

**Benefits**:
- Exhaustive pattern matching at compile time
- No default case needed
- Type safety guaranteed
- Clear type hierarchy

### 2. Builder Pattern
Used for configuration objects:

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .maxTurns(10)
    .permissionMode(PermissionMode.ACCEPT_EDITS)
    .build();

// Modify existing options
ClaudeAgentOptions modified = options.toBuilder()
    .maxTurns(20)
    .build();
```

**Benefits**:
- Readable configuration
- Optional parameters
- Immutable objects
- Chainable API

### 3. Facade Pattern
ClaudeSDK provides simplified interface:

```java
// Simple facade
List<Message> messages = ClaudeSDK.query("Hello");

// Hides complexity of:
// - Transport creation
// - QueryHandler setup
// - Message parsing
// - Resource cleanup
```

**Benefits**:
- Simple API for common cases
- Hides internal complexity
- Single entry point

### 4. Virtual Threads (Concurrency)
Leverages Project Loom for lightweight concurrency:

```java
// Background reader thread
Thread.ofVirtual()
    .name("ClaudeSDK-Reader")
    .start(() -> readLoop());

// Executor for control protocol
ExecutorService executor = Executors.newSingleThreadExecutor(
    Thread.ofVirtual().factory());
```

**Benefits**:
- Lightweight threads (thousands possible)
- Blocking I/O without thread pool exhaustion
- Simpler async code
- Better resource utilization

### 5. CompletableFuture (Async Operations)
Used for async callbacks and control protocol:

```java
// Permission callback
CompletableFuture<PermissionResult> future =
    canUseTool.apply(toolName, input, context);

// Control protocol request/response
CompletableFuture<ControlResponse> response =
    sendControlRequest(request);
```

**Benefits**:
- Non-blocking operations
- Composable async chains
- Error handling
- Timeout support

## Data Flow

### Query Execution Flow

```
User Code
    │
    ├─► ClaudeSDK.query(prompt, options)
    │       │
    │       ├─► Validate options
    │       ├─► Create Transport
    │       ├─► Create QueryHandler
    │       │       │
    │       │       ├─► Start reader thread
    │       │       └─► Process messages
    │       │               │
    │       │               ├─► Parse JSON
    │       │               ├─► Handle control protocol
    │       │               ├─► Invoke hooks
    │       │               ├─► Check permissions
    │       │               └─► Add to message queue
    │       │
    │       └─► Collect messages
    │               │
    └─────────────► Return List<Message>
```

### Interactive Client Flow

```
User Code
    │
    ├─► ClaudeSDKClient.connect()
    │       │
    │       ├─► Create Transport
    │       ├─► Create QueryHandler
    │       ├─► Start reader thread
    │       └─► Initialize (control protocol handshake)
    │
    ├─► client.sendMessage("Hello")
    │       │
    │       └─► Write to transport stdin
    │
    ├─► client.receiveResponse()
    │       │
    │       └─► Iterator reads from message queue
    │               │
    │               ├─► Blocks until message available
    │               ├─► Returns messages until ResultMessage
    │               └─► Auto-closes iterator
    │
    └─► client.close()
            │
            ├─► Close QueryHandler
            ├─► Close Transport
            └─► Cleanup resources
```

### Hook Invocation Flow

```
CLI Process
    │
    ├─► Sends hook request via stdout
    │       │
    │       └─► {"type": "control", "method": "sdk.hook_callback", ...}
    │
QueryHandler
    │
    ├─► Receives hook request
    │       │
    │       ├─► Parse hook event and input
    │       ├─► Match against registered hooks
    │       ├─► Invoke matching hooks in parallel
    │       │       │
    │       │       └─► CompletableFuture.allOf(...)
    │       │
    │       └─► Collect results
    │               │
    │               └─► Combine outputs (logs, messages, updates)
    │
    └─► Send hook response via stdin
            │
            └─► {"id": "...", "result": {...}}
```

### Stdin Lifecycle and In-Flight Tasks

> **`controlExecutor` must stay thread-per-task.** An SDK MCP tool call parks
> its control thread until the tool answers, and the `notifications/cancelled`
> that ends it arrives as a *separate* control request. Under any bounded pool
> that cancellation would queue behind the very call it exists to cancel, and
> deadlock. No test would catch it — a fixed pool of two passes everything.

When hooks, SDK MCP servers, or a `canUseTool` permission callback are registered, the control protocol needs stdin open for the entire conversation, so `QueryHandler.streamInput()` waits for a run-ending `result` frame before calling `transport.endInput()`. All three are served the same way — the CLI writes a `control_request` and blocks until the SDK writes the matching `control_response` to stdin — so all three count as bidirectional needs (`hasBidirectionalNeeds()`). Closing too early is not benign: the CLI in stream-json mode exits **only** on stdin EOF, so the close cannot simply be deferred to `close()` either — that would hang a one-shot `query()` forever.

The subtlety is that **a `result` frame ends one turn, not the run**. A background task keeps running past it and still needs stdin for hook and SDK-MCP control responses. Closing on the first result meant a still-running subagent's SDK-MCP tool calls failed with `"Stream closed"`, and — more quietly — its `PreToolUse` hooks were never delivered, so built-in tools kept executing and deny-gate hooks stopped gating.

`QueryHandler` therefore keeps a ledger of in-flight tasks, populated from `system` task lifecycle frames, and only treats a result as run-ending when the ledger is empty:

```
system: task_started (task_type ∈ DEFERRING_TASK_TYPES)  ─►  add task_id
system: task_notification                                ─►  remove task_id
system: task_updated (patch.status ∈ TERMINAL_TASK_STATUSES) ─► remove task_id

result frame
    ├─ ledger empty     ─►  complete firstResultEvent  ─►  endInput()
    └─ ledger non-empty ─►  keep stdin open, log at FINE
```

Each task completion wakes the parent for a follow-up turn that ends in another result frame, so the close still happens promptly — and chained background tasks work, because the ledger only empties after the last one settles.

`DEFERRING_TASK_TYPES` is `{"local_agent", "local_workflow"}`. The exclusions are deliberate, not oversights: background shells (`local_bash`) and monitors run indefinitely by design, and teammates stay `running` for their whole lifetime, so none of them reliably reaches a terminal status. Tracking one would withhold the close *forever* rather than briefly — and with no process exit, not even the reader's `finally` would run. Anything added to this set must be a type that reliably terminates.

`background_tasks_changed` frames are ignored in both directions. That payload is the live *background* set, but a subagent is registered in the foreground and only flips to backgrounded later without a second `task_started` — so narrowing against it would drop exactly the agent this ledger exists to protect, and widening from it could admit an id no later frame ever clears.

This is a mitigation rather than a complete answer: an empty ledger means "nothing we know of is running", which is not the same as "the run is over". A task that settles *before* its turn's result frame leaves the ledger empty at that result. No ledger can close that gap — it would need a run-boundary signal from the CLI — but the common ordering, where the task outlives the turn that spawned it, is fixed.

## Concurrency Model

### Thread Architecture

The SDK uses a multi-threaded architecture with virtual threads:

1. **Main Thread**: User's application thread
2. **Reader Thread**: Virtual thread reading from CLI stdout
3. **Control Executor**: Thread pool for async control protocol operations
4. **Streaming Executor**: Optional thread for streaming input messages
5. **Hook Executors**: Virtual threads for parallel hook execution

### Thread Safety

- **AtomicBoolean**: Used for connection and closed state
- **Volatile**: Used for visibility of QueryHandler and Transport
- **Synchronization**: Used for connect() to prevent race conditions
- **BlockingQueue**: Thread-safe message queue
- **ConcurrentHashMap**: Thread-safe control request tracking

### Resource Management

All resources implement AutoCloseable:

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    // Use client
} // Automatic cleanup: QueryHandler, Transport, Executors
```

## Type System

### Message Type Hierarchy

```
Message (sealed interface)
    ├── UserMessage (record)
    ├── AssistantMessage (record)
    │       └── content: List<ContentBlock>   (sealed interface)
    │               ├── TextBlock
    │               ├── ThinkingBlock
    │               ├── ToolUseBlock
    │               ├── ToolResultBlock
    │               ├── ServerToolUseBlock
    │               ├── ServerToolResultBlock
    │               ├── ImageBlock        - PDF page render
    │               ├── DocumentBlock     - whole PDF
    │               └── UnknownBlock      - forward-compat fallback
    ├── SystemMessage (record)
    ├── ResultMessage (record)
    │       └── modelUsage: Map<String, ModelUsage>
    └── StreamEvent (record)
```

Both sealed hierarchies are exhaustive-`switch` friendly, which means adding a member is a deliberate source-breaking event for callers. `ContentBlock` gained three members in 0.1.20; `UnknownBlock` exists so that *unmodelled* types no longer require an SDK change at all — the parser preserves them whole and logs once per type instead of throwing.

### Configuration Types

```
ClaudeAgentOptions
    ├── PermissionMode (enum)
    ├── ToolsPreset (record)
    ├── SystemPromptPreset (record)
    ├── SdkBeta (enum)
    ├── SettingSource (enum)
    ├── SandboxSettings (record)
    ├── ThinkingConfig (sealed interface)
    │       ├── ThinkingConfigAdaptive (record)
    │       ├── ThinkingConfigEnabled (record)
    │       └── ThinkingConfigDisabled (record)
    ├── McpServerConfig (sealed interface)
    │       ├── McpStdioServerConfig
    │       ├── McpSseServerConfig
    │       ├── McpHttpServerConfig
    │       └── McpSdkServerConfig
    ├── HookEvent (enum) - 10 events
    ├── HookMatcher (record)
    └── AgentDefinition (record)
```

### Permission Types

```
PermissionResult (sealed interface)
    ├── PermissionResultAllow (record)
    └── PermissionResultDeny (record)
            └── reason: String
```

### Hook Types

```
HookInput (sealed interface)
    ├── PreToolUseHookInput
    ├── PostToolUseHookInput
    ├── PostToolUseFailureHookInput
    ├── UserPromptSubmitHookInput
    ├── StopHookInput
    ├── SubagentStopHookInput
    ├── SubagentStartHookInput
    ├── PreCompactHookInput
    ├── NotificationHookInput
    └── PermissionRequestHookInput
```

## Dependencies

### Runtime Dependencies

1. **Jackson** (2.21.0)
   - `jackson-databind` - JSON serialization/deserialization
   - `jackson-annotations` - JSON annotations
   - Purpose: Parse CLI JSON messages, serialize control protocol

2. **JSpecify** (1.0.0)
   - Nullability annotations (`@Nullable`, `@NonNull`)
   - Purpose: Better null safety and IDE support

3. **networknt json-schema-validator** (2.0.4)
   - Purpose: Validate SDK MCP tool arguments against a tool's declared
     `inputSchema` before the handler runs, as the MCP specification requires
     of servers
   - Pinned to the 2.x line deliberately: 3.x is built against Jackson 3
     (`tools.jackson`) and would place a second complete JSON stack beside the
     Jackson 2 above. 2.0.4 is the newest release that reuses our databind.
   - Its YAML schema reader is excluded (tool schemas arrive as parsed maps),
     as is a Surefire report formatter it declares compile-scope by mistake
   - Brings `slf4j-api` (2.0.17) transitively. The SDK logs through
     `java.util.logging` and ships **no** SLF4J binding — choosing one is the
     application's call. An application with no provider sees a one-time
     `No SLF4J providers were found` notice on stderr the first time it
     constructs an SDK MCP server; adding any binding removes it.

### Test Dependencies

1. **JUnit 5** (6.0.2)
   - Testing framework
   - Purpose: Unit and integration tests

2. **AssertJ** (3.27.7)
   - Fluent assertion library
   - Purpose: Readable test assertions

3. **Mockito** (5.21.0)
   - Mocking framework
   - Purpose: Mock dependencies in tests

### Build Dependencies

1. **Maven Compiler Plugin** (3.14.1)
   - Java 25 compilation with `-parameters` flag
   - Purpose: Preserve parameter names for @Tool annotation

2. **Flatten Maven Plugin** (1.7.3)
   - Resolves `${revision}` property
   - Purpose: CI-friendly versioning

3. **Templating Maven Plugin** (3.1.0)
   - Generate SdkVersion.java from template
   - Purpose: Inject version at build time

## Design Principles

1. **Type Safety**: Leverage Java's type system (sealed interfaces, records, enums)
2. **Immutability**: Configuration objects are immutable
3. **Thread Safety**: Document and enforce thread safety guarantees
4. **Resource Management**: AutoCloseable for proper cleanup
5. **Builder Pattern**: Fluent, readable configuration
6. **Fail Fast**: Validate early and throw meaningful exceptions
7. **Pattern Matching**: Use modern Java features for cleaner code
8. **Virtual Threads**: Lightweight concurrency without complexity
9. **Separation of Concerns**: Clear layer boundaries
10. **Extensibility**: Plugin system and custom transports

## Performance Considerations

1. **Virtual Threads**: Thousands of concurrent operations possible
2. **Buffered I/O**: Reduces system calls for subprocess communication
3. **Message Queue**: Configurable size to balance memory and throughput
4. **Lazy Initialization**: QueryHandler created only when needed
5. **Resource Pooling**: Reuse ExecutorService across operations
6. **Direct Memory**: Jackson uses efficient buffer handling
7. **Minimal Copying**: Message objects use records (no defensive copying)

## Future Extensibility

The architecture supports future enhancements:

1. **Custom Transports**: Implement Transport interface for remote Claude Code
2. **Additional Message Types**: Add to sealed interface hierarchy
3. **New Hook Events**: Add to HookEvent enum
4. **Plugin System**: SdkPluginConfig for custom extensions
5. **Alternative Protocols**: Replace control protocol implementation
6. **Streaming Improvements**: Enhanced partial message support
7. **Caching**: Add caching layer between SDK and CLI
8. **Metrics**: Add telemetry and performance monitoring
