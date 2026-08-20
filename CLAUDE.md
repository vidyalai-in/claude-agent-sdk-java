# Multi-Module Project Structure

This is a multi-module Maven project with two modules:
- `sdk/` - The Claude Agent SDK library (published to Maven Central, mirrored to GitHub Packages)
- `examples/` - Usage examples (depends on published SDK)

## CI-Friendly Versioning

The project uses Maven's CI-friendly version feature with the `${revision}` property. This means:
- **Version is defined once** in the parent `pom.xml` as `<revision>0.1.1-SNAPSHOT</revision>`
- All modules inherit this version automatically
- To change the version, edit only one place: the `<revision>` property in the root `pom.xml`
- The `flatten-maven-plugin` resolves `${revision}` before deployment

# Workflow

## Building the Project

```bash
# Build all modules from root directory
mvn clean install

# Build only SDK module
mvn clean install -pl sdk

# Build only examples module
mvn clean install -pl examples

# Build without tests
mvn clean install -DskipTests

# Run all tests (SDK module)
mvn test -pl sdk

# Run specific test class
mvn test -Dtest=IntegrationTest -pl sdk

# Run specific test method
mvn test -Dtest=IntegrationTest#testQuerySinglePrompt -pl sdk

# Generate Javadoc for SDK
mvn javadoc:javadoc -pl sdk
```

## SDK Module Commands

```bash
# Navigate to SDK directory
cd sdk

# Compile source code
mvn compile

# Compile tests
mvn test-compile

# Run all tests
mvn test

# Build JAR (skip tests)
mvn package -DskipTests

# Install locally for examples development
mvn install -DskipTests

# Clean build
mvn clean

# Generate Javadoc
mvn javadoc:javadoc
```

# Running Examples

Examples are located in the `examples/` module, a separate Maven module that depends on the SDK. Building from the repository root satisfies that dependency from the reactor; building `examples/` standalone resolves it from Maven Central, which needs no repository or authentication setup.

## From Root Directory (Recommended)

```bash
# Build all modules and run an example
mvn clean package -DskipTests
mvn exec:java -Dexec.mainClass="examples.QuickStart" -pl examples

# Run different examples (sorted alphabetically)
mvn exec:java -Dexec.mainClass="examples.AdvancedFeatures" -pl examples
mvn exec:java -Dexec.mainClass="examples.AgentsExample" -pl examples
mvn exec:java -Dexec.mainClass="examples.AutoSchemaGeneration" -pl examples
mvn exec:java -Dexec.mainClass="examples.DynamicControlExample" -pl examples
mvn exec:java -Dexec.mainClass="examples.ErrorHandling" -pl examples
mvn exec:java -Dexec.mainClass="examples.FilesystemAgentsExample" -pl examples
mvn exec:java -Dexec.mainClass="examples.ForwardSubagentTextExample" -pl examples
mvn exec:java -Dexec.mainClass="examples.Hooks" -pl examples
mvn exec:java -Dexec.mainClass="examples.IncludePartialMessagesExample" -pl examples
mvn exec:java -Dexec.mainClass="examples.LargeAgentsExample" -pl examples
mvn exec:java -Dexec.mainClass="examples.MaxBudgetExample" -pl examples
mvn exec:java -Dexec.mainClass="examples.McpServer" -pl examples
mvn exec:java -Dexec.mainClass="examples.MessageOriginExample" -pl examples
mvn exec:java -Dexec.mainClass="examples.MultiTurnConversation" -pl examples
mvn exec:java -Dexec.mainClass="examples.PermissionCallbacks" -pl examples
mvn exec:java -Dexec.mainClass="examples.PluginsExample" -pl examples
mvn exec:java -Dexec.mainClass="examples.QuickStart" -pl examples
mvn exec:java -Dexec.mainClass="examples.SessionListingExample" -pl examples
mvn exec:java -Dexec.mainClass="examples.SessionStoreExample" -pl examples
mvn exec:java -Dexec.mainClass="examples.SettingSourcesExample" -Dexec.args="all" -pl examples
mvn exec:java -Dexec.mainClass="examples.SkillsExample" -pl examples
mvn exec:java -Dexec.mainClass="examples.StderrCallbackExample" -pl examples
mvn exec:java -Dexec.mainClass="examples.StreamingEvents" -pl examples
mvn exec:java -Dexec.mainClass="examples.StructuredOutputExample" -pl examples
mvn exec:java -Dexec.mainClass="examples.SubagentTranscriptExample" -pl examples
mvn exec:java -Dexec.mainClass="examples.SystemPromptExample" -pl examples
mvn exec:java -Dexec.mainClass="examples.ToolsConfigurationExample" -pl examples
mvn exec:java -Dexec.mainClass="examples.ToolUsage" -pl examples
mvn exec:java -Dexec.mainClass="examples.TruncatingResumeExample" -pl examples
mvn exec:java -Dexec.mainClass="examples.WindowsBatchCliExample" -pl examples
```

## From Examples Directory

```bash
# Navigate to examples directory
cd examples

# Build examples (resolves the published SDK from Maven Central)
mvn clean package -DskipTests

# Run an example using Maven exec plugin (recommended)
mvn exec:java -Dexec.mainClass="examples.QuickStart"

# Or use java -cp (requires SDK installed in local Maven repo)
# First ensure SDK is installed: cd ../sdk && mvn install -DskipTests && cd ../examples
java -cp target/classes:target/dependency/* examples.QuickStart
java -cp target/classes:target/dependency/* examples.MultiTurnConversation

# Alternative: java -cp with explicit SDK path (for local development)
java -cp target/classes:../sdk/target/classes:target/dependency/* examples.QuickStart
```

## Testing Examples Against Local SDK Changes

To test examples against your local development version (not the published package):

1. **Install SDK locally:**
   ```bash
   cd sdk
   mvn clean install -DskipTests
   cd ..
   ```
   This installs the SDK JAR to your local Maven repository (~/.m2/repository).

2. **Run examples:**
   - **Using Maven (recommended):** Maven automatically uses the local repository version
     ```bash
     mvn exec:java -Dexec.mainClass="examples.QuickStart" -pl examples
     # Or from examples directory:
     cd examples && mvn exec:java -Dexec.mainClass="examples.QuickStart"
     ```

   - **Using java -cp:** Include SDK from local Maven repo or target directory
     ```bash
     # After mvn install, SDK is in ~/.m2/repository
     cd examples
     mvn package -DskipTests  # Copies SDK from local repo
     java -cp target/classes:target/dependency/* examples.QuickStart

     # Or include SDK target directory directly (no install needed)
     java -cp target/classes:../sdk/target/classes:target/dependency/* examples.QuickStart
     ```

**Note:** The examples module already uses `${project.version}` to depend on the same version as the parent, so no pom.xml changes are needed for local development.

# Codebase Structure

## SDK Module (`sdk/`)

```
sdk/src/main/java/in/vidyalai/claude/sdk/
├── ClaudeSDK.java              # Main facade with static helper methods
├── ClaudeSDKClient.java        # Interactive client for bidirectional conversations
├── ClaudeAgentOptions.java     # Configuration options (builder pattern)
├── exceptions/                 # Exception types
│   ├── ClaudeSDKException.java      # Base exception
│   ├── CLIConnectionException.java  # Connection errors
│   ├── CLINotFoundException.java    # CLI not found
│   ├── ProcessException.java        # Process failures
│   ├── CLIJSONDecodeException.java  # JSON parsing errors
│   ├── MessageParseException.java   # Message parsing errors
│   ├── ResultException.java         # CLI reported a terminal error result;
│   │                                # carries the result payload (subclass of
│   │                                # ProcessException)
│   └── QueryFailedException.java    # Run ended in an error result; carries
│                                    # the messages collected before it
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
    ├── message/                    # Message and content types
    │   ├── Message.java                # Sealed interface for messages
    │   ├── UserMessage.java            # User message record
    │   ├── AssistantMessage.java       # Assistant message record
    │   ├── AssistantMessageError.java  # Error types enum
    │   ├── SystemMessage.java          # System message record
    │   ├── ResultMessage.java          # Result message record
    │   ├── StreamEvent.java            # Stream event record
    │   ├── ConversationResetMessage.java # Conversation replaced mid-session
    │   ├── MessageOrigin.java          # Provenance of a user-role message
    │   ├── MessageOriginKind.java      # Origin kind enum
    │   ├── TaskNotificationOriginSubkind.java # Task-notification origin sub-kind enum
    │   ├── ContentBlock.java           # Sealed interface for content
    │   ├── TextBlock.java              # Text content record
    │   ├── ThinkingBlock.java          # Thinking content record
    │   ├── ToolUseBlock.java           # Tool use record
    │   └── ToolResultBlock.java        # Tool result record
    ├── permission/                 # Permission types
    │   ├── PermissionMode.java         # Permission mode enum
    │   ├── PermissionResult*.java      # Permission callback results
    │   ├── PermissionUpdate.java       # Permission update types
    │   ├── PermissionBehavior.java     # Permission behavior enum
    │   ├── PermissionRuleValue.java    # Permission rule value enum
    │   ├── CanUseTool.java             # Permission callback interface
    │   └── ToolPermissionContext.java  # Permission context record
    ├── hook/                       # Hook system types
    │   ├── HookEvent.java              # Hook event enum (10 events)
    │   ├── HookMatcher.java            # Hook matcher class
    │   ├── HookContext.java            # Hook context record
    │   ├── input/                      # Hook input types (from CLI)
    │   │   ├── HookInput.java              # Base sealed interface
    │   │   ├── PreToolUseHookInput.java    # Pre-tool-use hook
    │   │   ├── PostToolUseHookInput.java   # Post-tool-use hook
    │   │   ├── PostToolUseFailureHookInput.java # Tool failure hook
    │   │   ├── UserPromptSubmitHookInput.java  # User prompt hook
    │   │   ├── StopHookInput.java          # Stop hook
    │   │   ├── SubagentStopHookInput.java  # Subagent stop hook
    │   │   ├── SubagentStartHookInput.java # Subagent start hook
    │   │   ├── PreCompactHookInput.java    # Pre-compact hook
    │   │   ├── NotificationHookInput.java  # Notification hook
    │   │   └── PermissionRequestHookInput.java # Permission request hook
    │   └── output/                     # Hook output types (to CLI)
    │       ├── HookOutput.java             # Hook output class
    │       ├── HookSpecificOutput.java     # Hook-specific output builder
    │       ├── PreToolUseHookSpecificOutput.java
    │       ├── PostToolUseHookSpecificOutput.java
    │       ├── PostToolUseFailureHookSpecificOutput.java
    │       ├── UserPromptSubmitHookSpecificOutput.java
    │       ├── NotificationHookSpecificOutput.java
    │       ├── SubagentStartHookSpecificOutput.java
    │       └── PermissionRequestHookSpecificOutput.java
    ├── config/                     # Configuration types
    │   ├── AIModel.java                # AI model enum
    │   ├── AgentDefinition.java        # Agent definition
    │   ├── SystemPromptPreset.java     # System prompt preset
    │   ├── ToolsPreset.java            # Tools preset
    │   ├── SettingSource.java          # Setting source enum
    │   ├── SdkBeta.java                # Beta features enum
    │   ├── SandboxSettings.java        # Sandbox configuration
    │   ├── SandboxNetworkConfig.java   # Network config
    │   └── CompactTriggerType.java     # Compact trigger types
    ├── mcp/                        # MCP server types
    │   ├── McpServerConfig.java        # MCP server config interface
    │   ├── StdioMcpServerConfig.java   # Stdio server config
    │   ├── SseMcpServerConfig.java     # SSE server config
    │   ├── HttpMcpServerConfig.java    # HTTP server config
    │   └── McpSdkServerConfig.java     # SDK server config
    ├── control/                    # Control protocol types
    │   ├── request/                    # Request types
    │   │   ├── SDKHookCallbackRequest.java
    │   │   └── ...
    │   └── response/                   # Response types
    │       └── ...
    ├── session/                    # SessionStore mirroring types
    │   ├── SessionStore.java               # Adapter interface (sync + async variants)
    │   ├── SessionStoreExecutor.java       # Configurable executor for async wrappers
    │   ├── InMemorySessionStore.java       # Reference adapter + filePathToSessionKey helper
    │   ├── SessionKey.java                 # Project key + session ID + optional subpath
    │   ├── SessionListSubkeysKey.java      # Key arg for listSubkeys()
    │   ├── SessionStoreEntry.java          # Map-backed structural supertype
    │   ├── SessionStoreListEntry.java      # Listing entry (sessionId + mtime)
    │   ├── SessionSummaryEntry.java        # Incremental summary sidecar
    │   └── SessionSummary.java             # foldSessionSummary helper
    └── package-info.java           # Package documentation
```

### Public testing helpers (`sdk/src/main/java/in/vidyalai/claude/sdk/testing/`)

```
testing/
└── SessionStoreConformance.java    # 14-contract behavioral suite for SessionStore adapters
```

### Internal runtime helpers (`sdk/src/main/java/in/vidyalai/claude/sdk/internal/`)

The internal package contains the runtime SessionStore integration:

```
internal/
├── TranscriptMirrorBatcher.java    # Buffers transcript_mirror frames + flushes to store
├── SessionResume.java              # Materializes store→temp CLAUDE_CONFIG_DIR for CLI resume
├── SessionImport.java              # Local JSONL → store replay (importSessionToStore)
├── SessionStoreValidation.java     # Fail-fast pre-flight option checks
├── CanUseToolConfig.java           # Shared canUseTool validation + stdio routing
├── SessionStores.java              # *_from_store and *_via_store APIs
├── Sessions.java                   # Local-disk session listing/reading
├── SessionMutations.java           # Local-disk session mutations + buildForkLines (shared)
└── ...
```

## Examples Module (`examples/`)

```
examples/src/main/java/examples/
├── QuickStart.java                 # Basic usage
├── MultiTurnConversation.java      # Interactive conversations
├── ToolUsage.java                  # Using built-in tools
├── McpServer.java                  # Creating custom MCP tools
├── AutoSchemaGeneration.java       # Automatic schema generation
├── Hooks.java                      # Hook callbacks
├── PermissionCallbacks.java        # Custom permission logic
├── StreamingEvents.java            # Real-time streaming
├── StructuredOutputExample.java    # Structured output with JSON Schema validation
├── DynamicControlExample.java      # Dynamic control (setPermissionMode, setModel, interrupt)
├── ErrorHandling.java              # Exception handling
├── AdvancedFeatures.java           # Checkpointing, sandbox, output format
├── ToolsConfigurationExample.java  # Tools configuration
├── MaxBudgetExample.java           # Budget limiting
├── SettingSourcesExample.java      # Settings sources
├── StderrCallbackExample.java      # CLI stderr output
├── PluginsExample.java             # Plugin system
├── AgentsExample.java              # Programmatic subagent definitions
├── FilesystemAgentsExample.java    # Filesystem-based agent configuration
├── SystemPromptExample.java        # Custom system prompt usage
├── IncludePartialMessagesExample.java # Streaming with partial message updates
├── SessionStoreExample.java        # Mirror transcripts to a custom SessionStore
├── TruncatingResumeExample.java    # Rewind a session with resumeSessionAt/resumeDropsTurn
├── MessageOriginExample.java       # Message provenance + conversation resets
├── ForwardSubagentTextExample.java # Forward subagent text/thinking blocks
├── WindowsBatchCliExample.java     # Windows .cmd CLI opt-in (Windows-only)
└── plugins/                        # Example plugin implementations
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
- `listSubagents(String sessionId)` / `listSubagents(String, Path)` - List subagent IDs for a session
- `getSubagentMessages(String sessionId, String agentId, ...)` - Read a subagent's transcript
- `projectKeyForDirectory(Path)` - Compute the SessionStore project key for a directory
- `listSessionsFromStore(SessionStore, Path, Integer, int)` - List sessions from a SessionStore
- `getSessionInfoFromStore(SessionStore, String, Path)` - Read session metadata from a SessionStore
- `getSessionMessagesFromStore(SessionStore, String, Path, Integer, int)` - Read messages from a SessionStore
- `listSubagentsFromStore(SessionStore, String, Path)` - List subagents from a SessionStore
- `getSubagentMessagesFromStore(SessionStore, String, String, Path, Integer, int)` - Read subagent transcript from a SessionStore
- `renameSessionViaStore(SessionStore, String, String, Path)` - Append a custom-title entry via SessionStore
- `tagSessionViaStore(SessionStore, String, String, Path)` - Append a tag entry via SessionStore (null clears)
- `deleteSessionViaStore(SessionStore, String, Path)` - Delete a session via SessionStore
- `forkSessionViaStore(SessionStore, String, Path, String, String)` - Fork a session via SessionStore
- `importSessionToStore(String, SessionStore, Path, ...)` - Replay a local on-disk session into a SessionStore
- `getVersion()` - Get SDK version

## ClaudeSDKClient
Interactive client methods:
- `connect()` - Start conversation
- `connect(String prompt)` - Start conversation with initial prompt
- `sendMessage(String prompt)` - Send follow-up message
- `sendMessage(String prompt, String sessionId)` - Send message with session ID
- `query(String prompt)` - Send query message
- `query(String prompt, String sessionId)` - Send query with session ID
- `query(Iterator<Map> stream)` - Send streaming messages (keeps stdin open; repeatable)
- `query(Iterator<Map> stream, String sessionId)` - Send streaming messages to a session
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

Tests are located in the SDK module:

```
sdk/src/test/java/in/vidyalai/claude/sdk/
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

# Publishing

The SDK module is published to **two** registries: Maven Central (primary) and
GitHub Packages (mirror, kept for existing consumers). The parent POM and the
examples module are **not** published — `maven.deploy.skip` is `true` in the
parent and overridden to `false` only in `sdk/pom.xml`.

The two targets live in profiles in `sdk/pom.xml` and cannot share one
`mvn deploy` run: `central-publishing-maven-plugin` registers as a build
extension and takes over the deploy phase entirely, so it would swallow any
`distributionManagement` repository. Deploy twice:

```bash
mvn clean deploy -Pcentral -DskipTests    # Maven Central (always first)
mvn deploy -Pgithub -DskipTests -pl sdk   # GitHub Packages
```

**Central goes first, always.** A published Central release is permanent — it
cannot be deleted or overwritten. If validation rejects the bundle you can retry
the same version, but only while nothing has been published. GitHub Packages, by
contrast, allows delete-and-republish, so pushing there second means it never
advertises a version Central lacks.

Central releases require a GPG signature on every artifact (`maven-gpg-plugin`,
active only in the `central` profile) and a Central Portal user token in
`settings.xml` under the server id `central`.

## Release Process

1. **Update the `<revision>` property in root `pom.xml`** from SNAPSHOT to release version:
   ```xml
   <!-- Change from: -->
   <revision>0.1.1-SNAPSHOT</revision>
   <!-- To: -->
   <revision>0.1.1</revision>
   ```
   This automatically updates the version for all modules (parent, SDK, examples).

2. **Commit changes:**
   ```bash
   git add pom.xml .github/ CHANGELOG.md README.md CLAUDE.md
   git commit -m "Prepare release 0.1.1"
   git push origin main
   ```

3. **Trigger GitHub Actions workflow:**
   - Navigate to: GitHub repository > Actions tab
   - Select "Publish Release" workflow
   - Click "Run workflow"
   - Enter version: `0.1.1`
   - Leave "Also publish to GitHub Packages" checked (uncheck it once the
     GitHub Packages mirror is retired)
   - Click "Run workflow" button

4. **Monitor workflow execution** in the Actions tab

5. **Central releases itself:** `<autoPublish>true</autoPublish>` is set in the
   `central` profile, so no click is needed in the Portal UI. The deploy blocks
   until the bundle reaches `VALIDATED`; if validation fails the build fails and
   nothing is released (retry the same version). Track progress at
   https://central.sonatype.com/publishing/deployments — artifacts reach
   `repo1.maven.org` in ~10-30 minutes, search indexing lags a few hours

6. **Verify publication:**
   - Maven Central: https://central.sonatype.com/artifact/in.vidyalai/claude-agent-sdk-java
   - GitHub Packages: https://github.com/vidyalai-in/claude-agent-sdk-java/packages
   - Verify all JARs are present (main, sources, javadoc) plus `.asc` signatures
     on Central

7. **Post-release - update to next SNAPSHOT version:**
   ```bash
   # Update <revision> in root pom.xml to next SNAPSHOT (e.g., 0.1.2-SNAPSHOT)
   # Edit: <revision>0.1.2-SNAPSHOT</revision>
   git add pom.xml
   git commit -m "Prepare for next development iteration"
   git push origin main
   ```

## Manual Publishing

1. **Configure Maven settings** (`~/.m2/settings.xml`) with both servers:
   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>CENTRAL_PORTAL_TOKEN_USERNAME</username>
         <password>CENTRAL_PORTAL_TOKEN_PASSWORD</password>
       </server>
       <server>
         <id>github</id>
         <username>YOUR_GITHUB_USERNAME</username>
         <password>YOUR_GITHUB_PERSONAL_ACCESS_TOKEN</password>
       </server>
     </servers>
   </settings>
   ```
   The Central credentials are a **user token** generated at
   https://central.sonatype.com (Account > Generate User Token), not the login
   password. The GitHub PAT needs the `write:packages` scope.

2. **Ensure a GPG key is available.** Signing uses the local keyring via
   gpg-agent; in batch/CI contexts export `MAVEN_GPG_PASSPHRASE` instead. The
   public key must be on a keyserver or Central rejects the bundle:
   ```bash
   gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
   ```

3. **Deploy, Central first:**
   ```bash
   mvn clean deploy -Pcentral -DskipTests    # validates, then auto-releases
   mvn deploy -Pgithub -DskipTests -pl sdk
   ```

## Pre-Release Checklist

Before publishing a release:

1. Run full test suite: `mvn clean test -pl sdk`
2. Build locally: `mvn clean package`
3. Verify JARs are generated in `sdk/target/`:
   - `claude-agent-sdk-java-X.Y.Z.jar`
   - `claude-agent-sdk-java-X.Y.Z-sources.jar`
   - `claude-agent-sdk-java-X.Y.Z-javadoc.jar`
4. Update CHANGELOG.md with release notes
5. Update README.md if needed
6. Ensure all documentation is up to date

**Note:** With CI-friendly versioning, you only need to update the `<revision>` property in one place (root `pom.xml`). All modules automatically inherit the new version.

## Why Examples Module is Not Published

The examples module is **not** published to either registry because:
- Examples are reference code for learning, not a reusable library
- They depend on the SDK and have no independent value as an artifact
- Users should clone the repository to view/run examples
- Publishing would create confusion about which artifact to use
- Examples are version-specific and don't need independent versioning
