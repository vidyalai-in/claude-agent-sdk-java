# Permission System

Custom permission control for tool usage.

## Overview

The permission system controls which tools Claude can use and how permission requests are handled.

## Permission Modes

```java
.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
```

### Available Modes

- **PROMPT** (default) - Prompt user for each permission
- **ACCEPT_ALL** - Automatically accept all permissions
- **ACCEPT_EDITS** - Auto-accept file edits, prompt for others
- **BYPASS_PERMISSIONS** - Skip permission checks entirely
- **DONT_ASK** - Allow all tools without prompting
- **AUTO** - Automatically determine the appropriate permission mode

## Custom Permission Callback

For fine-grained control, use `canUseTool` callback (requires streaming mode):

```java
.canUseTool((toolName, input, context) -> {
    // Custom logic
    if (shouldAllow(toolName, context.blockedPath())) {
        return CompletableFuture.completedFuture(
            new PermissionResultAllow()
        );
    } else {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Access denied: " + context.blockedPath())
        );
    }
})
```

> **`canUseTool` only fires for `"ask"` decisions.** This callback is the SDK replacement for the interactive permission prompt — it runs only when the CLI's permission rules evaluate to `"ask"`. It is **not** invoked for tool calls already permitted by `allowedTools`, `permissionMode` (e.g. `ACCEPT_EDITS`, `BYPASS_PERMISSIONS`), or `permissions.allow` rules in settings — those never reach a prompt. To observe or gate **every** tool call regardless of permission rules, register a `PreToolUse` hook via `hooks(...)` instead.

## ToolPermissionContext

The CLI enriches the permission context so callbacks can render meaningful prompts without reconstructing them from the raw tool input:

```java
record ToolPermissionContext(
    @Nullable Object signal,                 // reserved for future abort signal support (always null today)
    List<PermissionUpdate> suggestions,      // permission suggestions from the CLI
    @Nullable String toolUseId,              // unique tool call ID within the assistant message
    @Nullable String agentId,                // sub-agent's ID if running inside a sub-agent
    @Nullable String blockedPath,            // file path that triggered the request (e.g. Bash hitting a denied path)
    @Nullable String decisionReason,         // why this prompt was triggered (e.g. PreToolUse hook's permissionDecisionReason)
    @Nullable String title,                  // full prompt sentence ("Claude wants to read foo.txt") — use as primary prompt text
    @Nullable String displayName,            // short noun phrase ("Read file") for buttons / compact UI
    @Nullable String description             // human-readable subtitle for the permission UI
)
```

Backwards-compatible constructors are preserved for code written before the enrichment fields existed:

- `new ToolPermissionContext()` — empty context
- `new ToolPermissionContext(suggestions)` — suggestions only
- `new ToolPermissionContext(signal, suggestions)` — signal + suggestions
- `new ToolPermissionContext(signal, suggestions, toolUseId, agentId)` — pre-enrichment 4-arg form

```java
.canUseTool((toolName, input, context) -> {
    // Prefer the CLI-supplied prompt text when present.
    String prompt = context.title() != null
        ? context.title()
        : "Allow " + toolName + "?";
    String why = context.decisionReason();
    if (why != null) prompt += " (" + why + ")";

    boolean ok = askUser(prompt);
    return CompletableFuture.completedFuture(
        ok ? new PermissionResultAllow()
           : new PermissionResultDeny("user declined"));
})
```

## PermissionDecision (in PreToolUse hooks)

`PermissionDecision` is the value a `PreToolUse` hook returns from `PreToolUseHookSpecificOutput.permissionDecision`:

| Constant | Wire value | Effect |
|----------|------------|--------|
| `ALLOW` | `"allow"` | Tool runs without prompting. |
| `DENY` | `"deny"` | Tool is blocked. |
| `ASK` | `"ask"` | Triggers the SDK's `canUseTool` callback (or the CLI prompt). |
| `DEFER` | `"defer"` | Stops the run without executing the tool; the deferred call is surfaced on `ResultMessage.deferredToolUse`. See [Hooks → Permission Decision: `"defer"`](./feature-hooks.md#permission-decision-defer). |

## PermissionResult

```java
// Allow
new PermissionResultAllow()

// Deny with reason
new PermissionResultDeny("Reason for denial")
```

## Examples

### Path-based Permissions

```java
.canUseTool((toolName, input, context) -> {
    // blockedPath is set by the CLI when the request was triggered by a
    // path violation (e.g. a Bash command touching a denied directory).
    // For tools like Read / Write the path is in `input` instead.
    String path = context.blockedPath() != null
        ? context.blockedPath()
        : (String) input.get("file_path");

    // Allow read-only in /src
    if (toolName.equals("Read") && path != null && path.startsWith("/src")) {
        return CompletableFuture.completedFuture(
            new PermissionResultAllow()
        );
    }

    // Deny write to sensitive dirs
    if (toolName.equals("Write") && path != null && path.contains("/config")) {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Cannot write to config")
        );
    }

    // Default allow
    return CompletableFuture.completedFuture(
        new PermissionResultAllow()
    );
})
```

### Time-based Permissions

```java
.canUseTool((toolName, input, context) -> {
    // Only allow during business hours
    int hour = LocalTime.now().getHour();
    if (hour < 9 || hour > 17) {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Outside business hours")
        );
    }
    
    return CompletableFuture.completedFuture(
        new PermissionResultAllow()
    );
})
```

### User Confirmation

```java
.canUseTool((toolName, input, context) -> {
    // Prompt user for dangerous operations
    if (toolName.equals("Bash")) {
        boolean approved = promptUser("Allow bash: " + input + "?");
        if (approved) {
            return CompletableFuture.completedFuture(
                new PermissionResultAllow()
            );
        } else {
            return CompletableFuture.completedFuture(
                new PermissionResultDeny("User rejected")
            );
        }
    }
    
    return CompletableFuture.completedFuture(
        new PermissionResultAllow()
    );
})
```

## See Also
- [Configuration Options](./feature-configuration-options.md#permission-settings)
- [Permission Callbacks Example](../examples/src/main/java/examples/PermissionCallbacks.java)
