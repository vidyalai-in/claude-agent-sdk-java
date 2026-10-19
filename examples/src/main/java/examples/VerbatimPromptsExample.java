package examples;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.types.config.SettingSource;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;

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
 * The session transcript, not the model's reply, decides whether the file was
 * read: the CLI records an expanded {@code @}-mention as a {@code file}
 * attachment. The model sees that expansion as a Read call and its result,
 * and with no tools it sometimes dismisses it as a forged tool result and
 * answers UNKNOWN, so the reply alone can understate what the CLI attached.
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
        report(marker, viaQuery(prompt, options(cwd, false)), cwd);

        System.out.println();
        System.out.println("=== verbatimPrompts(true) via ClaudeSDK.query ===");
        report(marker, viaQuery(prompt, options(cwd, true)), cwd);

        System.out.println();
        System.out.println("=== verbatimPrompts(true) via ClaudeSDKClient ===");
        report(marker, viaClient(prompt, options(cwd, true)), cwd);
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

    /** The model's reply and the run's session ID. */
    private record Run(String reply, String sessionId) {
    }

    private static Run viaQuery(String prompt, ClaudeAgentOptions options) {
        StringBuilder reply = new StringBuilder();
        String sessionId = null;
        for (Message message : ClaudeSDK.query(prompt, options)) {
            if (message instanceof AssistantMessage assistant) {
                reply.append(assistant.getTextContent());
            } else if (message instanceof ResultMessage result) {
                sessionId = result.sessionId();
            }
        }
        return new Run(reply.toString(), sessionId);
    }

    private static Run viaClient(String prompt, ClaudeAgentOptions options) {
        StringBuilder reply = new StringBuilder();
        String sessionId = null;
        try (ClaudeSDKClient client = new ClaudeSDKClient(options)) {
            client.connect();
            client.query(prompt);
            for (Message message : (Iterable<Message>) client.receiveResponse()::iterator) {
                if (message instanceof AssistantMessage assistant) {
                    reply.append(assistant.getTextContent());
                } else if (message instanceof ResultMessage result) {
                    sessionId = result.sessionId();
                }
            }
        }
        return new Run(reply.toString(), sessionId);
    }

    /**
     * The session's {@code file} attachments, as JSON text, read from its
     * transcript at
     * {@code $CLAUDE_CONFIG_DIR/projects/<project key for cwd>/<session>.jsonl}
     * ({@code CLAUDE_CONFIG_DIR} defaults to {@code ~/.claude}).
     */
    private static String attachedFiles(String sessionId, Path cwd) {
        if (sessionId == null) {
            throw new IllegalStateException("run should report a session id");
        }
        String configDir = System.getenv("CLAUDE_CONFIG_DIR");
        Path transcript = (configDir != null ? Path.of(configDir) : Path.of(System.getProperty("user.home"), ".claude"))
                .resolve("projects")
                .resolve(ClaudeSDK.projectKeyForDirectory(cwd))
                .resolve(sessionId + ".jsonl");
        if (!Files.exists(transcript)) {
            throw new IllegalStateException("no transcript for session " + sessionId + " at " + transcript);
        }
        ObjectMapper mapper = new ObjectMapper();
        StringBuilder attached = new StringBuilder();
        try {
            for (String line : Files.readAllLines(transcript)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode entry = mapper.readTree(line);
                if ("attachment".equals(entry.path("type").asText())
                        && "file".equals(entry.path("attachment").path("type").asText())) {
                    attached.append(entry.get("attachment"));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return attached.toString();
    }

    private static void report(String marker, Run run, Path cwd) {
        System.out.println("Claude: " + run.reply().strip());
        boolean attached = attachedFiles(run.sessionId(), cwd).contains(marker);
        boolean inReply = run.reply().contains(marker);
        if (attached) {
            System.out.println("-> the CLI attached the file to the session");
        } else if (inReply) {
            // Not attached, yet the reply has the marker: it reached the
            // model some other way, which this example does not expect.
            System.out.println("-> unexpected: the marker is in the reply but no file was attached");
        } else {
            System.out.println("-> the prompt was delivered as written; the file stayed unread");
        }
    }
}
