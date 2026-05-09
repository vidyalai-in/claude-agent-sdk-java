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
- `strictMcpConfig(boolean)` - When `true`, the CLI ignores project `.mcp.json`, user/global settings, and plugin-provided MCP servers — only the servers passed via `mcpServers(...)` are loaded. Maps to `--strict-mcp-config`.

### Permissions
- `permissionMode(PermissionMode)` - Permission mode
- `permissionPromptToolName(String)` - Tool for prompts
- `canUseTool(CanUseTool)` - Custom callback. **Fires only on `"ask"` decisions** — not for tool calls already permitted by `allowedTools`, `permissionMode`, or `permissions.allow` rules. Use a `PreToolUse` hook to gate every call regardless of decision.

### Sessions
- `continueConversation(boolean)` - Continue last session
- `resume(String)` - Resume specific session
- `forkSession(boolean)` - Fork resumed session
- `sessionStore(SessionStore)` - Mirror transcripts to an external store and resume from it (see [Session Store](./feature-session-store.md)). When set, the SDK forwards `--session-mirror` to the CLI and routes `transcript_mirror` frames to `store.appendAsync(...)`. Pre-flight validation rejects `continueConversation + sessionStore` without `listSessions()` support and `sessionStore + enableFileCheckpointing`.
- `sessionStoreFlush(SessionStoreFlushMode)` - When transcript-mirror entries are flushed to `sessionStore`. `BATCHED` (default) coalesces entries and flushes once per turn or when the buffer exceeds 500 entries / 1 MiB; `EAGER` schedules a background flush after every frame for near-real-time delivery. Ignored when `sessionStore` is unset. See [Flush Mode](./feature-session-store.md#flush-mode-batched-vs-eager).
- `loadTimeoutMs(long)` - Per-call timeout for `store.loadAsync()` / `listSubkeysAsync()` during resume materialization, in milliseconds (default `60_000`). A value of `0` means immediate timeout; large values effectively disable.

### Limits
- `maxTurns(Integer)` - Max conversation turns
- `maxBudgetUsd(Double)` - Max cost in USD
- `maxBufferSize(Integer)` - Max stdout buffer bytes
- `thinking(ThinkingConfig)` - Extended thinking configuration
- `effort(String)` - Thinking depth level (`"low"`, `"medium"`, `"high"`, `"xhigh"`, `"max"`). `"xhigh"` is Opus 4.7-specific and falls back to `"high"` on other models.
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
- `includeHookEvents(boolean)` - When `true`, the CLI streams hook lifecycle events (`PreToolUse`, `PostToolUse`, `Stop`, …) into the message stream as `HookEventMessage` objects. Maps to `--include-hook-events`. See [Hooks → Hook Lifecycle Events on the Stream](./feature-hooks.md#hook-lifecycle-events-on-the-stream).

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
