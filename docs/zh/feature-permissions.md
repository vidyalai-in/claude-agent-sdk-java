# 权限系统

针对工具使用的自定义权限控制。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../feature-permissions.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## 概览

权限系统控制 Claude 可以使用哪些工具，以及如何处理权限请求。

## 权限模式

```java
.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
```

### 可用模式

- **PROMPT**（默认）—— 每次权限请求都询问用户
- **ACCEPT_ALL** —— 自动接受所有权限请求
- **ACCEPT_EDITS** —— 自动接受文件编辑，其他情况询问
- **BYPASS_PERMISSIONS** —— 完全跳过权限检查
- **DONT_ASK** —— 允许所有工具且不询问
- **AUTO** —— 自动确定合适的权限模式

## 自定义权限回调

若需要细粒度控制，请使用 `canUseTool` 回调。它对所有入口点都有效 —— 字符串提示词在内部也会通过
stdin 以流的方式发送，因此承载权限请求的控制协议在那里同样可用。它不能与
`permissionPromptToolName` 同时使用：

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

> **`canUseTool` 仅在 `"ask"` 决策时触发。** 该回调是 SDK 对交互式权限提示的替代 —— 只有当 CLI 的
> 权限规则求值为 `"ask"` 时它才会运行。对于已经被 `allowedTools`、`permissionMode`（例如
> `ACCEPT_EDITS`、`BYPASS_PERMISSIONS`）或设置中的 `permissions.allow` 规则放行的工具调用，它
> **不会**被调用 —— 那些调用根本不会走到提示环节。若要不论权限规则如何都观察或拦截**每一次**工具
> 调用，请改为通过 `hooks(...)` 注册 `PreToolUse` 钩子。

### 遮蔽警告

由于 `canUseTool` 对已被其他选项放行的调用永远不会触发，SDK 会在连接时对检测到的明显被遮蔽的回调
发出一条**提示性警告**。该检查在每次构造查询时运行一次 —— 即 `ClaudeSDKClient.connect()` 启动时，
或任意 `ClaudeSDK.query(...)` 建立时 —— 并通过 `java.util.logging` 在名为
`in.vidyalai.claude.sdk.internal.CanUseToolShadow` 的 logger 上记录一条 `WARNING`。

当 `canUseTool` 回调与以下任一项同时设置时，会被报告为被遮蔽：

- **`permissionMode(PermissionMode.BYPASS_PERMISSIONS)`** —— 在查询回调之前，每一次工具调用都会被
  自动批准（显式的拒绝规则除外）。
- **放行*整个*工具的 `allowedTools` 条目** —— 即不带限定符（`"Read"`）、限定符为空（`"Read()"`）或
  限定符为单个通配符（`"Read(*)"`）的条目。像 `"Bash(ls:*)"` 这样起收窄作用的限定符**不会**遮蔽
  回调，因为不匹配的调用仍会落到回调上。警告会逐一列出被遮蔽的工具。

`skills("all")`（通过 `Builder.skillsAll()`）也被纳入考虑：它会让传输层注入一条裸的 `Skill` 放行
规则，因此它对回调的遮蔽效果与手写的 `"Skill"` 条目完全相同。具名 skill
（`skills(List.of("reviewer"))`）注入的是 `Skill(name)` 限定符，不构成遮蔽。

该警告**仅供参考 —— 它绝不会抛出异常**。遮蔽有可能是有意为之（例如某个回调专门用于处理*不在*
`allowedTools` 中的工具）。若要不论权限规则如何都观察或拦截每一次工具调用，请改用 `PreToolUse`
钩子 —— 但要注意，返回*允许*决策的 `PreToolUse` 钩子同样会跳过 `canUseTool`。设置文件中的放行规则
也可能遮蔽该回调，但本检查看不到它们。

要抑制该警告，请把 `in.vidyalai.claude.sdk.internal.CanUseToolShadow` logger 的级别提高到
`WARNING` 以上：

```java
java.util.logging.Logger
    .getLogger("in.vidyalai.claude.sdk.internal.CanUseToolShadow")
    .setLevel(java.util.logging.Level.SEVERE);
```

### 让回调保持确定性

设置文件中的规则正是该提示无法察觉的那种遮蔽情形，而且最容易让人栽跟头：`~/.claude/settings.json`
的 `permissions.allow` 下只要有一条裸的 `Write(*)`，就足以让 `canUseTool` 回调完全不再触发 ——
而这台机器昨天还是好好的。不会有任何报错 —— 回调只是根本不被查询，于是统计自己处理了多少次提示的
代码会报告零。

在回调需要可预期地触发的场合 —— 演示、测试、可复现的脚本 —— 请不要加载任何设置文件：

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
        .canUseTool(callback)
        // Load no settings files: an allow rule in the user's or the project's
        // settings.json shadows the callback exactly like an allowedTools
        // entry does, and the advisory above cannot see those rules.
        .settingSources(List.of())
        .permissionMode(PermissionMode.DEFAULT)
        .build();
```

另请注意，列在 `allowedTools` 中的工具永远不会走到提示环节，因此意在拦截该工具的回调必须把它从
列表中排除。examples 模块中的 `PermissionCallbacks.java` 两点都做到了。

> **惯用法说明：** Python SDK 以 `CanUseToolShadowedWarning`（`UserWarning` 的子类）报告这种情况，
> TypeScript SDK 则以 `CLAUDE_SDK_CAN_USE_TOOL_SHADOWED` 进程警告报告。Java SDK 使用
> `java.util.logging` —— 它惯用的警告通道 —— 来代替专门的警告类型。

## ToolPermissionContext

CLI 会丰富权限上下文，使回调无需从原始工具输入中重新拼凑，就能呈现有意义的提示：

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

为在这些丰富字段出现之前编写的代码保留了向后兼容的构造函数：

- `new ToolPermissionContext()` —— 空上下文
- `new ToolPermissionContext(suggestions)` —— 仅 suggestions
- `new ToolPermissionContext(signal, suggestions)` —— signal + suggestions
- `new ToolPermissionContext(signal, suggestions, toolUseId, agentId)` —— 丰富字段之前的 4 参数形式

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

## PermissionDecision（用于 PreToolUse 钩子）

`PermissionDecision` 是 `PreToolUse` 钩子通过
`PreToolUseHookSpecificOutput.permissionDecision` 返回的取值：

| 常量 | 协议取值 | 效果 |
|----------|------------|--------|
| `ALLOW` | `"allow"` | 工具直接运行，不作提示。 |
| `DENY` | `"deny"` | 工具被阻止。 |
| `ASK` | `"ask"` | 触发 SDK 的 `canUseTool` 回调（或 CLI 的提示）。 |
| `DEFER` | `"defer"` | 停止本次运行且不执行该工具；被延迟的调用会出现在 `ResultMessage.deferredToolUse` 上。参见[钩子 → 权限决策 `"defer"`](./feature-hooks.md#权限决策-defer)。 |

## PermissionResult

```java
// Allow
new PermissionResultAllow()

// Deny with reason
new PermissionResultDeny("Reason for denial")
```

## 示例

### 基于路径的权限

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

### 基于时间的权限

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

### 用户确认

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

## 另见
- [配置选项](./feature-configuration-options.md#权限设置)
- [权限回调示例](../../examples/src/main/java/examples/PermissionCallbacks.java)
