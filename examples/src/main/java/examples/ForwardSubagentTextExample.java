package examples;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.ContentBlock;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.message.TextBlock;
import in.vidyalai.claude.sdk.types.message.ThinkingBlock;
import in.vidyalai.claude.sdk.types.message.ToolUseBlock;

/**
 * Example demonstrating {@code forwardSubagentText}.
 *
 * <p>
 * A subagent spawned through the Agent tool runs its own conversation. By
 * default the parent stream only carries that subagent's {@code tool_use} /
 * {@code tool_result} blocks — enough for a progress heartbeat, but not enough
 * to render what the subagent actually said. They arrive as
 * {@link AssistantMessage} / {@code UserMessage} objects whose
 * {@code parentToolUseId} is the id of the Agent {@code tool_use} block that
 * spawned the subagent, which is how you attribute them.
 *
 * <p>
 * With {@code forwardSubagentText(true)} the subagent's text and thinking
 * blocks are forwarded the same way, so a UI can show the full nested
 * transcript. The option is sent to the CLI on the {@code initialize} control
 * request; older CLIs ignore it.
 *
 * <p>
 * This example runs the same prompt twice — once with the option off, once with
 * it on — and reports what reached the parent stream each time.
 *
 * <p>
 * Usage:
 * mvn exec:java -Dexec.mainClass="examples.ForwardSubagentTextExample" -pl examples
 */
public class ForwardSubagentTextExample {

    /** The Agent tool is exposed under either name depending on CLI version. */
    private static final Set<String> AGENT_TOOL_NAMES = Set.of("Agent", "Task");

    private static final String PROMPT = """
            Use the Agent tool exactly once with subagent_type 'greeter', prompt \
            'say hi', and run_in_background set to false. Then reply with the \
            single word DONE.""";

    public static void main(String[] args) {
        System.out.println("=== Default: subagent text stays out of the stream ===");
        run(false);

        System.out.println();
        System.out.println("=== forwardSubagentText(true): full nested transcript ===");
        run(true);
    }

    private static ClaudeAgentOptions options(boolean forwardSubagentText) {
        AgentDefinition greeter = new AgentDefinition(
                "Replies with a short greeting. Use for greeting tasks.",
                "Reply with one short friendly sentence. Do not use any tools.",
                // Foreground, so the run exercises the synchronous subagent
                // path where text forwarding is opt-in.
                null, null, "haiku", null, null, null, null, null, false, null, null);

        return ClaudeAgentOptions.builder()
                .forwardSubagentText(forwardSubagentText)
                .agents(Map.of("greeter", greeter))
                .allowedTools(List.copyOf(AGENT_TOOL_NAMES))
                .maxTurns(4)
                .build();
    }

    private static void run(boolean forwardSubagentText) {
        Set<String> agentToolUseIds = new HashSet<>();
        int forwardedText = 0;
        int forwardedOther = 0;

        for (Message msg : ClaudeSDK.query(PROMPT, options(forwardSubagentText))) {
            if (msg instanceof AssistantMessage assistant) {
                if (assistant.parentToolUseId() == null) {
                    // A top-level turn: note the Agent tool_use ids so the
                    // forwarded messages below can be attributed to them.
                    for (ContentBlock block : assistant.content()) {
                        if (block instanceof ToolUseBlock toolUse
                                && AGENT_TOOL_NAMES.contains(toolUse.name())) {
                            agentToolUseIds.add(toolUse.id());
                        }
                    }
                    continue;
                }

                // Forwarded from a subagent: parentToolUseId identifies which.
                String parent = assistant.parentToolUseId();
                String origin = agentToolUseIds.contains(parent) ? parent : parent + " (unknown)";
                for (ContentBlock block : assistant.content()) {
                    if (block instanceof TextBlock text) {
                        forwardedText++;
                        System.out.println("  [subagent " + origin + "] " + text.text());
                    } else if (block instanceof ThinkingBlock) {
                        forwardedText++;
                        System.out.println("  [subagent " + origin + "] (thinking)");
                    } else {
                        forwardedOther++;
                    }
                }
            } else if (msg instanceof ResultMessage result) {
                System.out.println("Result: " + result.subtype());
            }
        }

        System.out.println("Agent tool calls: " + agentToolUseIds.size());
        System.out.println("Forwarded text/thinking blocks: " + forwardedText
                + (forwardSubagentText ? "" : " (expected 0 by default)"));
        System.out.println("Forwarded tool_use/tool_result blocks: " + forwardedOther
                // These are forwarded whatever the option says — the greeter
                // is told not to use tools, so it produces none to forward.
                + (forwardedOther == 0 ? " (the greeter uses no tools)" : ""));
    }
}
