package examples;

import java.util.List;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.TextBlock;

/**
 * Example demonstrating the {@code skills} option on
 * {@link ClaudeAgentOptions}.
 *
 * <p>
 * Skills are reusable capability bundles installed as
 * {@code .claude/skills/<name>/SKILL.md}. The {@code skills} option is the one
 * place to turn skills on for an SDK session — the SDK auto-injects the
 * matching {@code Skill(name)} entries into {@code allowedTools} and defaults
 * {@code settingSources} to user/project so the CLI discovers installed skills
 * without extra wiring.
 * </p>
 *
 * <p>
 * Three modes are supported:
 * </p>
 * <ul>
 * <li>{@code .skillsAll()} — enable every discovered skill</li>
 * <li>{@code .skills(List.of("name", ...))} — enable only the listed skills</li>
 * <li>{@code .skills(List.of())} — suppress every skill from the listing</li>
 * </ul>
 *
 * <p>
 * <b>Note:</b> The {@code skills} option is a context filter, not a sandbox.
 * Unlisted skills are hidden from the model's listing and cannot be invoked via
 * the {@code Skill} tool, but their files remain on disk — a session with
 * {@code Read} or {@code Bash} can still access {@code .claude/skills/**}
 * directly.
 * </p>
 */
public class SkillsExample {

    public static void main(String[] args) {
        System.out.println("=== Skills Option Example ===\n");

        enableAllSkills();
        enableSpecificSkills();
        disableAllSkills();
    }

    static void enableAllSkills() {
        System.out.println("--- Mode: all skills ---");

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .skillsAll()
                .maxTurns(1)
                .build();

        runQuery("List the skills you have available.", options);
    }

    static void enableSpecificSkills() {
        System.out.println("--- Mode: explicit allowlist ---");

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .skills(List.of("commit", "review"))
                .maxTurns(1)
                .build();

        runQuery("Which skills can you invoke?", options);
    }

    static void disableAllSkills() {
        System.out.println("--- Mode: empty list (suppress all skills) ---");

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .skills(List.of())
                .maxTurns(1)
                .build();

        runQuery("Do you have any skills available?", options);
    }

    private static void runQuery(String prompt, ClaudeAgentOptions options) {
        try {
            List<Message> messages = ClaudeSDK.query(prompt, options);
            for (Message msg : messages) {
                if (msg instanceof AssistantMessage assistant) {
                    assistant.content().forEach(block -> {
                        if (block instanceof TextBlock text) {
                            System.out.println(text.text());
                        }
                    });
                }
            }
        } catch (Exception e) {
            System.err.println("Query failed: " + e.getMessage());
        }
        System.out.println();
    }

}
