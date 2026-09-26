package examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.types.config.SettingSource;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;

/**
 * Example demonstrating {@code verbatimPrompts}.
 *
 * <p>
 * Claude Code expands an {@code @/absolute/path} token in prompt text into the
 * contents of that file — outside the working directory and without a tool
 * call — and dispatches a leading {@code /command} as a slash command. That is
 * what you want for text a user typed, and not for text your application
 * assembled from somewhere else (earlier turns, tool results, third-party
 * content): an {@code @/path} inside it would make Claude Code read a local
 * file.
 *
 * <p>
 * {@code verbatimPrompts(true)} marks every prompt the SDK sends
 * {@code client_composed}, so it is delivered exactly as written. This example
 * plants a random marker in a temp file outside the working directory,
 * mentions the file with {@code @}, and gives the model no tools: the only way
 * it can learn the marker is the expansion. It then asks three times — through
 * {@code ClaudeSDK.query} with the option off, then on, and through
 * {@code ClaudeSDKClient} with it on — and reports whether the marker leaked.
 *
 * <p>
 * Requires Claude Code 2.1.248 or later; an older CLI ignores the option and
 * the SDK logs a warning.
 *
 * <p>
 * Usage:
 * mvn exec:java -Dexec.mainClass="examples.VerbatimPromptsExample" -pl examples
 */
public class VerbatimPromptsExample {

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("verbatim-prompts-");
        String marker = "MARKER-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path secret = Files.createDirectories(root.resolve("outside")).resolve("secret.txt");
        Files.writeString(secret, "The marker is " + marker + ".\n");
        Path cwd = Files.createDirectories(root.resolve("cwd"));

        String prompt = "What marker does this file contain? Reply with only the marker, "
                + "or UNKNOWN if you cannot see it. @" + secret;

        System.out.println("=== Default: @path is expanded ===");
        report(marker, viaQuery(prompt, options(cwd, false)));

        System.out.println();
        System.out.println("=== verbatimPrompts(true) via ClaudeSDK.query ===");
        report(marker, viaQuery(prompt, options(cwd, true)));

        System.out.println();
        System.out.println("=== verbatimPrompts(true) via ClaudeSDKClient ===");
        report(marker, viaClient(prompt, options(cwd, true)));
    }

    private static ClaudeAgentOptions options(Path cwd, boolean verbatimPrompts) {
        return ClaudeAgentOptions.builder()
                .verbatimPrompts(verbatimPrompts)
                .cwd(cwd)
                .model("haiku")
                // No tools: the only way the model can learn the marker is the
                // prompt's @-mention expansion, never a Read call.
                .tools(List.of())
                .settingSources(List.<SettingSource>of())
                .strictMcpConfig(true)
                .maxTurns(1)
                .build();
    }

    private static String viaQuery(String prompt, ClaudeAgentOptions options) {
        StringBuilder reply = new StringBuilder();
        for (Message message : ClaudeSDK.query(prompt, options)) {
            if (message instanceof AssistantMessage assistant) {
                reply.append(assistant.getTextContent());
            }
        }
        return reply.toString();
    }

    private static String viaClient(String prompt, ClaudeAgentOptions options) {
        StringBuilder reply = new StringBuilder();
        try (ClaudeSDKClient client = new ClaudeSDKClient(options)) {
            client.connect();
            client.query(prompt);
            for (Message message : (Iterable<Message>) client.receiveResponse()::iterator) {
                if (message instanceof AssistantMessage assistant) {
                    reply.append(assistant.getTextContent());
                }
            }
        }
        return reply.toString();
    }

    private static void report(String marker, String reply) {
        System.out.println("Claude: " + reply.strip());
        System.out.println(reply.contains(marker)
                ? "-> the file was read into the prompt"
                : "-> the prompt was delivered as written; the file stayed unread");
    }
}
