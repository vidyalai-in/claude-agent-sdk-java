package in.vidyalai.claude.sdk.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.config.AIModel;
import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.config.SandboxIgnoreViolations;
import in.vidyalai.claude.sdk.types.config.SandboxNetworkConfig;
import in.vidyalai.claude.sdk.types.config.SandboxSettings;
import in.vidyalai.claude.sdk.types.config.SdkBeta;
import in.vidyalai.claude.sdk.types.config.SettingSource;
import in.vidyalai.claude.sdk.types.config.SystemPromptPreset;
import in.vidyalai.claude.sdk.types.config.ThinkingConfigAdaptive;
import in.vidyalai.claude.sdk.types.config.ThinkingConfigDisabled;
import in.vidyalai.claude.sdk.types.config.ThinkingConfigEnabled;
import in.vidyalai.claude.sdk.types.config.ToolsPreset;
import in.vidyalai.claude.sdk.types.mcp.McpHttpServerConfig;
import in.vidyalai.claude.sdk.types.mcp.McpServerConfig;
import in.vidyalai.claude.sdk.types.mcp.McpSseServerConfig;
import in.vidyalai.claude.sdk.types.mcp.McpStdioServerConfig;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;

/**
 * Tests for SubprocessCLITransport.
 * Note: buildCommand is private, so we test via options validation instead.
 */
class SubprocessCLITransportTest {

    @Test
    void testOptionsWithSystemPromptString() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .systemPrompt("You are a helpful assistant")
                .build();

        assertThat(options.systemPrompt()).isEqualTo("You are a helpful assistant");
    }

    @Test
    void testOptionsWithSystemPromptPreset() {
        SystemPromptPreset preset = SystemPromptPreset.claudeCode();
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .systemPrompt(preset)
                .build();

        assertThat(options.systemPrompt()).isEqualTo(preset);
    }

    @Test
    void testOptionsWithTools() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .tools(List.of("Bash", "Read", "Write"))
                .build();

        assertThat(options.tools()).isEqualTo(List.of("Bash", "Read", "Write"));
    }

    @Test
    void testOptionsWithToolsPreset() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .tools(ToolsPreset.claudeCode())
                .build();

        assertThat(options.tools()).isInstanceOf(ToolsPreset.class);
    }

    @Test
    void testOptionsWithAllowedTools() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .allowedTools(List.of("Bash", "Read"))
                .build();

        assertThat(options.allowedTools()).containsExactly("Bash", "Read");
    }

    @Test
    void testOptionsWithDisallowedTools() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .disallowedTools(List.of("Write", "Edit"))
                .build();

        assertThat(options.disallowedTools()).containsExactly("Write", "Edit");
    }

    @Test
    void testOptionsWithModel() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .model("claude-sonnet-4-5")
                .build();

        assertThat(options.model()).isEqualTo("claude-sonnet-4-5");
    }

    @Test
    void testOptionsWithFallbackModel() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .fallbackModel("claude-haiku-3-5")
                .build();

        assertThat(options.fallbackModel()).isEqualTo("claude-haiku-3-5");
    }

    @Test
    void testOptionsWithPermissionMode() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
                .build();

        assertThat(options.permissionMode()).isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
    }

    @Test
    void testOptionsWithAllPermissionModes() {
        for (PermissionMode mode : PermissionMode.values()) {
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .permissionMode(mode)
                    .build();

            assertThat(options.permissionMode()).isEqualTo(mode);
        }
    }

    @Test
    void testOptionsWithMaxTurns() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .maxTurns(5)
                .build();

        assertThat(options.maxTurns()).isEqualTo(5);
    }

    @Test
    void testOptionsWithMaxBudgetUsd() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .maxBudgetUsd(1.5)
                .build();

        assertThat(options.maxBudgetUsd()).isEqualTo(1.5);
    }

    @SuppressWarnings("deprecation")
    @Test
    void testOptionsWithMaxThinkingTokens() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .maxThinkingTokens(8000)
                .build();

        assertThat(options.maxThinkingTokens()).isEqualTo(8000);
    }

    @Test
    void testOptionsWithThinkingConfig() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .thinking(new ThinkingConfigAdaptive())
                .effort("high")
                .build();

        assertThat(options.thinking()).isInstanceOf(ThinkingConfigAdaptive.class);
        assertThat(options.effort()).isEqualTo("high");
    }

    @Test
    void testBuildCommandWithThinkingAdaptive() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .thinking(new ThinkingConfigAdaptive())
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        int idx = cmd.indexOf("--thinking");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        assertThat(cmd.get(idx + 1)).isEqualTo("adaptive");
        assertThat(cmd).doesNotContain("--max-thinking-tokens");
        transport.close();
    }

    @Test
    void testBuildCommandWithThinkingEnabled() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .thinking(new ThinkingConfigEnabled(5000))
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        int idx = cmd.indexOf("--max-thinking-tokens");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        assertThat(cmd.get(idx + 1)).isEqualTo("5000");
        assertThat(cmd).doesNotContain("--thinking");
        transport.close();
    }

    @Test
    void testBuildCommandWithThinkingDisabled() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .thinking(new ThinkingConfigDisabled())
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        int idx = cmd.indexOf("--thinking");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        assertThat(cmd.get(idx + 1)).isEqualTo("disabled");
        assertThat(cmd).doesNotContain("--max-thinking-tokens");
        transport.close();
    }

    @Test
    void testBuildCommandThinkingDisplay_adaptive() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .thinking(new ThinkingConfigAdaptive(
                        in.vidyalai.claude.sdk.types.config.ThinkingDisplay.SUMMARIZED))
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        int idx = cmd.indexOf("--thinking-display");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        assertThat(cmd.get(idx + 1)).isEqualTo("summarized");
        transport.close();
    }

    @Test
    void testBuildCommandThinkingDisplay_enabled() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .thinking(new ThinkingConfigEnabled(5000,
                        in.vidyalai.claude.sdk.types.config.ThinkingDisplay.OMITTED))
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        int idx = cmd.indexOf("--thinking-display");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        assertThat(cmd.get(idx + 1)).isEqualTo("omitted");
        transport.close();
    }

    @Test
    void testBuildCommandThinkingDisplay_adaptiveWithoutDisplay_omitsFlag() {
        // Adaptive without display field => no --thinking-display in argv.
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .thinking(new ThinkingConfigAdaptive())
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        assertThat(cmd).doesNotContain("--thinking-display");
        transport.close();
    }

    @Test
    void testBuildCommandThinkingDisplay_enabledEmitsBudgetAndDisplay() {
        // Enabled + display => both --max-thinking-tokens AND --thinking-display.
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .thinking(new ThinkingConfigEnabled(20000,
                        in.vidyalai.claude.sdk.types.config.ThinkingDisplay.OMITTED))
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        int budgetIdx = cmd.indexOf("--max-thinking-tokens");
        assertThat(budgetIdx).isGreaterThanOrEqualTo(0);
        assertThat(cmd.get(budgetIdx + 1)).isEqualTo("20000");
        int displayIdx = cmd.indexOf("--thinking-display");
        assertThat(displayIdx).isGreaterThanOrEqualTo(0);
        assertThat(cmd.get(displayIdx + 1)).isEqualTo("omitted");
        transport.close();
    }

    @Test
    void testBuildCommandThinkingDisplay_disabledNeverEmitted() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .thinking(new ThinkingConfigDisabled())
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        assertThat(cmd).doesNotContain("--thinking-display");
        transport.close();
    }

    @Test
    void testBuildCommandResumeAndSessionId() {
        // resume and session_id are passed as --flag=value single tokens.
        String sessionId = "8f8b1c0e-2b1e-4a3f-9c2d-5e6f7a8b9c0d";
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .resume("abc123")
                .sessionId(sessionId)
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        assertThat(cmd).contains("--resume=abc123");
        assertThat(cmd).contains("--session-id=" + sessionId);
        // Never emitted as two separate argv tokens.
        assertThat(cmd).doesNotContain("--resume");
        assertThat(cmd).doesNotContain("--session-id");
        assertThat(cmd).doesNotContain("abc123");
        assertThat(cmd).doesNotContain(sessionId);
        transport.close();
    }

    @Test
    void testBuildCommandResumeAndSessionIdDoNotInjectFlags() {
        // The CLI declares --resume with an optional value, so in the two-token
        // form a dash-leading value is parsed as a separate flag rather than as
        // the option's value. Applications that route untrusted input into
        // these options would then let an attacker inject arbitrary CLI flags.
        // The --flag=value form binds the value to the flag.
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .resume("--evil")
                .sessionId("-r")
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        assertThat(cmd).contains("--resume=--evil");
        assertThat(cmd).contains("--session-id=-r");
        // The injected values never appear as standalone argv tokens...
        assertThat(cmd).doesNotContain("--evil");
        assertThat(cmd).doesNotContain("-r");
        // ...nor do the bare flags that would let the next token detach.
        assertThat(cmd).doesNotContain("--resume");
        assertThat(cmd).doesNotContain("--session-id");
        transport.close();
    }

    @Test
    void testBuildCommandResumeSessionAtAndDropsTurn() {
        // Truncating-resume options are passed as --flag=value single tokens.
        String at = "0d78eb23-2d48-4741-b970-4ed0a3356cce";
        String drops = "ce0a8011-2c8d-40f2-86e5-d6e1b0c041c0";
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .resume("abc123")
                .forkSession(true)
                .resumeSessionAt(at)
                .resumeDropsTurn(drops)
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        assertThat(cmd).contains("--resume-session-at=" + at);
        assertThat(cmd).contains("--resume-drops-turn=" + drops);
        // Never emitted as two separate argv tokens.
        assertThat(cmd).doesNotContain("--resume-session-at");
        assertThat(cmd).doesNotContain("--resume-drops-turn");
        assertThat(cmd).doesNotContain(at);
        assertThat(cmd).doesNotContain(drops);
        transport.close();
    }

    @Test
    void testBuildCommandResumeDropsTurnOmittedByDefault() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .resume("abc123")
                .resumeSessionAt("x")
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        assertThat(cmd).contains("--resume-session-at=x");
        assertThat(cmd).noneMatch(arg -> arg.startsWith("--resume-drops-turn"));
        transport.close();
    }

    @Test
    void testBuildCommandEmptyResumeDropsTurnIsForwarded() {
        // An empty declaration must reach the CLI (which rejects it) rather
        // than being dropped here and silently disarming the guard.
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .resume("abc123")
                .resumeSessionAt("x")
                .resumeDropsTurn("")
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        assertThat(cmd).contains("--resume-drops-turn=");
        transport.close();
    }

    @Test
    void testBuildCommandTruncatingResumeDoesNotInjectFlags() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .resume("abc123")
                .resumeSessionAt("--evil")
                .resumeDropsTurn("-r")
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        assertThat(cmd).contains("--resume-session-at=--evil");
        assertThat(cmd).contains("--resume-drops-turn=-r");
        assertThat(cmd).doesNotContain("--evil");
        assertThat(cmd).doesNotContain("-r");
        transport.close();
    }

    @Test
    void testBuildCommandIncludeHookEvents_addedWhenEnabled() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .includeHookEvents(true)
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        assertThat(cmd).contains("--include-hook-events");
        transport.close();
    }

    @Test
    void testBuildCommandIncludeHookEvents_omittedWhenDisabled() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        assertThat(cmd).doesNotContain("--include-hook-events");
        transport.close();
    }

    @Test
    void testBuildCommandStrictMcpConfig_addedWhenEnabled() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .strictMcpConfig(true)
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        assertThat(cmd).contains("--strict-mcp-config");
        transport.close();
    }

    @Test
    void testBuildCommandStrictMcpConfig_omittedWhenDisabled() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        assertThat(cmd).doesNotContain("--strict-mcp-config");
        transport.close();
    }

    @Test
    void testBuildCommandEffortXhigh() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .effort("xhigh")
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        int idx = cmd.indexOf("--effort");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        assertThat(cmd.get(idx + 1)).isEqualTo("xhigh");
        transport.close();
    }

    @Test
    void testBuildCommandSessionMirrorFlag_addedWhenSessionStorePresent() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sessionStore(new in.vidyalai.claude.sdk.types.session.InMemorySessionStore())
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        assertThat(cmd).contains("--session-mirror");
        transport.close();
    }

    @Test
    void testBuildCommandSessionMirrorFlag_omittedWhenSessionStoreNull() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        assertThat(cmd).doesNotContain("--session-mirror");
        transport.close();
    }

    @SuppressWarnings("deprecation")
    @Test
    void testBuildCommandThinkingPrecedenceOverMaxThinkingTokens() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .thinking(new ThinkingConfigAdaptive())
                .maxThinkingTokens(9999)
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        List<String> cmd = transport.buildCommand();

        int idx = cmd.indexOf("--thinking");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        assertThat(cmd.get(idx + 1)).isEqualTo("adaptive");
        assertThat(cmd).doesNotContain("--max-thinking-tokens");
        transport.close();
    }

    @Test
    void testOptionsWithContinueConversation() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .continueConversation(true)
                .build();

        assertThat(options.continueConversation()).isTrue();
    }

    @Test
    void testOptionsWithResume() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .resume("session-123")
                .build();

        assertThat(options.resume()).isEqualTo("session-123");
    }

    @Test
    void testOptionsWithForkSession() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .forkSession(true)
                .build();

        assertThat(options.forkSession()).isTrue();
    }

    @Test
    void testOptionsWithAddDirs() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .addDirs(List.of(Path.of("/path/to/dir1"), Path.of("/path/to/dir2")))
                .build();

        assertThat(options.addDirs()).containsExactly(Path.of("/path/to/dir1"), Path.of("/path/to/dir2"));
    }

    @SuppressWarnings("null")
    @Test
    void testOptionsWithMcpServersMap() {
        Map<String, McpServerConfig> servers = Map.of(
                "my-server", new McpStdioServerConfig("node", List.of("server.js")));
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(servers)
                .build();

        assertThat(options.mcpServers()).isEqualTo(servers);
    }

    @Test
    void testOptionsWithMcpServersPath() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(Path.of("/path/to/mcp-config.json"))
                .build();

        assertThat(options.mcpServers()).isEqualTo(Path.of("/path/to/mcp-config.json"));
    }

    @Test
    void testOptionsWithSettings() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .settings("{\"key\": \"value\"}")
                .build();

        assertThat(options.settings()).isEqualTo("{\"key\": \"value\"}");
    }

    @Test
    void testOptionsWithSandbox() {
        SandboxSettings sandbox = new SandboxSettings(true);
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sandbox(sandbox)
                .build();

        assertThat(options.sandbox()).isEqualTo(sandbox);
    }

    @Test
    void testOptionsWithOutputFormat() {
        Map<String, Object> outputFormat = Map.of(
                "type", "json_schema",
                "schema", Map.of("type", "object"));
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .outputFormat(outputFormat)
                .build();

        assertThat(options.outputFormat()).isEqualTo(outputFormat);
    }

    @Test
    void testOptionsWithEnableFileCheckpointing() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .enableFileCheckpointing(true)
                .build();

        assertThat(options.enableFileCheckpointing()).isTrue();
    }

    @Test
    void testOptionsWithIncludePartialMessages() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .includePartialMessages(true)
                .build();

        assertThat(options.includePartialMessages()).isTrue();
    }

    @Test
    void testOptionsWithExtraArgs() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .extraArgs(Map.of("custom-flag", "custom-value"))
                .build();

        assertThat(options.extraArgs()).containsEntry("custom-flag", "custom-value");
    }

    @Test
    void testOptionsWithUser() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .user("testuser")
                .build();

        assertThat(options.user()).isEqualTo("testuser");
    }

    @Test
    void testOptionsWithPermissionPromptToolName() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .permissionPromptToolName("stdio")
                .build();

        assertThat(options.permissionPromptToolName()).isEqualTo("stdio");
    }

    @Test
    void testOptionsWithBetas() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .betas(List.of(SdkBeta.CONTEXT_1M))
                .build();

        assertThat(options.betas()).containsExactly(SdkBeta.CONTEXT_1M);
    }

    @Test
    void testOptionsWithSettingSources() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .settingSources(List.of(SettingSource.USER, SettingSource.PROJECT))
                .build();

        assertThat(options.settingSources()).containsExactly(SettingSource.USER, SettingSource.PROJECT);
    }

    @Test
    void testBuildCommandPassesSettingSourcesEvenWhenEmpty() {
        // Regression for Python SDK fix #822: empty list must produce
        // --setting-sources= so the CLI knows to disable filesystem settings.
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .settingSources(List.of())
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        try {
            List<String> cmd = transport.buildCommand();
            assertThat(cmd).contains("--setting-sources=");
        } finally {
            transport.close();
        }
    }

    @Test
    void testBuildCommandOmitsSettingSourcesWhenNullAndNoSkills() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        try {
            List<String> cmd = transport.buildCommand();
            assertThat(cmd).noneMatch(s -> s.startsWith("--setting-sources"));
        } finally {
            transport.close();
        }
    }

    @Test
    void testSkillsAllInjectsBareSkillTool() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .skillsAll()
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        try {
            SubprocessCLITransport.SkillsDefaultsResult result = transport.applySkillsDefaults();
            assertThat(result.allowedTools()).contains("Skill");
            // Default settingSources should be user/project when not explicitly set.
            assertThat(result.settingSources())
                    .containsExactly(SettingSource.USER, SettingSource.PROJECT);
        } finally {
            transport.close();
        }
    }

    @Test
    void testSkillsListInjectsScopedSkillTools() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .skills(List.of("commit", "review"))
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        try {
            SubprocessCLITransport.SkillsDefaultsResult result = transport.applySkillsDefaults();
            assertThat(result.allowedTools()).contains("Skill(commit)", "Skill(review)");
            assertThat(result.settingSources())
                    .containsExactly(SettingSource.USER, SettingSource.PROJECT);
        } finally {
            transport.close();
        }
    }

    @Test
    void testSkillsNullIsNoOp() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .allowedTools(List.of("Read"))
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        try {
            SubprocessCLITransport.SkillsDefaultsResult result = transport.applySkillsDefaults();
            assertThat(result.allowedTools()).containsExactly("Read");
            assertThat(result.settingSources()).isNull();
        } finally {
            transport.close();
        }
    }

    @Test
    void testSkillsEmptyListPreservesAllowedToolsAndDefaultsSettingSources() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .skills(List.of())
                .allowedTools(List.of("Read"))
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        try {
            SubprocessCLITransport.SkillsDefaultsResult result = transport.applySkillsDefaults();
            assertThat(result.allowedTools()).containsExactly("Read");
            assertThat(result.settingSources())
                    .containsExactly(SettingSource.USER, SettingSource.PROJECT);
        } finally {
            transport.close();
        }
    }

    @Test
    void testSkillsMergesWithExistingAllowedTools() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .allowedTools(List.of("Read", "Write"))
                .skills(List.of("pdf"))
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        try {
            List<String> cmd = transport.buildCommand();
            int idx = cmd.indexOf("--allowedTools");
            assertThat(idx).isGreaterThanOrEqualTo(0);
            assertThat(cmd.get(idx + 1)).isEqualTo("Read,Write,Skill(pdf)");
        } finally {
            transport.close();
        }
    }

    @Test
    void testSkillsDoesNotMutateOptions() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .allowedTools(List.of("Read"))
                .skills(List.of("pdf"))
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        try {
            transport.buildCommand();
            assertThat(options.allowedTools()).containsExactly("Read");
            assertThat(options.settingSources()).isNull();
        } finally {
            transport.close();
        }
    }

    @Test
    void testSkillsDoesNotDuplicateEntries() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .allowedTools(List.of("Skill(pdf)"))
                .skills(List.of("pdf"))
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        try {
            List<String> cmd = transport.buildCommand();
            int idx = cmd.indexOf("--allowedTools");
            assertThat(cmd.get(idx + 1)).isEqualTo("Skill(pdf)");
        } finally {
            transport.close();
        }
    }

    @Test
    void testSkillsAllDoesNotDuplicateBareSkillTool() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .allowedTools(List.of("Skill", "Read"))
                .skillsAll()
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        try {
            List<String> cmd = transport.buildCommand();
            int idx = cmd.indexOf("--allowedTools");
            assertThat(cmd.get(idx + 1)).isEqualTo("Skill,Read");
        } finally {
            transport.close();
        }
    }

    @Test
    void testSkillsRespectsExplicitSettingSources() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .skillsAll()
                .settingSources(List.of(SettingSource.USER))
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        try {
            SubprocessCLITransport.SkillsDefaultsResult result = transport.applySkillsDefaults();
            assertThat(result.settingSources()).containsExactly(SettingSource.USER);
        } finally {
            transport.close();
        }
    }

    // -------------------------------------------------------------------------
    // OTEL trace context propagation
    // -------------------------------------------------------------------------
    //
    // The Java implementation injects W3C trace context via reflection so
    // OpenTelemetry stays an optional dependency. The "active span"
    // injection paths are covered upstream in the Python SDK; here we cover
    // the no-OTEL/no-active-span cases that are testable without adding
    // opentelemetry-api to the test classpath.

    @Test
    void testOtelTraceContextNoopWhenOtelAbsent() {
        // OpenTelemetry is not on the test classpath, so applyEnvDefaults
        // must NOT inject TRACEPARENT/TRACESTATE on its own. Inherited env
        // values (if any) are preserved.
        ClaudeAgentOptions options = ClaudeAgentOptions.builder().build();
        java.util.Map<String, String> env = new java.util.HashMap<>();

        SubprocessCLITransport.applyEnvDefaults(options, env);

        assertThat(env).doesNotContainKey("TRACEPARENT");
        assertThat(env).doesNotContainKey("TRACESTATE");
    }

    @Test
    void testOtelTraceContextNoopPreservesInheritedEnv() {
        // Stale W3C context inherited from the parent process must pass
        // through unchanged when OTEL is absent (no active span ⇒ no scrub).
        ClaudeAgentOptions options = ClaudeAgentOptions.builder().build();
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("TRACEPARENT", "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
        env.put("TRACESTATE", "vendor=abc");

        SubprocessCLITransport.applyEnvDefaults(options, env);

        assertThat(env.get("TRACEPARENT"))
                .isEqualTo("00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
        assertThat(env.get("TRACESTATE")).isEqualTo("vendor=abc");
    }

    @Test
    void testOtelUserSuppliedEnvWinsOverPropagator() {
        // ClaudeAgentOptions.env always wins over OTEL injection. Even
        // without OTEL on the classpath, this verifies that explicit env
        // values flow through applyEnvDefaults intact.
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .env(java.util.Map.of("TRACEPARENT", "custom"))
                .build();
        java.util.Map<String, String> env = new java.util.HashMap<>();

        SubprocessCLITransport.applyEnvDefaults(options, env);

        assertThat(env.get("TRACEPARENT")).isEqualTo("custom");
    }

    @Test
    void testOptionsWithAgents() {
        AgentDefinition agent = new AgentDefinition(
                "Test agent",
                "You are a test agent",
                List.of("Bash"),
                AIModel.SONNET.getValue());
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .agents(Map.of("test-agent", agent))
                .build();

        assertThat(options.agents()).containsKey("test-agent");
    }

    @Test
    void testOptionsCombined() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .model("claude-sonnet-4-5")
                .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
                .maxTurns(10)
                .systemPrompt("Be helpful")
                .build();

        assertThat(options.model()).isEqualTo("claude-sonnet-4-5");
        assertThat(options.permissionMode()).isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(options.maxTurns()).isEqualTo(10);
        assertThat(options.systemPrompt()).isEqualTo("Be helpful");
    }

    // ==================== CLI Path Tests ====================

    @Test
    void testOptionsWithCliPath() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .cliPath(Path.of("/usr/local/bin/claude"))
                .build();

        assertThat(options.cliPath()).isEqualTo(Path.of("/usr/local/bin/claude"));
    }

    @Test
    void testOptionsWithCwd() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .cwd(Path.of("/custom/working/dir"))
                .build();

        assertThat(options.cwd()).isEqualTo(Path.of("/custom/working/dir"));
    }

    // ==================== Session Continuation Tests ====================

    @Test
    void testSessionContinuationOptions() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .continueConversation(true)
                .resume("session-123")
                .forkSession(true)
                .build();

        assertThat(options.continueConversation()).isTrue();
        assertThat(options.resume()).isEqualTo("session-123");
        assertThat(options.forkSession()).isTrue();
    }

    // ==================== System Prompt Preset Tests ====================

    @SuppressWarnings("null")
    @Test
    void testOptionsWithSystemPromptPresetAndAppend() {
        SystemPromptPreset preset = SystemPromptPreset.claudeCode("Be concise.");
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .systemPrompt(preset)
                .build();

        assertThat(options.systemPrompt()).isInstanceOf(SystemPromptPreset.class);
        SystemPromptPreset actual = (SystemPromptPreset) options.systemPrompt();
        assertThat(actual.append()).isEqualTo("Be concise.");
    }

    // ==================== Tools Configuration Tests ====================

    @Test
    void testOptionsWithToolsEmptyArray() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .tools(List.of())
                .build();

        assertThat(options.tools()).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> tools = (List<String>) options.tools();
        assertThat(tools).isEmpty();
    }

    @Test
    void testOptionsWithMultipleToolConfigs() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .tools(List.of("Bash", "Read", "Write"))
                .allowedTools(List.of("Edit"))
                .disallowedTools(List.of("Execute"))
                .build();

        assertThat(options.tools()).isEqualTo(List.of("Bash", "Read", "Write"));
        assertThat(options.allowedTools()).containsExactly("Edit");
        assertThat(options.disallowedTools()).containsExactly("Execute");
    }

    // ==================== Sandbox Configuration Tests ====================

    @SuppressWarnings("null")
    @Test
    void testOptionsWithSandboxMinimal() {
        SandboxSettings sandbox = new SandboxSettings(true);
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sandbox(sandbox)
                .build();

        assertThat(options.sandbox()).isNotNull();
        assertThat(options.sandbox().enabled()).isTrue();
    }

    @SuppressWarnings("null")
    @Test
    void testOptionsWithSandboxAndSettings() {
        SandboxSettings sandbox = new SandboxSettings(
                true,
                true,
                List.of("git", "docker"),
                null,
                null,
                null,
                false);
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .settings("{\"permissions\": {\"allow\": [\"Bash(ls:*)\"]}}")
                .sandbox(sandbox)
                .build();

        assertThat(options.settings()).contains("permissions");
        assertThat(options.sandbox()).isNotNull();
        assertThat(options.sandbox().enabled()).isTrue();
        assertThat(options.sandbox().autoAllowBashIfSandboxed()).isTrue();
        assertThat(options.sandbox().excludedCommands()).containsExactly("git", "docker");
    }

    @SuppressWarnings("null")
    @Test
    void testSandboxNetworkConfig() {
        SandboxNetworkConfig network = new SandboxNetworkConfig(
                List.of("/tmp/ssh-agent.sock"),
                false,
                true,
                8080,
                8081);
        SandboxSettings sandbox = new SandboxSettings(
                true,
                null,
                null,
                null,
                network,
                null,
                null);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sandbox(sandbox)
                .build();

        assertThat(options.sandbox().network()).isNotNull();
        assertThat(options.sandbox().network().allowUnixSockets()).containsExactly("/tmp/ssh-agent.sock");
        assertThat(options.sandbox().network().allowAllUnixSockets()).isFalse();
        assertThat(options.sandbox().network().allowLocalBinding()).isTrue();
        assertThat(options.sandbox().network().httpProxyPort()).isEqualTo(8080);
        assertThat(options.sandbox().network().socksProxyPort()).isEqualTo(8081);
    }

    @SuppressWarnings("null")
    @Test
    void testSandboxIgnoreViolationsConfig() {
        SandboxIgnoreViolations ignore = new SandboxIgnoreViolations(
                List.of("/tmp"),
                List.of("localhost", "127.0.0.1"));
        SandboxSettings sandbox = new SandboxSettings(
                true,
                null,
                null,
                null,
                null,
                ignore,
                null);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sandbox(sandbox)
                .build();

        assertThat(options.sandbox().ignoreViolations()).isNotNull();
        assertThat(options.sandbox().ignoreViolations().file()).containsExactly("/tmp");
        assertThat(options.sandbox().ignoreViolations().network()).containsExactly("localhost", "127.0.0.1");
    }

    // ==================== MCP Server Configuration Tests ====================

    @SuppressWarnings("null")
    @Test
    void testOptionsWithMcpServersStdio() {
        McpStdioServerConfig server = new McpStdioServerConfig(
                "node",
                List.of("server.js", "--port", "3000"),
                Map.of("NODE_ENV", "production"));
        Map<String, McpServerConfig> servers = Map.of("test-server", server);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(servers)
                .build();

        assertThat(options.mcpServers()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, McpServerConfig> resultServers = (Map<String, McpServerConfig>) options.mcpServers();
        assertThat(resultServers).containsKey("test-server");
        McpStdioServerConfig resultServer = (McpStdioServerConfig) resultServers.get("test-server");
        assertThat(resultServer.command()).isEqualTo("node");
        assertThat(resultServer.args()).containsExactly("server.js", "--port", "3000");
        assertThat(resultServer.env()).containsEntry("NODE_ENV", "production");
    }

    @SuppressWarnings("null")
    @Test
    void testOptionsWithMcpServersSSE() {
        McpSseServerConfig server = new McpSseServerConfig(
                "https://api.example.com/sse",
                Map.of("Authorization", "Bearer token123"));
        Map<String, McpServerConfig> servers = Map.of("sse-server", server);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(servers)
                .build();

        @SuppressWarnings("unchecked")
        Map<String, McpServerConfig> resultServers = (Map<String, McpServerConfig>) options.mcpServers();
        McpSseServerConfig resultServer = (McpSseServerConfig) resultServers.get("sse-server");
        assertThat(resultServer.url()).isEqualTo("https://api.example.com/sse");
        assertThat(resultServer.headers()).containsEntry("Authorization", "Bearer token123");
    }

    @SuppressWarnings("null")
    @Test
    void testOptionsWithMcpServersHttp() {
        McpHttpServerConfig server = new McpHttpServerConfig(
                "https://api.example.com/mcp",
                Map.of("X-API-Key", "key123"));
        Map<String, McpServerConfig> servers = Map.of("http-server", server);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(servers)
                .build();

        @SuppressWarnings("unchecked")
        Map<String, McpServerConfig> resultServers = (Map<String, McpServerConfig>) options.mcpServers();
        McpHttpServerConfig resultServer = (McpHttpServerConfig) resultServers.get("http-server");
        assertThat(resultServer.url()).isEqualTo("https://api.example.com/mcp");
        assertThat(resultServer.headers()).containsEntry("X-API-Key", "key123");
    }

    // Note: mcpServers as JSON string is not supported directly in the builder.
    // Use Path or Map<String, McpServerConfig> instead.

    // ==================== Extra Args Tests ====================

    @Test
    void testOptionsWithExtraArgsMultiple() {
        Map<String, String> extraArgs = Map.of(
                "new-flag", "value",
                "another-option", "test-value");
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .extraArgs(extraArgs)
                .build();

        assertThat(options.extraArgs()).containsEntry("new-flag", "value");
        assertThat(options.extraArgs()).containsEntry("another-option", "test-value");
    }

    @Test
    void testOptionsWithExtraArgsBooleanFlag() {
        // Boolean flags have null value in Python, in Java we use empty string
        Map<String, String> extraArgs = new java.util.HashMap<>();
        extraArgs.put("boolean-flag", "");
        extraArgs.put("another-flag", "value");

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .extraArgs(extraArgs)
                .build();

        assertThat(options.extraArgs()).containsEntry("boolean-flag", "");
        assertThat(options.extraArgs()).containsEntry("another-flag", "value");
    }

    // ==================== FGTS Environment Variable Tests ====================
    // Note: CLAUDE_CODE_ENABLE_FINE_GRAINED_TOOL_STREAMING was reverted in Python
    // SDK v0.1.49 — the SDK no longer sets this env var automatically.

    @Test
    void testIncludePartialMessages_doesNotSetFGTS() {
        // Fine-grained tool streaming is no longer auto-enabled (reverted upstream)
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .includePartialMessages(true)
                .build();

        Map<String, String> env = new java.util.HashMap<>();
        SubprocessCLITransport.applyEnvDefaults(options, env);

        assertThat(env).doesNotContainKey("CLAUDE_CODE_ENABLE_FINE_GRAINED_TOOL_STREAMING");
    }

    @Test
    void testIncludePartialMessages_false_doesNotSetFGTS() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .includePartialMessages(false)
                .build();

        Map<String, String> env = new java.util.HashMap<>();
        SubprocessCLITransport.applyEnvDefaults(options, env);

        assertThat(env).doesNotContainKey("CLAUDE_CODE_ENABLE_FINE_GRAINED_TOOL_STREAMING");
    }

    @Test
    void testCallerCanOverrideEntrypoint() {
        // Caller-supplied CLAUDE_CODE_ENTRYPOINT should survive the env merge
        Map<String, String> customEnv = Map.of("CLAUDE_CODE_ENTRYPOINT", "custom-caller");
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .env(customEnv)
                .build();

        Map<String, String> env = new java.util.HashMap<>();
        SubprocessCLITransport.applyEnvDefaults(options, env);

        // Caller's entrypoint must win over the sdk-java default
        assertThat(env.get("CLAUDE_CODE_ENTRYPOINT")).isEqualTo("custom-caller");
        // SDK version is still always set
        assertThat(env).containsKey("CLAUDE_AGENT_SDK_VERSION");
    }

    @Test
    void testDefaultEntrypointApplied() {
        // Without custom env, default entrypoint is sdk-java
        ClaudeAgentOptions options = ClaudeAgentOptions.builder().build();

        Map<String, String> env = new java.util.HashMap<>();
        SubprocessCLITransport.applyEnvDefaults(options, env);

        assertThat(env.get("CLAUDE_CODE_ENTRYPOINT")).isEqualTo("sdk-java");
    }

    @Test
    void testClaudeCodeEnvVarStripped() {
        // CLAUDECODE is stripped so spawned subprocesses don't detect a parent CC
        ClaudeAgentOptions options = ClaudeAgentOptions.builder().build();

        Map<String, String> env = new java.util.HashMap<>();
        env.put("CLAUDECODE", "1");
        env.put("OTHER_VAR", "kept");
        SubprocessCLITransport.applyEnvDefaults(options, env);

        assertThat(env).doesNotContainKey("CLAUDECODE");
        assertThat(env.get("OTHER_VAR")).isEqualTo("kept");
    }

    @Test
    void testOptionsEnvCannotOverrideSdkVersion() {
        // options.env cannot override CLAUDE_AGENT_SDK_VERSION — SDK always controls it
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .env(Map.of("CLAUDE_AGENT_SDK_VERSION", "0.0.0"))
                .build();

        Map<String, String> env = new java.util.HashMap<>();
        SubprocessCLITransport.applyEnvDefaults(options, env);

        // SDK version must be the real version, not the caller's override
        assertThat(env.get("CLAUDE_AGENT_SDK_VERSION")).isNotEqualTo("0.0.0");
        assertThat(env.get("CLAUDE_AGENT_SDK_VERSION")).isNotBlank();
    }

    @Test
    void testMaxMcpOutputTokensPassesThrough() {
        // MAX_MCP_OUTPUT_TOKENS set in options.env must reach the subprocess environment
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .env(Map.of("MAX_MCP_OUTPUT_TOKENS", "500000"))
                .build();

        Map<String, String> env = new java.util.HashMap<>();
        SubprocessCLITransport.applyEnvDefaults(options, env);

        assertThat(env.get("MAX_MCP_OUTPUT_TOKENS")).isEqualTo("500000");
    }

    @Test
    void testMaxMcpOutputTokensNotInjectedByDefault() {
        // When not set, the SDK must not inject a default — the CLI's own governs
        ClaudeAgentOptions options = ClaudeAgentOptions.builder().build();

        Map<String, String> env = new java.util.HashMap<>();
        SubprocessCLITransport.applyEnvDefaults(options, env);

        assertThat(env).doesNotContainKey("MAX_MCP_OUTPUT_TOKENS");
    }

    // ==================== Environment Variables Tests ====================

    @Test
    void testOptionsWithEnvironmentVariables() {
        Map<String, String> env = Map.of(
                "MY_VAR", "my_value",
                "ANOTHER_VAR", "another_value");
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .env(env)
                .build();

        assertThat(options.env()).containsEntry("MY_VAR", "my_value");
        assertThat(options.env()).containsEntry("ANOTHER_VAR", "another_value");
    }

    // ==================== Output Format Tests ====================

    @SuppressWarnings("null")
    @Test
    void testOptionsWithJsonSchemaOutputFormat() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "result", Map.of("type", "string"),
                        "confidence", Map.of("type", "number")),
                "required", List.of("result"));
        Map<String, Object> outputFormat = Map.of(
                "type", "json_schema",
                "schema", schema);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .outputFormat(outputFormat)
                .build();

        assertThat(options.outputFormat()).containsEntry("type", "json_schema");
        @SuppressWarnings("unchecked")
        Map<String, Object> resultSchema = (Map<String, Object>) options.outputFormat().get("schema");
        assertThat(resultSchema).containsEntry("type", "object");
    }

    // ==================== Max Buffer Size Tests ====================

    @Test
    void testOptionsWithMaxBufferSize() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .maxBufferSize(2048)
                .build();

        assertThat(options.maxBufferSize()).isEqualTo(2048);
    }

    // ==================== Full Options Combination Test ====================

    @SuppressWarnings({ "null", "deprecation" })
    @Test
    void testAllOptionsCombined() {
        SandboxSettings sandbox = new SandboxSettings(true);
        Map<String, McpServerConfig> mcpServers = Map.of(
                "test", new McpStdioServerConfig("node", List.of("server.js")));
        AgentDefinition agent = new AgentDefinition("Test", "Test prompt");

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .model("claude-sonnet-4-5")
                .fallbackModel("claude-haiku-3-5")
                .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
                .maxTurns(10)
                .maxBudgetUsd(5.0)
                .maxBufferSize(2048)
                .maxThinkingTokens(8000)
                .thinking(new ThinkingConfigDisabled())
                .effort("low")
                .systemPrompt("Be helpful and concise")
                .tools(List.of("Bash", "Read", "Write"))
                .allowedTools(List.of("Edit"))
                .disallowedTools(List.of("Execute"))
                .cwd(Path.of("/workspace"))
                .cliPath(Path.of("/usr/local/bin/claude"))
                .continueConversation(false)
                .resume("session-abc")
                .forkSession(true)
                .settings("{\"key\": \"value\"}")
                .sandbox(sandbox)
                .mcpServers(mcpServers)
                .env(Map.of("ENV_VAR", "value"))
                .extraArgs(Map.of("custom", "arg"))
                .user("testuser")
                .betas(List.of(SdkBeta.CONTEXT_1M))
                .agents(Map.of("test-agent", agent))
                .includePartialMessages(true)
                .enableFileCheckpointing(true)
                .build();

        // Verify all options
        assertThat(options.model()).isEqualTo("claude-sonnet-4-5");
        assertThat(options.fallbackModel()).isEqualTo("claude-haiku-3-5");
        assertThat(options.permissionMode()).isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(options.maxTurns()).isEqualTo(10);
        assertThat(options.maxBudgetUsd()).isEqualTo(5.0);
        assertThat(options.maxBufferSize()).isEqualTo(2048);
        assertThat(options.maxThinkingTokens()).isEqualTo(8000);
        assertThat(options.thinking()).isInstanceOf(ThinkingConfigDisabled.class);
        assertThat(options.effort()).isEqualTo("low");
        assertThat(options.systemPrompt()).isEqualTo("Be helpful and concise");
        assertThat(options.tools()).isEqualTo(List.of("Bash", "Read", "Write"));
        assertThat(options.allowedTools()).containsExactly("Edit");
        assertThat(options.disallowedTools()).containsExactly("Execute");
        assertThat(options.cwd()).isEqualTo(Path.of("/workspace"));
        assertThat(options.cliPath()).isEqualTo(Path.of("/usr/local/bin/claude"));
        assertThat(options.continueConversation()).isFalse();
        assertThat(options.resume()).isEqualTo("session-abc");
        assertThat(options.forkSession()).isTrue();
        assertThat(options.settings()).isEqualTo("{\"key\": \"value\"}");
        assertThat(options.sandbox()).isNotNull();
        assertThat(options.mcpServers()).isNotNull();
        assertThat(options.env()).containsEntry("ENV_VAR", "value");
        assertThat(options.extraArgs()).containsEntry("custom", "arg");
        assertThat(options.user()).isEqualTo("testuser");
        assertThat(options.betas()).containsExactly(SdkBeta.CONTEXT_1M);
        assertThat(options.agents()).containsKey("test-agent");
        assertThat(options.includePartialMessages()).isTrue();
        assertThat(options.enableFileCheckpointing()).isTrue();
    }

    // ------------------------------------------------------------------
    // Stderr callback isolation (Python parity: #932)
    // ------------------------------------------------------------------

    @Test
    void testStderrCallbackRaiseDoesNotTerminateLoop() {
        // Regression for Python SDK issue #929: a raise from ``options.stderr``
        // must not kill the read loop. Previously the outer catch caught it,
        // exited the loop, and silently dropped every subsequent stderr line
        // for the rest of the session.
        List<String> received = new ArrayList<>();
        Consumer<String> stderrCb = line -> {
            received.add(line);
            if (received.size() == 1) {
                throw new RuntimeException("simulated handler failure");
            }
        };

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .cliPath(Path.of("/usr/bin/claude"))
                .stderrCallback(stderrCb)
                .build();

        SubprocessCLITransport transport = new SubprocessCLITransport(options);

        BufferedReader fakeStderr = new BufferedReader(new StringReader("line 1\nline 2\nline 3\n"));
        transport.handleStderr(fakeStderr);

        // All three lines must be delivered despite the first raise.
        assertThat(received).containsExactly("line 1", "line 2", "line 3");
        transport.close();
    }

}
