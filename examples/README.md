# Claude Agent SDK Java - Examples

This module contains usage examples demonstrating various features of the Claude Agent SDK for Java.

## Prerequisites

- Java 25+
- Maven 3.6+
- Claude Code CLI installed (see main README)

## Available Examples

- **QuickStart.java** - Basic SDK usage and simple queries
- **MultiTurnConversation.java** - Interactive multi-turn conversations
- **ToolUsage.java** - Using built-in tools (Read, Write, Bash, etc.)
- **McpServer.java** - Creating custom MCP tools
- **AutoSchemaGeneration.java** - Automatic JSON schema generation for tools
- **Hooks.java** - Implementing hook callbacks for lifecycle events
- **PermissionCallbacks.java** - Custom permission logic for tool approvals
- **StreamingEvents.java** - Real-time streaming of Claude's responses
- **ErrorHandling.java** - Exception handling and error recovery
- **AdvancedFeatures.java** - File checkpointing, sandbox mode, structured output
- **ToolsConfigurationExample.java** - Tools configuration (array, preset, empty)
- **MaxBudgetExample.java** - Budget limiting and cost control
- **SettingSourcesExample.java** - Settings sources (user, project, local)
- **StderrCallbackExample.java** - Capturing CLI stderr output
- **PluginsExample.java** - Plugin system usage

## Running Examples

### Option 1: From Root Directory (Recommended)

```bash
# Navigate to project root
cd /path/to/claude-agent-sdk-java

# Build all modules
mvn clean package -DskipTests

# Run an example
mvn exec:java -Dexec.mainClass="examples.QuickStart" -pl examples
mvn exec:java -Dexec.mainClass="examples.MultiTurnConversation" -pl examples
mvn exec:java -Dexec.mainClass="examples.McpServer" -pl examples
```

### Option 2: From Examples Directory

```bash
# Navigate to examples directory
cd examples

# Build examples
mvn clean package -DskipTests

# Run using Maven exec plugin
mvn exec:java -Dexec.mainClass="examples.QuickStart"

# Or run using java -cp
java -cp target/classes:target/dependency/* examples.QuickStart
```

## SDK Dependency

By default, this module depends on the **local SNAPSHOT version** of the SDK for development:

```xml
<dependency>
    <groupId>in.vidyalai</groupId>
    <artifactId>claude-agent-sdk-java</artifactId>
    <version>0.1.1-SNAPSHOT</version>
</dependency>
```

### Testing Against Published Package

To test examples against the published SDK from GitHub Packages:

1. Update `pom.xml` dependency to a release version:
   ```xml
   <version>0.1.0</version>
   ```

2. Configure GitHub authentication in `~/.m2/settings.xml`:
   ```xml
   <settings>
     <servers>
       <server>
         <id>github</id>
         <username>YOUR_GITHUB_USERNAME</username>
         <password>YOUR_GITHUB_PERSONAL_ACCESS_TOKEN</password>
       </server>
     </servers>
   </settings>
   ```

3. Build and run examples as usual

### Testing Local SDK Changes

To test examples against your local SDK modifications:

1. Install SDK locally:
   ```bash
   cd ../sdk
   mvn clean install -DskipTests
   cd ../examples
   ```

2. Ensure `pom.xml` uses SNAPSHOT version (default)

3. Run examples

## Example Usage Patterns

### Basic Query
See `QuickStart.java` for the simplest usage:
```java
ClaudeSDK.query("What is the capital of France?");
```

### Multi-Turn Conversation
See `MultiTurnConversation.java` for interactive conversations:
```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    client.connect("Hello!");
    client.sendMessage("Follow-up question");
}
```

### Custom Tools via MCP
See `McpServer.java` for creating custom tools:
```java
@Tool(description = "Get current weather")
public String getWeather(String city) {
    return "Sunny, 72°F in " + city;
}
```

### Permission Callbacks
See `PermissionCallbacks.java` for custom permission logic:
```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .permissionCallback((toolName, input, ctx) -> {
        // Custom logic
        return CompletableFuture.completedFuture(new PermissionResultAllow());
    })
    .build();
```

## Troubleshooting

### Build Failures

**Problem**: `Could not resolve dependencies for project in.vidyalai:claude-agent-sdk-examples`

**Solution**:
- If using SNAPSHOT version: Run `mvn install` in the `sdk/` directory first
- If using published version: Ensure GitHub authentication is configured

### Runtime Errors

**Problem**: `CLINotFoundException: Claude Code CLI not found`

**Solution**: Install Claude Code CLI:
```bash
curl -fsSL https://claude.ai/install.sh | bash
```

Or specify custom path:
```java
ClaudeAgentOptions.builder()
    .cliPath(Path.of("/path/to/claude"))
    .build();
```

## Contributing Examples

When adding new examples:

1. Create a new `.java` file in `src/main/java/examples/`
2. Use descriptive class names (e.g., `FeatureNameExample.java`)
3. Include comments explaining key concepts
4. Add the example to this README's "Available Examples" section
5. Update main README.md if needed

## License

MIT License - See LICENSE file in the project root
