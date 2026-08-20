package examples;

import java.util.List;

import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.SDKSessionInfo;
import in.vidyalai.claude.sdk.types.message.SessionMessage;

/**
 * Example demonstrating the subagent transcript helpers.
 *
 * <p>
 * When a session spawns subagents (via the {@code Task} tool or programmatic
 * agent definitions), each subagent's transcript is written to
 * {@code ~/.claude/projects/<project>/<sessionId>/subagents/agent-<agentId>.jsonl}.
 * Use {@link ClaudeSDK#listSubagents(String)} to enumerate subagent IDs and
 * {@link ClaudeSDK#getSubagentMessages(String, String)} to read the
 * conversation chain.
 * </p>
 *
 * <p>
 * Each returned {@link SessionMessage} is attributed to the Agent
 * {@code tool_use} in the parent session that spawned the subagent, via
 * {@code parentToolUseId} — recovered from the {@code agent-<id>.meta.json}
 * sidecar next to the transcript, since the transcript lines themselves do not
 * record it. {@code parentAgentId} names the spawning subagent when one
 * subagent spawned another, and is null for subagents the main session
 * spawned. Both are null when the sidecar is missing or unusable.
 * </p>
 */
public class SubagentTranscriptExample {

    public static void main(String[] args) {
        System.out.println("=== Subagent Transcript Example ===\n");

        List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 10, false);
        if (sessions.isEmpty()) {
            System.out.println("No sessions found in ~/.claude/projects/");
            return;
        }

        for (SDKSessionInfo session : sessions) {
            List<String> agentIds = ClaudeSDK.listSubagents(session.sessionId());
            if (agentIds.isEmpty()) {
                continue;
            }

            System.out.printf("Session %s has %d subagent(s):%n",
                    session.sessionId(), agentIds.size());

            for (String agentId : agentIds) {
                List<SessionMessage> messages = ClaudeSDK.getSubagentMessages(
                        session.sessionId(), agentId);
                System.out.printf("  - agent %s: %d message(s)%n",
                        agentId, messages.size());

                // Every message in one subagent transcript shares the same
                // parents, so report them once.
                if (!messages.isEmpty()) {
                    SessionMessage first = messages.get(0);
                    System.out.printf("      spawned by tool_use: %s%n",
                            (first.parentToolUseId() != null)
                                    ? first.parentToolUseId()
                                    : "(unknown — no usable .meta.json sidecar)");
                    if (first.parentAgentId() != null) {
                        System.out.printf("      nested under agent: %s%n",
                                first.parentAgentId());
                    }
                }
            }
            System.out.println();
            return;
        }

        System.out.println("No sessions with subagent transcripts were found.");
    }

}
