# Configuration Options

Complete guide to configuring Claude SDK behavior using `ClaudeAgentOptions`.

## Table of Contents
- [Overview](#overview)
- [Builder Pattern](#builder-pattern)
- [Tool Configuration](#tool-configuration)
- [System Prompt](#system-prompt)
- [MCP Servers](#mcp-servers)
- [Permission Settings](#permission-settings)
- [Session Management](#session-management)
- [Limits](#limits)
- [Model Configuration](#model-configuration)
- [Working Directory and CLI](#working-directory-and-cli)
- [Environment Variables](#environment-variables)
- [Callbacks](#callbacks)
- [Hooks](#hooks)
- [Advanced Features](#advanced-features)
- [Complete Examples](#complete-examples)

## Overview

`ClaudeAgentOptions` provides 30+ configuration options to control Claude SDK behavior. It uses an immutable builder pattern for type-safe configuration.

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .maxTurns(10)
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .build();
```

## Builder Pattern

### Creating Options

```java
// Start with builder
ClaudeAgentOptions.Builder builder = ClaudeAgentOptions.builder();

// Configure
builder.model("claude-sonnet-4-5")
       .maxTurns(10);

// Build immutable instance
ClaudeAgentOptions options = builder.build();
```

### Default Options

```java
// Use defaults
ClaudeAgentOptions options = ClaudeAgentOptions.defaults();
```

### Modifying Existing Options

```java
// Create from existing
ClaudeAgentOptions modified = options.toBuilder()
    .maxTurns(20)
    .model("claude-opus-4-6")
    .build();
```

## Tool Configuration

### tools()

Specify which tools Claude can use.

```java
// Use all available tools (default)
.tools(null)

// Specify list of tool names
.tools(List.of("Read", "Write", "Bash"))

// Use preset
.tools(new ToolsPreset("code-editing"))
```

**Tool Names**:
- `Read` - Read files
- `Write` - Write/create files
- `Edit` - Edit existing files
- `Bash` - Execute bash commands
- `Grep` - Search file contents
- `Glob` - Find files by pattern
- `Task` - Spawn subagents
- `WebFetch` - Fetch web content
- `WebSearch` - Search the web
- MCP tools: `mcp__<server>__<tool>`

### allowedTools()

Whitelist specific tools.

```java
.allowedTools(List.of(
    "Read",
    "Grep",
    "Glob",
    "mcp__calc__add"
))
```

### disallowedTools()

Blacklist specific tools.

```java
.disallowedTools(List.of(
    "Bash",      // Block shell access
    "Write",     // Block file writing
    "WebFetch"   // Block web access
))
```

**Priority**: `disallowedTools` takes precedence over `allowedTools`.

## System Prompt

### systemPrompt()

Set custom system prompt to guide Claude's behavior.

```java
// String prompt
.systemPrompt("You are a code reviewer. Focus on security and performance.")

// Multi-line prompt
.systemPrompt("""
    You are a helpful coding assistant.
    - Be concise
    - Provide working code examples
    - Explain your reasoning
    """)

// Use preset
.systemPrompt(new SystemPromptPreset("concise"))
```

## MCP Servers

### mcpServers()

Configure Model Context Protocol servers for custom tools.

```java
// SDK MCP server (in-process)
McpSdkServerConfig sdkServer = ClaudeSDK.createSdkMcpServer(
    "my-tools",
    new MyTools()
);

.mcpServers(Map.of("tools", sdkServer))

// External stdio server
McpStdioServerConfig externalServer = new McpStdioServerConfig(
    "node",
    List.of("server.js"),
    Map.of("NODE_ENV", "production")
);

.mcpServers(Map.of(
    "sdk", sdkServer,
    "external", externalServer
))

// From file path
.mcpServers(Path.of("~/.claude/mcp_servers.json"))

// From JSON string
.mcpServers("""
    {
        "server1": {"type": "stdio", "command": "node", "args": ["server.js"]}
    }
    """)
```

## Permission Settings

### permissionMode()

Control how tool permissions are handled.

```java
.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
```

**Modes**:
- `PROMPT` (default) - Ask for each permission
- `ACCEPT_ALL` - Auto-accept all permissions
- `ACCEPT_EDITS` - Auto-accept file edits, prompt for others
- `BYPASS_PERMISSIONS` - Skip permission checks entirely

### permissionPromptToolName()

Specify tool for permission prompts (advanced, usually set automatically).

```java
.permissionPromptToolName("stdio")
```

## Session Management

### continueConversation()

Continue previous conversation.

```java
.continueConversation(true)  // Continue from last session
.continueConversation(false) // Start fresh (default)
```

### resume()

Resume specific session by ID.

```java
.resume("session-12345")
```

### forkSession()

Fork resumed session into new session (keeps context, new ID).

```java
.resume("session-12345")
.forkSession(true)
```

## Limits

### maxTurns()

Maximum conversation turns.

```java
.maxTurns(10)  // Limit to 10 turns
```

**Use cases**:
- Budget control
- Prevent runaway conversations
- Quick queries: `.maxTurns(1)`

### maxBudgetUsd()

Maximum cost in US dollars.

```java
.maxBudgetUsd(1.0)  // Limit to $1.00
```

Stops execution when budget exceeded.

### maxBufferSize()

Maximum bytes for buffering CLI stdout.

```java
.maxBufferSize(10 * 1024 * 1024)  // 10MB
```

Default: 100MB. Increase for large outputs.

### thinking()

**NEW**: Control extended thinking behavior with fine-grained configuration.

```java
// Adaptive thinking (32K token default)
.thinking(new ThinkingConfigAdaptive())

// Fixed token budget
.thinking(new ThinkingConfigEnabled(10000))

// Disable thinking
.thinking(new ThinkingConfigDisabled())
```

**Types**:
- `ThinkingConfigAdaptive` - Adaptive thinking with 32,000 token default
- `ThinkingConfigEnabled(int budgetTokens)` - Fixed token budget (must be > 0)
- `ThinkingConfigDisabled` - No thinking tokens

**Note**: This option takes precedence over the deprecated `maxThinkingTokens()`.

See [Extended Thinking Configuration](./feature-thinking-config.md) for complete guide.

### effort()

**NEW**: Set thinking depth/intensity level.

```java
.effort("low")     // Minimal thinking
.effort("medium")  // Balanced (default)
.effort("high")    // Deep thinking
.effort("max")     // Maximum reasoning
```

**Valid values**: `"low"`, `"medium"`, `"high"`, `"max"`

Works in conjunction with `thinking()` to control reasoning depth.

See [Extended Thinking Configuration](./feature-thinking-config.md) for examples.

### maxThinkingTokens()

**DEPRECATED**: Use `thinking()` instead.

Maximum tokens for thinking blocks.

```java
.maxThinkingTokens(10000)  // Deprecated - use thinking() instead
```

### maxMsgQSize()

Maximum message queue size.

```java
.maxMsgQSize(1000)
```

Increase for high-throughput scenarios.

## Model Configuration

### model()

Set the AI model.

```java
.model("claude-sonnet-4-5")
```

**Available Models**:
- `claude-opus-4-6` - Most capable, expensive
- `claude-sonnet-4-5` - Balanced (default)
- `claude-haiku-4-5` - Fast, economical

### fallbackModel()

Fallback if primary model unavailable.

```java
.model("claude-opus-4-6")
.fallbackModel("claude-sonnet-4-5")
```

### betas()

Enable beta features.

```java
.betas(List.of(
    SdkBeta.PROMPT_CACHING,
    SdkBeta.EXTENDED_THINKING
))
```

See [Anthropic API Beta Headers](https://docs.anthropic.com/en/api/beta-headers).

## Working Directory and CLI

### cwd()

Set working directory for file operations.

```java
.cwd(Path.of("/path/to/project"))
```

**Important**: Always set for file operations to ensure correct paths.

### cliPath()

Custom path to Claude Code CLI.

```java
.cliPath(Path.of("/custom/path/to/claude"))
```

Default: Searches system PATH.

### settings()

Path to settings JSON file.

```java
.settings("/path/to/settings.json")
```

### addDirs()

Additional directories to add to context.

```java
.addDirs(List.of(
    Path.of("/path/to/lib"),
    Path.of("/path/to/docs")
))
```

## Environment Variables

### env()

Set environment variables for CLI process.

```java
.env(Map.of(
    "API_KEY", "secret-key",
    "DEBUG", "true",
    "NODE_ENV", "production"
))
```

### extraArgs()

Pass arbitrary CLI flags.

```java
.extraArgs(Map.of(
    "--verbose", "",
    "--config", "custom.json"
))
```

## Callbacks

### canUseTool()

Custom permission callback for tools (requires streaming mode).

```java
.canUseTool((toolName, input, context) -> {
    // Check permission
    if (isAllowed(toolName)) {
        return CompletableFuture.completedFuture(
            new PermissionResultAllow()
        );
    } else {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Tool not allowed")
        );
    }
})
```

**Signature**:
```java
BiFunction<String, Object, ToolPermissionContext, CompletableFuture<PermissionResult>>
```

### stderrCallback()

Receive stderr output from CLI.

```java
.stderrCallback(line -> {
    System.err.println("CLI stderr: " + line);
})
```

## Hooks

### hooks()

Register hook callbacks for lifecycle events.

```java
.hooks(Map.of(
    HookEvent.PRE_TOOL_USE, List.of(
        new HookMatcher(null, "Read", (context) -> {
            System.out.println("About to read file");
            return CompletableFuture.completedFuture(
                HookOutput.empty()
            );
        })
    )
))
```

**Available Events**:
- `PRE_TOOL_USE` - Before tool execution
- `POST_TOOL_USE` - After tool success
- `POST_TOOL_USE_FAILURE` - After tool failure
- `USER_PROMPT_SUBMIT` - User submits message
- `STOP` - Session stops
- `SUBAGENT_START` - Subagent starts
- `SUBAGENT_STOP` - Subagent stops
- `PRE_COMPACT` - Before message compaction
- `NOTIFICATION` - Notification events
- `PERMISSION_REQUEST` - Permission requested

## Advanced Features

### user()

Set user identity for tracking.

```java
.user("user-12345")
```

### includePartialMessages()

Enable partial message streaming.

```java
.includePartialMessages(true)
```

Receive `StreamEvent` messages with deltas as content is generated.

### agents()

Define custom agent configurations.

```java
.agents(Map.of(
    "my-agent", new AgentDefinition(
        "Custom agent",
        "claude-sonnet-4-5",
        List.of("Read", "Write"),
        "You are a specialized agent"
    )
))
```

### settingSources()

Control which settings files to load.

```java
.settingSources(List.of(
    SettingSource.USER,     // ~/.claude/
    SettingSource.PROJECT,  // .claude/ in project
    SettingSource.LOCAL     // .claude.local/
))
```

### sandbox()

Configure bash command sandboxing.

```java
.sandbox(new SandboxSettings(
    true,  // enabled
    new SandboxNetworkConfig(true, List.of(), List.of()),
    SandboxIgnoreViolations.NONE
))
```

### plugins()

Add custom plugins.

```java
.plugins(List.of(
    new SdkPluginConfig("my-plugin", config)
))
```

### outputFormat()

Structured output format (Messages API style).

```java
.outputFormat(Map.of(
    "type", "json_schema",
    "schema", Map.of(
        "type", "object",
        "properties", Map.of(
            "name", Map.of("type", "string"),
            "age", Map.of("type", "integer")
        ),
        "required", List.of("name")
    )
))
```

### checkpointFiles()

Enable file checkpointing for rewinding.

```java
.checkpointFiles(true)
```

Allows using `ClaudeSDKClient.rewindFiles()`.

## Complete Examples

### Example 1: Read-Only Code Analysis

```java
var options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .cwd(Path.of("/path/to/codebase"))
    .allowedTools(List.of("Read", "Grep", "Glob"))
    .disallowedTools(List.of("Write", "Edit", "Bash"))
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .maxTurns(10)
    .maxBudgetUsd(0.50)
    .systemPrompt("You are a code analyzer. Only read and analyze code.")
    .build();
```

### Example 2: Interactive Development

```java
var calcServer = ClaudeSDK.createSdkMcpServer("calc", new Calculator());

var options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .cwd(Path.of("/project"))
    .allowedTools(List.of(
        "Read", "Write", "Edit", "Grep", "Glob",
        "mcp__calc__add", "mcp__calc__multiply"
    ))
    .mcpServers(Map.of("calc", calcServer))
    .permissionMode(PermissionMode.ACCEPT_EDITS)
    .maxTurns(50)
    .checkpointFiles(true)
    .systemPrompt("""
        You are a development assistant.
        - Write clean, tested code
        - Follow project conventions
        - Ask before major changes
        """)
    .build();
```

### Example 3: Budget-Conscious Batch Processing

```java
var options = ClaudeAgentOptions.builder()
    .model("claude-haiku-4-5")  // Cheapest model
    .maxTurns(1)                // Single turn only
    .maxBudgetUsd(0.10)         // 10 cent limit
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .systemPrompt("Be extremely concise.")
    .build();

for (String item : batchItems) {
    String result = ClaudeSDK.queryForText(item, options);
    processResult(result);
}
```

### Example 4: Custom Tools with Hooks

```java
var tools = new MyCustomTools();
var server = ClaudeSDK.createSdkMcpServer("tools", tools);

var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("tools", server))
    .allowedTools(List.of("mcp__tools__process"))
    .hooks(Map.of(
        HookEvent.PRE_TOOL_USE, List.of(
            new HookMatcher(null, "mcp__tools__process", context -> {
                log("Processing: " + context.input());
                return CompletableFuture.completedFuture(
                    HookOutput.logs(List.of("Started processing"))
                );
            })
        ),
        HookEvent.POST_TOOL_USE, List.of(
            new HookMatcher(null, "mcp__tools__process", context -> {
                log("Completed processing");
                return CompletableFuture.completedFuture(
                    HookOutput.empty()
                );
            })
        )
    ))
    .build();
```

### Example 5: Resuming Sessions

```java
// First session
var options1 = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .build();

try (var client = ClaudeSDK.createClient(options1)) {
    client.connect("What is Java?");
    // ... conversation
    // Note session ID from messages
}

// Resume later with context
var options2 = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .resume("previous-session-id")
    .build();

try (var client = ClaudeSDK.createClient(options2)) {
    client.connect("Tell me more about lambdas");
    // Has context from previous session
}
```

### Example 6: Streaming with Permission Callback

```java
var options = ClaudeAgentOptions.builder()
    .canUseTool((toolName, input, context) -> {
        // Custom permission logic
        boolean allowed = checkPermission(toolName, context.path());

        if (allowed) {
            return CompletableFuture.completedFuture(
                new PermissionResultAllow()
            );
        } else {
            return CompletableFuture.completedFuture(
                new PermissionResultDeny("Access denied to " + context.path())
            );
        }
    })
    .build();

// Must use streaming mode
var messages = List.of(
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "Read sensitive.txt"))
);

List<Message> responses = ClaudeSDK.query(messages.iterator(), options);
```

## Best Practices

### 1. Always Set Working Directory for File Operations

```java
// ✅ Good
.cwd(Path.of("/project/root"))

// ❌ Bad: Undefined behavior
// No cwd set, files relative to CLI process directory
```

### 2. Use Appropriate Models

```java
// ✅ Good: Match model to task
.model("claude-haiku-4-5")  // Simple tasks
.model("claude-sonnet-4-5") // Balanced
.model("claude-opus-4-6")   // Complex reasoning

// ❌ Bad: Always using most expensive
.model("claude-opus-4-6")  // For everything!
```

### 3. Set Budget Limits

```java
// ✅ Good: Protect against unexpected costs
.maxBudgetUsd(1.0)
.maxTurns(10)

// ❌ Bad: No limits
// Could get expensive!
```

### 4. Configure Tools Appropriately

```java
// ✅ Good: Explicit tool control
.allowedTools(List.of("Read", "Grep"))
.disallowedTools(List.of("Bash"))

// ❌ Bad: All tools allowed by default
// Potential security risk
```

### 5. Use System Prompts

```java
// ✅ Good: Guide behavior
.systemPrompt("You are a code reviewer. Focus on security.")

// ❌ Bad: No guidance
// Claude may not understand context
```

### 6. Enable Checkpointing for File Operations

```java
// ✅ Good: Enable for safety
.checkpointFiles(true)

// Allows rewinding if mistakes
client.rewindFiles(checkpointId);
```

### 7. Handle Sensitive Data Carefully

```java
// ✅ Good: Don't pass secrets in env
.env(Map.of("CONFIG_PATH", "/path/to/config"))

// ❌ Bad: Secrets in environment
.env(Map.of("API_KEY", "secret-123"))  // Logged!
```

## See Also

- [Simple Queries](./feature-simple-queries.md) - Using options with queries
- [Interactive Conversations](./feature-interactive-conversations.md) - Using options with client
- [MCP Servers](./feature-mcp-servers.md) - Configuring MCP servers
- [Hooks](./feature-hooks.md) - Hook configuration
- [Permissions](./feature-permissions.md) - Permission system
