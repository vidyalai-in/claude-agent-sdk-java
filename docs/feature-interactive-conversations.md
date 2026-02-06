# Interactive Conversations

Interactive conversations enable multi-turn, stateful interactions with Claude using the `ClaudeSDKClient` class.

## Table of Contents
- [Overview](#overview)
- [When to Use ClaudeSDKClient](#when-to-use-claudesdkclient)
- [Basic Usage](#basic-usage)
- [Connection Management](#connection-management)
- [Sending Messages](#sending-messages)
- [Receiving Messages](#receiving-messages)
- [Control Methods](#control-methods)
- [Session Management](#session-management)
- [Thread Safety](#thread-safety)
- [Resource Management](#resource-management)
- [Examples](#examples)
- [Best Practices](#best-practices)

## Overview

`ClaudeSDKClient` provides full control over bidirectional conversations with Claude. Unlike the simple `ClaudeSDK.query()` facade, the client:

- **Maintains State**: Conversation context preserved across messages
- **Bidirectional**: Send and receive messages at any time
- **Interactive**: Send follow-ups based on responses
- **Controllable**: Interrupt, change model, adjust permissions dynamically
- **Session-aware**: Support for resuming and forking sessions

## When to Use ClaudeSDKClient

### ✅ Perfect For

1. **Chat Interfaces**
   ```java
   try (var client = ClaudeSDK.createClient()) {
       client.connect();
       while (userInput = getUserInput()) {
           client.sendMessage(userInput);
           for (var msg : client.receiveResponse()) {
               display(msg);
           }
       }
   }
   ```

2. **REPL-like Interfaces**
   ```java
   while (true) {
       String command = console.readLine();
       client.sendMessage(command);
       processResponse(client.receiveResponse());
   }
   ```

3. **Multi-turn Conversations**
   ```java
   client.sendMessage("What is Python?");
   // ... process response
   client.sendMessage("Show me a code example");
   // ... context preserved
   ```

4. **Interactive Debugging**
   ```java
   client.sendMessage("Analyze this error");
   var response = client.receiveResponse();
   if (needsMoreInfo) {
       client.sendMessage("Here's more context...");
   }
   ```

5. **Long-running Sessions**
   ```java
   try (var client = ClaudeSDK.createClient(options)) {
       client.connect();
       // Hours-long session with state
   }
   ```

### ❌ Not Ideal For

- Simple one-off questions → Use `ClaudeSDK.query()`
- Batch processing → Use `ClaudeSDK.query()`
- Fire-and-forget scripts → Use `ClaudeSDK.query()`

## Basic Usage

### Creating and Connecting

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

// Create with default options
ClaudeSDKClient client = ClaudeSDK.createClient();

// Or with custom options
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .maxTurns(20)
    .build();
ClaudeSDKClient client = ClaudeSDK.createClient(options);

// Connect (establishes subprocess)
client.connect();
```

### Simple Conversation

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect();

    // First message
    client.sendMessage("What is 2 + 2?");
    for (var msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }

    // Follow-up (context preserved)
    client.sendMessage("What about 3 + 3?");
    for (var msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### Connect with Initial Message

```java
// Connect and send initial message in one call
client.connect("Hello, Claude!");

for (var msg : client.receiveResponse()) {
    // Process initial response
}
```

## Connection Management

### connect()

Establishes connection to Claude Code CLI.

```java
client.connect();  // No initial message
client.connect("Initial prompt");  // With initial message
```

**Thread Safety**: Thread-safe and idempotent. Multiple concurrent calls are protected.

**Throws**:
- `IllegalStateException` - If called after `close()`
- `CLIConnectionException` - If connection fails

### isConnected()

Check if client is connected.

```java
if (client.isConnected()) {
    client.sendMessage("Hello");
}
```

### disconnect() / close()

Close the connection and cleanup resources.

```java
client.disconnect();  // Explicit disconnect
// or
client.close();  // AutoCloseable

// Best practice: use try-with-resources
try (var client = ClaudeSDK.createClient()) {
    // Use client
}  // Automatically closed
```

**Thread Safety**: Thread-safe and idempotent. Safe to call multiple times.

## Sending Messages

### sendMessage(String prompt)

Send a message and continue receiving.

```java
client.sendMessage("Hello, Claude!");
```

**Use when**: You want to send a message and continue listening for all events.

### sendMessage(String prompt, String sessionId)

Send a message to a specific session.

```java
client.sendMessage("Hello!", "session-1");
```

### query(String prompt)

Send a message and receive only the response (blocks until ResultMessage).

```java
List<Message> response = client.query("What is 2 + 2?");
```

**Use when**: You want a request/response pattern (send and wait for complete response).

### query(String prompt, String sessionId)

Query with specific session ID.

```java
List<Message> response = client.query("Question", "session-1");
```

### query(Iterator<Map<String, Object>> messageStream)

Send multiple messages as a stream.

```java
var messages = List.of(
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "First")),
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "Second"))
);

List<Message> responses = client.query(messages.iterator());
```

## Receiving Messages

### receiveMessages()

Returns an iterator over ALL messages (continuous stream).

```java
Iterator<Message> messages = client.receiveMessages();

while (messages.hasNext()) {
    Message msg = messages.next();
    // Process each message as it arrives

    if (shouldStop(msg)) {
        break;
    }
}
```

**Use when**:
- You want to process messages continuously
- You're handling multiple sessions
- You need to see all events including system messages

**Characteristics**:
- Iterator blocks until message available
- Returns messages until stream ends
- Multiple iterators share the same queue (messages distributed)

### receiveResponse()

Returns an iterator that stops at the next ResultMessage.

```java
Iterable<Message> response = client.receiveResponse();

for (Message msg : response) {
    // Process messages until ResultMessage
}
// Iterator auto-closes when ResultMessage received
```

**Use when**:
- You want request/response pattern
- You're waiting for a specific query to complete
- You want automatic stopping at ResultMessage

**Characteristics**:
- Blocks until messages available
- Stops and auto-closes at ResultMessage
- Returns all messages for one complete response

### Processing Messages

```java
for (Message msg : client.receiveResponse()) {
    switch (msg) {
        case UserMessage user ->
            System.out.println("User: " + user.content());

        case AssistantMessage assistant -> {
            System.out.println("Claude: " + assistant.getTextContent());

            // Process tool uses
            for (ContentBlock block : assistant.content()) {
                if (block instanceof ToolUseBlock tool) {
                    System.out.println("Tool: " + tool.name());
                }
            }
        }

        case ResultMessage result -> {
            System.out.println("Done! Cost: $" + result.totalCostUsd());
            System.out.println("Stop reason: " + result.stopReason());
        }

        case SystemMessage system ->
            System.out.println("System: " + system.subtype());

        case StreamEvent event ->
            System.out.println("Partial: " + event.delta());
    }
}
```

## Control Methods

### interrupt()

Interrupt the current execution.

```java
// In another thread
client.interrupt();
```

**Use cases**:
- User cancels operation
- Timeout reached
- Stop expensive operation

### setModel(String model)

Change the AI model mid-conversation.

```java
client.setModel("claude-opus-4-6");
```

**Use cases**:
- Switch to more powerful model for complex tasks
- Switch to cheaper model for simple questions

### setPermissionMode(PermissionMode mode)

Change permission mode during conversation.

```java
client.setPermissionMode(PermissionMode.ACCEPT_EDITS);
```

**Available modes**:
- `ACCEPT_ALL` - Auto-accept all permissions
- `ACCEPT_EDITS` - Auto-accept edits, prompt for others
- `BYPASS_PERMISSIONS` - Skip all permission checks
- `PROMPT` - Prompt for all permissions (default)

### rewindFiles(String userMessageId)

Rewind files to their state at a specific user message (requires checkpointing).

```java
// Enable checkpointing in options
var options = ClaudeAgentOptions.builder()
    .checkpointFiles(true)
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();

    client.sendMessage("Create file.txt");
    for (var msg : client.receiveResponse()) {
        if (msg instanceof UserMessage user) {
            String messageId = user.id();
            // Save ID for later
        }
    }

    // Later: rewind to that message
    client.rewindFiles(messageId);
}
```

### getMcpStatus()

Get status of MCP server connections.

```java
Map<String, Object> status = client.getMcpStatus();
System.out.println("MCP servers: " + status);
```

### getServerInfo()

Get server initialization info.

```java
Map<String, Object> info = client.getServerInfo();
System.out.println("CLI version: " + info.get("version"));
```

## Session Management

### Default Session

By default, all messages use the "default" session.

```java
client.sendMessage("Hello");  // Uses "default" session
```

### Multiple Sessions

Send messages to different sessions.

```java
// Session 1
client.sendMessage("Analyze code.java", "session-1");

// Session 2
client.sendMessage("Write tests", "session-2");

// Receive from all sessions
for (var msg : client.receiveMessages()) {
    // Process messages from any session
}
```

### Resume Previous Session

```java
// First conversation
var options1 = ClaudeAgentOptions.builder()
    .build();

try (var client = ClaudeSDK.createClient(options1)) {
    client.connect("What is Java?");
    // ... conversation
}

// Later: resume with context
var options2 = ClaudeAgentOptions.builder()
    .resume("previous-session-id")
    .build();

try (var client = ClaudeSDK.createClient(options2)) {
    client.connect("Tell me more");
    // Has context from previous session
}
```

### Fork Session

Fork creates a new session from an existing one.

```java
var options = ClaudeAgentOptions.builder()
    .resume("previous-session-id")
    .forkSession(true)  // Fork instead of continue
    .build();
```

**Difference**:
- `resume(id)` - Continues the same session
- `resume(id) + forkSession(true)` - Creates new session with same context

## Thread Safety

`ClaudeSDKClient` is **partially thread-safe**:

### Thread-Safe Operations

```java
// ✅ Safe: Multiple threads can send
Thread t1 = Thread.startVirtualThread(() ->
    client.sendMessage("Query 1"));
Thread t2 = Thread.startVirtualThread(() ->
    client.sendMessage("Query 2"));

// ✅ Safe: Control methods
client.interrupt();
client.setModel("claude-sonnet-4-5");
client.setPermissionMode(PermissionMode.ACCEPT_ALL);

// ✅ Safe: connect() is synchronized
client.connect();  // Only one connection established
```

### Shared State

```java
// ⚠️ Warning: Multiple iterators share the queue
Iterator<Message> iter1 = client.receiveMessages();
Iterator<Message> iter2 = client.receiveMessages();

// Messages distributed across both iterators!
// Typically use only one iterator per client
```

### Best Practice

```java
// ✅ Good: One receive loop per client
try (var client = ClaudeSDK.createClient()) {
    client.connect();

    // Dedicated receive thread
    Thread.ofVirtual().start(() -> {
        for (var msg : client.receiveMessages()) {
            processMessage(msg);
        }
    });

    // Main thread sends
    client.sendMessage("Question 1");
    client.sendMessage("Question 2");
}
```

## Resource Management

### AutoCloseable

Always use try-with-resources:

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    client.connect();
    // Use client
}  // Automatically cleaned up
```

### Manual Cleanup

If not using try-with-resources:

```java
ClaudeSDKClient client = ClaudeSDK.createClient();
try {
    client.connect();
    // Use client
} finally {
    client.close();  // Important!
}
```

### Resources Cleaned Up

On `close()`, the client cleans up:
- QueryHandler and thread pools
- Streaming executor
- Transport and CLI subprocess
- Message queues and iterators

## Examples

### Example 1: Interactive Chat

```java
import java.util.Scanner;

public class Chat {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .model("claude-sonnet-4-5")
            .build();

        try (var client = ClaudeSDK.createClient(options);
             var scanner = new Scanner(System.in)) {

            client.connect();
            System.out.println("Chat started! Type 'exit' to quit.");

            while (true) {
                System.out.print("\nYou: ");
                String input = scanner.nextLine();

                if ("exit".equalsIgnoreCase(input)) {
                    break;
                }

                client.sendMessage(input);

                System.out.print("Claude: ");
                for (var msg : client.receiveResponse()) {
                    if (msg instanceof AssistantMessage assistant) {
                        System.out.print(assistant.getTextContent());
                    }
                }
                System.out.println();
            }
        }
    }
}
```

### Example 2: Interrupt Long Operation

```java
import java.util.concurrent.TimeUnit;

public class InterruptExample {
    public static void main(String[] args) {
        try (var client = ClaudeSDK.createClient()) {
            client.connect();

            // Start long operation in background
            Thread.ofVirtual().start(() -> {
                client.sendMessage("Analyze all files in this large codebase");
                for (var msg : client.receiveResponse()) {
                    System.out.println(msg);
                }
            });

            // Wait 5 seconds then interrupt
            TimeUnit.SECONDS.sleep(5);
            System.out.println("Interrupting...");
            client.interrupt();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### Example 3: Dynamic Model Switching

```java
public class ModelSwitching {
    public static void main(String[] args) {
        try (var client = ClaudeSDK.createClient()) {
            client.connect();

            // Simple question with Haiku
            client.setModel("claude-haiku-4-5");
            client.sendMessage("What is 2+2?");
            processResponse(client.receiveResponse());

            // Complex question with Opus
            client.setModel("claude-opus-4-6");
            client.sendMessage("Explain quantum entanglement");
            processResponse(client.receiveResponse());

            // Back to Sonnet for balanced tasks
            client.setModel("claude-sonnet-4-5");
            client.sendMessage("Write a Java function");
            processResponse(client.receiveResponse());
        }
    }
}
```

### Example 4: Multi-session Management

```java
public class MultiSession {
    public static void main(String[] args) {
        try (var client = ClaudeSDK.createClient()) {
            client.connect();

            // Start multiple tasks in different sessions
            client.sendMessage("Review code.java for bugs", "review");
            client.sendMessage("Write tests for util.java", "testing");
            client.sendMessage("Document api.java", "docs");

            // Process responses from all sessions
            for (var msg : client.receiveMessages()) {
                switch (msg) {
                    case AssistantMessage a ->
                        System.out.println("[Session] " + a.getTextContent());
                    case ResultMessage r ->
                        System.out.println("[Done] Cost: $" + r.totalCostUsd());
                    default -> {}
                }

                // Stop when all three sessions complete
                if (allSessionsComplete()) {
                    break;
                }
            }
        }
    }
}
```

### Example 5: File Checkpointing

```java
public class Checkpointing {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .checkpointFiles(true)
            .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();

            String checkpointId = null;

            // Create a file and save checkpoint
            client.sendMessage("Create test.txt with 'Hello'");
            for (var msg : client.receiveResponse()) {
                if (msg instanceof UserMessage user) {
                    checkpointId = user.id();
                }
            }

            // Modify the file
            client.sendMessage("Append 'World' to test.txt");
            for (var msg : client.receiveResponse()) {}

            // Rewind to original state
            if (checkpointId != null) {
                client.rewindFiles(checkpointId);
                System.out.println("Rewound to checkpoint");
            }
        }
    }
}
```

## Best Practices

### 1. Use Try-with-Resources

```java
// ✅ Good
try (var client = ClaudeSDK.createClient()) {
    client.connect();
}

// ❌ Bad: Resource leak
var client = ClaudeSDK.createClient();
client.connect();
// Forgot to close!
```

### 2. Handle Connection Errors

```java
// ✅ Good
try (var client = ClaudeSDK.createClient()) {
    try {
        client.connect();
    } catch (CLIConnectionException e) {
        System.err.println("Failed to connect: " + e.getMessage());
        return;
    }
    // Use client
}
```

### 3. Use receiveResponse() for Request/Response

```java
// ✅ Good: Clean request/response
client.sendMessage("Question");
for (var msg : client.receiveResponse()) {
    // Processes until ResultMessage
}

// ❌ Bad: Manual ResultMessage checking
for (var msg : client.receiveMessages()) {
    if (msg instanceof ResultMessage) break;
}
```

### 4. Don't Create Multiple Receive Iterators

```java
// ✅ Good: Single iterator
Iterator<Message> messages = client.receiveMessages();

// ❌ Bad: Messages split across iterators
Iterator<Message> iter1 = client.receiveMessages();
Iterator<Message> iter2 = client.receiveMessages();
```

### 5. Set Appropriate Limits

```java
// ✅ Good: Configure limits
var options = ClaudeAgentOptions.builder()
    .maxTurns(50)  // Long conversation
    .maxBudgetUsd(5.0)
    .build();

// ❌ Bad: No limits in interactive session
var client = ClaudeSDK.createClient();  // Could be expensive!
```

### 6. Handle All Message Types

```java
// ✅ Good: Exhaustive pattern matching
for (var msg : client.receiveResponse()) {
    switch (msg) {
        case UserMessage u -> handleUser(u);
        case AssistantMessage a -> handleAssistant(a);
        case ResultMessage r -> handleResult(r);
        case SystemMessage s -> handleSystem(s);
        case StreamEvent e -> handleStream(e);
    }
}
```

### 7. Use Control Methods Appropriately

```java
// ✅ Good: Switch models based on task complexity
if (isComplexTask) {
    client.setModel("claude-opus-4-6");
}

client.sendMessage(task);

// ✅ Good: Interrupt on timeout
CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS)
    .execute(() -> client.interrupt());
```

## See Also

- [Simple Queries](./feature-simple-queries.md) - For one-shot queries
- [Configuration Options](./feature-configuration-options.md) - All ClaudeAgentOptions
- [Message Types](./feature-message-types.md) - Understanding messages
- [ClaudeSDKClient API Reference](./api-claude-sdk-client.md) - Complete API docs
