package examples;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.SystemMessage;

/**
 * Example demonstrating that large agent definitions (260KB+) work correctly.
 *
 * <p>
 * Previously, agents were passed via the {@code --agents} CLI flag which has
 * platform-specific size limits (ARG_MAX). Large agent definitions would
 * silently fail to register. Now agents are sent via the initialize control
 * request through stdin, which has no size limits.
 *
 * <p>
 * This matches the TypeScript and Python SDK behavior.
 *
 * <p>
 * Usage:
 * 
 * <pre>
 * mvn exec:java -Dexec.mainClass="examples.LargeAgentsExample" -pl examples
 * </pre>
 */
public class LargeAgentsExample {

    public static void main(String[] args) {
        largeAgentsViaClient();
        largeAgentsViaQuery();
    }

    /**
     * Generates multiple agents with large prompts for stress testing.
     *
     * @param numAgents    number of agents to generate
     * @param promptSizeKb size of each agent's prompt in KB
     * @return map of agent name to AgentDefinition
     */
    static Map<String, AgentDefinition> generateLargeAgents(int numAgents, int promptSizeKb) {
        Map<String, AgentDefinition> agents = new HashMap<>();
        for (int i = 0; i < numAgents; i++) {
            String promptContent = "You are test agent #" + i + ". " + "x".repeat(promptSizeKb * 1024);
            agents.put("large-agent-" + i, new AgentDefinition(
                    "Large test agent #" + i + " for stress testing",
                    promptContent));
        }
        return agents;
    }

    /**
     * Demonstrates large agents (260KB+) sent via ClaudeSDKClient.
     *
     * <p>
     * The initialize request is sent through stdin with no size limits,
     * so all 20 agents are registered successfully.
     */
    @SuppressWarnings("null")
    static void largeAgentsViaClient() {
        System.out.println("=== Large Agents via ClaudeSDKClient (260KB+) ===");

        // Generate 20 agents with 13KB prompts each = ~260KB total
        Map<String, AgentDefinition> agents = generateLargeAgents(20, 13);

        long totalSize = agents.values().stream()
                .mapToLong(a -> a.prompt() != null ? a.prompt().length() : 0)
                .sum();
        System.out.printf("Total agent payload: %.1f KB%n", totalSize / 1024.0);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .agents(agents)
                .maxTurns(1)
                .build();

        try (ClaudeSDKClient client = ClaudeSDK.createClient(options)) {
            client.connect("List available agents");

            for (Message msg : client.receiveResponse()) {
                if (msg instanceof SystemMessage sys && "init".equals(sys.subtype())) {
                    List<String> registeredAgents = sys.get("agents");
                    if (registeredAgents != null) {
                        System.out.printf("Registered agents: %d/%d%n", registeredAgents.size(), agents.size());
                        if (registeredAgents.size() >= agents.size()) {
                            System.out.println("✓ All large agents registered successfully via initialize request");
                        } else {
                            System.out.println("✗ Some agents failed to register: " + registeredAgents);
                        }
                    }
                } else if (msg instanceof ResultMessage result && result.totalCostUsd() != null
                        && result.totalCostUsd() > 0) {
                    System.out.printf("Cost: $%.4f%n", result.totalCostUsd());
                }
            }
        }
        System.out.println();
    }

    /**
     * Demonstrates large agents (260KB+) sent via the one-shot query function.
     *
     * <p>
     * The query function also uses the control protocol internally, so agents
     * are sent via stdin regardless of prompt type.
     */
    @SuppressWarnings("null")
    static void largeAgentsViaQuery() {
        System.out.println("=== Large Agents via query() function (260KB+) ===");

        // Generate 20 agents with 13KB prompts each = ~260KB total
        Map<String, AgentDefinition> agents = generateLargeAgents(20, 13);

        long totalSize = agents.values().stream()
                .mapToLong(a -> a.prompt() != null ? a.prompt().length() : 0)
                .sum();
        System.out.printf("Total agent payload: %.1f KB%n", totalSize / 1024.0);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .agents(agents)
                .maxTurns(1)
                .build();

        for (Message msg : ClaudeSDK.query("What is 2 + 2?", options)) {
            if (msg instanceof SystemMessage sys && "init".equals(sys.subtype())) {
                List<String> registeredAgents = sys.get("agents");
                if (registeredAgents != null) {
                    System.out.printf("Registered agents: %d/%d%n", registeredAgents.size(), agents.size());
                    if (registeredAgents.size() >= agents.size()) {
                        System.out.println("✓ All large agents registered successfully via initialize request");
                    } else {
                        System.out.println("✗ Some agents failed to register: " + registeredAgents);
                    }
                }
            } else if (msg instanceof ResultMessage result && result.totalCostUsd() != null
                    && result.totalCostUsd() > 0) {
                System.out.printf("Cost: $%.4f%n", result.totalCostUsd());
            }
        }
        System.out.println();
    }

}
