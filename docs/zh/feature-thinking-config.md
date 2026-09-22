# 扩展思考配置

通过细粒度的配置选项控制 Claude 的扩展思考行为。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../feature-thinking-config.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## 目录
- [概览](#概览)
- [ThinkingConfig 类型](#thinkingconfig-类型)
- [思考展示](#思考展示)
- [努力级别](#努力级别)
- [使用示例](#使用示例)
- [模式匹配](#模式匹配)
- [最佳实践](#最佳实践)
- [API 参考](#api-参考)

## 概览

扩展思考允许 Claude 在生成响应之前使用额外的推理 token。SDK 提供了两个配置选项：

1. **ThinkingConfig** —— 控制是否启用思考，并设置 token 预算
2. **Effort** —— 设置思考的深度/强度级别

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(16000))  // 16K thinking tokens
    .effort("high")                               // High effort level
    .build();
```

**注意**：`thinking()` 的优先级高于已废弃的 `maxThinkingTokens()` 选项。

## ThinkingConfig 类型

ThinkingConfig 是一个密封接口，有三种变体：

### ThinkingConfigAdaptive

使用自适应思考，由系统自动决定使用多少思考。会向 CLI 传递 `--thinking adaptive`。

```java
// Default — no display override
ThinkingConfig config = new ThinkingConfigAdaptive();

// With explicit display
ThinkingConfig display = new ThinkingConfigAdaptive(ThinkingDisplay.SUMMARIZED);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigAdaptive())
    .build();
```

**参数**：
- `display`（可选，可以为 `null`）—— 参见下文的[思考展示](#思考展示)。设置后会以
  `--thinking-display <value>` 转发。

**最适合**：
- 复杂的推理任务
- 开放式问题
- 你希望由 Claude 自行决定思考深度时
- 研究与分析任务

### ThinkingConfigEnabled

以指定的 token 预算启用思考。会向 CLI 传递 `--max-thinking-tokens <budgetTokens>`。当设置了
`display` 时，还会传递 `--thinking-display <value>`。

```java
// Default — no display override
ThinkingConfig config = new ThinkingConfigEnabled(10000);  // 10K tokens

// With explicit display
ThinkingConfig display = new ThinkingConfigEnabled(10000, ThinkingDisplay.OMITTED);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(8000))  // 8K token budget
    .build();
```

**参数**：
- `budgetTokens`（int）—— 最大思考 token 数（必须为正）
- `display`（可选，可以为 `null`）—— 参见下文的[思考展示](#思考展示)

**抛出**：若 `budgetTokens ≤ 0`，抛出 `IllegalArgumentException`

**最适合**：
- 关注预算的应用
- 可预测的成本控制
- 你已经知道复杂度级别时
- 测试与基准测试

### ThinkingConfigDisabled

完全禁用扩展思考。会向 CLI 传递 `--thinking disabled`。

```java
ThinkingConfig config = new ThinkingConfigDisabled();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigDisabled())
    .build();
```

**最适合**：
- 简单的查询与响应
- 延迟至关重要时
- 对成本敏感的操作
- 不需要推理的直白任务

## 思考展示

`ThinkingDisplay` 控制模型是返回思考文本，还是只返回签名块。它会以
`--thinking-display <value>` 转发给 CLI。

```java
public enum ThinkingDisplay {
    SUMMARIZED("summarized"),  // Return thinking text in the assistant stream
    OMITTED("omitted");        // Omit thinking text; return signature blocks only
}
```

**何时设置**：
- Opus 4.7+ 默认为 `omitted`（仅签名）。若你希望流中包含文本，请传入 `SUMMARIZED`。
- 较旧的模型两者都可以传 —— adaptive 与 enabled 都尊重 `display` 字段。

**兼容性**：
- 仅对 `ThinkingConfigAdaptive` 与 `ThinkingConfigEnabled` 转发。`ThinkingConfigDisabled` 从不发出
  `--thinking-display`。
- 把 `display` 留为 `null`（无参构造函数）意味着使用 CLI 针对具体模型的默认值。

```java
// Force summarized output regardless of model default
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigAdaptive(ThinkingDisplay.SUMMARIZED))
    .build();

// Suppress thinking text on a fixed budget
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(20000, ThinkingDisplay.OMITTED))
    .build();
```

## 努力级别

`effort` 选项控制思考的深度/强度。有效取值：

| 级别 | 说明 | 适用场景 |
|-------|-------------|----------|
| `"low"` | 最少的思考，响应最快 | 简单查询、快速响应 |
| `"medium"` | 适中的思考 | 通用任务 |
| `"high"` | 深度推理（默认） | 复杂问题、细致分析 |
| `"xhigh"` | 更深的推理深度（仅限 Opus 4.7） | Opus 4.7 上最难的问题 |
| `"max"` | 最大努力 | 研究、关键推理 |

```java
// Raw string overload
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .effort("xhigh")        // Opus 4.7-specific; falls back to "high" on other models
    .model("claude-opus-4-7")
    .build();

// Type-safe EffortLevel enum overload (recommended)
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .effort(EffortLevel.XHIGH)
    .model("claude-opus-4-7")
    .build();
```

**说明**：

- 努力级别与 ThinkingConfig 协同工作。你可以在不显式设置 ThinkingConfig 的情况下使用 effort。
- `"xhigh"` 是 **Opus 4.7 专有的**，在其他模型上会回退为 `"high"`。底层字段仍是普通的 `String`，
  因此将来新增的 effort 取值无需升级 SDK 即可传递。
- 位于 `in.vidyalai.claude.sdk.types.config.EffortLevel` 的 `EffortLevel` 枚举对应 Python SDK 导出的
  `EffortLevel` 类型别名，推荐在新代码中使用。`String` 重载仍然保留，以保持完全的灵活性（并支持枚举
  中尚未收录的未来级别）。详见 [EffortLevel 枚举](feature-configuration-options.md#effortlevel-枚举)。

## 使用示例

### 配合自适应思考的简单查询

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.config.ThinkingConfigAdaptive;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigAdaptive())
    .build();

List<Message> messages = ClaudeSDK.query(
    "Explain the halting problem in computer science",
    options
);
```

### 受预算控制的思考

```java
import in.vidyalai.claude.sdk.types.config.ThinkingConfigEnabled;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(5000))  // Max 5K tokens
    .effort("medium")
    .maxBudgetUsd(1.0)  // Also limit total cost
    .build();

List<Message> messages = ClaudeSDK.query(
    "What are the key differences between Java and Python?",
    options
);
```

### 为提速而禁用思考

```java
import in.vidyalai.claude.sdk.types.config.ThinkingConfigDisabled;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigDisabled())
    .build();

// Fast response for simple query
String response = ClaudeSDK.queryForText(
    "What is the capital of France?",
    options
);
```

### 高努力级别的研究任务

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigAdaptive())
    .effort("max")  // Maximum thinking depth
    .maxTurns(20)   // Allow extended conversation
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect("Research the latest developments in quantum computing");

    for (Message msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### 带思考的交互式会话

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(12000))
    .effort("high")
    .includePartialMessages(true)  // Stream thinking blocks
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();

    client.sendMessage("Help me debug this algorithm");
    for (Message msg : client.receiveResponse()) {
        switch (msg) {
            case ThinkingBlock thinking ->
                System.out.println("Thinking: " + thinking.thinking());
            case AssistantMessage assistant ->
                System.out.println("Response: " + assistant.getTextContent());
            default -> {}
        }
    }

    // Continue conversation
    client.sendMessage("Now optimize it for performance");
    // ... receive response
}
```

### 配合扩展思考的 Beta 功能

```java
import in.vidyalai.claude.sdk.types.config.SdkBeta;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .betas(List.of(SdkBeta.CONTEXT_1M))  // Extended context
    .thinking(new ThinkingConfigEnabled(16000))
    .effort("high")
    .build();

// Large context with deep thinking
List<Message> messages = ClaudeSDK.query(
    "Analyze this entire codebase and suggest improvements",
    options
);
```

## 模式匹配

使用 Java 的模式匹配来处理不同的 ThinkingConfig 类型：

```java
ThinkingConfig config = options.thinking();

if (config != null) {
    switch (config) {
        case ThinkingConfigAdaptive adaptive ->
            System.out.println("Using adaptive thinking (32K default)");
        case ThinkingConfigEnabled enabled ->
            System.out.println("Budget: " + enabled.budgetTokens() + " tokens");
        case ThinkingConfigDisabled disabled ->
            System.out.println("Thinking disabled");
    }
}
```

类型安全的检查：

```java
if (config instanceof ThinkingConfigEnabled enabled) {
    int budget = enabled.budgetTokens();
    System.out.println("Thinking budget: " + budget);
}
```

## 最佳实践

### 各类型的适用时机

**ThinkingConfigAdaptive**：
- ✅ 复杂的推理任务
- ✅ 问题复杂度未知
- ✅ 研究与分析
- ❌ 对预算敏感的应用
- ❌ 简单查询

**ThinkingConfigEnabled**：
- ✅ 需要预算控制
- ✅ 复杂度级别已知
- ✅ 生产环境应用
- ✅ 测试与基准测试
- ❌ 最优预算未知时

**ThinkingConfigDisabled**：
- ✅ 简单查询
- ✅ 对延迟敏感的应用
- ✅ 成本最小化
- ❌ 需要复杂推理
- ❌ 研究类任务

### 组合 thinking 与 effort

```java
// Low complexity - disable thinking
.thinking(new ThinkingConfigDisabled())
.effort("low")

// Medium complexity - fixed budget
.thinking(new ThinkingConfigEnabled(8000))
.effort("medium")

// High complexity - adaptive with high effort
.thinking(new ThinkingConfigAdaptive())
.effort("high")

// Maximum reasoning - adaptive with max effort
.thinking(new ThinkingConfigAdaptive())
.effort("max")
```

### 成本优化

```java
// Optimize for cost
ClaudeAgentOptions costOptimized = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(3000))  // Low token budget
    .effort("low")
    .maxBudgetUsd(0.50)  // Hard cost limit
    .maxTurns(5)  // Limit conversation length
    .build();

// Optimize for quality
ClaudeAgentOptions qualityOptimized = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigAdaptive())  // Full adaptive thinking
    .effort("max")  // Maximum effort
    .maxTurns(50)  // Allow extended reasoning
    .build();

// Balanced approach
ClaudeAgentOptions balanced = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(10000))  // Moderate budget
    .effort("medium")  // Standard effort
    .maxBudgetUsd(2.0)  // Reasonable limit
    .build();
```

### 从 maxThinkingTokens 迁移

`thinking()` 选项取代了已废弃的 `maxThinkingTokens()`：

```java
// Old (deprecated)
.maxThinkingTokens(10000)

// New (recommended)
.thinking(new ThinkingConfigEnabled(10000))

// Note: thinking() takes precedence if both are set
```

## API 参考

### ThinkingConfig 接口

```java
public sealed interface ThinkingConfig
    permits ThinkingConfigAdaptive, ThinkingConfigEnabled, ThinkingConfigDisabled

String type()  // Returns "adaptive", "enabled", or "disabled"
```

### ThinkingConfigAdaptive record

```java
public record ThinkingConfigAdaptive(@Nullable ThinkingDisplay display) implements ThinkingConfig {
    public ThinkingConfigAdaptive() { this(null); }   // convenience: no display override
}
```

CLI 标志：`--thinking adaptive`（始终）；`--thinking-display <value>`（当 `display != null` 时）。

### ThinkingConfigEnabled record

```java
public record ThinkingConfigEnabled(
    int budgetTokens,
    @Nullable ThinkingDisplay display
) implements ThinkingConfig {
    public ThinkingConfigEnabled(int budgetTokens) { this(budgetTokens, null); }
}
```

**参数**：
- `budgetTokens` —— 最大思考 token 数（必须 > 0）
- `display`（可选，可以为 `null`）—— 参见下文的 `ThinkingDisplay`

**抛出**：若 `budgetTokens ≤ 0`，抛出 `IllegalArgumentException`

CLI 标志：`--max-thinking-tokens <budgetTokens>`（始终）；`--thinking-display <value>`
（当 `display != null` 时）。

### ThinkingConfigDisabled record

```java
public record ThinkingConfigDisabled() implements ThinkingConfig
```

CLI 标志：`--thinking disabled`。disabled 变体从不发出 `--thinking-display`。

### ThinkingDisplay 枚举

```java
public enum ThinkingDisplay {
    SUMMARIZED("summarized"),
    OMITTED("omitted");
}
```

会作为 `--thinking-display` 的取值转发。

### ClaudeAgentOptions 方法

```java
// Builder methods
ClaudeAgentOptions.Builder thinking(ThinkingConfig thinking)
ClaudeAgentOptions.Builder effort(String effort)

// Getter methods
ThinkingConfig thinking()
String effort()
```

## 相关功能

- [配置选项](./feature-configuration-options.md) —— 全部配置选项
- [消息类型](./feature-message-types.md) —— ThinkingBlock 消息
- [流式事件](./feature-streaming-events.md) —— 流式传输思考块
- [Beta 功能](./feature-configuration-options.md#betas) —— 扩展上下文与思考

## 示例代码

完整演示请参见以下示例：
- `examples/AdvancedFeatures.java` —— betaFeatures() 与 completeConfiguration() 方法
- `sdk/src/test/java/in/vidyalai/claude/sdk/ClaudeAgentOptionsTest.java` —— 单元测试
