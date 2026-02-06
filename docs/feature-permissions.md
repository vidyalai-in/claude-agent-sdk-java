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

## Custom Permission Callback

For fine-grained control, use `canUseTool` callback (requires streaming mode):

```java
.canUseTool((toolName, input, context) -> {
    // Custom logic
    if (shouldAllow(toolName, context.path())) {
        return CompletableFuture.completedFuture(
            new PermissionResultAllow()
        );
    } else {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Access denied: " + context.path())
        );
    }
})
```

## ToolPermissionContext

```java
record ToolPermissionContext(
    String toolName,
    @Nullable String path,
    @Nullable Object args
)
```

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
    String path = context.path();
    
    // Allow read-only in /src
    if (toolName.equals("Read") && path.startsWith("/src")) {
        return CompletableFuture.completedFuture(
            new PermissionResultAllow()
        );
    }
    
    // Deny write to sensitive dirs
    if (toolName.equals("Write") && path.contains("/config")) {
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
