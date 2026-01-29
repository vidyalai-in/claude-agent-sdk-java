# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
