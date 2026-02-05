package examples;

import java.util.List;
import java.util.Map;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.config.AIModel;
import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.config.SettingSource;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;

/**
 * Example demonstrating custom agent definitions with specific tools, prompts,
 * and models.
 *
 * This example shows how to define and use custom agents that specialize in
 * different tasks.
 * Agents can have their own:
 * - Description (what the agent does)
 * - System prompt (behavior instructions)
 * - Allowed tools (capabilities)
 * - Model (which Claude model to use)
 *
 * Usage:
 * mvn exec:java -Dexec.mainClass="examples.AgentsExample" -pl examples
 */
public class AgentsExample {

    public static void main(String[] args) {
        codeReviewerExample();
        documentationWriterExample();
        multipleAgentsExample();
    }

    /**
     * Example using a custom code reviewer agent.
     */
    @SuppressWarnings("null")
    static void codeReviewerExample() {
        System.out.println("=== Code Reviewer Agent Example ===");

        AgentDefinition codeReviewer = new AgentDefinition(
                "Reviews code for best practices and potential issues",
                """
                        You are a code reviewer. Analyze code for bugs, performance issues,
                        security vulnerabilities, and adherence to best practices.
                        Provide constructive feedback.
                        """,
                List.of("Read", "Grep"),
                AIModel.SONNET);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .agents(Map.of("code-reviewer", codeReviewer))
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage(
                    "Use the code-reviewer agent to review the code in sdk/src/main/java/in/vidyalai/claude/sdk/types/hook/HookEvent.java");

            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage assistant) {
                    System.out.println("Claude: " + assistant.getTextContent());
                } else if (msg instanceof ResultMessage result && result.totalCostUsd() != null
                        && result.totalCostUsd() > 0) {
                    System.out.printf("%nCost: $%.4f%n", result.totalCostUsd());
                }
            }
        }
        System.out.println();
    }

    /**
     * Example using a documentation writer agent.
     */
    @SuppressWarnings("null")
    static void documentationWriterExample() {
        System.out.println("=== Documentation Writer Agent Example ===");

        AgentDefinition docWriter = new AgentDefinition(
                "Writes comprehensive documentation",
                """
                        You are a technical documentation expert. Write clear, comprehensive
                        documentation with examples. Focus on clarity and completeness.
                        """,
                List.of("Read", "Write", "Edit"),
                AIModel.SONNET);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .agents(Map.of("doc-writer", docWriter))
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("Use the doc-writer agent to explain what AgentDefinition is used for");

            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage assistant) {
                    System.out.println("Claude: " + assistant.getTextContent());
                } else if (msg instanceof ResultMessage result && result.totalCostUsd() != null
                        && result.totalCostUsd() > 0) {
                    System.out.printf("%nCost: $%.4f%n", result.totalCostUsd());
                }
            }
        }
        System.out.println();
    }

    /**
     * Example with multiple custom agents.
     */
    @SuppressWarnings("null")
    static void multipleAgentsExample() {
        System.out.println("=== Multiple Agents Example ===");

        AgentDefinition analyzer = new AgentDefinition(
                "Analyzes code structure and patterns",
                "You are a code analyzer. Examine code structure, patterns, and architecture.",
                List.of("Read", "Grep", "Glob"),
                null // Use default model
        );

        AgentDefinition tester = new AgentDefinition(
                "Creates and runs tests",
                "You are a testing expert. Write comprehensive tests and ensure code quality.",
                List.of("Read", "Write", "Bash"),
                AIModel.SONNET);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .agents(Map.of(
                        "analyzer", analyzer,
                        "tester", tester))
                .settingSources(List.of(SettingSource.USER, SettingSource.PROJECT))
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();
            client.sendMessage("Use the analyzer agent to find all Java files in the examples/ directory");

            for (Message msg : client.receiveResponse()) {
                if (msg instanceof AssistantMessage assistant) {
                    System.out.println("Claude: " + assistant.getTextContent());
                } else if (msg instanceof ResultMessage result && result.totalCostUsd() != null
                        && result.totalCostUsd() > 0) {
                    System.out.printf("%nCost: $%.4f%n", result.totalCostUsd());
                }
            }
        }
        System.out.println();
    }

}
