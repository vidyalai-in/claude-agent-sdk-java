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
- `systemPrompt(String)` - Custom system prompt (`--system-prompt`)
- `systemPrompt(SystemPromptPreset)` - Claude Code preset, optionally with `append`, `excludeDynamicSections` and `snapshot`
- `systemPrompt(SystemPromptCustom)` - Custom prompt that can also set `snapshot`; `SystemPromptCustom.of(prompt, snapshot)`
- `systemPrompt(SystemPromptFile)` - Prompt loaded from a file (`--system-prompt-file`)

`snapshot` (preset and custom forms only) controls whether the session keeps the prompt it recorded on its first request (`true`) or rebuilds it every request (`false`); it is sent on the `initialize` request as `systemPromptSnapshot` and omitted when `null`. Requires Claude Code 2.1.257+. See [Configuration Options → Snapshot](./feature-configuration-options.md#snapshot).

### MCP Servers
- `mcpServers(Map<String, McpServerConfig>)` - Server configs, serialized as `{"mcpServers": {...}}` for `--mcp-config`
- `mcpServers(Path)` / `mcpServersPath(Path)` - MCP config file, passed as-is to `--mcp-config`
- `mcpServersJson(String)` - Inline MCP config JSON, passed as-is to `--mcp-config`
- `strictMcpConfig(boolean)` - When `true`, the CLI ignores project `.mcp.json`, user/global settings, and plugin-provided MCP servers — only the servers passed via `mcpServers(...)` are loaded. Maps to `--strict-mcp-config`.

### Permissions
- `permissionMode(PermissionMode)` - Permission mode
- `permissionPromptToolName(String)` - Tool for prompts
- `canUseTool(CanUseTool)` - Custom callback. **Fires only on `"ask"` decisions** — not for tool calls already permitted by `allowedTools`, `permissionMode`, or `permissions.allow` rules. Use a `PreToolUse` hook to gate every call regardless of decision. The SDK logs an advisory `WARNING` at connection time if this callback is visibly shadowed by whole-tool `allowedTools` entries or `BYPASS_PERMISSIONS`; see [Shadowing Warning](feature-permissions.md#shadowing-warning).

### Sessions
- `continueConversation(boolean)` - Continue last session
- `resume(String)` - Resume specific session
- `forkSession(boolean)` - Fork resumed session
- `resumeSessionAt(String)` - Truncating resume: load the resumed conversation only up to and including this transcript-entry UUID, branching from an earlier point. Use with `resume` and usually `forkSession`. Accepts any transcript-entry UUID — typically an `AssistantMessage.uuid()` observed live, or a `SessionMessage.uuid()` from `ClaudeSDK.getSessionMessages(...)`. Emitted as `--resume-session-at=<value>`. See [Truncating Resume](./feature-session-history.md#truncating-resume).
- `resumeDropsTurn(String)` - With `resumeSessionAt`: the UUID of the user prompt whose turn the truncation intends to discard. The CLI then validates at load time that *every* entry after the fork point belongs to that turn and refuses otherwise — so a queued user message or task notification the session absorbed mid-turn is never silently dropped. A refusal surfaces as an exception whose message contains `Resume rejected by --resume-drops-turn:`; treat it as deterministic and resume plainly rather than retrying. Forwarded whenever non-null, so an empty string reaches the CLI and is rejected there as malformed rather than silently disarming the guard. Emitted as `--resume-drops-turn=<value>`.
- `sessionStore(SessionStore)` - Mirror transcripts to an external store and resume from it (see [Session Store](./feature-session-store.md)). When set, the SDK forwards `--session-mirror` to the CLI and routes `transcript_mirror` frames to `store.appendAsync(...)`. Pre-flight validation rejects `continueConversation + sessionStore` without `listSessions()` support and `sessionStore + enableFileCheckpointing`.
- `sessionStoreFlush(SessionStoreFlushMode)` - When transcript-mirror entries are flushed to `sessionStore`. `BATCHED` (default) coalesces entries and flushes once per turn or when the buffer exceeds 500 entries / 1 MiB; `EAGER` schedules a background flush after every frame for near-real-time delivery. Ignored when `sessionStore` is unset. See [Flush Mode](./feature-session-store.md#flush-mode-batched-vs-eager).
- `loadTimeoutMs(long)` - Per-call timeout for `store.loadAsync()` / `listSubkeysAsync()` during resume materialization, in milliseconds (default `60_000`). A value of `0` means immediate timeout; large values effectively disable.

### Limits
- `maxTurns(Integer)` - Max conversation turns
- `maxBudgetUsd(Double)` - Max cost in USD
- `maxBufferSize(Integer)` - Max stdout buffer bytes
- `thinking(ThinkingConfig)` - Extended thinking configuration
- `effort(String)` - Thinking depth level (`"low"`, `"medium"`, `"high"`, `"xhigh"`, `"max"`). `"xhigh"` is Opus 4.7-specific and falls back to `"high"` on other models.
- `effort(EffortLevel)` - Same as above, but type-safe using the [`EffortLevel`](feature-configuration-options.md#effortlevel-enum) enum (`LOW`, `MEDIUM`, `HIGH`, `XHIGH`, `MAX`). Pass `null` to clear.
- `maxThinkingTokens(Integer)` - **DEPRECATED** Use `thinking()` instead. On newer models the value is treated as on/off (0 = disabled, anything else = adaptive)
- `maxMsgQSize(Integer)` - Max message queue size

### Model
- `model(String)` - AI model name
- `fallbackModel(String)` - Fallback model
- `betas(List<SdkBeta>)` - Beta features

### Environment
- `cwd(Path)` - Working directory
- `cliPath(Path)` - Custom CLI path (a Windows `.bat`/`.cmd` path is refused; see below)
- `allowUnsafeWindowsBatchCli(boolean)` - Waive the Windows batch-script refusal; also requires `-Djdk.lang.Process.allowAmbiguousCommands=false` and rejects cmd.exe metacharacters in every argument (default `false`)
- `settings(String)` - Settings file path or inline JSON string; passed as-is to `--settings`, or merged with `sandbox` into one JSON string when a sandbox is set
- `addDirs(List<Path>)` - Additional context dirs
- `env(Map<String, String>)` - Environment variables, merged over the inherited environment. The SDK also sets `CLAUDE_CODE_ENTRYPOINT` (overridable), `CLAUDE_AGENT_SDK_VERSION`, and `CLAUDE_CODE_SDK_READS_SESSION_STATE=1` unless already named; see [env()](./feature-configuration-options.md#env)
- `extraArgs(Map<String, String>)` - Extra CLI flags; keys without the leading `--`, blank value for a bare flag

### Callbacks
- `stderrCallback(Consumer<String>)` - Stderr callback

### Hooks
- `hooks(Map<HookEvent, List<HookMatcher>>)` - Hook callbacks
- `includeHookEvents(boolean)` - When `true`, the CLI streams hook lifecycle events (`PreToolUse`, `PostToolUse`, `Stop`, …) into the message stream as `HookEventMessage` objects. Maps to `--include-hook-events`. See [Hooks → Hook Lifecycle Events on the Stream](./feature-hooks.md#hook-lifecycle-events-on-the-stream).

### Advanced
- `user(String)` - User identity
- `includePartialMessages(boolean)` - Emit a `StreamEvent` per API stream event (`--include-partial-messages`)
- `verbatimPrompts(boolean)` - Mark every user message the SDK sends `client_composed`, so Claude Code delivers it as written: no `@path` expansion, no slash-command dispatch. Overwrites any per-message value while on. Requires Claude Code 2.1.248+ (the SDK warns on older CLIs). See [verbatimPrompts()](./feature-configuration-options.md#verbatimprompts)
- `forwardSubagentText(boolean)` - When `true`, subagent text and thinking blocks are forwarded into the message stream alongside the `tool_use` / `tool_result` blocks that are always forwarded. Sent on the `initialize` control request (no CLI flag). See [Agents → Observing a subagent's output](./feature-agents.md#observing-a-subagents-output).
- `agents(Map<String, AgentDefinition>)` - Custom agents
- `settingSources(List<SettingSource>)` - Settings sources (empty list disables all sources via `--setting-sources=`; omitted keeps CLI defaults)
- `skills(List<String>)` - Skills allowlist (auto-injects `Skill(name)` into `allowedTools` and defaults `settingSources` to user/project). Names must be exact — wildcards, rule delimiters, and surrounding whitespace throw `IllegalArgumentException` at `connect()`
- `skillsAll()` - Enable every discovered skill (auto-injects bare `Skill` tool)
- `sandbox(SandboxSettings)` - Sandbox config for bash commands; its `network` key configures the sandbox's own network isolation (tool-level restrictions stay in permission rules)
- `plugins(List<SdkPluginConfig>)` - Local plugin directories, `SdkPluginConfig.local(path)` → `--plugin-dir`
- `outputFormat(Map<String, Object>)` - Output format
- `enableFileCheckpointing(boolean)` - Enable file checkpointing for `rewindFiles()`; incompatible with `sessionStore`

## See Also
- [Configuration Options Guide](./feature-configuration-options.md)
