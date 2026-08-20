# MCP Servers (Model Context Protocol)

MCP (Model Context Protocol) allows you to create custom tools that Claude can use during conversations. The SDK supports both in-process SDK servers and external stdio/SSE/HTTP servers.

## Table of Contents
- [Overview](#overview)
- [SDK MCP Servers vs External Servers](#sdk-mcp-servers-vs-external-servers)
- [Creating SDK MCP Servers](#creating-sdk-mcp-servers)
- [Using @Tool Annotation](#using-tool-annotation)
- [Tool Title and Annotations](#tool-title-and-annotations)
- [Programmatic Tool Creation](#programmatic-tool-creation)
- [Tool Schema](#tool-schema)
- [Tool Execution](#tool-execution)
- [Protocol Details](#protocol-details)
- [Custom MCP Handlers](#custom-mcp-handlers)
- [External MCP Servers](#external-mcp-servers)
- [MCP Server Status](#mcp-server-status)
- [Examples](#examples)
- [Best Practices](#best-practices)

## Overview

MCP (Model Context Protocol) provides a standardized way to define custom tools that Claude can invoke during conversations. The SDK supports:

1. **SDK MCP Servers** (In-Process) - Run directly in your application
2. **External MCP Servers** - Run as separate processes (stdio/SSE/HTTP)

**Key Benefits:**
- Extend Claude's capabilities with custom functionality
- Access to your application's state and APIs
- Type-safe tool definitions
- Automatic schema generation
- Async execution with CompletableFuture

## SDK MCP Servers vs External Servers

### SDK MCP Servers (In-Process)

**Advantages:**
- ✅ **Better Performance**: No IPC overhead
- ✅ **Simpler Deployment**: Single process
- ✅ **Easier Debugging**: Same process, same debugger
- ✅ **Direct Access**: Access application state directly
- ✅ **Type Safety**: Java type system
- ✅ **No Serialization**: Direct method calls

**Use Cases:**
- Application-specific tools
- Database access
- Business logic
- Internal APIs
- Testing and prototyping

### External MCP Servers

**Advantages:**
- ✅ **Language Agnostic**: Written in any language
- ✅ **Isolation**: Separate process space
- ✅ **Reusability**: Share across applications
- ✅ **Security**: Process sandboxing

**Use Cases:**
- Third-party tools
- Language-specific libraries (Node.js, Python)
- Shared tool servers
- Legacy systems

## Creating SDK MCP Servers

There are three ways to create SDK MCP servers:

1. Using `@Tool` annotation (declarative)
2. Using `SdkMcpTool.create()` (programmatic)
3. Using `SdkMcpServer.create()` (manual)

### Quick Start

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.mcp.Tool;
import in.vidyalai.claude.sdk.mcp.ToolResult;
import in.vidyalai.claude.sdk.types.mcp.McpSdkServerConfig;
import java.util.concurrent.CompletableFuture;

public class MyTools {
    @Tool(name = "greet", description = "Greet a user")
    public CompletableFuture<ToolResult> greet(String name) {
        return CompletableFuture.completedFuture(
            ToolResult.text("Hello, " + name + "!")
        );
    }
}

// Create server from annotated class
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "my-tools",
    new MyTools()
);

// Use in options
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("tools", server))
    .allowedTools(List.of("mcp__tools__greet"))
    .build();
```

## Using @Tool Annotation

The `@Tool` annotation provides a declarative way to define tools.

### Basic Annotation

```java
import in.vidyalai.claude.sdk.mcp.Tool;
import in.vidyalai.claude.sdk.mcp.ToolResult;
import java.util.concurrent.CompletableFuture;
import java.util.Map;

public class Calculator {

    @Tool(name = "add", description = "Add two numbers")
    public CompletableFuture<ToolResult> add(double a, double b) {
        double result = a + b;
        return CompletableFuture.completedFuture(
            ToolResult.text("Result: " + result)
        );
    }

    @Tool(name = "multiply", description = "Multiply two numbers")
    public CompletableFuture<ToolResult> multiply(Map<String, Object> args) {
        double a = ((Number) args.get("a")).doubleValue();
        double b = ((Number) args.get("b")).doubleValue();
        return CompletableFuture.completedFuture(
            ToolResult.text("Result: " + (a * b))
        );
    }
}
```

## Tool Title and Annotations

### Tool Title

Use the `title` attribute to provide a friendly display name distinct from the technical tool name:

```java
@Tool(
    name = "fetch_user_data",
    title = "User Data Fetcher",
    description = "Fetch user data from the database"
)
public CompletableFuture<ToolResult> fetchUserData(String userId) {
    // ...
}
```

A title is sent both at the top level of the tool, where MCP 2025-06-18 puts
it, and inside `annotations`, where earlier revisions look — a client that
predates the top-level field strips what it does not know. It reaches
`tools/list` whether or not the tool also declares annotations.

### Tool Annotations (Semantic Hints)

Use the `annotations` attribute to attach behavioral hints to a tool. Implement the `ToolAnnotations` interface:

```java
import in.vidyalai.claude.sdk.mcp.ToolAnnotations;

public class ReadOnlyHints implements ToolAnnotations {
    @Override
    public Boolean readOnlyHint() { return true; }
}

@Tool(
    name = "read_file",
    title = "File Reader",
    description = "Read the contents of a file",
    annotations = ReadOnlyHints.class
)
public CompletableFuture<ToolResult> readFile(String path) {
    // ...
}
```

Available annotation hints:

| Hint | Description |
|------|-------------|
| `readOnlyHint` | Tool only reads data, does not modify state |
| `destructiveHint` | Tool performs irreversible operations |
| `idempotentHint` | Repeated calls with same inputs produce same results |
| `openWorldHint` | Tool queries external systems with unbounded results |
| `maxResultSizeChars` | Maximum result size in characters before the CLI spills to a temp file |

### maxResultSizeChars (Anthropic-Specific Hint)

The `maxResultSizeChars` annotation controls the CLI's layer-2 tool-result spill threshold. By default the CLI spills tool results larger than ~50K characters to temporary files. Setting this annotation raises (or lowers) that threshold for a specific tool.

Because the MCP SDK's Zod schema strips unknown annotation fields, `maxResultSizeChars` is forwarded via `_meta` with the namespaced key `anthropic/maxResultSizeChars` in the `tools/list` JSONRPC response.

```java
ToolAnnotations hints = ToolAnnotations.builder()
    .readOnlyHint(true)
    .maxResultSizeChars(200_000)  // Allow up to 200K chars
    .build();

SdkMcpTool<Map<String, Object>> bigResultTool = SdkMcpTool.builder("large_query", "Query returning large results")
    .inputSchema(Map.of("type", "object", "properties", Map.of(
        "query", Map.of("type", "string")
    ), "required", List.of("query")))
    .handler(args -> CompletableFuture.completedFuture(
        ToolResult.text(runLargeQuery((String) args.get("query")))
    ))
    .annotations(hints)
    .build();
```

### Method Signatures

The annotated method can have two signatures:

#### 1. Typed Parameters (Recommended)

```java
@Tool(name = "greet", description = "Greet a user")
public CompletableFuture<ToolResult> greet(String firstName, String lastName) {
    return CompletableFuture.completedFuture(
        ToolResult.text("Hello, " + firstName + " " + lastName + "!")
    );
}
```

**Requirements:**
- Compile with `-parameters` flag to preserve parameter names
- Parameters are automatically mapped to JSON Schema
- Type mapping:
  - `String` → `"string"`
  - `int`, `Integer`, `long`, `Long` → `"integer"`
  - `double`, `Double`, `float`, `Float` → `"number"`
  - `boolean`, `Boolean` → `"boolean"`
  - `Map<String, Object>` → `"object"`

#### 2. Map Parameter

```java
@Tool(name = "greet", description = "Greet a user")
public CompletableFuture<ToolResult> greet(Map<String, Object> args) {
    String name = (String) args.get("name");
    return CompletableFuture.completedFuture(
        ToolResult.text("Hello, " + name + "!")
    );
}
```

**Use this when:**
- You want manual parameter extraction
- The schema is complex
- You need optional parameters

### Automatic Schema Generation

When you use typed parameters, the SDK automatically generates a JSON Schema:

```java
@Tool(name = "search", description = "Search for items")
public CompletableFuture<ToolResult> search(String query, int limit) {
    // Implementation
}
```

Generates schema:
```json
{
    "type": "object",
    "properties": {
        "query": {
            "type": "string"
        },
        "limit": {
            "type": "integer"
        }
    },
    "required": ["query", "limit"]
}
```

### Explicit Schema

For complex schemas, provide explicit JSON:

```java
@Tool(
    name = "search",
    description = "Search for items",
    inputSchema = """
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The search query"
                },
                "limit": {
                    "type": "integer",
                    "description": "Max results",
                    "default": 10,
                    "minimum": 1,
                    "maximum": 100
                },
                "filters": {
                    "type": "object",
                    "properties": {
                        "category": {"type": "string"},
                        "minPrice": {"type": "number"}
                    }
                }
            },
            "required": ["query"]
        }
        """
)
public CompletableFuture<ToolResult> search(Map<String, Object> args) {
    // Implementation
}
```

### Creating Server from Annotations

```java
// Create server from annotated instance
Calculator calculator = new Calculator();
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "calculator",
    calculator
);

// Or with version
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "calculator",
    "1.0.0",
    calculator
);
```

## Programmatic Tool Creation

For dynamic tool creation, use `SdkMcpTool.create()` or the builder:

```java
import in.vidyalai.claude.sdk.mcp.SdkMcpTool;
import java.util.concurrent.CompletableFuture;

// Simple creation
SdkMcpTool<Map<String, Object>> uppercaseTool = SdkMcpTool.create(
    "uppercase",                           // Tool name
    "Convert text to uppercase",           // Description
    Map.of(                                // JSON Schema
        "type", "object",
        "properties", Map.of(
            "text", Map.of(
                "type", "string",
                "description", "The text to convert"
            )
        ),
        "required", List.of("text")
    ),
    args -> {                              // Handler function
        String text = (String) args.get("text");
        return CompletableFuture.completedFuture(
            ToolResult.text(text.toUpperCase())
        );
    }
);

// With title and annotations using builder
ToolAnnotations hints = ToolAnnotations.builder()
    .readOnlyHint(true)
    .idempotentHint(true)
    .build();

SdkMcpTool<Map<String, Object>> searchTool = SdkMcpTool.builder("search", "Search records")
    .title("Record Search")
    .inputSchema(Map.of("type", "object", "properties", Map.of(
        "query", Map.of("type", "string")
    ), "required", List.of("query")))
    .handler(args -> CompletableFuture.completedFuture(
        ToolResult.text("Results for: " + args.get("query"))
    ))
    .annotations(hints)
    .build();
```

### Creating Server from Tools

```java
import in.vidyalai.claude.sdk.mcp.SdkMcpServer;

// Create multiple tools
List<SdkMcpTool<?>> tools = List.of(
    uppercaseTool,
    lowercaseTool,
    reverseTool
);

// Create server
SdkMcpServer server = SdkMcpServer.create(
    "text-tools",  // Server name
    "1.0.0",       // Version
    tools          // Tool list
);

// Get config for options
McpSdkServerConfig config = server.toConfig();
```

## Tool Schema

### JSON Schema Format

A tool's `inputSchema` is JSON Schema. Arguments are validated against it
before the handler runs (see [Argument validation](#argument-validation)).
The dialect is taken from the schema's `$schema` keyword when present;
without one, Draft 2020-12 is assumed — the dialect the MCP specification is
written against. Draft 4, 6, 7, 2019-09 and 2020-12 are all understood.

```java
Map<String, Object> schema = Map.of(
    "type", "object",
    "properties", Map.of(
        "name", Map.of(
            "type", "string",
            "description", "User's name",
            "minLength", 1
        ),
        "age", Map.of(
            "type", "integer",
            "description", "User's age",
            "minimum", 0,
            "maximum", 150
        ),
        "email", Map.of(
            "type", "string",
            "format", "email"
        )
    ),
    "required", List.of("name", "email")
);
```

### Supported Types

- `string` - Text values
- `integer` - Whole numbers
- `number` - Floating point numbers
- `boolean` - true/false
- `object` - Nested objects
- `array` - Lists of values
- `null` - Null values

### Constraints

```java
Map.of(
    // String constraints
    "minLength", 1,
    "maxLength", 100,
    "pattern", "^[A-Z][a-z]+$",
    "format", "email",  // email, uri, date-time, etc.

    // Number constraints
    "minimum", 0,
    "maximum", 100,
    "exclusiveMinimum", true,
    "multipleOf", 5,

    // Array constraints
    "minItems", 1,
    "maxItems", 10,
    "uniqueItems", true,

    // Enum values
    "enum", List.of("red", "green", "blue")
);
```

## Tool Execution

### ToolResult

Tools must return `ToolResult` (wrapped in CompletableFuture):

```java
import in.vidyalai.claude.sdk.mcp.ToolResult;

// Text result
ToolResult.text("Hello, world!");

// JSON result — serialized into a single text block
ToolResult.json(Map.of("status", "success", "data", data));

// Image result (Base64)
ToolResult.image(base64Data, "image/png");

// Several content blocks
ToolResult.builder()
    .addText("Result:")
    .addJson(data)
    .addResourceLink("Full report", "file:///tmp/report.md", "Every row")
    .build();

// From raw MCP content blocks, normalized (see below)
ToolResult.ofContent(List.of(
    Map.of("type", "text", "text", "Result:"),
    Map.of("type", "resource_link", "name", "Docs", "uri", "https://example.com")
));

// Error result
ToolResult.error("Failed to process request");
```

#### Content blocks

MCP defines more content types than the CLI renders, so the ones it cannot
show are folded into text — the same conversion the Python SDK performs:

| Block | Becomes |
|---|---|
| `text` | itself |
| `image` | itself |
| `resource_link` | text: name, URI and description on their own lines, blanks skipped (`Resource link` when all are absent) |
| `resource` carrying `text` | that text |
| `resource` carrying binary data | dropped, logged at `WARNING` |
| anything else | dropped, logged at `WARNING` |

`addResourceLink(...)` and `addResource(...)` apply the same rules, so a
handler can build a result block by block without knowing them.

### Async Execution

Tools execute asynchronously using CompletableFuture:

```java
@Tool(name = "fetch_data", description = "Fetch data from API")
public CompletableFuture<ToolResult> fetchData(String url) {
    // Async HTTP request
    return httpClient.sendAsync(request, BodyHandlers.ofString())
        .thenApply(response -> ToolResult.text(response.body()))
        .exceptionally(e -> ToolResult.error(e.getMessage()));
}
```

### Error Handling

Report a failure the model should see by returning `ToolResult.error(...)`,
which produces a result carrying `isError: true`:

```java
@Tool(name = "divide", description = "Divide two numbers")
public CompletableFuture<ToolResult> divide(double a, double b) {
    if (b == 0) {
        return CompletableFuture.completedFuture(
            ToolResult.error("Cannot divide by zero")
        );
    }

    return CompletableFuture.completedFuture(
        ToolResult.text("Result: " + (a / b))
    );
}
```

You do not have to catch everything yourself: a handler that throws, or whose
`CompletableFuture` completes exceptionally, is reported the same way (see
[Failure semantics](#failure-semantics) below).

### Argument validation

Before a handler runs, the arguments in a `tools/call` are validated against
the tool's declared `inputSchema`. This is required of MCP servers — *"Servers
**MUST** validate all tool inputs"* — and it means a handler only ever sees
arguments that match the contract it published.

A call that does not match comes back as a tool result with `isError: true`
and text beginning `Input validation error:`, and **the handler is not
invoked**:

```
{"count": 21}            -> handler runs, returns its result
{}                       -> Input validation error: required property 'count' not found
{"count": "twenty-one"}  -> Input validation error: /count string found, integer expected
```

Two consequences worth relying on:

- A tool with side effects cannot half-apply one before failing on arguments
  it never agreed to accept.
- The model receives a sentence naming the offending property, which it can
  act on, rather than whatever exception the handler happened to throw when
  it tried to read a missing or mistyped value.

A tool with no schema, or an empty one, is not validated — there is nothing
to check against.

#### A schema that is not valid JSON Schema

Validation fails **closed**, as the Python SDK does. Each `inputSchema` is
checked against its own dialect's meta-schema when the server is built; a tool
whose schema does not pass is logged at `WARNING` and every call to it comes
back as `isError` without the handler running:

```
{"type": "object", "properties": "not-an-object"}
    -> Tool 'x' has an inputSchema this server cannot use, so it cannot be
       called: /properties string found, object expected
```

This matters more than it looks. The validator accepts a malformed schema
happily and then mis-validates against it: `{"type": "bogus"}` compiles and
then matches nothing, so every call would fail citing the caller's arguments
rather than the real defect, while `"properties": "a string"` is ignored
outright and waves every call through unchecked. Neither is a state a handler
should run in.

The text deliberately does **not** begin `Input validation error:`. That
prefix tells the model its arguments were wrong; a broken schema is a defect
in the server the model cannot route around, and mislabelling it invites an
endless retry. Unknown keywords stay legal — a schema carrying `x-vendor`
extensions validates fine.

### Failure semantics

How each kind of failure reaches the caller:

| Situation | Response | What the model sees |
|---|---|---|
| Handler returns `ToolResult.error(msg)` | result, `isError: true` | `msg` |
| Handler throws, or its future fails | result, `isError: true` | the exception message, or its class name when the message is null/blank |
| Arguments do not match `inputSchema` | result, `isError: true` | `Input validation error: …` (handler not run) |
| `inputSchema` is not valid JSON Schema | result, `isError: true` | `… inputSchema this server cannot use …` (handler not run) |
| Tool name is not registered | result, `isError: true` | `Tool '<name>' not found` |
| The call was cancelled | JSON-RPC error `-32800` | nothing; the CLI already gave up |
| Method is not one this server implements | JSON-RPC error `-32601` | nothing; the model never issues these |
| `params` is missing or malformed | JSON-RPC error `-32602` | nothing; same |

Everything a *tool call* can run into is a **tool execution error**: the call
was processed and produced a result that happens to describe a failure, so the
text reaches the model as output it can read and adapt to. A JSON-RPC error
says the request could not be processed at all, and the model never sees one —
which is why an unknown tool is reported as a result too, matching the Python
SDK. These semantics are the SDK's own, so a tool behaves identically on both.

### Cancelling a running tool

The CLI enforces its own timeout on an MCP tool call (`MCP_TOOL_TIMEOUT`).
When it fires, the CLI stops waiting and sends MCP's
`notifications/cancelled`; the SDK answers the pending call with `-32800` and
discards whatever the handler eventually returns.

The handler itself keeps running unless it looks. A `CompletableFuture` cannot
be interrupted from outside — `cancel(true)` completes the future and leaves
the work alone — so a tool that does anything long, or anything with side
effects, should take a `ToolCallContext` alongside its arguments:

```java
SdkMcpTool<Map<String, Object>> crawl = SdkMcpTool.create(
        "crawl", "Fetch every page under a URL", schema,
        (args, context) -> CompletableFuture.supplyAsync(() -> {
            List<String> pages = new ArrayList<>();
            for (String url : urlsFrom(args)) {
                if (context.isCancelled()) {
                    break;              // nobody is waiting for this any more
                }
                pages.add(fetch(url));
            }
            return ToolResult.text(String.join("\n", pages));
        }));
```

`context.onCancel(runnable)` covers work that cannot poll — a blocking read, a
call out to another service — by giving you somewhere to close the resource;
it runs immediately if the call is already cancelled.
`context.throwIfCancelled()` is the checkpoint form for a handler that would
rather unwind.

Handlers that take only their arguments keep working exactly as before; they
simply cannot observe cancellation. Methods annotated with `@Tool` can declare
a `ToolCallContext` parameter anywhere in their signature — it is injected, and
never appears in the tool's published schema.

Disconnecting has the same effect: closing the client abandons the calls still
in flight, so a shutdown is not held up by a tool nothing can interrupt.

A handler failure is also logged locally at `WARNING` with its stack trace, so
a crashing tool is debuggable without the model's transcript being the only
record.

### Long-Running Operations

```java
@Tool(name = "process_large_file", description = "Process a large file")
public CompletableFuture<ToolResult> processFile(String path) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            // Long-running operation
            byte[] data = Files.readAllBytes(Path.of(path));
            String result = processData(data);
            return ToolResult.text("Processed: " + result);
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    });
}
```

## Protocol Details

### Protocol version

The server advertises `2025-06-18` and `2024-11-05`, newest first. On
`initialize` it echoes the version the client asked for when it speaks it, and
otherwise answers with the newest it does — the handshake the specification
prescribes.

`2025-03-26` is deliberately not claimed. That revision made JSON-RPC batching
mandatory to *receive*, and a batch is a top-level array, which the control
request carrying these messages types as a map and cannot represent. Claiming
a version whose one required change the SDK could not honor would be a promise
the client acts on.

### Methods

`initialize`, `ping`, `tools/list` and `tools/call` are implemented. Anything
else is answered `-32601`, which is correct rather than missing: the server
advertises only the `tools` capability, so a conformant client never asks for
resources, prompts or completions. (Verified against the CLI: a server
declaring only `tools` is never sent `resources/list` or `prompts/list`.)

### Notifications

A JSON-RPC notification — a message with a `method` and no `id` — never gets a
response, as JSON-RPC requires. `notifications/initialized` and
`notifications/cancelled` are acted on; anything else is logged at `FINE` and
dropped. The *control request* that carried the notification is still
acknowledged, with `{"jsonrpc": "2.0", "result": {}}`, or the CLI would wait
forever.

A message with no `method` at all is a JSON-RPC response, or junk. The SDK
sends the CLI no requests, so nothing arriving that way is its to match: it is
ignored rather than answered.

## Custom MCP Handlers

`McpSdkServerConfig` holds an `McpMessageHandler`, not specifically an
`SdkMcpServer`. Implement the interface directly to serve parts of MCP that
`SdkMcpServer` does not — resources, prompts, completions — or to adapt a
third-party MCP library:

```java
public class MyMcpServer implements McpMessageHandler {

    @Override
    public CompletableFuture<Map<String, Object>> handleMessage(Map<String, Object> message) {
        // Return the JSON-RPC response for a request, or null for a
        // notification, which must never be answered.
        ...
    }

    @Override
    public void close() {
        // Optional: the connection using this handler is going away.
    }
}

var options = ClaudeAgentOptions.builder()
        .mcpServers(Map.of("mine", new McpSdkServerConfig("mine", new MyMcpServer())))
        .build();
```

What the CLI sends is driven by the `capabilities` returned from
`initialize`, so a handler that advertises resources will be asked for them.

`close()` means "the connection using you is going away", not "shut down": one
handler can be registered with more than one client, so it must be idempotent
and stay usable afterwards. For the same reason, register one `SdkMcpServer`
per connection — two live connections sharing one server can issue the same
JSON-RPC id, and the second call is then refused with `-32603` rather than
risking a response reaching the wrong caller.

## External MCP Servers

### Stdio Server

```java
import in.vidyalai.claude.sdk.types.mcp.McpStdioServerConfig;

McpStdioServerConfig server = new McpStdioServerConfig(
    "node",                              // Command
    List.of("path/to/server.js"),        // Arguments
    Map.of("NODE_ENV", "production")     // Environment variables
);

var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("external", server))
    .build();
```

### SSE Server

```java
import in.vidyalai.claude.sdk.types.mcp.McpSseServerConfig;

McpSseServerConfig server = new McpSseServerConfig(
    "http://localhost:8080/sse"  // SSE endpoint URL
);
```

### HTTP Server

```java
import in.vidyalai.claude.sdk.types.mcp.McpHttpServerConfig;

McpHttpServerConfig server = new McpHttpServerConfig(
    "http://localhost:8080"  // Base URL
);
```

### Mixed Servers

You can use both SDK and external servers together:

```java
// SDK server (in-process)
McpSdkServerConfig sdkServer = ClaudeSDK.createSdkMcpServer(
    "app-tools",
    new MyTools()
);

// External stdio server
McpStdioServerConfig externalServer = new McpStdioServerConfig(
    "node",
    List.of("external-server.js"),
    Map.of()
);

// Configure both
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of(
        "app", sdkServer,
        "external", externalServer
    ))
    .allowedTools(List.of(
        "mcp__app__my_tool",
        "mcp__external__their_tool"
    ))
    .build();
```

### Strict MCP Configuration

By default the CLI loads MCP servers from the project `.mcp.json`, the user/global settings, and any plugins, in addition to whatever you pass via `mcpServers(...)`. Set `strictMcpConfig(true)` to ignore everything except the servers you pass in:

```java
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("app", sdkServer))
    .strictMcpConfig(true)   // ignore project / user / plugin MCP configs
    .build();
```

Maps to the CLI's `--strict-mcp-config` flag. Useful for reproducible deployments or test isolation when you want exact control over which MCP servers are reachable.

## Examples

### Example 1: Calculator

```java
public class Calculator {

    @Tool(name = "calculate", description = "Perform calculations")
    public CompletableFuture<ToolResult> calculate(
            double a, double b, String operation) {

        double result = switch (operation) {
            case "add" -> a + b;
            case "subtract" -> a - b;
            case "multiply" -> a * b;
            case "divide" -> {
                if (b == 0) {
                    return CompletableFuture.completedFuture(
                        ToolResult.error("Cannot divide by zero")
                    );
                }
                yield a / b;
            }
            default -> throw new IllegalArgumentException(
                "Unknown operation: " + operation
            );
        };

        return CompletableFuture.completedFuture(
            ToolResult.text(a + " " + operation + " " + b + " = " + result)
        );
    }
}

// Usage
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "calculator",
    new Calculator()
);

var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("calc", server))
    .allowedTools(List.of("mcp__calc__calculate"))
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();
    client.sendMessage("Calculate 15 * 7, then 100 / 4");

    for (Message msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### Example 2: Database Access

```java
public class DatabaseTools {
    private final DataSource dataSource;

    public DatabaseTools(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Tool(name = "query_users", description = "Query users from database")
    public CompletableFuture<ToolResult> queryUsers(String filter) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM users WHERE name LIKE ?")) {

                stmt.setString(1, "%" + filter + "%");
                ResultSet rs = stmt.executeQuery();

                List<Map<String, Object>> users = new ArrayList<>();
                while (rs.next()) {
                    users.add(Map.of(
                        "id", rs.getInt("id"),
                        "name", rs.getString("name"),
                        "email", rs.getString("email")
                    ));
                }

                return ToolResult.json(Map.of(
                    "count", users.size(),
                    "users", users
                ));

            } catch (SQLException e) {
                return ToolResult.error("Database error: " + e.getMessage());
            }
        });
    }
}
```

### Example 3: API Integration

```java
public class WeatherTools {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String apiKey;

    public WeatherTools(String apiKey) {
        this.apiKey = apiKey;
    }

    @Tool(name = "get_weather", description = "Get current weather")
    public CompletableFuture<ToolResult> getWeather(String city) {
        String url = "https://api.weather.com/weather?city=" + city +
                     "&key=" + apiKey;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .build();

        return httpClient.sendAsync(request, BodyHandlers.ofString())
            .thenApply(response -> {
                // Parse JSON response
                Map<String, Object> data = parseJson(response.body());
                return ToolResult.json(data);
            })
            .exceptionally(e -> ToolResult.error(
                "Failed to fetch weather: " + e.getMessage()
            ));
    }
}
```

## Best Practices

### 1. Use Appropriate Return Types

```java
// ✅ Good: Specific result types
ToolResult.text("Simple text response");
ToolResult.json(Map.of("key", "value"));
ToolResult.error("Error message");

// ❌ Bad: Always using text for structured data
ToolResult.text("{\"key\":\"value\"}");  // Should use json()
```

### 2. Handle Errors Gracefully

```java
// ✅ Good: Proper error handling
@Tool(name = "read_file", description = "Read a file")
public CompletableFuture<ToolResult> readFile(String path) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            String content = Files.readString(Path.of(path));
            return ToolResult.text(content);
        } catch (IOException e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }
    });
}

// ❌ Bad: Throwing exceptions
public CompletableFuture<ToolResult> readFile(String path) {
    String content = Files.readString(Path.of(path));  // Throws!
    return CompletableFuture.completedFuture(ToolResult.text(content));
}
```

### 3. Provide Good Descriptions

```java
// ✅ Good: Descriptive and clear
@Tool(
    name = "search_products",
    description = "Search for products by name, category, or price range. " +
                  "Returns a list of matching products with details."
)

// ❌ Bad: Vague description
@Tool(name = "search", description = "Search")
```

### 4. Use Explicit Schemas for Complex Inputs

A declared schema is what the SDK validates arguments against, so the more
precisely it describes the input, the more the handler can take for granted —
and the more useful the message the model gets back when it calls the tool
wrongly. A tool with no schema is not validated at all.

```java
// ✅ Good: Explicit schema with validation
@Tool(
    name = "create_user",
    description = "Create a new user",
    inputSchema = """
        {
            "type": "object",
            "properties": {
                "email": {"type": "string", "format": "email"},
                "age": {"type": "integer", "minimum": 18}
            },
            "required": ["email"]
        }
        """
)

// ❌ Bad: No validation
@Tool(name = "create_user", description = "Create user")
public CompletableFuture<ToolResult> createUser(Map<String, Object> args)
```

### 5. Keep Tools Focused

```java
// ✅ Good: Single responsibility
@Tool(name = "add_numbers", description = "Add two numbers")
@Tool(name = "multiply_numbers", description = "Multiply two numbers")

// ❌ Bad: Too much in one tool
@Tool(name = "math", description = "Do any math operation")
```

### 6. Use Async for I/O Operations

```java
// ✅ Good: Async I/O
@Tool(name = "fetch", description = "Fetch URL")
public CompletableFuture<ToolResult> fetch(String url) {
    return httpClient.sendAsync(request, BodyHandlers.ofString())
        .thenApply(response -> ToolResult.text(response.body()));
}

// ❌ Bad: Blocking I/O
public CompletableFuture<ToolResult> fetch(String url) {
    String result = blockingHttpCall(url);  // Blocks!
    return CompletableFuture.completedFuture(ToolResult.text(result));
}
```

### 7. Configure Tool Permissions

```java
// ✅ Good: Explicitly allow tools
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("calc", calcServer))
    .allowedTools(List.of(
        "mcp__calc__add",
        "mcp__calc__subtract"
    ))
    .build();

// ❌ Bad: Allowing all tools (security risk)
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("calc", calcServer))
    .build();  // All tools allowed!
```

## MCP Server Status

`ClaudeSDKClient.getMcpStatus()` returns an `McpStatusResponse` with the current connection state of all configured MCP servers.

### McpStatusResponse

```java
record McpStatusResponse(
    List<McpServerStatus> mcpServers   // list of server status entries
)
```

### McpServerStatus

```java
record McpServerStatus(
    String name,                               // server name as configured
    McpServerConnectionStatus status,          // connection state
    @Nullable McpServerInfo serverInfo,        // info from MCP handshake (when connected)
    @Nullable String error,                    // error message (when status = FAILED)
    @Nullable McpServerStatusConfig config,    // server configuration
    @Nullable String scope,                    // config scope (project, user, local)
    @Nullable List<McpToolInfo> tools          // available tools (when connected)
)
```

### McpServerConnectionStatus

```java
enum McpServerConnectionStatus {
    CONNECTED,    // server is connected and ready
    FAILED,       // connection attempt failed
    NEEDS_AUTH,   // server requires authentication
    PENDING,      // connection in progress
    DISABLED      // server is disabled
}
```

### McpServerInfo

```java
record McpServerInfo(
    String name,      // server name from MCP handshake
    String version    // server version from MCP handshake
)
```

### McpToolInfo

```java
record McpToolInfo(
    String name,                               // tool name
    @Nullable String description,              // tool description
    @Nullable McpToolAnnotations annotations   // behavioral hints
)
```

### McpServerStatusConfig (sealed interface)

Represents server configuration in status responses. Polymorphic — use pattern matching:

```java
switch (server.config()) {
    case McpStdioServerConfig c -> System.out.println("stdio: " + c.command());
    case McpSseServerConfig c -> System.out.println("sse: " + c.url());
    case McpHttpServerConfig c -> System.out.println("http: " + c.url());
    case McpSdkServerConfigStatus c -> System.out.println("sdk: " + c.name());
    case McpClaudeAIProxyServerConfig c -> System.out.println("proxy: " + c.url());
    case null -> {}
}
```

### Example: Checking MCP Status

```java
try (var client = ClaudeSDK.createClient(options)) {
    client.connect();

    McpStatusResponse status = client.getMcpStatus();
    for (McpServerStatus server : status.mcpServers()) {
        System.out.printf("[%s] %s%n", server.status(), server.name());
        if (server.status() == McpServerConnectionStatus.CONNECTED) {
            if (server.tools() != null) {
                server.tools().forEach(t -> System.out.println("  - " + t.name()));
            }
        } else if (server.status() == McpServerConnectionStatus.FAILED) {
            System.err.println("  Error: " + server.error());
        }
    }
}
```

## See Also

- [Configuration Options](./feature-configuration-options.md) - mcpServers and tools options
- [Tool Usage Example](../examples/src/main/java/examples/McpServer.java) - Complete examples
- [Auto Schema Generation Example](../examples/src/main/java/examples/AutoSchemaGeneration.java)
- [MCP Specification](https://spec.modelcontextprotocol.io/) - Official MCP protocol docs
