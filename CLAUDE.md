# Workflow

```bash
# Compile source code
mvn compile

# Compile tests
mvn test-compile

# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=IntegrationTest

# Run specific test method
mvn test -Dtest=IntegrationTest#testQuerySinglePrompt

# Build JAR (skip tests)
mvn package -DskipTests

# Clean build
mvn clean

# Generate Javadoc
mvn javadoc:javadoc
```

# Running Examples

Examples are located in the `usage/examples/` directory and are compiled as part of the main build (but excluded from the SDK JAR).

```bash
# Build project and prepare dependencies
mvn clean package -DskipTests

# Method 1: Run example using Maven exec plugin (must specify -Dexec.mainClass)
mvn exec:java -Dexec.mainClass="examples.QuickStart"

# Run different examples (sorted alphabetically)
mvn exec:java -Dexec.mainClass="examples.AdvancedFeatures"
mvn exec:java -Dexec.mainClass="examples.AutoSchemaGeneration"
mvn exec:java -Dexec.mainClass="examples.ErrorHandling"
mvn exec:java -Dexec.mainClass="examples.Hooks"
mvn exec:java -Dexec.mainClass="examples.MaxBudgetExample"
mvn exec:java -Dexec.mainClass="examples.McpServer"
mvn exec:java -Dexec.mainClass="examples.MultiTurnConversation"
mvn exec:java -Dexec.mainClass="examples.PermissionCallbacks"
mvn exec:java -Dexec.mainClass="examples.PluginsExample"
mvn exec:java -Dexec.mainClass="examples.QuickStart"
mvn exec:java -Dexec.mainClass="examples.SettingSourcesExample" -Dexec.args="all"
mvn exec:java -Dexec.mainClass="examples.StderrCallbackExample"
mvn exec:java -Dexec.mainClass="examples.StreamingEvents"
mvn exec:java -Dexec.mainClass="examples.ToolsConfigurationExample"
mvn exec:java -Dexec.mainClass="examples.ToolUsage"

# Method 2: Run example using java -cp
java -cp target/classes:target/dependency/* examples.QuickStart
java -cp target/classes:target/dependency/* examples.MultiTurnConversation

# Note: Must run 'mvn package' first to copy dependencies to target/dependency/
```

# Codebase Structure

```
src/main/java/in/vidyalai/claude/sdk/
├── ClaudeSDK.java              # Main facade with static helper methods
├── ClaudeSDKClient.java        # Interactive client for bidirectional conversations
├── ClaudeAgentOptions.java     # Configuration options (builder pattern)
├── exceptions/                 # Exception types
│   ├── ClaudeSDKException.java      # Base exception
│   ├── CLIConnectionException.java  # Connection errors
│   ├── CLINotFoundException.java    # CLI not found
│   ├── ProcessException.java        # Process failures
│   ├── CLIJSONDecodeException.java  # JSON parsing errors
│   └── MessageParseException.java   # Message parsing errors
├── transport/                  # Transport layer
│   └── Transport.java              # Transport interface
├── internal/                   # Internal implementation
│   ├── QueryHandler.java           # Control protocol handler
│   ├── MessageParser.java          # Message parsing logic
│   └── transport/                  # Internal transport implementations
│       └── SubprocessCLITransport.java # CLI subprocess implementation
├── mcp/                        # MCP (Model Context Protocol) support
│   ├── SdkMcpServer.java           # In-process MCP server
│   ├── SdkMcpTool.java             # Tool definition
│   ├── Tool.java                   # @Tool annotation
│   └── ToolResult.java             # Tool result wrapper
└── types/                      # Type definitions
    ├── Message.java                # Sealed interface for messages
    ├── UserMessage.java            # User message record
    ├── AssistantMessage.java       # Assistant message record
    ├── SystemMessage.java          # System message record
    ├── ResultMessage.java          # Result message record
    ├── StreamEvent.java            # Stream event record
    ├── ContentBlock.java           # Sealed interface for content
    ├── TextBlock.java              # Text content record
    ├── ThinkingBlock.java          # Thinking content record
    ├── ToolUseBlock.java           # Tool use record
    ├── ToolResultBlock.java        # Tool result record
    ├── PermissionMode.java         # Permission mode enum
    ├── PermissionResult*.java      # Permission callback results
    ├── HookEvent.java              # Hook event enum
    ├── Hook*.java                  # Hook-related types
    ├── McpServerConfig.java        # MCP server configs
    └── ...                         # Other type definitions
```

# Key Classes

## ClaudeSDK (Facade)
Static methods for common operations:
- `query(String prompt)` - One-shot query
- `query(String prompt, ClaudeAgentOptions options)` - Query with options
- `query(Iterator<Map> stream)` - Streaming query
- `query(Iterator<Map> stream, ClaudeAgentOptions options)` - Streaming query with options
- `queryForText(String prompt, ClaudeAgentOptions options)` - Get text response
- `queryForResult(String prompt, ClaudeAgentOptions options)` - Get result message
- `createClient()` - Create new ClaudeSDKClient
- `createClient(ClaudeAgentOptions options)` - Create client with options
- `createSdkMcpServer(String name, List<SdkMcpTool> tools)` - Create MCP server from tools
- `createSdkMcpServer(String name, String version, List<SdkMcpTool> tools)` - Create MCP server with version
- `createSdkMcpServer(String name, Object instance)` - Create MCP server from @Tool annotations
- `getVersion()` - Get SDK version

## ClaudeSDKClient
Interactive client methods:
- `connect()` - Start conversation
- `connect(String prompt)` - Start conversation with initial prompt
- `sendMessage(String prompt)` - Send follow-up message
- `sendMessage(String prompt, String sessionId)` - Send message with session ID
- `query(String prompt)` - Send query message
- `query(String prompt, String sessionId)` - Send query with session ID
- `query(Iterator<Map> stream)` - Send streaming messages
- `receiveMessages()` - Get all messages iterator
- `receiveResponse()` - Get messages until ResultMessage
- `getMcpStatus()` - Get MCP server connection status
- `interrupt()` - Interrupt execution
- `setModel(String model)` - Change model
- `setPermissionMode(PermissionMode mode)` - Change permission mode
- `rewindFiles(String userMessageId)` - Rewind to checkpoint
- `getServerInfo()` - Get server initialization info
- `isConnected()` - Check if client is connected
- `disconnect()` / `close()` - Close connection

## ClaudeAgentOptions (Builder)
Configuration with builder pattern:
```java
ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .systemPrompt("...")
    .allowedTools(List.of("Read", "Write"))
    .permissionMode(PermissionMode.ACCEPT_EDITS)
    .maxTurns(10)
    .cwd(Path.of("/project"))
    .build();
```

# Test Structure

```
src/test/java/in/vidyalai/claude/sdk/
├── IntegrationTest.java           # End-to-end tests
├── StreamingClientTest.java       # ClaudeSDKClient tests
├── ClaudeAgentOptionsTest.java    # Options builder tests
├── callbacks/
│   └── CallbacksTest.java         # Permission/hook callback tests
├── exceptions/
│   └── ExceptionsTest.java        # Exception type tests
├── mcp/
│   └── SdkMcpTest.java            # MCP server tests
├── transport/
│   ├── SubprocessCLITransportTest.java  # Transport tests
│   └── SubprocessBufferingTest.java     # Buffer handling tests
└── types/
    ├── MessageParserTest.java     # Message parsing tests
    ├── TypesTest.java             # Type creation tests
    └── AdditionalTypesTest.java   # Additional type tests
```

# Design Patterns

## Sealed Interfaces (Pattern Matching)
```java
// Message types
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, ResultMessage, StreamEvent {}

// Usage with pattern matching
switch (message) {
    case UserMessage u -> handleUser(u);
    case AssistantMessage a -> handleAssistant(a);
    // ...
}
```

## Builder Pattern (Options)
```java
ClaudeAgentOptions.builder()
    .option1(value1)
    .option2(value2)
    .build();

// Modify existing options
options.toBuilder()
    .modifiedOption(newValue)
    .build();
```

## Virtual Threads (Concurrency)
```java
Thread.startVirtualThread(() -> {
    // Background work
});
```

## CompletableFuture (Async Callbacks)
```java
(toolName, input, context) ->
    CompletableFuture.completedFuture(new PermissionResultAllow());
```

# Dependencies

- Jackson (JSON processing)
- JSpecify (Nullability annotations)
- JUnit 5 (Testing)
- AssertJ (Test assertions)
