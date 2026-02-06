# Message Types API Reference

Type hierarchy for Claude messages.

## Message Interface

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, ResultMessage, StreamEvent {
    String type();
}
```

## UserMessage

```java
record UserMessage(
    String id,
    String role,
    String content,
    @Nullable String name
) implements Message
```

## AssistantMessage

```java
record AssistantMessage(
    String id,
    String role,
    List<ContentBlock> content,
    @Nullable AssistantMessageError error,
    @Nullable String stopReason,
    @Nullable Integer stopSequenceIndex
) implements Message {
    String getTextContent();  // Extract all text
}
```

### AssistantMessageError

```java
enum AssistantMessageError {
    OVERLOADED,
    TIMEOUT,
    RATE_LIMIT,
    UNKNOWN
}
```

## SystemMessage

```java
record SystemMessage(
    String subtype,
    @Nullable Object data
) implements Message
```

## ResultMessage

```java
record ResultMessage(
    @Nullable String stopReason,
    @Nullable Integer stopSequenceIndex,
    @Nullable Integer usageInput,
    @Nullable Integer usageOutput,
    @Nullable Double totalCostUsd,
    @Nullable String errorMessage
) implements Message
```

## StreamEvent

```java
record StreamEvent(
    String eventType,
    @Nullable Object delta,
    @Nullable Object data
) implements Message
```

## Content Blocks

### ContentBlock Interface

```java
sealed interface ContentBlock permits TextBlock,
    ThinkingBlock, ToolUseBlock, ToolResultBlock
```

### TextBlock

```java
record TextBlock(String text) implements ContentBlock
```

### ThinkingBlock

```java
record ThinkingBlock(String thinking) implements ContentBlock
```

### ToolUseBlock

```java
record ToolUseBlock(
    String id,
    String name,
    @Nullable Object input
) implements ContentBlock
```

### ToolResultBlock

```java
record ToolResultBlock(
    String toolUseId,
    @Nullable Object content,
    @Nullable Boolean isError
) implements ContentBlock
```

## See Also
- [Message Types Guide](./feature-message-types.md)
