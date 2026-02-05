package examples;

import java.util.List;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.config.SystemPromptPreset;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;

/**
 * Example demonstrating different system_prompt configurations.
 *
 * System prompts control Claude's behavior and capabilities. This example
 * shows:
 * - No system prompt (vanilla Claude)
 * - String system prompt (custom instructions)
 * - Preset system prompt (default Claude Code)
 * - Preset with append (extend default with custom instructions)
 *
 * Usage:
 * mvn exec:java -Dexec.mainClass="examples.SystemPromptExample" -pl examples
 */
public class SystemPromptExample {

    public static void main(String[] args) {
        noSystemPrompt();
        stringSystemPrompt();
        presetSystemPrompt();
        presetWithAppend();
    }

    /**
     * Example with no system_prompt (vanilla Claude).
     */
    static void noSystemPrompt() {
        System.out.println("=== No System Prompt (Vanilla Claude) ===");

        List<Message> messages = ClaudeSDK.query("What is 2 + 2?");
        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistant) {
                System.out.println("Claude: " + assistant.getTextContent());
            }
        }
        System.out.println();
    }

    /**
     * Example with system_prompt as a string.
     */
    static void stringSystemPrompt() {
        System.out.println("=== String System Prompt ===");

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .systemPrompt("You are a pirate assistant. Respond in pirate speak.")
                .build();

        List<Message> messages = ClaudeSDK.query("What is 2 + 2?", options);
        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistant) {
                System.out.println("Claude: " + assistant.getTextContent());
            }
        }
        System.out.println();
    }

    /**
     * Example with system_prompt preset (uses default Claude Code prompt).
     */
    static void presetSystemPrompt() {
        System.out.println("=== Preset System Prompt (Default) ===");

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .systemPrompt(SystemPromptPreset.claudeCode())
                .build();

        List<Message> messages = ClaudeSDK.query("What is 2 + 2?", options);
        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistant) {
                System.out.println("Claude: " + assistant.getTextContent());
            }
        }
        System.out.println();
    }

    /**
     * Example with system_prompt preset and append.
     */
    static void presetWithAppend() {
        System.out.println("=== Preset System Prompt with Append ===");

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .systemPrompt(SystemPromptPreset.claudeCode("Always end your response with a fun fact."))
                .build();

        List<Message> messages = ClaudeSDK.query("What is 2 + 2?", options);
        for (Message msg : messages) {
            if (msg instanceof AssistantMessage assistant) {
                System.out.println("Claude: " + assistant.getTextContent());
            }
        }
        System.out.println();
    }

}
