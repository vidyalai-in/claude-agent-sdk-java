# Claude Agent SDK for Java - Technical Documentation

Welcome to the technical documentation for the Claude Agent SDK for Java. This documentation provides comprehensive information about the SDK's architecture, features, and usage.

## Overview

The Claude Agent SDK for Java is a comprehensive library for integrating Claude AI capabilities into Java applications. It provides a type-safe, modern Java API for interacting with Claude Code CLI, supporting both simple one-shot queries and complex multi-turn conversations.

**Key Highlights:**
- 🎯 **Type-Safe API**: Leverages Java 25 features (sealed interfaces, records, pattern matching)
- ⚡ **Virtual Threads**: Async operations powered by Project Loom virtual threads
- 🔧 **Flexible Architecture**: Support for both stateless queries and stateful conversations
- 🛠️ **Custom Tools**: Create custom tools using MCP (Model Context Protocol)
- 🔌 **Plugin System**: Extensible architecture for custom functionality
- 🎨 **Builder Pattern**: Fluent API for configuration

## Documentation Index

### Architecture & Design
- **[Architecture Overview](./architecture.md)** - System architecture, design patterns, and internal structure
  - High-level architecture diagram
  - Core components (API layer, configuration, protocol, transport)
  - Design patterns (sealed interfaces, builder, facade, virtual threads)
  - Data flow diagrams and concurrency model
  - Type system hierarchy and dependencies

### Core Features
- **[Simple Queries](./feature-simple-queries.md)** - One-shot queries using ClaudeSDK facade
  - Basic usage examples
  - Query methods overview
  - Configuration options
  - Message handling patterns
  - Best practices

- **[Interactive Conversations](./feature-interactive-conversations.md)** - Multi-turn conversations using ClaudeSDKClient
  - Connection management
  - Sending and receiving messages
  - Control methods
  - Session management
  - Thread safety
  - Complete examples

- **[Configuration Options](./feature-configuration-options.md)** - Complete guide to ClaudeAgentOptions builder
  - All 30+ configuration options
  - Tool configuration
  - Permission settings
  - Model configuration
  - Environment variables
  - Hooks and callbacks
  - Complete examples for common patterns

- **[Message Types](./feature-message-types.md)** - Understanding the message type system
  - UserMessage, AssistantMessage, SystemMessage, ResultMessage, StreamEvent, RateLimitEvent
  - Task lifecycle messages (TaskStartedMessage, TaskProgressMessage, TaskNotificationMessage, TaskUpdatedMessage)
  - HookEventMessage (when `includeHookEvents` is enabled)
  - DeferredToolUse on ResultMessage; `apiErrorStatus` HTTP status field
  - ConversationResetMessage — the conversation was replaced mid-session (e.g. `/clear`)
  - Message origin — telling your own turns from session-injected ones
  - Content blocks (Text, Thinking, ToolUse, ToolResult)
  - Pattern matching
  - Examples and best practices

- **[MCP Servers](./feature-mcp-servers.md)** - Creating custom tools with Model Context Protocol
  - SDK MCP servers (in-process)
  - External MCP servers (stdio/SSE/HTTP)
  - @Tool annotation usage
  - Programmatic tool creation
  - Tool schemas and validation
  - Async execution patterns
  - Complete examples (calculator, database, API integration)

- **[Agent Definitions](./feature-agents.md)** - Custom subagents with specialized prompts, tools, and models
  - Inline agent definitions
  - Filesystem-based agents
  - Large agent support (260KB+ via stdin)
  - AgentDefinition API (with skills, memory scope, and MCP server fields)

- **[Session History](./feature-session-history.md)** - Read and manage historical Claude Code conversation sessions from disk
  - Listing sessions across all projects or filtered by directory
  - Looking up a single session by ID (`getSessionInfo`)
  - Reading full conversation transcripts
  - Reading subagent transcripts (`listSubagents`, `getSubagentMessages`)
  - Renaming sessions (`renameSession`)
  - Tagging sessions for organization (`tagSession`)
  - Deleting sessions (`deleteSession`) — cascades subagent transcript dir
  - Forking sessions with UUID remapping (`forkSession`)
  - Truncating resume (`resumeSessionAt` / `resumeDropsTurn`) — rewind safely to an earlier point
  - SDKSessionInfo (with tag, createdAt, nullable fileSize) and SessionMessage types
  - Pagination with offset and worktree support

- **[Session Store](./feature-session-store.md)** - Mirror transcripts to S3 / Postgres / Redis / custom backends
  - `SessionStore` adapter protocol with sync + async (`CompletableFuture`) variants
  - Configurable virtual-thread executor via `SessionStoreExecutor`
  - Bundled `InMemorySessionStore` reference adapter + `filePathToSessionKey` helper
  - Read APIs: `listSessionsFromStore`, `getSessionInfoFromStore`, `getSessionMessagesFromStore`, `listSubagentsFromStore`, `getSubagentMessagesFromStore`
  - Mutation APIs: `renameSessionViaStore`, `tagSessionViaStore`, `deleteSessionViaStore`, `forkSessionViaStore`
  - `importSessionToStore` for local→store replay; `MirrorErrorMessage` for non-fatal append failures
  - Public `SessionStoreConformance` test harness (14 contracts, framework-agnostic)
  - Resume from a store (subprocess gets a temp `CLAUDE_CONFIG_DIR`); transcript mirror batcher

- **[Skills](./feature-skills.md)** - Top-level `skills` option for the main session
  - Three modes: `skillsAll()`, `skills(List)`, `skills(List.of())`
  - Auto-injects `Skill(name)` into `allowedTools` and defaults `settingSources`
  - Wire-protocol propagation via initialize control request
  - Idempotent injection; explicit settings always win
  - Skill-name validation — blocks `--allowedTools` rule injection, rejects names that can never match

- **[W3C Trace Context Propagation](./feature-trace-context.md)** - Distributed tracing across SDK and CLI
  - Best-effort `TRACEPARENT`/`TRACESTATE` injection into the CLI subprocess
  - Zero runtime dependency on OpenTelemetry (reflection-based)
  - Stale-env scrubbing, baggage-only contexts, propagator errors

### Advanced Features
- **[Extended Thinking Configuration](./feature-thinking-config.md)** - Control Claude's reasoning depth
  - ThinkingConfig types (Adaptive, Enabled, Disabled)
  - Effort levels (low, medium, high, max)
  - Budget control and optimization
  - Complete usage examples

- **[Hooks System](./feature-hooks.md)** - Intercepting and responding to lifecycle events
  - 10 hook events
  - HookMatcher and HookOutput
  - PostToolUse `updatedToolOutput` (replace any tool's output) and `updatedMCPToolOutput`
  - `PermissionDecision.DEFER` + `DeferredToolUse` on ResultMessage
  - `includeHookEvents` + HookEventMessage stream
  - Examples for common use cases

- **[Permission System](./feature-permissions.md)** - Custom permission callbacks and modes
  - Permission modes
  - Custom permission callbacks (fire only on `"ask"` decisions)
  - Enriched `ToolPermissionContext` (`title`, `displayName`, `description`, `decisionReason`, `blockedPath`)
  - Path-based, time-based, and user confirmation examples

- **[Streaming Events](./feature-streaming-events.md)** - Real-time partial message updates
  - Enabling streaming
  - Processing stream events
  - UI integration examples

- **[Transport Layer](./feature-transport-layer.md)** - Custom transport implementations
  - Transport interface
  - Default implementation
  - Custom transport example
  - Windows batch-script refusal, and the explicit opt-in for npm `claude.cmd` deployments

- **[Plugin System](./feature-plugin-system.md)** - Creating and using plugins
  - SdkPluginConfig
  - Use cases and examples

### API Reference
- **[ClaudeSDK](./api-claude-sdk.md)** - Static facade for simple queries
  - Query methods
  - Client factory methods
  - MCP server factory methods
  - Convenience methods

- **[ClaudeSDKClient](./api-claude-sdk-client.md)** - Interactive client for conversations
  - Connection methods
  - Sending/receiving messages
  - Control methods
  - Thread safety notes

- **[ClaudeAgentOptions](./api-claude-agent-options.md)** - Configuration builder
  - All configuration options
  - Builder methods

- **[Message Types](./api-message-types.md)** - Complete message type hierarchy
  - All message types and content blocks
  - Field documentation

- **[Exception Types](./api-exceptions.md)** - Error handling and exceptions
  - Exception hierarchy
  - Error handling examples

### Project Resources
- **[CHANGELOG](./CHANGELOG.md)** - Version history and release notes
- **[Python SDK Parity](./PYTHON_SDK_PARITY.md)** - Feature comparison with Python SDK

## Project Structure

This is a multi-module Maven project:

```
claude-agent-sdk-java/
├── sdk/              # Core SDK library (published to Maven Central; mirrored to GitHub Packages)
│   ├── src/main/java/in/vidyalai/claude/sdk/
│   │   ├── ClaudeSDK.java              # Main facade
│   │   ├── ClaudeSDKClient.java        # Interactive client
│   │   ├── ClaudeAgentOptions.java     # Configuration builder
│   │   ├── exceptions/                 # Exception types
│   │   ├── transport/                  # Transport layer
│   │   ├── internal/                   # Internal implementation
│   │   ├── mcp/                        # MCP server support
│   │   └── types/                      # Type definitions
│   └── src/test/java/                  # SDK tests
└── examples/         # Usage examples (separate module)
    └── src/main/java/examples/
        ├── QuickStart.java
        ├── MultiTurnConversation.java
        ├── McpServer.java
        └── ... (15+ examples)
```

## Getting Started

### Prerequisites
- Java 25 (uses virtual threads and sealed interfaces)
- Maven 3.6+
- Claude Code CLI installed separately

### Installation

Add to your `pom.xml` — no repository or authentication setup is required:

```xml
<dependency>
    <groupId>in.vidyalai</groupId>
    <artifactId>claude-agent-sdk-java</artifactId>
    <version>0.1.21</version>
</dependency>
```

Releases are also mirrored to GitHub Packages for consumers already pointing at it. That route needs a personal access token even though the artifacts are public, so prefer Maven Central unless you have a reason not to — see the [root README](../README.md#alternative-github-packages) for the repository and authentication setup.

### Hello World

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;

// Simple query
List<Message> messages = ClaudeSDK.query("What is 2 + 2?");
for (Message msg : messages) {
    if (msg instanceof AssistantMessage assistant) {
        System.out.println(assistant.getTextContent());
    }
}
```

### Interactive Conversation

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

var options = ClaudeAgentOptions.builder()
    .maxTurns(5)
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();
    client.sendMessage("Hello!");

    for (var msg : client.receiveResponse()) {
        // Process messages
    }

    client.sendMessage("Tell me more");
    for (var msg : client.receiveResponse()) {
        // Process follow-up
    }
}
```

## Examples

The SDK includes 15+ comprehensive examples covering:
- Basic queries and conversations
- Custom MCP tools
- Permission callbacks
- Hook system
- Streaming events
- Error handling
- Advanced features (checkpointing, sandbox, output format)
- And more...

See the `examples/` directory in the repository.

## How to Use This Documentation

1. **New Users**: Start with this README for installation and quick start
2. **Understanding Architecture**: Read [Architecture Overview](./architecture.md)
3. **Simple Use Cases**: Follow [Simple Queries](./feature-simple-queries.md)
4. **Custom Tools**: Learn about [MCP Servers](./feature-mcp-servers.md)
5. **Advanced Features**: Explore other feature guides as needed

## Documentation Status

### ✅ Completed - All Core Documentation
- Main README with quick start and overview
- Architecture overview (comprehensive with diagrams; SessionStore subsystem)
- All feature guides:
  - Simple Queries
  - Interactive Conversations
  - Configuration Options (including `sessionStore` and `loadTimeoutMs`)
  - Message Types (task messages, server tool blocks, MirrorErrorMessage)
  - MCP Servers (with ToolAnnotations, tool titles, and status types)
  - Agent Definitions
  - Extended Thinking Configuration (with `ThinkingDisplay`)
  - Hooks System (with agentId/agentType fields)
  - Permission System
  - Streaming Events
  - Transport Layer (with `--session-mirror`, `--thinking-display`, drop `--debug-to-stderr`)
  - Plugin System
  - Session History (listSessions / getSessionMessages)
  - Session Store (mirror transcripts to S3/Postgres/Redis/custom)
- Complete API Reference (5 documents):
  - ClaudeSDK (including session history methods)
  - ClaudeSDKClient
  - ClaudeAgentOptions
  - Message Types
  - Exception Types
- Code examples (20+ examples in examples/ directory)
- Python SDK Parity documentation

## Contributing to Documentation

When adding new documentation:
1. Follow the existing structure and format
2. Include code examples that work
3. Verify all code examples against actual implementation
4. Add cross-references to related documentation
5. Update the documentation index with new documents
6. Follow the documentation standards:
   - Clear table of contents
   - Practical examples
   - Best practices section
   - See Also section with links

## Documentation Principles

All documentation in this project follows these principles:
1. **Accuracy**: All code examples must work and match the actual API
2. **Completeness**: Cover all major use cases and scenarios
3. **Clarity**: Use clear language and explain complex concepts
4. **Examples**: Include practical, runnable code examples
5. **Cross-referencing**: Link to related documentation
6. **Best Practices**: Include recommended patterns and anti-patterns
7. **Up-to-date**: Keep in sync with code changes

## Support & Resources

- **GitHub Repository**: https://github.com/vidyalai-in/claude-agent-sdk-java
- **Issues**: Report bugs and feature requests on GitHub Issues
- **Example Code**: See `examples/` directory in repository
- **MCP Specification**: https://spec.modelcontextprotocol.io/
- **License**: MIT License
- **Python SDK**: For comparison, see https://github.com/anthropics/anthropic-sdk-python
- **Claude Agent Python SDK Docs**: https://platform.claude.com/docs/en/agent-sdk/python

## Contributing

Contributions are welcome! Please see the repository for contribution guidelines.

## Version

Current version: 0.1.21

See [CHANGELOG.md](./CHANGELOG.md) for version history and release notes.
