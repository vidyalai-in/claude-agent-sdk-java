package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeAgentOptions.CanUseTool;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;
import in.vidyalai.claude.sdk.types.permission.PermissionResult;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;

/**
 * Tests for the {@code canUseTool} shadowing warning
 * ({@link CanUseToolShadow}).
 *
 * <p>Mirrors the Python SDK's {@code test_option_warnings.py} and the
 * TypeScript SDK's {@code canUseToolShadowing} tests: the warning message is
 * built when {@code canUseTool} is set but {@code allowedTools} /
 * {@code permissionMode} auto-approve tool calls before the callback would be
 * consulted.
 */
class CanUseToolShadowTest {

    private static final CanUseTool CALLBACK = (toolName, input, context) -> CompletableFuture
            .completedFuture((PermissionResult) new PermissionResultAllow());

    @Nested
    class WholeToolAllowed {

        @ParameterizedTest
        @CsvSource(value = {
                // No specifier -- a plain tool-wide allow.
                "Read | Read",
                "mcp__server__tool | mcp__server__tool",
                // Empty / lone-wildcard specifiers collapse to a tool-wide allow.
                "Read(*) | Read",
                "Read() | Read",
                "mcp__server__tool(*) | mcp__server__tool",
                // A real specifier only allows matching invocations.
                "Bash(ls:*) | ",
                "Bash(git log:*) | ",
                "Bash(*.py) | ",
                // Blank entries match no tool.
                " | ",
                // Malformed entries fall back to the whole string as a tool name
                // in the CLI, so they match nothing.
                "Bash(ls:* | ",
                "Bash(ls)x | ",
                "(foo) | ",
                // "(*)" has no tool name before the paren; "Read(*x" never closes.
                "(*) | ",
                "Read(*x | ",
        }, delimiter = '|')
        void wholeToolAllowed(String entry, String expected) {
            String trimmed = (expected == null) ? null : expected.strip();
            String want = (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
            assertThat(CanUseToolShadow.wholeToolAllowed(entry == null ? "" : entry)).isEqualTo(want);
        }
    }

    @Nested
    class GetShadowedWarning {

        @SuppressWarnings("null")
        @Test
        void bypassPermissionsMessage() {
            String message = CanUseToolShadow.getShadowedWarning(
                    PermissionMode.BYPASS_PERMISSIONS, List.of());
            assertThat(message).isNotNull()
                    .contains("bypassPermissions")
                    .contains("PreToolUse");
        }

        @SuppressWarnings("null")
        @Test
        void bareEntriesMessage() {
            String message = CanUseToolShadow.getShadowedWarning(
                    null, List.of("Read", "mcp__server__tool", "Bash(ls:*)"));
            assertThat(message).isNotNull()
                    .contains("Read, mcp__server__tool")
                    .doesNotContain("Bash(ls:*)")
                    .contains("PreToolUse")
                    .contains("settings files");
        }

        @SuppressWarnings("null")
        @Test
        void bypassPermissionsTakesPrecedenceOverBareEntries() {
            String message = CanUseToolShadow.getShadowedWarning(
                    PermissionMode.BYPASS_PERMISSIONS, List.of("Read", "Write"));
            assertThat(message).isNotNull()
                    .contains("bypassPermissions")
                    .doesNotContain("Read")
                    .doesNotContain("Write");
        }

        @SuppressWarnings("null")
        @Test
        void preservesAllowedToolsOrder() {
            String message = CanUseToolShadow.getShadowedWarning(null, List.of("Write", "Read"));
            assertThat(message).isNotNull().contains("Write, Read");
        }

        @Test
        void acceptEditsWithoutBareEntriesReturnsNull() {
            assertThat(CanUseToolShadow.getShadowedWarning(PermissionMode.ACCEPT_EDITS, List.of()))
                    .isNull();
        }

        @SuppressWarnings("null")
        @Test
        void acceptEditsStillReportsBareEntries() {
            String message = CanUseToolShadow.getShadowedWarning(
                    PermissionMode.ACCEPT_EDITS, List.of("Read"));
            assertThat(message).isNotNull()
                    .contains("Read")
                    .doesNotContain("bypassPermissions");
        }

        @SuppressWarnings("null")
        @Test
        void wildcardAndEmptySpecifiersAreWholeToolAllows() {
            String message = CanUseToolShadow.getShadowedWarning(null, List.of("Read(*)", "Write()"));
            assertThat(message).isNotNull().contains("invoked for: Read, Write.");
        }

        @SuppressWarnings("null")
        @Test
        void blankEntriesNeverReachTheMessage() {
            String message = CanUseToolShadow.getShadowedWarning(null, List.of("   ", "Read"));
            assertThat(message).isNotNull().contains("invoked for: Read.");
        }

        @SuppressWarnings("null")
        @Test
        void entriesResolvingToTheSameToolAreReportedOnce() {
            String message = CanUseToolShadow.getShadowedWarning(
                    null, List.of("Read", "Read()", "Read(*)"));
            assertThat(message).isNotNull().contains("invoked for: Read.");
        }

        @SuppressWarnings("null")
        @Test
        void dedupPreservesFirstSeenOrder() {
            String message = CanUseToolShadow.getShadowedWarning(
                    null, List.of("Write", "Read", "Write()"));
            assertThat(message).isNotNull().contains("invoked for: Write, Read.");
        }

        @Test
        void specifierAndEmptyEntriesReturnNull() {
            assertThat(CanUseToolShadow.getShadowedWarning(null, List.of(
                    "Bash(ls:*)", "Bash(git log:*)", "mcp__server__tool(param:value)", "")))
                    .isNull();
        }

        @Test
        void emptyAllowedToolsDefaultModeReturnsNull() {
            assertThat(CanUseToolShadow.getShadowedWarning(null, List.of())).isNull();
        }
    }

    @Nested
    class ShadowedWarningFor {

        @Test
        void noWarningWithoutCallback() {
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
                    .allowedTools(List.of("Read", "Bash"))
                    .build();
            assertThat(CanUseToolShadow.shadowedWarningFor(options)).isNull();
        }

        @SuppressWarnings("null")
        @Test
        void warnsWithBypass() {
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .canUseTool(CALLBACK)
                    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
                    .build();
            assertThat(CanUseToolShadow.shadowedWarningFor(options))
                    .isNotNull()
                    .contains("bypassPermissions");
        }

        @SuppressWarnings("null")
        @Test
        void warnsWithWildcardSpecifier() {
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .canUseTool(CALLBACK)
                    .allowedTools(List.of("Read(*)"))
                    .build();
            assertThat(CanUseToolShadow.shadowedWarningFor(options))
                    .isNotNull()
                    .contains("invoked for: Read");
        }

        @Test
        void noWarningForAcceptEditsWithSpecifierEntries() {
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .canUseTool(CALLBACK)
                    .permissionMode(PermissionMode.ACCEPT_EDITS)
                    .allowedTools(List.of("Bash(ls:*)"))
                    .build();
            assertThat(CanUseToolShadow.shadowedWarningFor(options)).isNull();
        }

        @SuppressWarnings("null")
        @Test
        void skillsAllShadowsTheSkillTool() {
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .canUseTool(CALLBACK)
                    .skillsAll()
                    .build();
            assertThat(CanUseToolShadow.shadowedWarningFor(options))
                    .isNotNull()
                    .contains("invoked for: Skill");
        }

        @Test
        void namedSkillsDoNotShadow() {
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .canUseTool(CALLBACK)
                    .skills(List.of("reviewer"))
                    .build();
            assertThat(CanUseToolShadow.shadowedWarningFor(options)).isNull();
        }

        @SuppressWarnings("null")
        @Test
        void skillsAllDoesNotDuplicateExplicitSkillEntry() {
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .canUseTool(CALLBACK)
                    .skillsAll()
                    .allowedTools(List.of("Skill"))
                    .build();
            assertThat(CanUseToolShadow.shadowedWarningFor(options))
                    .isNotNull()
                    .contains("invoked for: Skill.");
        }

        @SuppressWarnings("null")
        @Test
        void skillsAllLeavesCallerAllowedToolsUntouched() {
            List<String> allowed = List.of("Read");
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .canUseTool(CALLBACK)
                    .skillsAll()
                    .allowedTools(allowed)
                    .build();
            assertThat(CanUseToolShadow.shadowedWarningFor(options))
                    .isNotNull()
                    .contains("Read, Skill");
            assertThat(options.allowedTools()).containsExactly("Read");
        }
    }
}
