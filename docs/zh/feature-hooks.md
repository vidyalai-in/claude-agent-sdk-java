# 钩子系统

拦截并响应 Claude 会话中的生命周期事件。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../feature-hooks.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## 概览

钩子会在 agent 循环的特定节点运行你的代码。你为某个 `HookEvent` 注册一个回调；当 CLI 到达该节点时，
它会向 SDK 发送一个钩子请求，SDK 以类型化的 `HookInput` 调用你的回调，而你的回调用一个 `HookOutput`
作答，它可以放行、阻止，或添加上下文。

共有 10 个可以注册回调的钩子事件。

## 钩子事件

`HookEvent`（包 `in.vidyalai.claude.sdk.types.hook`）：

| 常量 | 事件名称 | 触发时机 | 输入类型 |
|----------|------------|---------------|------------|
| `PRE_TOOL_USE` | `PreToolUse` | 工具运行之前 | `PreToolUseHookInput` |
| `POST_TOOL_USE` | `PostToolUse` | 工具成功执行之后 | `PostToolUseHookInput` |
| `POST_TOOL_USE_FAILURE` | `PostToolUseFailure` | 工具执行失败之后 | `PostToolUseFailureHookInput` |
| `USER_PROMPT_SUBMIT` | `UserPromptSubmit` | 提交提示词时 | `UserPromptSubmitHookInput` |
| `STOP` | `Stop` | 会话即将停止时 | `StopHookInput` |
| `SUBAGENT_START` | `SubagentStart` | 子 agent 启动时 | `SubagentStartHookInput` |
| `SUBAGENT_STOP` | `SubagentStop` | 子 agent 停止时 | `SubagentStopHookInput` |
| `PRE_COMPACT` | `PreCompact` | 上下文压缩之前 | `PreCompactHookInput` |
| `NOTIFICATION` | `Notification` | 发生通知时 | `NotificationHookInput` |
| `PERMISSION_REQUEST` | `PermissionRequest` | 请求权限时 | `PermissionRequestHookInput` |

## 基础用法

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

对于工具事件，`matcher` 可以是一个工具名称（`"Bash"`）、用 `|` 连接的多个名称（`"Write|MultiEdit|Edit"`），
或 `"*"`。各事件的 matcher 语法请参阅
[Claude Code 钩子参考](https://docs.anthropic.com/en/docs/claude-code/hooks#structure)。

回调是一个 `HookMatcher.HookCallback`：

```java
@FunctionalInterface
interface HookCallback extends BiFunction<HookInput, HookContext, CompletableFuture<HookOutput>> {}
```

它接收类型化的输入以及一个 `HookContext`：

```java
record HookContext(
    @Nullable String toolUseId,  // the tool use ID, for tool events
    @Nullable Object signal      // reserved for future abort support; always null
)
```

SDK 会等待返回的 future 完成后再答复 CLI。对于同步的工作，返回 `CompletableFuture.completedFuture(...)` 即可。

> **派发顺序：** 当同一事件上注册了多个 matcher 时，CLI 会**并发地**（并行）派发所有匹配的钩子
> 回调，而不是顺序执行。请把每个钩子设计成彼此独立的 —— 不要依赖某一个先完成再开始另一个
> （例如，不要串联那些假定存在门控顺序的限流钩子）。

## 钩子输入

`HookInput` 是一个 sealed 接口；请用 `instanceof` 对具体的 record 进行分支。每种输入都带有以下字段：

| 访问器 | 类型 | 说明 |
|----------|------|-------------|
| `hookEventName()` | `String` | 事件名称，例如 `"PreToolUse"` |
| `sessionId()` | `String` | 会话 ID |
| `transcriptPath()` | `String` | 会话记录的路径 |
| `cwd()` | `String` | 工作目录 |
| `permissionMode()` | `@Nullable String` | 当前权限模式 |
| `promptId()` | `@Nullable String` | 正在处理的用户提示词的 UUID。与 OpenTelemetry 事件上的 `prompt.id` 属性一致。在首次用户输入之前为 null |
| `effort()` | `@Nullable HookEffort` | 当前轮次生效的努力级别，形式为 `HookEffort(String level)`（例如 `"low"`、`"high"`、`"max"`，已计入针对所选模型的降级）。在支持努力级别的模型上、于工具使用上下文中触发的钩子会带有该字段 |

事件专属字段：

| 输入 | 字段 |
|-------|--------|
| `PreToolUseHookInput` | `toolName`、`toolInput`、`toolUseId`、`agentId`、`agentType` |
| `PostToolUseHookInput` | `toolName`、`toolInput`、`toolResponse`、`toolUseId`、`agentId`、`agentType`、`durationMs` |
| `PostToolUseFailureHookInput` | `toolName`、`toolInput`、`toolUseId`、`error`、`isInterrupt`、`agentId`、`agentType`、`durationMs` |
| `PermissionRequestHookInput` | `toolName`、`toolInput`、`permissionSuggestions`、`agentId`、`agentType` |
| `UserPromptSubmitHookInput` | `prompt` |
| `StopHookInput` | `stopHookActive`、`lastAssistantMessage`、`backgroundTasks`、`sessionCrons` |
| `SubagentStartHookInput` | `agentId`、`agentType` |
| `SubagentStopHookInput` | `stopHookActive`、`agentId`、`agentTranscriptPath`、`agentType`、`lastAssistantMessage`、`backgroundTasks`、`sessionCrons` |
| `PreCompactHookInput` | `trigger`（`CompactTriggerType`）、`customInstructions` |
| `NotificationHookInput` | `message`、`title`、`notificationType` |

CLI 可能省略的字段在缺失时为 `null`：`permissionMode`、`promptId`、`effort`、工具输入上的
`agentId`/`agentType`、`isInterrupt`、`durationMs`、`lastAssistantMessage`、`backgroundTasks`、
`sessionCrons`、`permissionSuggestions`、`title` 和 `customInstructions`。

### 子 agent 上下文

与工具相关的输入（`PreToolUseHookInput`、`PostToolUseHookInput`、
`PostToolUseFailureHookInput`、`PermissionRequestHookInput`）都包含两个用于表示子 agent 上下文的
可选字段：

| 字段 | 类型 | 说明 |
|-------|------|-------------|
| `agentId` | `@Nullable String` | 子 agent 标识符。仅在由任务派生的子 agent 内部存在；在主线程上为 null。 |
| `agentType` | `@Nullable String` | agent 类型名称（例如 `"general-purpose"`）。在子 agent 内部，或以 `--agent` 启动的主线程上存在。 |

```java
HookMatcher.HookCallback subagentAware = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse && preToolUse.agentId() != null) {
        System.out.println("Tool used inside sub-agent: " + preToolUse.agentId());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};
```

### 工具计时

`PostToolUseHookInput.durationMs()` 和 `PostToolUseFailureHookInput.durationMs()`
以毫秒为单位报告工具的执行时间，不含权限提示和钩子所花的时间。

### Stop 与 SubagentStop 时的后台工作

`StopHookInput` 和 `SubagentStopHookInput` 会报告会话（或子 agent）停止时仍在运行的内容：

- `lastAssistantMessage` —— 停止前最后一条 assistant 消息的文本。
- `backgroundTasks` —— 进行中的后台工作，以 `BackgroundTaskSummary` record 表示：`id`、`type`
  （例如 `"shell"`、`"subagent"`、`"monitor"`、`"workflow"`）、`status`、`description`，以及
  `command`（shell 任务）、`agentType`（子 agent 任务）、`server` 和 `tool`（monitor / MCP 任务）
  和 `name`（workflow 任务）。没有进行中的工作时为空。
- `sessionCrons` —— 会话范围内、稍后会恢复会话的定时唤醒，以 `SessionCronSummary` record 表示：
  `id`、`schedule`（一个 cron 表达式）、`recurring`（一次性唤醒为 `false`）和 `prompt`。

对于子 agent，这两个列表的范围都是其父会话。`description`、`command` 以及 cron 的 `prompt`
上限为 1000 个字符；被截断的值会以字符串内的 `"… [+N chars]"` 标记结尾。

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

回调返回一个 `HookOutput`（包 `in.vidyalai.claude.sdk.types.hook.output`），通过它的构建器构造：

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

只有你设置过的字段才会被发送。设置了 `async` 时，只发送 `async` 和 `asyncTimeout`。

## 钩子专属输出

`HookSpecificOutput` 是一个 sealed 接口，每个事件对应一个 record。每个 record 序列化时都带有它的
`hookEventName`，以及你设置过的字段（为 `null` 的字段会被省略）：

| Record | 字段 |
|--------|--------|
| `PreToolUseHookSpecificOutput` | `permissionDecision`（`PermissionDecision`）、`permissionDecisionReason`、`updatedInput`、`additionalContext` |
| `PostToolUseHookSpecificOutput` | `additionalContext`、`updatedToolOutput`、`updatedMCPToolOutput` |
| `PostToolUseFailureHookSpecificOutput` | `additionalContext` |
| `UserPromptSubmitHookSpecificOutput` | `additionalContext`、`sessionTitle`、`suppressOriginalPrompt` |
| `NotificationHookSpecificOutput` | `additionalContext` |
| `SubagentStartHookSpecificOutput` | `additionalContext` |
| `PermissionRequestHookSpecificOutput` | `decision`（一个 map） |
| `StopHookSpecificOutput` | `additionalContext` |
| `SubagentStopHookSpecificOutput` | `additionalContext` |
| `SessionStartHookSpecificOutput` | `additionalContext`、`initialUserMessage`、`sessionTitle`、`watchPaths`、`reloadSkills` |
| `UserPromptExpansionHookSpecificOutput` | `additionalContext`、`suppressOriginalPrompt` |
| `PostToolBatchHookSpecificOutput` | `additionalContext` |
| `PermissionDeniedHookSpecificOutput` | `retry` |
| `PreModelSwitchHookSpecificOutput` | `permissionDecision`、`permissionDecisionReason` |
| `PostModelSwitchHookSpecificOutput` | `additionalContext` |
| `ElicitationHookSpecificOutput` | `action`（`ElicitationAction`）、`content` |
| `ElicitationResultHookSpecificOutput` | `action`（`ElicitationAction`）、`content` |
| `CwdChangedHookSpecificOutput` | `watchPaths` |
| `FileChangedHookSpecificOutput` | `watchPaths` |
| `WorktreeCreateHookSpecificOutput` | `worktreePath`（必填） |
| `MessageDisplayHookSpecificOutput` | `displayContent` |

> **回调能用到其中哪些。** SDK 回调只能为上面列出的 10 个 `HookEvent` 注册，因此只有前 9 个 record
> 用于答复回调能够接收到的事件。其余的（从 `SessionStart` 到 `MessageDisplay`）描述的是 `HookEvent`
> 尚未列出的事件的输出。定义这些类型是为了与 Python 和 TypeScript SDK 保持对等，它们定义了相同的结构。

字段说明：

- **`UserPromptSubmitHookSpecificOutput`** —— `sessionTitle` 设置会话标题。当输出的 `decision` 为
  `"block"` 时，`suppressOriginalPrompt` 会在阻止消息中省略原始提示词。单参数构造函数
  `(additionalContext)` 仍然保留。
- **`StopHookSpecificOutput` / `SubagentStopHookSpecificOutput`** —— `additionalContext` 是传递给模型
  （或子 agent）的非错误反馈；会话会继续进行，以便它据此采取行动。
- **`SessionStartHookSpecificOutput`** —— `initialUserMessage` 会成为会话的第一条用户消息；当会话从
  `clear` 或 `compact` 开始时，`sessionTitle` 会被忽略；`watchPaths` 是要监视 FileChanged 事件的
  绝对路径；`reloadSkills` 会在 SessionStart 钩子完成后重新扫描技能和命令目录。
- **`PermissionDeniedHookSpecificOutput`** —— `retry` 告诉模型它可以重试被拒绝的工具调用。对于没有
  分类器裁定的拒绝，该字段会被忽略。
- **`PreModelSwitchHookSpecificOutput`** —— `ALLOW` 继续切换，`DENY` 取消切换；在非交互式会话中，
  `ASK` 会被视为拒绝。构造函数会拒绝 `PermissionDecision.DEFER`。
- **`PostModelSwitchHookSpecificOutput`** —— `additionalContext` 会随新模型处理的下一个请求一起送达模型。
- **`ElicitationHookSpecificOutput`** —— 接受、拒绝或取消一个 MCP elicitation 请求；`content` 存放
  表单字段的值，且仅在 `ElicitationAction.ACCEPT` 时使用。`ElicitationResultHookSpecificOutput`
  会在响应抵达 MCP 服务器之前覆盖其 action 或 content。
- **`CwdChangedHookSpecificOutput` / `FileChangedHookSpecificOutput`** —— `watchPaths` 会替换当前的
  动态监视列表。
- **`MessageDisplayHookSpecificOutput`** —— 仅影响显示：`displayContent` 替换屏幕上的文本，但不会改变
  已存储的消息或模型看到的内容。省略它则显示原文。

### 阻止工具调用

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

### 用 Stop 钩子让模型继续工作

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

检查 `stopHookActive()` 可以避免无休止地把模型送回去工作：当会话已经因为某个 Stop 钩子而在继续时，
它为 `true`。

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

## 权限决策 `"defer"`

`PreToolUse` 钩子可以返回 `permissionDecision: "defer"`（通过 `PermissionDecision.DEFER` /
`PreToolUseHookSpecificOutput`），从而在不执行该工具的情况下停止本次运行。CLI 会把这次被延迟的
调用呈现在 `ResultMessage.deferredToolUse` 上，供 SDK 使用方检查并决定是否继续。

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

`DeferredToolUse` 携带 `id`、`name` 和 `input`。完整的 `ResultMessage` 结构请参见
[消息类型](./feature-message-types.md#resultmessage)。

## 消息流上的钩子生命周期事件

在 `ClaudeAgentOptions` 上设置 `includeHookEvents(true)`，即可在消息流中额外接收到作为
`HookEventMessage` 对象的钩子生命周期事件。这对可观测性很有用（记录每一次钩子触发），而无需为每个
想观察的事件都注册一个钩子。无论此设置如何，CLI 都会发出 `SessionStart` 和 `Setup` 钩子事件。

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

## 后台子 agent 完成之后的钩子

使用一次性的 `ClaudeSDK.query(...)` 时，在后台子 agent 完成所唤醒的那一轮中，钩子只有在 CLI 调用它时 stdin 仍然打开才会运行。SDK 会保持 stdin 打开直到运行结束，而不是在第一个 result 处关闭；相关规则以及一条关于 CLI 版本的注意事项参见 [Agent → 后台子 agent 与回调](./feature-agents.md#后台子-agent-与回调)，可运行的演示参见 `BackgroundAgentHooksExample`。

## 另见
- [配置选项](./feature-configuration-options.md#钩子)
- [权限](./feature-permissions.md)
- [Hooks 示例](../../examples/src/main/java/examples/Hooks.java)
