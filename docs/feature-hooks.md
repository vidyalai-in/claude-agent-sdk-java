# Hooks System

Intercept and respond to lifecycle events in Claude conversations.

## Overview

Hooks run your code at specific points in the agent loop. You register a
callback for a `HookEvent`; when the CLI reaches that point it sends the SDK a
hook request, the SDK calls your callback with a typed `HookInput`, and your
callback answers with a `HookOutput` that can allow, block, or add context.

There are 10 hook events you can register callbacks for.

## Hook Events

`HookEvent` (package `in.vidyalai.claude.sdk.types.hook`):

| Constant | Event name | When it fires | Input type |
|----------|------------|---------------|------------|
| `PRE_TOOL_USE` | `PreToolUse` | Before a tool runs | `PreToolUseHookInput` |
| `POST_TOOL_USE` | `PostToolUse` | After a tool succeeds | `PostToolUseHookInput` |
| `POST_TOOL_USE_FAILURE` | `PostToolUseFailure` | After a tool fails | `PostToolUseFailureHookInput` |
| `USER_PROMPT_SUBMIT` | `UserPromptSubmit` | When a prompt is submitted | `UserPromptSubmitHookInput` |
| `STOP` | `Stop` | When the session is about to stop | `StopHookInput` |
| `SUBAGENT_START` | `SubagentStart` | When a subagent starts | `SubagentStartHookInput` |
| `SUBAGENT_STOP` | `SubagentStop` | When a subagent stops | `SubagentStopHookInput` |
| `PRE_COMPACT` | `PreCompact` | Before context compaction | `PreCompactHookInput` |
| `NOTIFICATION` | `Notification` | On a notification | `NotificationHookInput` |
| `PERMISSION_REQUEST` | `PermissionRequest` | When a permission is requested | `PermissionRequestHookInput` |

## Basic Usage

```java
HookMatcher.HookCallback logReads = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse) {
        System.out.println("About to read: " + preToolUse.toolInput().get("file_path"));
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};

var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(
        HookEvent.PRE_TOOL_USE, List.of(new HookMatcher("Read", List.of(logReads)))
    ))
    .build();
```

## HookMatcher

```java
record HookMatcher(
    @Nullable String matcher,        // which tools/events to match; null matches all
    List<HookCallback> hooks,        // callbacks to run
    @Nullable Double timeoutSeconds  // timeout for all hooks in this matcher (CLI default: 60)
)

new HookMatcher(List<HookCallback> hooks)                  // matcher = null
new HookMatcher(String matcher, List<HookCallback> hooks)  // timeoutSeconds = null
```

For tool events, `matcher` is a tool name (`"Bash"`), several names joined
with `|` (`"Write|MultiEdit|Edit"`), or `"*"`. See the
[Claude Code hooks reference](https://docs.anthropic.com/en/docs/claude-code/hooks#structure)
for the matcher syntax of each event.

A callback is a `HookMatcher.HookCallback`:

```java
@FunctionalInterface
interface HookCallback extends BiFunction<HookInput, HookContext, CompletableFuture<HookOutput>> {}
```

It receives the typed input and a `HookContext`:

```java
record HookContext(
    @Nullable String toolUseId,  // the tool use ID, for tool events
    @Nullable Object signal      // reserved for future abort support; always null
)
```

The SDK waits for the returned future before answering the CLI. Return
`CompletableFuture.completedFuture(...)` for synchronous work.

> **Dispatch order:** when multiple matchers are registered on the same event,
> the CLI dispatches all matching hook callbacks **concurrently** (in parallel),
> not sequentially. Design each hook to be independent — do not rely on one
> completing before another starts (e.g. don't chain rate-limiter hooks that
> assume a gating order).

## Hook Inputs

`HookInput` is a sealed interface; branch on the concrete record with
`instanceof`. Every input carries these fields:

| Accessor | Type | Description |
|----------|------|-------------|
| `hookEventName()` | `String` | The event name, e.g. `"PreToolUse"` |
| `sessionId()` | `String` | Session ID |
| `transcriptPath()` | `String` | Path to the session transcript |
| `cwd()` | `String` | Working directory |
| `permissionMode()` | `@Nullable String` | Current permission mode |
| `promptId()` | `@Nullable String` | UUID of the user prompt being processed. Matches the `prompt.id` attribute on OpenTelemetry events. Null until the first user input |
| `effort()` | `@Nullable HookEffort` | Effort in effect for the current turn, as `HookEffort(String level)` (e.g. `"low"`, `"high"`, `"max"`, after any downgrade for the selected model). Present for hooks that fire within a tool-use context on models that support effort |

Event-specific fields:

| Input | Fields |
|-------|--------|
| `PreToolUseHookInput` | `toolName`, `toolInput`, `toolUseId`, `agentId`, `agentType` |
| `PostToolUseHookInput` | `toolName`, `toolInput`, `toolResponse`, `toolUseId`, `agentId`, `agentType`, `durationMs` |
| `PostToolUseFailureHookInput` | `toolName`, `toolInput`, `toolUseId`, `error`, `isInterrupt`, `agentId`, `agentType`, `durationMs` |
| `PermissionRequestHookInput` | `toolName`, `toolInput`, `permissionSuggestions`, `agentId`, `agentType` |
| `UserPromptSubmitHookInput` | `prompt` |
| `StopHookInput` | `stopHookActive`, `lastAssistantMessage`, `backgroundTasks`, `sessionCrons` |
| `SubagentStartHookInput` | `agentId`, `agentType` |
| `SubagentStopHookInput` | `stopHookActive`, `agentId`, `agentTranscriptPath`, `agentType`, `lastAssistantMessage`, `backgroundTasks`, `sessionCrons` |
| `PreCompactHookInput` | `trigger` (`CompactTriggerType`), `customInstructions` |
| `NotificationHookInput` | `message`, `title`, `notificationType` |

Fields the CLI may omit are `null` when absent: `permissionMode`, `promptId`,
`effort`, `agentId`/`agentType` on the tool inputs, `isInterrupt`,
`durationMs`, `lastAssistantMessage`, `backgroundTasks`, `sessionCrons`,
`permissionSuggestions`, `title` and `customInstructions`.

### Subagent context

The tool-related inputs (`PreToolUseHookInput`, `PostToolUseHookInput`,
`PostToolUseFailureHookInput`, `PermissionRequestHookInput`) carry two optional
fields for subagent context:

| Field | Type | Description |
|-------|------|-------------|
| `agentId` | `@Nullable String` | Sub-agent identifier. Present only inside a task-spawned sub-agent; null on the main thread. |
| `agentType` | `@Nullable String` | Agent type name (e.g. `"general-purpose"`). Present inside a sub-agent or on the main thread when started with `--agent`. |

```java
HookMatcher.HookCallback subagentAware = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse && preToolUse.agentId() != null) {
        System.out.println("Tool used inside sub-agent: " + preToolUse.agentId());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};
```

### Tool timing

`PostToolUseHookInput.durationMs()` and `PostToolUseFailureHookInput.durationMs()`
report the tool's execution time in milliseconds, excluding permission-prompt
and hook time.

### Background work on Stop and SubagentStop

`StopHookInput` and `SubagentStopHookInput` report what is still running when
the session (or subagent) stops:

- `lastAssistantMessage` — text of the last assistant message before stopping.
- `backgroundTasks` — in-flight background work, as `BackgroundTaskSummary`
  records: `id`, `type` (e.g. `"shell"`, `"subagent"`, `"monitor"`,
  `"workflow"`), `status`, `description`, plus `command` (shell tasks),
  `agentType` (subagent tasks), `server` and `tool` (monitor / MCP tasks) and
  `name` (workflow tasks). Empty when nothing is in flight.
- `sessionCrons` — session-scoped scheduled wakeups that will resume the
  session later, as `SessionCronSummary` records: `id`, `schedule` (a cron
  expression), `recurring` (`false` for one-shot wakeups) and `prompt`.

For a subagent, both lists are scoped to the parent session. `description`,
`command` and a cron's `prompt` are capped at 1000 characters; a clipped value
ends with an in-string `"… [+N chars]"` marker.

```java
HookMatcher.HookCallback onStop = (input, context) -> {
    if (input instanceof StopHookInput stop && stop.backgroundTasks() != null) {
        for (BackgroundTaskSummary task : stop.backgroundTasks()) {
            System.out.printf("Still running: %s (%s, %s)%n", task.description(), task.type(), task.status());
        }
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};
```

## HookOutput

A callback returns a `HookOutput` (package
`in.vidyalai.claude.sdk.types.hook.output`), built with its builder:

```java
HookOutput.empty()                 // no fields: the CLI proceeds as normal
HookOutput.async(30_000)           // defer: {"async": true, "asyncTimeout": 30000}

HookOutput.builder()
    .shouldContinue(false)         // "continue": whether Claude proceeds after the hook
    .stopReason("Budget exhausted")// shown when continue is false
    .suppressOutput(true)          // hide stdout from transcript mode
    .decision("block")             // "block" to indicate blocking behavior
    .systemMessage("Heads up")     // warning displayed to the user
    .reason("Blocked by policy")   // feedback for Claude about the decision
    .hookSpecificOutput(specific)  // event-specific controls, see below
    .build();
```

Only the fields you set are sent. When `async` is set, only `async` and
`asyncTimeout` are sent.

## Hook-Specific Outputs

`HookSpecificOutput` is a sealed interface with one record per event. Each
serializes with its `hookEventName` plus the fields you set (`null` fields are
omitted):

| Record | Fields |
|--------|--------|
| `PreToolUseHookSpecificOutput` | `permissionDecision` (`PermissionDecision`), `permissionDecisionReason`, `updatedInput`, `additionalContext` |
| `PostToolUseHookSpecificOutput` | `additionalContext`, `updatedToolOutput`, `updatedMCPToolOutput` |
| `PostToolUseFailureHookSpecificOutput` | `additionalContext` |
| `UserPromptSubmitHookSpecificOutput` | `additionalContext`, `sessionTitle`, `suppressOriginalPrompt` |
| `NotificationHookSpecificOutput` | `additionalContext` |
| `SubagentStartHookSpecificOutput` | `additionalContext` |
| `PermissionRequestHookSpecificOutput` | `decision` (a map) |
| `StopHookSpecificOutput` | `additionalContext` |
| `SubagentStopHookSpecificOutput` | `additionalContext` |
| `SessionStartHookSpecificOutput` | `additionalContext`, `initialUserMessage`, `sessionTitle`, `watchPaths`, `reloadSkills` |
| `UserPromptExpansionHookSpecificOutput` | `additionalContext`, `suppressOriginalPrompt` |
| `PostToolBatchHookSpecificOutput` | `additionalContext` |
| `PermissionDeniedHookSpecificOutput` | `retry` |
| `PreModelSwitchHookSpecificOutput` | `permissionDecision`, `permissionDecisionReason` |
| `PostModelSwitchHookSpecificOutput` | `additionalContext` |
| `ElicitationHookSpecificOutput` | `action` (`ElicitationAction`), `content` |
| `ElicitationResultHookSpecificOutput` | `action` (`ElicitationAction`), `content` |
| `CwdChangedHookSpecificOutput` | `watchPaths` |
| `FileChangedHookSpecificOutput` | `watchPaths` |
| `WorktreeCreateHookSpecificOutput` | `worktreePath` (required) |
| `MessageDisplayHookSpecificOutput` | `displayContent` |

> **Which of these a callback can use.** SDK callbacks register only for the
> ten `HookEvent`s above, so only the first nine records answer an event a
> callback can receive. The others (`SessionStart` through `MessageDisplay`)
> describe outputs for events `HookEvent` does not list yet. They are typed for
> parity with the Python and TypeScript SDKs, which define the same shapes.

Field notes:

- **`UserPromptSubmitHookSpecificOutput`** — `sessionTitle` sets the session
  title. `suppressOriginalPrompt` omits the original prompt from the block
  message when the output's `decision` is `"block"`. A one-argument
  constructor `(additionalContext)` remains.
- **`StopHookSpecificOutput` / `SubagentStopHookSpecificOutput`** —
  `additionalContext` is non-error feedback delivered to the model (or
  subagent); the conversation continues so it can act on it.
- **`SessionStartHookSpecificOutput`** — `initialUserMessage` becomes the
  session's first user message; `sessionTitle` is ignored when the session
  starts from `clear` or `compact`; `watchPaths` are absolute paths to watch
  for FileChanged events; `reloadSkills` re-scans skill and command directories
  after SessionStart hooks complete.
- **`PermissionDeniedHookSpecificOutput`** — `retry` tells the model it may
  retry the denied tool call. Ignored for denials without a classifier verdict.
- **`PreModelSwitchHookSpecificOutput`** — `ALLOW` proceeds and `DENY` cancels
  the switch; `ASK` is treated as a refusal outside an interactive session. The
  constructor rejects `PermissionDecision.DEFER`.
- **`PostModelSwitchHookSpecificOutput`** — `additionalContext` reaches the
  model with the next request the new model serves.
- **`ElicitationHookSpecificOutput`** — accepts, declines or cancels an MCP
  elicitation request; `content` holds the form field values and is only used
  with `ElicitationAction.ACCEPT`. `ElicitationResultHookSpecificOutput`
  overrides the action or content before the response reaches the MCP server.
- **`CwdChangedHookSpecificOutput` / `FileChangedHookSpecificOutput`** —
  `watchPaths` replaces the current dynamic watch list.
- **`MessageDisplayHookSpecificOutput`** — display-only: `displayContent`
  replaces the text on screen without changing the stored message or what the
  model sees. Omit it to display the original.

### Blocking a tool call

```java
HookMatcher.HookCallback blockRm = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse
            && String.valueOf(preToolUse.toolInput().get("command")).contains("rm -rf")) {
        return CompletableFuture.completedFuture(HookOutput.builder()
            .hookSpecificOutput(new PreToolUseHookSpecificOutput(
                PermissionDecision.DENY,
                "rm -rf is not allowed",
                null,   // updatedInput
                null))  // additionalContext
            .build());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};

var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(HookEvent.PRE_TOOL_USE, List.of(new HookMatcher("Bash", List.of(blockRm)))))
    .build();
```

### Keeping the model going from a Stop hook

```java
HookMatcher.HookCallback keepGoing = (input, context) -> {
    if (input instanceof StopHookInput stop && !stop.stopHookActive()) {
        return CompletableFuture.completedFuture(HookOutput.builder()
            .hookSpecificOutput(new StopHookSpecificOutput("The test suite still fails; fix it before stopping."))
            .build());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};
```

Checking `stopHookActive()` avoids sending the model back to work forever: it
is `true` when the session is already continuing because of a Stop hook.

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
HookMatcher.HookCallback redact = (input, context) -> {
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
};

var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(HookEvent.POST_TOOL_USE, List.of(new HookMatcher("Bash", List.of(redact)))))
    .build();
```

## Permission Decision: `"defer"`

A `PreToolUse` hook can return `permissionDecision: "defer"` (via `PermissionDecision.DEFER` / `PreToolUseHookSpecificOutput`) to stop the run without executing the tool. The CLI surfaces the deferred call on `ResultMessage.deferredToolUse` so the SDK consumer can inspect it and decide whether to resume.

```java
HookMatcher.HookCallback deferRisky = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse && looksDangerous(preToolUse.toolInput())) {
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
};

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

Set `includeHookEvents(true)` on `ClaudeAgentOptions` to also receive hook lifecycle events as `HookEventMessage` objects in the message stream. This is useful for observability (logging every hook fire) without registering a hook for every event you want to watch. The CLI emits the `SessionStart` and `Setup` hook events regardless of this setting.

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
        // Log every tool call before it runs
        HookMatcher.HookCallback logPre = (input, context) -> {
            if (input instanceof PreToolUseHookInput pre) {
                System.out.println("Tool: " + pre.toolName());
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        // Track tool results and timing
        HookMatcher.HookCallback logPost = (input, context) -> {
            if (input instanceof PostToolUseHookInput post) {
                System.out.println("Result: " + post.toolResponse() + " in " + post.durationMs() + "ms");
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        // Handle errors
        HookMatcher.HookCallback logFailure = (input, context) -> {
            if (input instanceof PostToolUseFailureHookInput failure) {
                System.err.println("Error: " + failure.error());
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        var options = ClaudeAgentOptions.builder()
            .hooks(Map.of(
                HookEvent.PRE_TOOL_USE, List.of(new HookMatcher("*", List.of(logPre))),
                HookEvent.POST_TOOL_USE, List.of(new HookMatcher("*", List.of(logPost))),
                HookEvent.POST_TOOL_USE_FAILURE, List.of(new HookMatcher("*", List.of(logFailure)))
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

## Hooks after a background subagent finishes

With a one-shot `ClaudeSDK.query(...)`, a hook in the turn a background
subagent's completion wakes runs only if stdin is still open when the CLI calls
it. The SDK keeps stdin open until the run ends rather than at the first
result; see [Agents → Background subagents and callbacks](./feature-agents.md#background-subagents-and-callbacks)
for the rules and a CLI-version caveat, and `BackgroundAgentHooksExample` for a
runnable demonstration.

## See Also
- [Configuration Options](./feature-configuration-options.md#hooks)
- [Permissions](./feature-permissions.md)
- [Hooks Example](../examples/src/main/java/examples/Hooks.java)
