# ClaudeAgentOptions API Reference

Configuration options builder.

## Class Overview

```java
public final class ClaudeAgentOptions
```

Immutable configuration object using builder pattern.

## Creating Options

```java
// Builder
ClaudeAgentOptions.builder()
    .option1(value)
    .build()

// Defaults
ClaudeAgentOptions.defaults()

// From existing
options.toBuilder()
    .modifiedOption(newValue)
    .build()
```

## All Configuration Options

### Tool Configuration
- `tools(Object)` - Tool list or preset
- `allowedTools(List<String>)` - Whitelist
- `disallowedTools(List<String>)` - Blacklist

### System Prompt
- `systemPrompt(Object)` - String, `SystemPromptPreset`, or `SystemPromptFile`

### MCP Servers
- `mcpServers(Object)` - Map, Path, or String

### Permissions
- `permissionMode(PermissionMode)` - Permission mode
- `permissionPromptToolName(String)` - Tool for prompts
- `canUseTool(CanUseTool)` - Custom callback

### Sessions
- `continueConversation(boolean)` - Continue last session
- `resume(String)` - Resume specific session
- `forkSession(boolean)` - Fork resumed session

### Limits
- `maxTurns(Integer)` - Max conversation turns
- `maxBudgetUsd(Double)` - Max cost in USD
- `maxBufferSize(Integer)` - Max stdout buffer bytes
- `thinking(ThinkingConfig)` - **NEW** Extended thinking configuration
- `effort(String)` - **NEW** Thinking depth level ("low", "medium", "high", "max")
- `maxThinkingTokens(Integer)` - **DEPRECATED** Use `thinking()` instead
- `maxMsgQSize(Integer)` - Max message queue size

### Model
- `model(String)` - AI model name
- `fallbackModel(String)` - Fallback model
- `betas(List<SdkBeta>)` - Beta features

### Environment
- `cwd(Path)` - Working directory
- `cliPath(Path)` - Custom CLI path
- `settings(String)` - Settings file path
- `addDirs(List<Path>)` - Additional context dirs
- `env(Map<String, String>)` - Environment variables
- `extraArgs(Map<String, String>)` - Extra CLI flags

### Callbacks
- `stderrCallback(Consumer<String>)` - Stderr callback

### Hooks
- `hooks(Map<HookEvent, List<HookMatcher>>)` - Hook callbacks

### Advanced
- `user(String)` - User identity
- `includePartialMessages(boolean)` - Enable streaming
- `agents(Map<String, AgentDefinition>)` - Custom agents
- `settingSources(List<SettingSource>)` - Settings sources (empty list disables all sources via `--setting-sources=`; omitted keeps CLI defaults)
- `skills(List<String>)` - Skills allowlist (auto-injects `Skill(name)` into `allowedTools` and defaults `settingSources` to user/project)
- `skillsAll()` - Enable every discovered skill (auto-injects bare `Skill` tool)
- `sandbox(SandboxSettings)` - Sandbox config
- `plugins(List<SdkPluginConfig>)` - Plugin configs
- `outputFormat(Map<String, Object>)` - Output format
- `checkpointFiles(boolean)` - Enable checkpointing

## See Also
- [Configuration Options Guide](./feature-configuration-options.md)
