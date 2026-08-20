# Simple Queries

Simple queries provide a straightforward way to interact with Claude for one-shot, stateless operations using the `ClaudeSDK` facade.

## Table of Contents
- [Overview](#overview)
- [When to Use Simple Queries](#when-to-use-simple-queries)
- [Basic Usage](#basic-usage)
- [Query Methods](#query-methods)
- [Configuration Options](#configuration-options)
- [Message Handling](#message-handling)
- [Examples](#examples)
- [Best Practices](#best-practices)

## Overview

The `ClaudeSDK` class provides static methods for simple, stateless queries. It handles all the complexity of:
- Transport creation and management
- QueryHandler setup
- Message parsing
- Resource cleanup

**Key Characteristics:**
- **Unidirectional**: Send all messages upfront, receive all responses
- **Stateless**: Each query is independent
- **Simple**: Fire-and-forget style
- **No interrupts**: Cannot interrupt or send follow-up messages
- **Automatic cleanup**: Resources are managed internally

## When to Use Simple Queries

### ✅ Good Use Cases

1. **One-off Questions**
   ```java
   ClaudeSDK.query("What is the capital of France?");
   ```

2. **Batch Processing**
   ```java
   for (String prompt : prompts) {
       List<Message> result = ClaudeSDK.query(prompt, options);
       processResult(result);
   }
   ```

3. **Code Generation**
   ```java
   String code = ClaudeSDK.queryForText(
       "Generate a Java function to reverse a string",
       options);
   ```

4. **CI/CD Pipelines**
   ```java
   String review = ClaudeSDK.queryForText(
       "Review this code for security issues: " + code,
       options);
   ```

5. **Automated Scripts**
   ```java
   ResultMessage result = ClaudeSDK.queryForResult(
       "Analyze this log file",
       options);
   System.out.println("Cost: $" + result.totalCostUsd());
   ```

### ❌ Not Suitable For

1. **Interactive Conversations** - Use `ClaudeSDKClient` instead
2. **Chat Interfaces** - Use `ClaudeSDKClient` for multi-turn
3. **Follow-up Questions** - Use `ClaudeSDKClient` for context
4. **Interrupt Capability** - Use `ClaudeSDKClient` for control
5. **Long-running Sessions** - Use `ClaudeSDKClient` for state

## Basic Usage

### Simplest Query (Default Options)

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.Message;

List<Message> messages = ClaudeSDK.query("What is 2 + 2?");
```

### Query with Options

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .maxTurns(1)
    .build();

List<Message> messages = ClaudeSDK.query("What is 2 + 2?", options);
```

### Get Just the Text

```java
String answer = ClaudeSDK.queryForText(
    "What is 2 + 2?",
    ClaudeAgentOptions.defaults()
);
System.out.println(answer); // "4"
```

### Get the Result Message

```java
ResultMessage result = ClaudeSDK.queryForResult(
    "What is 2 + 2?",
    ClaudeAgentOptions.defaults()
);
System.out.println("Cost: $" + result.totalCostUsd());
System.out.println("Stop reason: " + result.stopReason());
```

## Query Methods

### 1. query(String prompt)

Executes a query with default options.

```java
List<Message> messages = ClaudeSDK.query("Hello, Claude!");
```

**Returns**: `List<Message>` - All messages from the conversation

### 2. query(String prompt, ClaudeAgentOptions options)

Executes a query with custom options.

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .maxTurns(1)
    .build();

List<Message> messages = ClaudeSDK.query("Hello!", options);
```

**Parameters**:
- `prompt` - The question or instruction
- `options` - Configuration options

**Returns**: `List<Message>` - All messages from the conversation

**Throws**:
- `IllegalArgumentException` - If both `canUseTool` and `permissionPromptToolName` are set
- `CLIConnectionException` - If connection fails
- `ProcessException` - If CLI process fails

### 3. query(Iterator<Map<String, Object>> messageStream, ClaudeAgentOptions options)

Executes a streaming query with multiple messages.

```java
var messages = List.of(
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "First message")),
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "Follow-up"))
);

List<Message> responses = ClaudeSDK.query(messages.iterator(), options);
```

**Parameters**:
- `messageStream` - Iterator of message dictionaries
- `options` - Configuration options

**Returns**: `List<Message>` - All messages from the conversation

**Message Format**:
```java
{
    "type": "user",
    "session_id": "default",
    "message": {
        "role": "user",
        "content": "Your message here"
    }
}
```

### 4. queryForText(String prompt, ClaudeAgentOptions options)

Convenience method that returns only the text content from assistant messages.

```java
String text = ClaudeSDK.queryForText(
    "What is the capital of France?",
    ClaudeAgentOptions.defaults()
);
```

**Returns**: `String` - Combined text content from all assistant messages

### 5. queryForResult(String prompt, ClaudeAgentOptions options)

Convenience method that returns only the result message.

```java
ResultMessage result = ClaudeSDK.queryForResult(
    "Analyze this code",
    options
);

System.out.println("Cost: $" + result.totalCostUsd());
System.out.println("Input tokens: " + result.usageInput());
System.out.println("Output tokens: " + result.usageOutput());
```

**Returns**: `ResultMessage` - The final result, or null if not found

## Configuration Options

### Essential Options for Simple Queries

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    // Model selection
    .model("claude-sonnet-4-5")
    .fallbackModel("claude-haiku-4-5")

    // Limits
    .maxTurns(5)
    .maxBudgetUsd(1.0)
    .maxThinkingTokens(10000)

    // System prompt
    .systemPrompt("You are a helpful assistant. Be concise.")

    // Tools
    .allowedTools(List.of("Read", "Grep"))
    .disallowedTools(List.of("Write", "Edit"))

    // Permissions
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)

    // Working directory
    .cwd(Path.of("/path/to/project"))

    // Environment
    .env(Map.of("KEY", "value"))

    .build();
```

### Common Patterns

#### Quick Read-Only Query
```java
var options = ClaudeAgentOptions.builder()
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .allowedTools(List.of("Read", "Grep", "Glob"))
    .disallowedTools(List.of("Write", "Edit", "Bash"))
    .maxTurns(5)
    .build();
```

#### Budget-Constrained Query
```java
var options = ClaudeAgentOptions.builder()
    .maxBudgetUsd(0.10)  // Limit to 10 cents
    .maxTurns(3)
    .model("claude-haiku-4-5")  // Use cheaper model
    .build();
```

#### Fast Single-Turn Query
```java
var options = ClaudeAgentOptions.builder()
    .maxTurns(1)
    .model("claude-haiku-4-5")
    .systemPrompt("Be extremely concise.")
    .build();
```

## Message Handling

### Processing All Messages

```java
List<Message> messages = ClaudeSDK.query("Hello!", options);

for (Message msg : messages) {
    switch (msg) {
        case UserMessage user ->
            System.out.println("User: " + user.content());

        case AssistantMessage assistant -> {
            System.out.println("Claude: " + assistant.getTextContent());
            // Process tool uses
            for (ContentBlock block : assistant.content()) {
                if (block instanceof ToolUseBlock tool) {
                    System.out.println("Used tool: " + tool.name());
                }
            }
        }

        case ResultMessage result ->
            System.out.println("Cost: $" + result.totalCostUsd());

        case SystemMessage system ->
            System.out.println("System: " + system.subtype());

        case StreamEvent event ->
            // Usually not present in simple queries
            System.out.println("Stream event: " + event);
    }
}
```

### Extracting Specific Information

```java
List<Message> messages = ClaudeSDK.query(prompt, options);

// Get last assistant message
AssistantMessage lastAssistant = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .map(m -> (AssistantMessage) m)
    .reduce((first, second) -> second)
    .orElse(null);

// Get all text content
String allText = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .map(m -> ((AssistantMessage) m).getTextContent())
    .collect(Collectors.joining("\n"));

// Get result
ResultMessage result = messages.stream()
    .filter(m -> m instanceof ResultMessage)
    .map(m -> (ResultMessage) m)
    .findFirst()
    .orElse(null);
```

## Examples

### Example 1: Code Review

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

public class CodeReview {
    public static void main(String[] args) {
        String code = """
            public void processUser(User user) {
                db.save(user);  // No null check!
            }
            """;

        var options = ClaudeAgentOptions.builder()
            .systemPrompt("You are a code reviewer. Focus on bugs and security.")
            .maxTurns(1)
            .build();

        String review = ClaudeSDK.queryForText(
            "Review this code for issues:\n" + code,
            options
        );

        System.out.println(review);
    }
}
```

### Example 2: Batch Translation

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

public class Translator {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .model("claude-haiku-4-5")  // Fast and cheap
            .maxTurns(1)
            .systemPrompt("Translate to French. Return only the translation.")
            .build();

        List<String> phrases = List.of(
            "Hello, how are you?",
            "The weather is nice today.",
            "I love programming."
        );

        for (String phrase : phrases) {
            String translation = ClaudeSDK.queryForText(phrase, options);
            System.out.println(phrase + " -> " + translation);
        }
    }
}
```

### Example 3: Log Analysis

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import java.nio.file.Path;

public class LogAnalyzer {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .cwd(Path.of("/path/to/logs"))
            .allowedTools(List.of("Read", "Grep"))
            .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
            .maxTurns(10)
            .build();

        String analysis = ClaudeSDK.queryForText(
            "Analyze error.log and summarize all ERROR level messages",
            options
        );

        System.out.println(analysis);
    }
}
```

### Example 4: Cost-Aware Query

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.message.ResultMessage;

public class CostAwareQuery {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .maxBudgetUsd(0.05)  // 5 cent limit
            .build();

        ResultMessage result = ClaudeSDK.queryForResult(
            "Explain quantum computing",
            options
        );

        if (result != null) {
            System.out.println("Cost: $" + result.totalCostUsd());
            System.out.println("Input tokens: " + result.usageInput());
            System.out.println("Output tokens: " + result.usageOutput());
            System.out.println("Stop reason: " + result.stopReason());
        }
    }
}
```

## Best Practices

### 1. Use Appropriate Options

```java
// ✅ Good: Configure limits
var options = ClaudeAgentOptions.builder()
    .maxTurns(5)
    .maxBudgetUsd(1.0)
    .build();

// ❌ Bad: No limits
ClaudeSDK.query(longComplexTask);  // Could be expensive!
```

### 2. Handle All Message Types

```java
// ✅ Good: Pattern matching handles all types
switch (message) {
    case AssistantMessage a -> process(a);
    case ResultMessage r -> logCost(r);
    case UserMessage u -> log(u);
    case SystemMessage s -> log(s);
    case StreamEvent e -> log(e);
}

// ❌ Bad: Only handling one type
if (message instanceof AssistantMessage) {
    // Missing other types!
}
```

### 3. Use Convenience Methods When Appropriate

```java
// ✅ Good: Simple use case
String answer = ClaudeSDK.queryForText(prompt, options);

// ❌ Overkill: Manual extraction
List<Message> messages = ClaudeSDK.query(prompt, options);
String answer = messages.stream()...  // Complex extraction
```

### 4. Set Working Directory for File Operations

```java
// ✅ Good: Explicit working directory
var options = ClaudeAgentOptions.builder()
    .cwd(Path.of("/project/root"))
    .allowedTools(List.of("Read", "Write"))
    .build();

// ❌ Bad: Using current directory (unpredictable)
ClaudeSDK.query("Read config.json", options);
```

### 5. Choose the Right Model

```java
// ✅ Good: Match model to task
var fastOptions = ClaudeAgentOptions.builder()
    .model("claude-haiku-4-5")  // Quick, simple tasks
    .build();

var complexOptions = ClaudeAgentOptions.builder()
    .model("claude-opus-4-6")  // Complex reasoning
    .build();

// ❌ Bad: Using opus for simple tasks (expensive)
ClaudeAgentOptions.builder()
    .model("claude-opus-4-6")
    .build();
ClaudeSDK.query("What is 2+2?", options);  // Overkill!
```

### 6. Use Streaming for Multi-Turn Scenarios

```java
// ✅ Good: Streaming for multiple messages
var messages = List.of(
    Map.of("type", "user", ...),
    Map.of("type", "user", ...)
);
ClaudeSDK.query(messages.iterator(), options);

// ❌ Bad: Multiple separate queries (loses context)
ClaudeSDK.query("First question", options);
ClaudeSDK.query("Follow-up", options);  // No context!
```

### 7. Handle Errors

```java
// ✅ Good: Handle exceptions
try {
    List<Message> messages = ClaudeSDK.query(prompt, options);
    // Process messages
} catch (CLIConnectionException e) {
    System.err.println("Failed to connect: " + e.getMessage());
} catch (ProcessException e) {
    System.err.println("CLI process failed: " + e.getMessage());
}

// ❌ Bad: No error handling
ClaudeSDK.query(prompt, options);  // Could throw!
```

## See Also

- [Interactive Conversations](./feature-interactive-conversations.md) - For multi-turn conversations
- [Configuration Options](./feature-configuration-options.md) - Complete options guide
- [Message Types](./feature-message-types.md) - Understanding messages
- [ClaudeSDK API Reference](./api-claude-sdk.md) - Detailed API documentation
