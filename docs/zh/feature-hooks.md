# 钩子系统

拦截并响应 Claude 会话中的生命周期事件。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../feature-hooks.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## 概览

钩子让你可以在会话生命周期的特定节点执行自定义代码。共有 10 个可监听的钩子事件。

## 钩子事件

- **PRE_TOOL_USE** —— 工具执行之前
- **POST_TOOL_USE** —— 工具成功执行之后
- **POST_TOOL_USE_FAILURE** —— 工具执行失败之后
- **USER_PROMPT_SUBMIT** —— 用户提交消息时
- **STOP** —— 会话停止时
- **SUBAGENT_START** —— 子 agent 启动时
- **SUBAGENT_STOP** —— 子 agent 停止时
- **PRE_COMPACT** —— 消息压缩之前
- **NOTIFICATION** —— 发生通知事件时
- **PERMISSION_REQUEST** —— 请求权限时

## 基础用法

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

> **派发顺序：** 当同一事件上注册了多个 matcher 时，CLI 会**并发地**（并行）派发所有匹配的钩子
> 回调，而不是顺序执行。请把每个钩子设计成彼此独立的 —— 不要依赖某一个先完成再开始另一个
> （例如，不要串联那些假定存在门控顺序的限流钩子）。

## 钩子输入字段

所有与工具相关的钩子输入（`PreToolUseHookInput`、`PostToolUseHookInput`、
`PostToolUseFailureHookInput`、`PermissionRequestHookInput`）都包含两个用于表示子 agent 上下文的
可选字段：

| 字段 | 类型 | 说明 |
|-------|------|-------------|
| `agentId` | `@Nullable String` | 子 agent 标识符。仅在由任务派生的子 agent 内部存在；在主线程上为 null。 |
| `agentType` | `@Nullable String` | agent 类型名称（例如 `"general-purpose"`）。在子 agent 内部，或以 `--agent` 启动的主线程上存在。 |

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

## PostToolUse 输出替换

`PostToolUseHookSpecificOutput` 让 `PostToolUse` 钩子能在工具输出抵达模型之前替换掉它。

```java
record PostToolUseHookSpecificOutput(
    @Nullable String additionalContext,    // extra context for the model
    @Nullable Object updatedToolOutput,    // replacement for any tool's output
    @Nullable Object updatedMCPToolOutput  // replacement for MCP tool output only
)
```

- **`updatedToolOutput`** —— 替换任意工具（包括内置工具）的输出。对于内置工具，取值必须符合该工具
  的输出 schema（例如 `Bash` 为 `{"stdout": ..., "stderr": ..., "interrupted": ...}`）；形状不匹配
  的取值会被拒绝，并保留原始输出。
- **`updatedMCPToolOutput`** —— 仅替换 MCP 工具的输出。请优先使用 `updatedToolOutput`，它对所有
  工具都有效。
- 为 `updatedToolOutput` 出现之前编写的代码，保留了向后兼容的 2 参数构造函数
  `(additionalContext, updatedMCPToolOutput)`。

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

## 权限决策 `"defer"`

`PreToolUse` 钩子可以返回 `permissionDecision: "defer"`（通过 `PermissionDecision.DEFER` /
`PreToolUseHookSpecificOutput`），从而在不执行该工具的情况下停止本次运行。CLI 会把这次被延迟的
调用呈现在 `ResultMessage.deferredToolUse` 上，供 SDK 使用方检查并决定是否继续。

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

`DeferredToolUse` 携带 `id`、`name` 和 `input`。完整的 `ResultMessage` 结构请参见
[消息类型](./feature-message-types.md#resultmessage)。

## 消息流上的钩子生命周期事件

在 `ClaudeAgentOptions` 上设置 `includeHookEvents(true)`，即可在消息流中额外接收到作为
`HookEventMessage` 对象的钩子生命周期事件。这对可观测性很有用（记录每一次钩子触发），而无需为每个
想观察的事件都注册一个钩子。

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

`HookEventMessage` 的字段：

| 字段 | 类型 | 说明 |
|-------|------|-------------|
| `subtype` | `String` | 钩子开始时为 `"hook_started"`，完成时为 `"hook_response"` |
| `data` | `Map<String, Object>` | 来自 CLI 的完整原始事件字典（`hook_response` 上含 `output`、`exit_code`、`outcome`） |
| `hookEventName` | `String` | 钩子事件名称（例如 `"PreToolUse"`、`"PostToolUse"`、`"Stop"`） |
| `sessionId` | `@Nullable String` | 该事件所属的会话 ID |
| `uuid` | `@Nullable String` | 唯一事件 ID |

`HookEventMessage.type()` 返回 `"system"`，但它**不**满足 `instanceof SystemMessage` —— 请直接对
`HookEventMessage` 分支处理。

## 完整示例

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

## 另见
- [配置选项](./feature-configuration-options.md#钩子)
- [Hooks 示例](../../examples/src/main/java/examples/Hooks.java)
