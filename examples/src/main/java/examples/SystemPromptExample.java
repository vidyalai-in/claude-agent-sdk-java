package examples;

import java.util.List;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.config.SystemPromptCustom;
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
 * - Snapshot off (rebuild the prompt on every request, e.g. while iterating
 *   on its wording across resumed calls)
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
        snapshotOff();
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

    /**
     * Example with {@code snapshot} off.
     *
     * <p>
     * By default Claude Code builds the system prompt on a session's first
     * request, records it, and reuses it on every later request, including
     * after the session is resumed. A changed custom prompt, or changed
     * {@code append} text on the preset, then has no effect until the session
     * is compacted or a new one starts. {@code snapshot = false} rebuilds the
     * prompt on every request instead. The same setting is available on the
     * preset via {@code SystemPromptPreset.claudeCode(...).withSnapshot(false)}.
     * Requires Claude Code CLI 2.1.257 or later.
     */
    static void snapshotOff() {
        System.out.println("=== Custom System Prompt with snapshot off ===");

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .systemPrompt(SystemPromptCustom.of("You are a release bot. Answer in one line.", false))
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
