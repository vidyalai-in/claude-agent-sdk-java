# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
