# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.7] - 2026-03-15

### Added
- **Typed `RateLimitEvent` message**: `rate_limit_event` messages from the CLI are now parsed into a typed `RateLimitEvent` record (matching Python SDK v0.1.49). Previously returned `null` (forward-compat). New types:
  - `RateLimitEvent` — implements `Message`, includes `rateLimitInfo`, `uuid`, `sessionId`
  - `RateLimitInfo` — fields: `status` (`"allowed"`, `"allowed_warning"`, `"rejected"`), `resetsAt`, `rateLimitType`, `utilization`, `overageStatus`, `overageResetsAt`, `overageDisabledReason`, `raw`
- **Session mutation APIs**: Two new static methods on `ClaudeSDK` (matching Python SDK v0.1.49):
  - `renameSession(String sessionId, String title)` — rename a session by appending a `custom-title` entry to its JSONL file
  - `renameSession(String sessionId, String title, Path directory)` — rename within a specific project directory
  - `tagSession(String sessionId, String tag)` — tag a session (pass `null` to clear)
  - `tagSession(String sessionId, String tag, Path directory)` — tag within a specific project directory
  - Tags are Unicode-sanitized (removes zero-width chars, directional marks, private-use chars) for CLI filter compatibility
  - Internal `SessionMutations` class mirrors Python SDK's `_internal/session_mutations.py`

### Fixed
- **Reverted fine-grained tool streaming**: Removed automatic `CLAUDE_CODE_ENABLE_FINE_GRAINED_TOOL_STREAMING=1` env var when `includePartialMessages=true` (matching Python SDK revert in v0.1.49, commit 21560e3)

### Synced
- Python SDK v0.1.48 → v0.1.49 (commits d6f0352..302ceb6)
- v0.1.49: Typed `RateLimitEvent`, `rename_session`, `tag_session` APIs; CLI bumps to 2.1.72-2.1.76; revert FGTS

[0.1.7]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.7

## [0.1.6] - 2026-03-07

### Added
- **Typed MCP Status Response**: `getMcpStatus()` on `ClaudeSDKClient` now returns typed `McpStatusResponse` instead of raw `Map<String, Object>`. New types added: `McpStatusResponse`, `McpServerStatus`, `McpServerInfo`, `McpToolInfo`, `McpToolAnnotations` (matching Python SDK v0.1.45)
- **MCP Control Methods**: Three new methods on `ClaudeSDKClient`:
  - `reconnectMcpServer(String serverName)` — reconnect a disconnected or failed MCP server
  - `toggleMcpServer(String serverName, boolean enabled)` — enable or disable an MCP server
  - `stopTask(String taskId)` — stop a running task (emits `task_notification` with status `"stopped"`)
- **Typed Task System Messages**: `parseSystemMessage` now dispatches to typed subclasses (matching Python SDK v0.1.45):
  - `TaskStartedMessage` — emitted when a task starts (fields: `taskId`, `description`, `uuid`, `sessionId`, `toolUseId`, `taskType`)
  - `TaskProgressMessage` — emitted while a task is in progress (adds `usage`, `lastToolName`)
  - `TaskNotificationMessage` — emitted when a task completes/fails/stops (adds `status`, `outputFile`, `summary`, `usage`)
  - All three implement `Message` and are sealed variants of the interface
- **`stop_reason` on `ResultMessage`**: New optional `stopReason` field on `ResultMessage` (matching Python SDK v0.1.45)
- **`agent_id`/`agent_type` on tool-lifecycle hook inputs**: Added optional `agentId` and `agentType` fields to `PreToolUseHookInput`, `PostToolUseHookInput`, `PostToolUseFailureHookInput`, and `PermissionRequestHookInput` for sub-agent attribution (matching Python SDK v0.1.45)
- **Session listing APIs**: Full implementation of `ClaudeSDK.listSessions()` and `ClaudeSDK.getSessionMessages()` (matching Python SDK v0.1.45):
  - `listSessions()` — list all sessions across all projects from `~/.claude/projects/`
  - `listSessions(Path directory)` — list sessions for a specific project directory
  - `listSessions(Path directory, Integer limit, boolean includeWorktrees)` — full control
  - `getSessionMessages(String sessionId)` — retrieve full conversation history for a session
  - `getSessionMessages(String sessionId, Path directory)` — search within a specific project
  - `getSessionMessages(String sessionId, Path directory, Integer limit, int offset)` — full control
  - Sessions are read directly from JSONL files using lightweight head/tail reads (64 KB each) for listing and full reads for messages
  - Internal `Sessions` class mirrors Python SDK's `_internal/sessions.py` including path sanitization, hash algorithm, sidechain filtering, and conversation chain reconstruction
- **Session listing types**: Added `SDKSessionInfo` and `SessionMessage` record types (matching Python SDK v0.1.45)
- **New control protocol request types**: `SDKControlMcpReconnectRequest`, `SDKControlMcpToggleRequest`, `SDKControlStopTaskRequest`

### Fixed
- **Fine-grained tool streaming**: When `includePartialMessages` option is `true`, the env var `CLAUDE_CODE_ENABLE_FINE_GRAINED_TOOL_STREAMING=1` is now set automatically so tool input parameters stream eagerly rather than being buffered (matching Python SDK v0.1.48)

### Synced
- Python SDK v0.1.39 → v0.1.48 (commits 146e3d6..d6f0352)
- v0.1.40-v0.1.44: CLI bumps to 2.1.51-2.1.59 (no API changes)
- v0.1.45: Major API additions (task messages, MCP control, session types, stop_reason, typed MCP status)
- v0.1.46: Fix string prompt stdin closing (already correct in Java SDK); CLI 2.1.68-2.1.69
- v0.1.47: CLI bump to 2.1.70 (no API changes)
- v0.1.48: Fine-grained tool streaming for partial messages; CLI 2.1.71

[0.1.6]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.6

## [0.1.5] - 2026-02-21

### Fixed
- **Forward-Compatible Message Parsing**: `MessageParser.parse()` now returns `null` for unknown message types instead of throwing `MessageParseException`, matching Python SDK v0.1.39 behavior. This makes the SDK forward-compatible with newer CLI versions that may emit new message types (e.g., `rate_limit_event` introduced in CLI v2.1.45+). The message iterator in `QueryHandler` silently skips null messages.

### Synced
- Python SDK v0.1.36 → v0.1.39 (commits 4d74748..146e3d6)
- v0.1.37: CLI bump to 2.1.44 (no API changes)
- v0.1.38: CLI bumps to 2.1.45 and 2.1.47 (no API changes)
- v0.1.39: Fix unknown message types (rate_limit_event, etc.) to return null instead of crashing

[0.1.5]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.5

## [0.1.4] - 2026-02-16

### Added
- **ThinkingConfig Types**: Added `ThinkingConfig` sealed interface with three variants for controlling extended thinking behavior (matching Python SDK v0.1.36):
  - `ThinkingConfigAdaptive` — uses adaptive thinking with default 32,000 token budget
  - `ThinkingConfigEnabled` — enables thinking with a specified token budget
  - `ThinkingConfigDisabled` — disables extended thinking
- **Thinking Configuration Option**: Added `thinking` field to `ClaudeAgentOptions` that takes precedence over the deprecated `maxThinkingTokens` field
- **Effort Option**: Added `effort` field to `ClaudeAgentOptions` for controlling thinking depth with values: "low", "medium", "high", "max" (matching Python SDK v0.1.36)

### Changed
- Updated thinking token resolution logic in `SubprocessCLITransport` to support new `ThinkingConfig` types
- `thinking` config now takes precedence over deprecated `maxThinkingTokens` field
- Thinking config resolves to `--max-thinking-tokens` CLI flag: adaptive → 32,000 (default), enabled → budget_tokens, disabled → 0
- Effort level is passed to CLI via `--effort` flag

### Documentation
- Updated `docs/PYTHON_SDK_PARITY.md` to reflect 100% parity with Python SDK v0.1.36
- Added 4 new type definitions (ThinkingConfig, ThinkingConfigAdaptive, ThinkingConfigEnabled, ThinkingConfigDisabled)
- Updated configuration options count from 35+ to 37+ options

[0.1.4]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.4

## [0.1.3] - 2026-02-08

### Added
- **MCP Tool Annotations Support**: Added `ToolAnnotations` class with support for `readOnlyHint`, `destructiveHint`, `idempotentHint`, and `openWorldHint` to provide semantic hints about tool behavior (matching Python SDK v0.1.31)
- Annotations can be specified via `@Tool` annotation attributes or `SdkMcpTool.Builder.annotations()`
- Annotations are automatically included in MCP `tools/list` responses when set
- **LargeAgentsExample**: New example demonstrating that 260KB+ agent definitions work correctly via the initialize request, covering both `ClaudeSDKClient` and `query()` usage

### Changed
- **Agent Definitions Fix**: Agents are now sent via initialize request through stdin instead of CLI `--agents` flag, avoiding platform-specific ARG_MAX limits and enabling arbitrarily large agent definitions (260KB+) (matching Python SDK v0.1.31)
- Removed `--agents` CLI flag handling from `SubprocessCLITransport`
- Updated `SDKControlInitializeRequest` to include optional `agents` field
- All agent definitions are now passed through the control protocol initialization handshake

### Fixed
- Large agent definitions (260KB+) no longer fail silently due to command-line argument length limits
- Agents are properly registered when using both `query()` and `ClaudeSDKClient`

[0.1.3]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.3

## [0.1.2] - 2026-02-05

### Added
- **New hook events**: Added support for three new hook event types to match Python SDK v0.1.29:
  - `Notification` — for handling notification events with `NotificationHookInput` and `NotificationHookSpecificOutput`
  - `SubagentStart` — for handling subagent startup with `SubagentStartHookInput` and `SubagentStartHookSpecificOutput`
  - `PermissionRequest` — for handling permission requests with `PermissionRequestHookInput` and `PermissionRequestHookSpecificOutput`

- **Enhanced hook input types**: Added missing fields to existing hook types:
  - `PreToolUseHookInput`: added `toolUseId` field
  - `PostToolUseHookInput`: added `toolUseId` field
  - `SubagentStopHookInput`: added `agentId`, `agentTranscriptPath`, and `agentType` fields

- **Enhanced hook output types**: Added new fields to hook-specific output types:
  - `PreToolUseHookSpecificOutput`: added `additionalContext` field
  - `PostToolUseHookSpecificOutput`: added `updatedMCPToolOutput` field

- **New examples**: Added four new comprehensive examples to improve SDK documentation:
  - `AgentsExample.java` — demonstrates programmatic subagent definitions
  - `FilesystemAgentsExample.java` — shows filesystem-based agent configuration
  - `SystemPromptExample.java` — illustrates custom system prompt usage
  - `IncludePartialMessagesExample.java` — demonstrates streaming with partial message updates

### Fixed
- **AssistantMessage error field**: Ensured the `error` field in `AssistantMessage` is correctly populated from top-level response data (matching Python SDK v0.1.28 bug fix). The Java implementation already had the correct behavior with proper documentation.

### Documentation
- Updated `PYTHON_SDK_PARITY.md` with comprehensive comparison against Python SDK v0.1.29
- Verified 100% feature parity with all functional features from Python SDK v0.1.29
- Updated documentation to reflect 76+ types with complete parity
- Documented all 10 hook events with enhanced fields

[0.1.2]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.2

## [0.1.1] - 2026-01-30

### Added
- `PostToolUseFailure` hook event type for handling tool use failures
- `PostToolUseFailureHookInput` type with fields for tool name, input, use ID, error, and optional interrupt flag
- `HookSpecificOutput.postToolUseFailure()` builder method for creating hook responses

[0.1.1]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.1

## [0.1.0] - 2026-01-29

### Added
- Initial release of Claude Agent SDK for Java
- Core `ClaudeSDK` facade with static helper methods for one-shot queries
- `ClaudeSDKClient` for bidirectional, multi-turn conversations
- Support for custom MCP servers with `@Tool` annotation
- Hook system for pre/post tool use callbacks
- Permission callbacks for tool execution control
- Comprehensive example suite (15 examples covering all major features)
- Full Java 25 support with sealed interfaces and virtual threads

### Features
- One-shot queries with `ClaudeSDK.query()`
- Multi-turn conversations with `ClaudeSDKClient`
- Custom tool creation via SDK MCP servers
- File checkpointing and rewind with `rewindFiles()`
- Streaming events support
- Sandbox configuration
- Permission mode management
- Dynamic model switching
- Error handling with custom exceptions
- Automatic version detection via templating

[0.1.0]: https://github.com/vidyalai-in/claude-agent-sdk-java/releases/tag/v0.1.0
