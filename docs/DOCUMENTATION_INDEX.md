# Documentation Index

This index provides an overview of all available documentation for the Claude Agent SDK for Java.

## Getting Started

- **[README](./README.md)** - Main documentation entry point with quick start guide
- **[Architecture Overview](./architecture.md)** - System architecture, design patterns, and internal structure

## Feature Documentation

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
  - UserMessage, AssistantMessage, SystemMessage, ResultMessage, StreamEvent
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

### Advanced Features
- **[Hooks System](./feature-hooks.md)** - Intercepting and responding to lifecycle events
  - 10 hook events
  - HookMatcher and HookOutput
  - Examples for common use cases

- **[Permission System](./feature-permissions.md)** - Custom permission callbacks and modes
  - Permission modes
  - Custom permission callbacks
  - Path-based, time-based, and user confirmation examples

- **[Streaming Events](./feature-streaming-events.md)** - Real-time partial message updates
  - Enabling streaming
  - Processing stream events
  - UI integration examples

- **[Transport Layer](./feature-transport-layer.md)** - Custom transport implementations
  - Transport interface
  - Default implementation
  - Custom transport example

- **[Plugin System](./feature-plugin-system.md)** - Creating and using plugins
  - SdkPluginConfig
  - Use cases and examples

## API Reference
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

## Project Resources

- **[CHANGELOG](./CHANGELOG.md)** - Version history and release notes
- **[Python SDK Parity](./PYTHON_SDK_PARITY.md)** - Feature comparison with Python SDK

## Architecture Documentation

The [Architecture Overview](./architecture.md) covers:
- High-level architecture diagram
- Core components (API layer, configuration, protocol, transport)
- Design patterns (sealed interfaces, builder, facade, virtual threads)
- Data flow diagrams (query execution, client flow, hook invocation)
- Concurrency model and thread safety
- Type system hierarchy
- Dependencies and design principles

## Documentation Status

### ✅ Completed - All Core Documentation
- Main README with quick start and overview
- Architecture overview (comprehensive with diagrams)
- All feature guides (10 comprehensive guides):
  - Simple Queries
  - Interactive Conversations
  - Configuration Options
  - Message Types
  - MCP Servers
  - Hooks System
  - Permission System
  - Streaming Events
  - Transport Layer
  - Plugin System
- Complete API Reference (5 documents):
  - ClaudeSDK
  - ClaudeSDKClient
  - ClaudeAgentOptions
  - Message Types
  - Exception Types
- Code examples (17+ examples in examples/ directory)
- Python SDK Parity documentation

## How to Use This Documentation

1. **New Users**: Start with [README](./README.md) for installation and quick start
2. **Understanding Architecture**: Read [Architecture Overview](./architecture.md)
3. **Simple Use Cases**: Follow [Simple Queries](./feature-simple-queries.md)
4. **Custom Tools**: Learn about [MCP Servers](./feature-mcp-servers.md)
5. **Advanced Features**: Explore other feature guides as needed

## Contributing to Documentation

When adding new documentation:
1. Follow the existing structure and format
2. Include code examples that work
3. Verify all code examples against actual implementation
4. Add cross-references to related documentation
5. Update this index with new documents
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

## External Resources

- **GitHub Repository**: https://github.com/vidyalai-in/claude-agent-sdk-java
- **Example Code**: See `examples/` directory in repository
- **MCP Specification**: https://spec.modelcontextprotocol.io/
- **Claude Agent Python SDK Docs**: https://platform.claude.com/docs/en/agent-sdk/python
