package examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.config.SettingSource;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.hook.input.PreToolUseHookInput;
import in.vidyalai.claude.sdk.types.hook.output.HookOutput;
import in.vidyalai.claude.sdk.types.hook.output.PreToolUseHookSpecificOutput;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.permission.PermissionDecision;

/**
 * Example: a one-shot query whose hooks keep working after a background
 * subagent finishes.
 *
 * <p>
 * A {@code ClaudeSDK.query(...)} with hooks, a {@code canUseTool} callback or
 * SDK MCP servers holds stdin open so the CLI can ask the SDK questions while
 * it works. A {@code result} only ends a <i>turn</i>: when a background
 * subagent finishes, its completion wakes the parent for a follow-up turn, and
 * that turn's hook requests need stdin too. The SDK keeps stdin open until the
 * CLI reports the session {@code idle} (it asks for those
 * {@code session_state_changed} frames itself and keeps them out of your
 * stream), bounded between turns by {@code CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS}.
 *
 * <p>
 * Here the parent launches a background subagent, ends its turn, and — when
 * woken by the subagent's completion — writes the subagent's reply to a file.
 * A {@code PreToolUse} hook gates that {@code Write}; the file only appears if
 * the hook was still being served after the first result.
 *
 * <p>
 * The SDK asks for session state with {@code CLAUDE_CODE_SDK_READS_SESSION_STATE}.
 * A CLI that does not honor it yet (2.1.283 does not) sends none, and stdin
 * then closes at the first result, so whether the write lands depends on
 * timing. Opting in yourself with {@code CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1}
 * (in the environment or {@code options.env}) makes the CLI report state today;
 * the SDK then ends the run at {@code idle} and passes the frames through.
 *
 * <p>
 * Usage:
 * mvn exec:java -Dexec.mainClass="examples.BackgroundAgentHooksExample" -pl examples
 */
public class BackgroundAgentHooksExample {

    public static void main(String[] args) throws IOException {
        Path cwd = Files.createTempDirectory("background-agent-hooks-");
        Path target = cwd.resolve("out.txt");
        List<String> asked = new CopyOnWriteArrayList<>();

        HookMatcher.HookCallback allowAndRecord = (input, context) -> {
            asked.add(((PreToolUseHookInput) input).toolName());
            return CompletableFuture.completedFuture(HookOutput.builder()
                    .hookSpecificOutput(new PreToolUseHookSpecificOutput(
                            PermissionDecision.ALLOW, null, null, null))
                    .build());
        };

        AgentDefinition worker = new AgentDefinition(
                "Answers with one word.",
                "Reply with the single word DONE and nothing else.",
                List.of(), null, "haiku", null, null, null, null, null, null, null, null);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .cwd(cwd)
                .model("haiku")
                .settingSources(List.<SettingSource>of())
                .strictMcpConfig(true)
                .systemPrompt("Be terse. Follow the user's steps exactly.")
                .tools(List.of("Agent", "Write"))
                .agents(Map.of("worker", worker))
                .hooks(Map.of(HookEvent.PRE_TOOL_USE,
                        List.of(new HookMatcher("Write", List.of(allowAndRecord)))))
                .build();

        String prompt = "Step 1: call the Agent tool once with subagent_type `worker`, "
                + "description `say done`, prompt `Say DONE.` and run_in_background true, "
                + "then end your turn right away with the single word LAUNCHED. Do not wait "
                + "for it. "
                + "Step 2: when you are told the subagent finished, use the Write tool to "
                + "write its reply to " + target + ", then answer FINISHED.";

        int results = 0;
        for (Message message : ClaudeSDK.query(prompt, options)) {
            if (message instanceof AssistantMessage assistant && !assistant.getTextContent().isBlank()) {
                System.out.println("Claude: " + assistant.getTextContent().strip());
            } else if (message instanceof ResultMessage result) {
                results++;
                System.out.println("-- result " + results + ": " + result.subtype());
            }
        }

        System.out.println();
        System.out.println("Turns completed: " + results);
        System.out.println("Hook asked about: " + asked);
        System.out.println(Files.exists(target)
                ? "Follow-up turn was served: " + target + " = " + Files.readString(target).strip()
                : "The follow-up turn's write did not happen. A CLI that does not honor "
                        + "CLAUDE_CODE_SDK_READS_SESSION_STATE sends no session state, so stdin closes at the "
                        + "first result; set CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1 to opt in yourself.");
    }
}
