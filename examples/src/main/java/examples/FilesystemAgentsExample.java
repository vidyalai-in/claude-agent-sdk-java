package examples;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.types.config.SettingSource;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.SystemMessage;

/**
 * Example demonstrating loading filesystem-based agents via setting_sources.
 *
 * This example shows how to load agents defined in .claude/agents/ files
 * using the setting_sources option. This is different from inline
 * AgentDefinition
 * objects - these agents are loaded from markdown files on disk.
 *
 * This example tests loading agents from .claude/agents/test-agent.md file.
 *
 * Usage:
 * mvn exec:java -Dexec.mainClass="examples.FilesystemAgentsExample" -pl examples
 */
public class FilesystemAgentsExample {

    @SuppressWarnings("null")
    public static void main(String[] args) {
        System.out.println("=== Filesystem Agents Example ===");
        System.out.println("Testing: setting_sources=['project'] with .claude/agents/test-agent.md");
        System.out.println();

        // Use the SDK repo directory which has .claude/agents/test-agent.md
        Path sdkDir = Path.of(System.getProperty("user.dir"));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .settingSources(List.of(SettingSource.PROJECT))
                .cwd(sdkDir)
                .build();

        List<String> messageTypes = new ArrayList<>();
        List<String> agentsFound = new ArrayList<>();

        try (ClaudeSDKClient client = new ClaudeSDKClient(options)) {
            client.connect();
            client.query("Say hello in exactly 3 words");

            for (Message msg : client.receiveResponse()) {
                messageTypes.add(msg.getClass().getSimpleName());

                if (msg instanceof SystemMessage system && "init".equals(system.subtype())) {
                    agentsFound = extractAgents(system);
                    System.out.println("Init message received. Agents loaded: " + agentsFound);
                } else if (msg instanceof AssistantMessage assistant) {
                    System.out.println("Assistant: " + assistant.getTextContent());
                } else if (msg instanceof ResultMessage result) {
                    System.out.printf("Result: subtype=%s, cost=$%.4f%n",
                            result.subtype(),
                            result.totalCostUsd() != null ? result.totalCostUsd() : 0.0);
                }
            }
        }

        System.out.println();
        System.out.println("=== Summary ===");
        System.out.println("Message types received: " + messageTypes);
        System.out.println("Total messages: " + messageTypes.size());

        // Validate the results
        boolean hasInit = messageTypes.contains("SystemMessage");
        boolean hasAssistant = messageTypes.contains("AssistantMessage");
        boolean hasResult = messageTypes.contains("ResultMessage");
        boolean hasTestAgent = agentsFound.contains("test-agent");

        System.out.println();
        if (hasInit && hasAssistant && hasResult) {
            System.out.println("SUCCESS: Received full response (init, assistant, result)");
        } else {
            System.out.println("FAILURE: Did not receive full response");
            System.out.println("  - Init: " + hasInit);
            System.out.println("  - Assistant: " + hasAssistant);
            System.out.println("  - Result: " + hasResult);
        }

        if (hasTestAgent) {
            System.out.println("SUCCESS: test-agent was loaded from filesystem");
        } else {
            System.out.println("WARNING: test-agent was NOT loaded (may not exist in .claude/agents/)");
        }
    }

    /**
     * Extract agent names from system message init data.
     */
    @SuppressWarnings("unchecked")
    private static List<String> extractAgents(SystemMessage msg) {
        if (!"init".equals(msg.subtype()) || msg.data() == null) {
            return List.of();
        }

        Object agentsObj = msg.data().get("agents");
        if (!(agentsObj instanceof List)) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        for (Object a : (List<Object>) agentsObj) {
            if (a instanceof String) {
                result.add((String) a);
            } else if (a instanceof java.util.Map) {
                Object name = ((java.util.Map<?, ?>) a).get("name");
                if (name instanceof String) {
                    result.add((String) name);
                }
            }
        }
        return result;
    }

}
