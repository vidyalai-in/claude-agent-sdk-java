# Hooks System

Intercept and respond to lifecycle events in Claude conversations.

## Overview

Hooks allow you to execute custom code at specific points in the conversation lifecycle. There are 10 hook events you can listen to.

## Hook Events

- **PRE_TOOL_USE** - Before tool execution
- **POST_TOOL_USE** - After successful tool execution
- **POST_TOOL_USE_FAILURE** - After tool execution fails
- **USER_PROMPT_SUBMIT** - When user submits a message
- **STOP** - When session stops
- **SUBAGENT_START** - When subagent starts
- **SUBAGENT_STOP** - When subagent stops  
- **PRE_COMPACT** - Before message compaction
- **NOTIFICATION** - On notification events
- **PERMISSION_REQUEST** - When permission requested

## Basic Usage

```java
var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(
        HookEvent.PRE_TOOL_USE, List.of(
            new HookMatcher(null, "Read", context -> {
                System.out.println("About to read: " + context.input());
                return CompletableFuture.completedFuture(
                    HookOutput.logs(List.of("Pre-tool log"))
                );
            })
        )
    ))
    .build();
```

## HookMatcher

```java
new HookMatcher(
    String toolName,           // null for all tools
    String matchPattern,       // Tool name pattern
    Function<HookContext, CompletableFuture<HookOutput>> handler
)
```

> **Dispatch order:** when multiple matchers are registered on the same event,
> the CLI dispatches all matching hook callbacks **concurrently** (in parallel),
> not sequentially. Design each hook to be independent — do not rely on one
> completing before another starts (e.g. don't chain rate-limiter hooks that
> assume a gating order).

## Hook Input Fields

All tool-related hook inputs (`PreToolUseHookInput`, `PostToolUseHookInput`, `PostToolUseFailureHookInput`, `PermissionRequestHookInput`) include two optional fields for subagent context:

| Field | Type | Description |
|-------|------|-------------|
| `agentId` | `@Nullable String` | Sub-agent identifier. Present only inside a task-spawned sub-agent; null on the main thread. |
| `agentType` | `@Nullable String` | Agent type name (e.g. `"general-purpose"`). Present inside a sub-agent or on the main thread when started with `--agent`. |

```java
new HookMatcher(null, null, context -> {
    PreToolUseHookInput input = (PreToolUseHookInput) context.input();
    if (input.agentId() != null) {
        System.out.println("Tool used inside sub-agent: " + input.agentId());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
})
```

## HookOutput

```java
// Empty output
HookOutput.empty()

// With logs
HookOutput.logs(List.of("Log message"))

// With messages
HookOutput.messages(List.of(
    Map.of("role", "user", "content", "Message")
))

// With permission updates
HookOutput.permissionUpdates(List.of(update))

// Combined
HookOutput.builder()
    .logs(List.of("Log"))
    .messages(List.of(message))
    .build()
```

## PostToolUse Output Replacement

`PostToolUseHookSpecificOutput` lets a `PostToolUse` hook substitute the tool's output before it reaches the model.

```java
record PostToolUseHookSpecificOutput(
    @Nullable String additionalContext,    // extra context for the model
    @Nullable Object updatedToolOutput,    // replacement for any tool's output
    @Nullable Object updatedMCPToolOutput  // replacement for MCP tool output only
)
```

- **`updatedToolOutput`** — replaces output for any tool (built-ins included). For built-in tools the value must match the tool's output schema (e.g. `{"stdout": ..., "stderr": ..., "interrupted": ...}` for `Bash`); a mismatched shape is rejected and the original output is kept.
- **`updatedMCPToolOutput`** — replaces output for MCP tools only. Prefer `updatedToolOutput`, which works for all tools.
- A backwards-compatible 2-arg constructor `(additionalContext, updatedMCPToolOutput)` is preserved for code written before `updatedToolOutput` existed.

```java
HookEvent.POST_TOOL_USE, List.of(
    new HookMatcher(null, "Bash", context -> {
        // Redact secrets from Bash output before the model sees it.
        Map<String, Object> redacted = Map.of(
            "stdout", "[redacted]",
            "stderr", "",
            "interrupted", false
        );
        return CompletableFuture.completedFuture(
            HookOutput.builder()
                .hookSpecificOutput(new PostToolUseHookSpecificOutput(null, redacted, null))
                .build()
        );
    })
)
```

## Permission Decision: `"defer"`

A `PreToolUse` hook can return `permissionDecision: "defer"` (via `PermissionDecision.DEFER` / `PreToolUseHookSpecificOutput`) to stop the run without executing the tool. The CLI surfaces the deferred call on `ResultMessage.deferredToolUse` so the SDK consumer can inspect it and decide whether to resume.

```java
HookEvent.PRE_TOOL_USE, List.of(
    new HookMatcher(null, "Bash", context -> {
        PreToolUseHookInput input = (PreToolUseHookInput) context.input();
        if (looksDangerous(input.toolInput())) {
            return CompletableFuture.completedFuture(
                HookOutput.builder()
                    .hookSpecificOutput(new PreToolUseHookSpecificOutput(
                        PermissionDecision.DEFER,
                        "Needs operator review",
                        null,
                        null))
                    .build()
            );
        }
        return CompletableFuture.completedFuture(HookOutput.empty());
    })
)

// Caller side — inspect the deferred call from the result message.
for (Message msg : ClaudeSDK.query(prompt, options)) {
    if (msg instanceof ResultMessage r && r.deferredToolUse() != null) {
        DeferredToolUse d = r.deferredToolUse();
        System.out.printf("Deferred %s (id=%s) input=%s%n", d.name(), d.id(), d.input());
    }
}
```

`DeferredToolUse` carries `id`, `name`, and `input`. See [Message Types](./feature-message-types.md#resultmessage) for the full `ResultMessage` shape.

## Hook Lifecycle Events on the Stream

Set `includeHookEvents(true)` on `ClaudeAgentOptions` to also receive hook lifecycle events as `HookEventMessage` objects in the message stream. This is useful for observability (logging every hook fire) without registering a hook for every event you want to watch.

```java
var options = ClaudeAgentOptions.builder()
    .includeHookEvents(true)
    .hooks(Map.of(/* still register hooks normally */))
    .build();

for (Message msg : ClaudeSDK.query(prompt, options)) {
    if (msg instanceof HookEventMessage hook) {
        // subtype is "hook_started" or "hook_response"
        System.out.printf("[%s] %s session=%s%n",
            hook.subtype(), hook.hookEventName(), hook.sessionId());
        // Full raw payload (output, exit_code, outcome on hook_response)
        Object outcome = hook.get("outcome");
        if (outcome != null) {
            System.out.println("  outcome: " + outcome);
        }
    }
}
```

`HookEventMessage` fields:

| Field | Type | Description |
|-------|------|-------------|
| `subtype` | `String` | `"hook_started"` when a hook begins, `"hook_response"` when it completes |
| `data` | `Map<String, Object>` | Full raw event dict from the CLI (`output`, `exit_code`, `outcome` on `hook_response`) |
| `hookEventName` | `String` | Hook event name (e.g. `"PreToolUse"`, `"PostToolUse"`, `"Stop"`) |
| `sessionId` | `@Nullable String` | Session ID this event belongs to |
| `uuid` | `@Nullable String` | Unique event ID |

`HookEventMessage.type()` returns `"system"`, but it does **not** match `instanceof SystemMessage` — branch on `HookEventMessage` directly.

## Complete Example

```java
public class HooksExample {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .hooks(Map.of(
                // Log all tool uses
                HookEvent.PRE_TOOL_USE, List.of(
                    new HookMatcher(null, null, context -> {
                        PreToolUseHookInput input = (PreToolUseHookInput) context.input();
                        System.out.println("Tool: " + input.toolName());
                        return CompletableFuture.completedFuture(
                            HookOutput.logs(List.of(
                                "Executing: " + input.toolName()
                            ))
                        );
                    })
                ),
                
                // Track tool results
                HookEvent.POST_TOOL_USE, List.of(
                    new HookMatcher(null, null, context -> {
                        PostToolUseHookInput input = (PostToolUseHookInput) context.input();
                        System.out.println("Result: " + input.result());
                        return CompletableFuture.completedFuture(
                            HookOutput.empty()
                        );
                    })
                ),
                
                // Handle errors
                HookEvent.POST_TOOL_USE_FAILURE, List.of(
                    new HookMatcher(null, null, context -> {
                        PostToolUseFailureHookInput input = 
                            (PostToolUseFailureHookInput) context.input();
                        System.err.println("Error: " + input.error());
                        return CompletableFuture.completedFuture(
                            HookOutput.logs(List.of("Tool failed"))
                        );
                    })
                )
            ))
            .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect("List files in current directory");
            for (var msg : client.receiveResponse()) {
                // Process
            }
        }
    }
}
```

## See Also
- [Configuration Options](./feature-configuration-options.md#hooks)
- [Hooks Example](../examples/src/main/java/examples/Hooks.java)
