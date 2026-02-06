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

## Quick Links

### Architecture & Design
- **[Architecture Overview](./architecture.md)** - System architecture, design patterns, and component relationships

### Core Features
- **[Simple Queries](./feature-simple-queries.md)** - One-shot queries using ClaudeSDK facade
- **[Interactive Conversations](./feature-interactive-conversations.md)** - Multi-turn conversations using ClaudeSDKClient
- **[Configuration Options](./feature-configuration-options.md)** - Complete guide to ClaudeAgentOptions builder
- **[Message Types](./feature-message-types.md)** - Understanding the message type system

### Advanced Features
- **[MCP Servers](./feature-mcp-servers.md)** - Creating custom tools with Model Context Protocol
- **[Hooks System](./feature-hooks.md)** - Intercepting and responding to lifecycle events
- **[Permission System](./feature-permissions.md)** - Custom permission callbacks and modes
- **[Streaming Events](./feature-streaming-events.md)** - Real-time partial message updates
- **[Transport Layer](./feature-transport-layer.md)** - Custom transport implementations
- **[Plugin System](./feature-plugin-system.md)** - Creating and using plugins

### API Reference
- **[ClaudeSDK](./api-claude-sdk.md)** - Static facade for simple queries
- **[ClaudeSDKClient](./api-claude-sdk-client.md)** - Interactive client for conversations
- **[ClaudeAgentOptions](./api-claude-agent-options.md)** - Configuration builder
- **[Message Types](./api-message-types.md)** - Complete message type hierarchy
- **[Exception Types](./api-exceptions.md)** - Error handling and exceptions

## Project Structure

This is a multi-module Maven project:

```
claude-agent-sdk-java/
├── sdk/              # Core SDK library (published to GitHub Packages)
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

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>in.vidyalai</groupId>
    <artifactId>claude-agent-sdk-java</artifactId>
    <version>0.1.3-SNAPSHOT</version>
</dependency>

<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/vidyalai-in/claude-agent-sdk-java</url>
    </repository>
</repositories>
```

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

## Support & Resources

- **GitHub Repository**: https://github.com/vidyalai-in/claude-agent-sdk-java
- **Issues**: Report bugs and feature requests on GitHub Issues
- **License**: MIT License
- **Python SDK**: For comparison, see https://github.com/anthropics/anthropic-sdk-python

## Contributing

Contributions are welcome! Please see the repository for contribution guidelines.

## Version

Current version: 0.1.3-SNAPSHOT

See [CHANGELOG.md](../docs/CHANGELOG.md) for version history and release notes.
