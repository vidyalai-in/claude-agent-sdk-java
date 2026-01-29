package examples;

import java.util.List;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.config.ToolsPreset;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.SystemMessage;

/**
 * Example demonstrating the tools option and verifying tools in system message.
 * <p>
 * Shows three ways to configure tools:
 * <ul>
 * <li>Array of specific tool names</li>
 * <li>Empty array to disable all tools</li>
 * <li>Preset configuration for all default tools</li>
 * </ul>
 */
public class ToolsConfigurationExample {

    public static void main(String[] args) {
        System.out.println("=== Tools Configuration Examples ===\n");

        // Example 1: Tools as array of specific tool names
        toolsArrayExample();

        // Example 2: Empty array disables all built-in tools
        toolsEmptyArrayExample();

        // Example 3: Tools preset (all default Claude Code tools)
        toolsPresetExample();
    }

    /**
     * Example with tools as array of specific tool names.
     */
    static void toolsArrayExample() {
        System.out.println("=== Tools Array Example ===");
        System.out.println("Setting tools=['Read', 'Glob', 'Grep']");
        System.out.println();

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .tools(List.of("Read", "Glob", "Grep"))
                .maxTurns(1)
                .build();

        List<Message> messages = ClaudeSDK.query(
                "What tools do you have available? Just list them briefly.",
                options);

        for (Message msg : messages) {
            if (msg instanceof SystemMessage system && "init".equals(system.subtype())) {
                @SuppressWarnings("unchecked")
                List<String> tools = (List<String>) system.data().get("tools");
                System.out.println("Tools from system message: " + tools);
                System.out.println();
            } else if (msg instanceof AssistantMessage assistant) {
                System.out.println("Claude: " + assistant.getTextContent());
            } else if (msg instanceof ResultMessage result) {
                if (result.totalCostUsd() != null) {
                    System.out.printf("\nCost: $%.4f\n", result.totalCostUsd());
                }
            }
        }
        System.out.println();
    }

    /**
     * Example with tools as empty array (disables all built-in tools).
     */
    static void toolsEmptyArrayExample() {
        System.out.println("=== Tools Empty Array Example ===");
        System.out.println("Setting tools=[] (disables all built-in tools)");
        System.out.println();

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .tools(List.of()) // Empty list disables all tools
                .maxTurns(1)
                .build();

        List<Message> messages = ClaudeSDK.query(
                "What tools do you have available? Just list them briefly.",
                options);

        for (Message msg : messages) {
            if (msg instanceof SystemMessage system && "init".equals(system.subtype())) {
                @SuppressWarnings("unchecked")
                List<String> tools = (List<String>) system.data().get("tools");
                System.out.println("Tools from system message: " + tools);
                System.out.println();
            } else if (msg instanceof AssistantMessage assistant) {
                System.out.println("Claude: " + assistant.getTextContent());
            } else if (msg instanceof ResultMessage result) {
                if (result.totalCostUsd() != null) {
                    System.out.printf("\nCost: $%.4f\n", result.totalCostUsd());
                }
            }
        }
        System.out.println();
    }

    /**
     * Example with tools preset (all default Claude Code tools).
     */
    static void toolsPresetExample() {
        System.out.println("=== Tools Preset Example ===");
        System.out.println("Setting tools=ToolsPreset.claudeCode()");
        System.out.println();

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .tools(ToolsPreset.claudeCode())
                .maxTurns(1)
                .build();

        List<Message> messages = ClaudeSDK.query(
                "What tools do you have available? Just list them briefly.",
                options);

        for (Message msg : messages) {
            if (msg instanceof SystemMessage system && "init".equals(system.subtype())) {
                @SuppressWarnings("unchecked")
                List<String> tools = (List<String>) system.data().get("tools");
                System.out.printf("Tools from system message (%d tools): [", tools.size());
                for (int i = 0; i < tools.size(); i++) {
                    System.out.print(tools.get(i));
                    if (i < (tools.size() - 1))
                        System.out.print(", ");
                }
                System.out.println("]");
                System.out.println();
            } else if (msg instanceof AssistantMessage assistant) {
                System.out.println("Claude: " + assistant.getTextContent());
            } else if (msg instanceof ResultMessage result) {
                if (result.totalCostUsd() != null) {
                    System.out.printf("\nCost: $%.4f\n", result.totalCostUsd());
                }
            }
        }
        System.out.println();
    }

}
