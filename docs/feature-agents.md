# Agent Definitions

Custom agents allow you to define specialized subagents with their own system prompts, tools, and models. Claude can spawn these agents during conversations to handle specific tasks.

## Table of Contents
- [Overview](#overview)
- [AgentDefinition Record](#agentdefinition-record)
- [Inline Agent Definitions](#inline-agent-definitions)
- [Filesystem-Based Agents](#filesystem-based-agents)
- [Large Agent Definitions](#large-agent-definitions)
- [Examples](#examples)

## Overview

Agents are named subagents that Claude can use during a conversation. Each agent has:

- A **description** — what the agent does (shown to Claude when deciding which agent to use)
- A **system prompt** — behavioral instructions for the agent
- **Tools** — the list of tools the agent is allowed to use (null inherits from parent)
- A **model** — the Claude model variant the agent runs on (null inherits from parent)
- **Skills** — the list of skill names available to the agent (null inherits from parent)
- **Memory** — the memory scope for the agent (null inherits from parent)
- **MCP Servers** — MCP server references the agent can use (null inherits from parent)

Agents are registered via `ClaudeAgentOptions.agents()` as a `Map<String, AgentDefinition>`, where the key is the agent's name.

## AgentDefinition Record

```java
import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.config.AIModel;
import in.vidyalai.claude.sdk.types.config.MemoryScope;

// Full constructor
AgentDefinition agent = new AgentDefinition(
    "Reviews code for quality and bugs",   // description
    "You are a code review expert...",     // system prompt
    List.of("Read", "Grep"),               // tools (null = inherit)
    "sonnet",                              // model (null = inherit)
    List.of("commit", "review"),           // skills (null = inherit)
    MemoryScope.PROJECT,                   // memory scope (null = inherit)
    List.of("my-mcp-server")              // MCP servers (null = inherit)
);

// Shorthand: description + prompt only (all other fields inherit from parent)
AgentDefinition simple = new AgentDefinition(
    "Summarizes text",
    "You are a concise summarizer."
);

// Backwards-compatible: description, prompt, tools, model
AgentDefinition compat = new AgentDefinition(
    "Reviews code",
    "You are a code reviewer.",
    List.of("Read", "Grep"),
    "sonnet"   // model can be "sonnet", "opus", "haiku", or full model ID
);
```

**Fields:**

| Field | Type | Description |
|-------|------|-------------|
| `description` | `String` | Human-readable description shown to Claude |
| `prompt` | `String` | System prompt defining the agent's behavior |
| `tools` | `List<String>` (nullable) | Allowed tool names; null inherits parent's tools |
| `disallowedTools` | `List<String>` (nullable) | Tools the agent cannot use; null means none |
| `model` | `String` (nullable) | Model alias ("sonnet", "opus", "haiku", "inherit") or full model ID |
| `skills` | `List<String>` (nullable) | Skill names available to the agent; null inherits |
| `memory` | `MemoryScope` (nullable) | Memory scope; null inherits from parent |
| `mcpServers` | `List<Object>` (nullable) | MCP server references (names or inline configs); null inherits |
| `initialPrompt` | `String` (nullable) | Initial prompt sent when agent starts |
| `maxTurns` | `Integer` (nullable) | Max turns for the agent; null means unlimited |
| `background` | `Boolean` (nullable) | Run the agent in background |
| `effort` | `String` (nullable) | Effort level: `"low"`, `"medium"`, `"high"`, `"xhigh"`, `"max"`. `"xhigh"` is Opus 4.7-specific and falls back to `"high"` on other models. See also the [`EffortLevel`](feature-configuration-options.md#effortlevel-enum) enum. |
| `permissionMode` | `String` (nullable) | Permission mode for the agent |

**Model field:** The `model` field accepts short aliases (`"sonnet"`, `"opus"`, `"haiku"`, `"inherit"`) or full model IDs (e.g., `"claude-sonnet-4-5"`).

### MemoryScope Enum

Controls which memory scope an agent operates in:

```java
import in.vidyalai.claude.sdk.types.config.MemoryScope;

MemoryScope.USER     // "user" — user-level memory
MemoryScope.PROJECT  // "project" — project-scoped memory
MemoryScope.LOCAL    // "local" — local/session-scoped memory
```

## Inline Agent Definitions

Register agents programmatically via `ClaudeAgentOptions`:

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.config.AgentDefinition;

AgentDefinition codeReviewer = new AgentDefinition(
    "Reviews code for best practices and potential issues",
    """
    You are a code reviewer. Analyze code for bugs, performance issues,
    security vulnerabilities, and adherence to best practices.
    Provide constructive feedback.
    """,
    List.of("Read", "Grep"),
    "sonnet"   // model can be "sonnet", "opus", "haiku", or full model ID
);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(Map.of("code-reviewer", codeReviewer))
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();
    client.sendMessage("Use the code-reviewer agent to review MyClass.java");

    for (Message msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### Multiple Agents

You can define multiple agents in a single session:

```java
AgentDefinition analyzer = new AgentDefinition(
    "Analyzes code structure and patterns",
    "You are a code analyzer. Examine code structure, patterns, and architecture.",
    List.of("Read", "Grep", "Glob"),
    null  // inherit model from parent
);

AgentDefinition tester = new AgentDefinition(
    "Creates and runs tests",
    "You are a testing expert. Write comprehensive tests and ensure code quality.",
    List.of("Read", "Write", "Bash"),
    "sonnet"
);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(Map.of(
        "analyzer", analyzer,
        "tester", tester
    ))
    .build();
```

## Filesystem-Based Agents

Agents can also be loaded from markdown files on disk using `settingSources`. Place agent definition files in `.claude/agents/` in your project directory:

```
.claude/
  agents/
    code-reviewer.md
    test-writer.md
```

Then enable filesystem agent loading:

```java
import in.vidyalai.claude.sdk.types.config.SettingSource;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .settingSources(List.of(SettingSource.PROJECT))
    .cwd(Path.of("/path/to/project"))
    .build();

try (ClaudeSDKClient client = new ClaudeSDKClient(options)) {
    client.connect();
    // Agents defined in .claude/agents/*.md are now available
}
```

You can verify which agents were loaded by checking the `SystemMessage` init event:

```java
for (Message msg : client.receiveResponse()) {
    if (msg instanceof SystemMessage system && "init".equals(system.subtype())) {
        List<String> agents = system.get("agents");
        System.out.println("Loaded agents: " + agents);
    }
}
```

## Large Agent Definitions

Agents are sent via the SDK control protocol's initialize request (via stdin), not as CLI arguments. This means there is **no size limit** on agent definitions — you can safely pass 260KB+ of agent data.

This behavior matches the TypeScript and Python SDK implementations and avoids platform-specific command-line argument length limits (ARG_MAX).

```java
// Large agents work reliably via stdin
Map<String, AgentDefinition> agents = new HashMap<>();
for (int i = 0; i < 20; i++) {
    String largePrompt = "You are agent #" + i + ". " + "x".repeat(13 * 1024);
    agents.put("agent-" + i, new AgentDefinition("Agent " + i, largePrompt));
}

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(agents)
    .maxTurns(1)
    .build();

// Works for both query() and createClient()
for (Message msg : ClaudeSDK.query("List available agents", options)) {
    // ...
}
```

## Examples

See the example files for complete runnable demonstrations:

- [`AgentsExample.java`](../examples/src/main/java/examples/AgentsExample.java) — Code reviewer, doc writer, and multiple agents
- [`FilesystemAgentsExample.java`](../examples/src/main/java/examples/FilesystemAgentsExample.java) — Loading agents from `.claude/agents/` files
- [`LargeAgentsExample.java`](../examples/src/main/java/examples/LargeAgentsExample.java) — Stress test with 260KB+ agent payloads

## See Also

- [Configuration Options](./feature-configuration-options.md) — `agents` and `settingSources` options
- [Interactive Conversations](./feature-interactive-conversations.md) — Using agents in multi-turn sessions
