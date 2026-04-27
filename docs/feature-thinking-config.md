# Extended Thinking Configuration

Control Claude's extended thinking behavior with fine-grained configuration options.

## Table of Contents
- [Overview](#overview)
- [ThinkingConfig Types](#thinkingconfig-types)
- [Thinking Display](#thinking-display)
- [Effort Levels](#effort-levels)
- [Usage Examples](#usage-examples)
- [Pattern Matching](#pattern-matching)
- [Best Practices](#best-practices)
- [API Reference](#api-reference)

## Overview

Extended thinking allows Claude to use additional reasoning tokens before generating responses. The SDK provides two configuration options:

1. **ThinkingConfig** - Control whether thinking is enabled and set token budgets
2. **Effort** - Set the thinking depth/intensity level

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(16000))  // 16K thinking tokens
    .effort("high")                               // High effort level
    .build();
```

**Note**: `thinking()` takes precedence over the deprecated `maxThinkingTokens()` option.

## ThinkingConfig Types

ThinkingConfig is a sealed interface with three variants:

### ThinkingConfigAdaptive

Use adaptive thinking where the system automatically determines how much thinking to use. Passes `--thinking adaptive` to the CLI.

```java
// Default — no display override
ThinkingConfig config = new ThinkingConfigAdaptive();

// With explicit display
ThinkingConfig display = new ThinkingConfigAdaptive(ThinkingDisplay.SUMMARIZED);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigAdaptive())
    .build();
```

**Parameters**:
- `display` (optional, may be `null`) — see [Thinking Display](#thinking-display) below. When set, forwarded as `--thinking-display <value>`.

**Best for**:
- Complex reasoning tasks
- Open-ended problems
- When you want Claude to decide thinking depth
- Research and analysis tasks

### ThinkingConfigEnabled

Enable thinking with a specific token budget. Passes `--max-thinking-tokens <budgetTokens>` to the CLI. When `display` is set, also passes `--thinking-display <value>`.

```java
// Default — no display override
ThinkingConfig config = new ThinkingConfigEnabled(10000);  // 10K tokens

// With explicit display
ThinkingConfig display = new ThinkingConfigEnabled(10000, ThinkingDisplay.OMITTED);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(8000))  // 8K token budget
    .build();
```

**Parameters**:
- `budgetTokens` (int) — Maximum thinking tokens (must be positive)
- `display` (optional, may be `null`) — see [Thinking Display](#thinking-display) below

**Throws**: `IllegalArgumentException` if `budgetTokens ≤ 0`

**Best for**:
- Budget-conscious applications
- Predictable cost control
- When you know the complexity level
- Testing and benchmarking

### ThinkingConfigDisabled

Disable extended thinking entirely. Passes `--thinking disabled` to the CLI.

```java
ThinkingConfig config = new ThinkingConfigDisabled();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigDisabled())
    .build();
```

**Best for**:
- Simple queries and responses
- When latency is critical
- Cost-sensitive operations
- Straightforward tasks that don't require reasoning

## Thinking Display

`ThinkingDisplay` controls whether the model returns thinking text or only signature blocks. Forwarded to the CLI as `--thinking-display <value>`.

```java
public enum ThinkingDisplay {
    SUMMARIZED("summarized"),  // Return thinking text in the assistant stream
    OMITTED("omitted");        // Omit thinking text; return signature blocks only
}
```

**When to set**:
- Opus 4.7+ defaults to `omitted` (signature-only). Pass `SUMMARIZED` if you want the text in the stream.
- Older models can pass either — adaptive/enabled both honor the `display` field.

**Compatibility**:
- Forwarded only for `ThinkingConfigAdaptive` and `ThinkingConfigEnabled`. `ThinkingConfigDisabled` never emits `--thinking-display`.
- Leaving `display` as `null` (the no-arg constructors) means the CLI's model-specific default is used.

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

## Effort Levels

The `effort` option controls thinking depth/intensity. Valid values:

| Level | Description | Use Case |
|-------|-------------|----------|
| `"low"` | Minimal thinking | Simple queries, quick responses |
| `"medium"` | Balanced thinking (default) | General-purpose tasks |
| `"high"` | Deep thinking | Complex problems, detailed analysis |
| `"max"` | Maximum thinking | Research, critical reasoning |

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .effort("high")
    .build();
```

**Note**: The effort level works in conjunction with ThinkingConfig. You can use effort without explicitly setting ThinkingConfig.

## Usage Examples

### Simple Query with Adaptive Thinking

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

### Budget-Controlled Thinking

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

### Disable Thinking for Speed

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

### High-Effort Research Task

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

### Interactive Conversation with Thinking

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

### Beta Features with Extended Thinking

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

## Pattern Matching

Use Java's pattern matching to handle different ThinkingConfig types:

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

Type-safe checking:

```java
if (config instanceof ThinkingConfigEnabled enabled) {
    int budget = enabled.budgetTokens();
    System.out.println("Thinking budget: " + budget);
}
```

## Best Practices

### When to Use Each Type

**ThinkingConfigAdaptive**:
- ✅ Complex reasoning tasks
- ✅ Unknown problem complexity
- ✅ Research and analysis
- ❌ Budget-sensitive applications
- ❌ Simple queries

**ThinkingConfigEnabled**:
- ✅ Budget control needed
- ✅ Known complexity level
- ✅ Production applications
- ✅ Testing and benchmarking
- ❌ When optimal budget is unknown

**ThinkingConfigDisabled**:
- ✅ Simple queries
- ✅ Latency-critical applications
- ✅ Cost minimization
- ❌ Complex reasoning needed
- ❌ Research tasks

### Combining Thinking and Effort

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

### Cost Optimization

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

### Migration from maxThinkingTokens

The `thinking()` option replaces the deprecated `maxThinkingTokens()`:

```java
// Old (deprecated)
.maxThinkingTokens(10000)

// New (recommended)
.thinking(new ThinkingConfigEnabled(10000))

// Note: thinking() takes precedence if both are set
```

## API Reference

### ThinkingConfig Interface

```java
public sealed interface ThinkingConfig
    permits ThinkingConfigAdaptive, ThinkingConfigEnabled, ThinkingConfigDisabled

String type()  // Returns "adaptive", "enabled", or "disabled"
```

### ThinkingConfigAdaptive Record

```java
public record ThinkingConfigAdaptive(@Nullable ThinkingDisplay display) implements ThinkingConfig {
    public ThinkingConfigAdaptive() { this(null); }   // convenience: no display override
}
```

CLI flags: `--thinking adaptive` (always); `--thinking-display <value>` (when `display != null`).

### ThinkingConfigEnabled Record

```java
public record ThinkingConfigEnabled(
    int budgetTokens,
    @Nullable ThinkingDisplay display
) implements ThinkingConfig {
    public ThinkingConfigEnabled(int budgetTokens) { this(budgetTokens, null); }
}
```

**Parameters**:
- `budgetTokens` — Maximum thinking tokens (must be > 0)
- `display` (optional, may be `null`) — see `ThinkingDisplay` below

**Throws**: `IllegalArgumentException` if `budgetTokens ≤ 0`

CLI flags: `--max-thinking-tokens <budgetTokens>` (always); `--thinking-display <value>` (when `display != null`).

### ThinkingConfigDisabled Record

```java
public record ThinkingConfigDisabled() implements ThinkingConfig
```

CLI flag: `--thinking disabled`. `--thinking-display` is never emitted for the disabled variant.

### ThinkingDisplay Enum

```java
public enum ThinkingDisplay {
    SUMMARIZED("summarized"),
    OMITTED("omitted");
}
```

Forwarded as the value of `--thinking-display`.

### ClaudeAgentOptions Methods

```java
// Builder methods
ClaudeAgentOptions.Builder thinking(ThinkingConfig thinking)
ClaudeAgentOptions.Builder effort(String effort)

// Getter methods
ThinkingConfig thinking()
String effort()
```

## Related Features

- [Configuration Options](./feature-configuration-options.md) - All configuration options
- [Message Types](./feature-message-types.md) - ThinkingBlock messages
- [Streaming Events](./feature-streaming-events.md) - Streaming thinking blocks
- [Beta Features](./feature-configuration-options.md#beta-features) - Extended context and thinking

## Example Code

See these examples for complete demonstrations:
- `examples/AdvancedFeatures.java` - betaFeatures() and completeConfiguration() methods
- `sdk/src/test/java/in/vidyalai/claude/sdk/ClaudeAgentOptionsTest.java` - Unit tests
