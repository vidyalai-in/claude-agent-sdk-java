# Message Types

Understanding the message type system for processing Claude conversations.

## Table of Contents
- [Overview](#overview)
- [Message Type Hierarchy](#message-type-hierarchy)
- [UserMessage](#usermessage)
- [AssistantMessage](#assistantmessage)
- [SystemMessage](#systemmessage)
- [ResultMessage](#resultmessage)
- [StreamEvent](#streamevent)
- [Content Blocks](#content-blocks)
- [Pattern Matching](#pattern-matching)
- [Examples](#examples)

## Overview

The SDK uses a sealed interface hierarchy for type-safe message handling. All messages implement the `Message` sealed interface, enabling exhaustive pattern matching.

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, ResultMessage, StreamEvent {}
```

## Message Type Hierarchy

```
Message (sealed interface)
├── UserMessage (record) - Messages from user
├── AssistantMessage (record) - Messages from Claude
│   └── content: List<ContentBlock>
│       ├── TextBlock - Plain text
│       ├── ThinkingBlock - Claude's reasoning
│       ├── ToolUseBlock - Tool invocation
│       └── ToolResultBlock - Tool results
├── SystemMessage (record) - System notifications
├── ResultMessage (record) - Final result with cost/usage
└── StreamEvent (record) - Partial streaming updates
```

## UserMessage

Represents messages from the user.

### Fields

```java
record UserMessage(
    String id,              // Message ID
    String role,            // Always "user"
    String content,         // Message content
    @Nullable String name   // Optional user name
) implements Message
```

### Example

```java
if (msg instanceof UserMessage user) {
    System.out.println("User said: " + user.content());
    System.out.println("Message ID: " + user.id());
}
```

## AssistantMessage

Represents messages from Claude.

### Fields

```java
record AssistantMessage(
    String id,                        // Message ID
    String role,                      // Always "assistant"
    List<ContentBlock> content,       // Content blocks
    @Nullable AssistantMessageError error,  // Error if any
    @Nullable String stopReason,      // Why stopped
    @Nullable Integer stopSequenceIndex
) implements Message
```

### Methods

#### getTextContent()

Extract all text from the message.

```java
AssistantMessage assistant = ...;
String text = assistant.getTextContent();
System.out.println(text);
```

Combines text from all `TextBlock` and `ThinkingBlock` content blocks.

### Example

```java
if (msg instanceof AssistantMessage assistant) {
    // Get all text
    String text = assistant.getTextContent();

    // Process content blocks
    for (ContentBlock block : assistant.content()) {
        switch (block) {
            case TextBlock text -> 
                System.out.println("Text: " + text.text());
            case ThinkingBlock thinking ->
                System.out.println("Thinking: " + thinking.thinking());
            case ToolUseBlock tool ->
                System.out.println("Used tool: " + tool.name());
            case ToolResultBlock result ->
                System.out.println("Tool result: " + result.content());
        }
    }

    // Check for errors
    if (assistant.error() != null) {
        System.err.println("Error: " + assistant.error());
    }
}
```

## SystemMessage

System-level notifications and events.

### Fields

```java
record SystemMessage(
    String subtype,           // Message subtype
    @Nullable Object data     // Optional data
) implements Message
```

### Common Subtypes

- `"connection_established"` - Connection ready
- `"session_started"` - Session initialized
- `"checkpoint_created"` - File checkpoint created
- `"model_changed"` - Model switched
- Custom system events

### Example

```java
if (msg instanceof SystemMessage system) {
    System.out.println("System: " + system.subtype());
    if (system.data() != null) {
        System.out.println("Data: " + system.data());
    }
}
```

## ResultMessage

Final result with cost and usage information.

### Fields

```java
record ResultMessage(
    @Nullable String stopReason,     // Why conversation stopped
    @Nullable Integer stopSequenceIndex,
    @Nullable Integer usageInput,    // Input tokens
    @Nullable Integer usageOutput,   // Output tokens
    @Nullable Double totalCostUsd,   // Total cost in USD
    @Nullable String errorMessage    // Error if any
) implements Message
```

### Stop Reasons

- `"end_turn"` - Natural completion
- `"max_turns"` - Hit maxTurns limit
- `"max_budget"` - Hit maxBudgetUsd limit
- `"tool_use"` - Ended on tool use
- `"interrupted"` - User interrupted
- `"error"` - Error occurred

### Example

```java
if (msg instanceof ResultMessage result) {
    System.out.println("Conversation complete!");
    System.out.println("Stop reason: " + result.stopReason());
    System.out.println("Input tokens: " + result.usageInput());
    System.out.println("Output tokens: " + result.usageOutput());
    System.out.println("Total cost: $" + result.totalCostUsd());

    if (result.errorMessage() != null) {
        System.err.println("Error: " + result.errorMessage());
    }
}
```

## StreamEvent

Partial message updates during streaming (requires `includePartialMessages`).

### Fields

```java
record StreamEvent(
    String eventType,         // Type of event
    @Nullable Object delta,   // Incremental update
    @Nullable Object data     // Full data
) implements Message
```

### Event Types

- `"content_block_start"` - New content block
- `"content_block_delta"` - Incremental content
- `"content_block_stop"` - Content block complete
- `"message_start"` - Message started
- `"message_delta"` - Message update
- `"message_stop"` - Message complete

### Example

```java
if (msg instanceof StreamEvent event) {
    System.out.println("Stream event: " + event.eventType());
    if (event.delta() != null) {
        System.out.println("Delta: " + event.delta());
    }
}
```

## Content Blocks

Assistant messages contain a list of content blocks.

### ContentBlock Hierarchy

```java
sealed interface ContentBlock permits TextBlock, ThinkingBlock,
    ToolUseBlock, ToolResultBlock {}
```

### TextBlock

Plain text content from Claude.

```java
record TextBlock(
    String text  // Text content
) implements ContentBlock
```

### ThinkingBlock

Claude's internal reasoning (when extended thinking enabled).

```java
record ThinkingBlock(
    String thinking  // Thinking content
) implements ContentBlock
```

### ToolUseBlock

Tool invocation by Claude.

```java
record ToolUseBlock(
    String id,              // Tool use ID
    String name,            // Tool name
    @Nullable Object input  // Tool input (usually Map)
) implements ContentBlock
```

### ToolResultBlock

Results from tool execution.

```java
record ToolResultBlock(
    String toolUseId,       // Corresponding tool use ID
    @Nullable Object content,  // Result content
    @Nullable Boolean isError  // Whether result is error
) implements ContentBlock
```

## Pattern Matching

Java's pattern matching makes message handling elegant and type-safe.

### Switch Expression

```java
String result = switch (message) {
    case UserMessage u -> "User: " + u.content();
    case AssistantMessage a -> "Claude: " + a.getTextContent();
    case SystemMessage s -> "System: " + s.subtype();
    case ResultMessage r -> "Cost: $" + r.totalCostUsd();
    case StreamEvent e -> "Streaming: " + e.eventType();
};
```

### Nested Pattern Matching

```java
switch (message) {
    case AssistantMessage assistant -> {
        for (ContentBlock block : assistant.content()) {
            switch (block) {
                case TextBlock text ->
                    System.out.println(text.text());
                case ThinkingBlock thinking ->
                    System.out.println("[Thinking] " + thinking.thinking());
                case ToolUseBlock tool ->
                    System.out.println("[Tool] " + tool.name() + ": " + tool.input());
                case ToolResultBlock result ->
                    System.out.println("[Result] " + result.content());
            }
        }
    }
    case ResultMessage result ->
        System.out.println("Done: $" + result.totalCostUsd());
    default -> {}
}
```

### Instance of with Pattern Variables

```java
if (message instanceof AssistantMessage assistant) {
    // 'assistant' variable available here
    for (ContentBlock block : assistant.content()) {
        if (block instanceof ToolUseBlock tool) {
            // 'tool' variable available here
            processToolUse(tool.name(), tool.input());
        }
    }
}
```

## Examples

### Example 1: Processing All Messages

```java
List<Message> messages = ClaudeSDK.query(prompt, options);

for (Message msg : messages) {
    switch (msg) {
        case UserMessage user ->
            log("User", user.content());

        case AssistantMessage assistant -> {
            log("Claude", assistant.getTextContent());
            for (ContentBlock block : assistant.content()) {
                if (block instanceof ToolUseBlock tool) {
                    log("Tool Used", tool.name());
                }
            }
        }

        case ResultMessage result -> {
            log("Result", "Cost: $" + result.totalCostUsd());
            log("Result", "Tokens: " + result.usageInput() + " in, " +
                         result.usageOutput() + " out");
        }

        case SystemMessage system ->
            log("System", system.subtype());

        case StreamEvent event ->
            log("Stream", event.eventType());
    }
}
```

### Example 2: Extracting Specific Information

```java
// Find last assistant message
Optional<AssistantMessage> lastAssistant = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .map(m -> (AssistantMessage) m)
    .reduce((first, second) -> second);

// Get all text content
String allText = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .map(m -> ((AssistantMessage) m).getTextContent())
    .collect(Collectors.joining("\n\n"));

// Get result
Optional<ResultMessage> result = messages.stream()
    .filter(m -> m instanceof ResultMessage)
    .map(m -> (ResultMessage) m)
    .findFirst();

// Get all tool uses
List<ToolUseBlock> toolUses = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .flatMap(m -> ((AssistantMessage) m).content().stream())
    .filter(b -> b instanceof ToolUseBlock)
    .map(b -> (ToolUseBlock) b)
    .toList();
```

### Example 3: Handling Errors

```java
for (Message msg : messages) {
    switch (msg) {
        case AssistantMessage assistant -> {
            if (assistant.error() != null) {
                System.err.println("Assistant error: " + assistant.error());
                handleAssistantError(assistant.error());
            }
        }

        case ResultMessage result -> {
            if (result.errorMessage() != null) {
                System.err.println("Result error: " + result.errorMessage());
                handleResultError(result);
            }
        }

        default -> {}
    }
}
```

### Example 4: Tool Use Tracking

```java
Map<String, Integer> toolUsage = new HashMap<>();

for (Message msg : messages) {
    if (msg instanceof AssistantMessage assistant) {
        for (ContentBlock block : assistant.content()) {
            if (block instanceof ToolUseBlock tool) {
                toolUsage.merge(tool.name(), 1, Integer::sum);
            }
        }
    }
}

System.out.println("Tool usage:");
toolUsage.forEach((tool, count) ->
    System.out.println(tool + ": " + count + " times"));
```

### Example 5: Cost Analysis

```java
double totalCost = 0.0;
int totalInputTokens = 0;
int totalOutputTokens = 0;

for (Message msg : messages) {
    if (msg instanceof ResultMessage result) {
        if (result.totalCostUsd() != null) {
            totalCost += result.totalCostUsd();
        }
        if (result.usageInput() != null) {
            totalInputTokens += result.usageInput();
        }
        if (result.usageOutput() != null) {
            totalOutputTokens += result.usageOutput();
        }
    }
}

System.out.println("Total cost: $" + totalCost);
System.out.println("Total tokens: " + (totalInputTokens + totalOutputTokens));
System.out.println("Average cost per token: $" + 
    (totalCost / (totalInputTokens + totalOutputTokens)));
```

## See Also

- [Simple Queries](./feature-simple-queries.md) - Processing messages from queries
- [Interactive Conversations](./feature-interactive-conversations.md) - Processing messages from client
- [Streaming Events](./feature-streaming-events.md) - StreamEvent details
- [API Reference: Message Types](./api-message-types.md) - Complete API documentation
