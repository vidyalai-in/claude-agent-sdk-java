package in.vidyalai.claude.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.config.EffortLevel;
import in.vidyalai.claude.sdk.types.config.SandboxSettings;
import in.vidyalai.claude.sdk.types.config.SdkBeta;
import in.vidyalai.claude.sdk.types.config.ThinkingConfigEnabled;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.hook.output.HookOutput;
import in.vidyalai.claude.sdk.types.mcp.McpServerConfig;
import in.vidyalai.claude.sdk.types.mcp.McpStdioServerConfig;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;
import in.vidyalai.claude.sdk.types.permission.PermissionResultAllow;
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;

class ClaudeAgentOptionsTest {

    @Test
    void builder_defaults() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder().build();

        assertThat(options.tools()).isNull();
        assertThat(options.allowedTools()).isEmpty();
        assertThat(options.disallowedTools()).isEmpty();
        assertThat(options.systemPrompt()).isNull();
        assertThat(options.permissionMode()).isNull();
        assertThat(options.continueConversation()).isFalse();
        assertThat(options.forkSession()).isFalse();
        assertThat(options.maxTurns()).isNull();
        assertThat(options.maxBudgetUsd()).isNull();
        assertThat(options.model()).isNull();
        assertThat(options.betas()).isEmpty();
        assertThat(options.cwd()).isNull();
        assertThat(options.cliPath()).isNull();
        assertThat(options.env()).isEmpty();
        assertThat(options.extraArgs()).isEmpty();
        assertThat(options.includePartialMessages()).isFalse();
        assertThat(options.includeHookEvents()).isFalse();
        assertThat(options.strictMcpConfig()).isFalse();
        assertThat(options.enableFileCheckpointing()).isFalse();
    }

    @Test
    void builder_includeHookEventsAndStrictMcpConfig() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .includeHookEvents(true)
                .strictMcpConfig(true)
                .effort("xhigh")
                .build();

        assertThat(options.includeHookEvents()).isTrue();
        assertThat(options.strictMcpConfig()).isTrue();
        assertThat(options.effort()).isEqualTo("xhigh");

        // toBuilder round-trips the new fields
        ClaudeAgentOptions roundTripped = options.toBuilder().build();
        assertThat(roundTripped.includeHookEvents()).isTrue();
        assertThat(roundTripped.strictMcpConfig()).isTrue();
        assertThat(roundTripped.effort()).isEqualTo("xhigh");
    }

    @SuppressWarnings("deprecation")
    @Test
    void builder_withAllOptions() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .tools(List.of("Bash", "Read"))
                .allowedTools(List.of("Write"))
                .disallowedTools(List.of("Execute"))
                .systemPrompt("You are a helpful assistant")
                .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
                .continueConversation(true)
                .resume("session-123")
                .forkSession(true)
                .maxTurns(5)
                .maxBudgetUsd(1.0)
                .maxBufferSize(2048)
                .maxThinkingTokens(8000)
                .thinking(new ThinkingConfigEnabled(10_000))
                .effort("medium")
                .model("claude-sonnet-4-5")
                .fallbackModel("claude-haiku-3-5")
                .betas(List.of(SdkBeta.CONTEXT_1M))
                .cwd(Path.of("/tmp"))
                .cliPath(Path.of("/usr/local/bin/claude"))
                .settings("{\"key\": \"value\"}")
                .addDirs(List.of(Path.of("/home")))
                .env(Map.of("KEY", "VALUE"))
                .extraArgs(Map.of("debug", "true"))
                .user("testuser")
                .includePartialMessages(true)
                .enableFileCheckpointing(true)
                .build();

        assertThat(options.tools()).isEqualTo(List.of("Bash", "Read"));
        assertThat(options.allowedTools()).containsExactly("Write");
        assertThat(options.disallowedTools()).containsExactly("Execute");
        assertThat(options.systemPrompt()).isEqualTo("You are a helpful assistant");
        assertThat(options.permissionMode()).isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(options.continueConversation()).isTrue();
        assertThat(options.resume()).isEqualTo("session-123");
        assertThat(options.forkSession()).isTrue();
        assertThat(options.maxTurns()).isEqualTo(5);
        assertThat(options.maxBudgetUsd()).isEqualTo(1.0);
        assertThat(options.maxBufferSize()).isEqualTo(2048);
        assertThat(options.maxThinkingTokens()).isEqualTo(8000);
        assertThat(options.thinking()).isInstanceOf(ThinkingConfigEnabled.class);
        assertThat(options.effort()).isEqualTo("medium");
        assertThat(options.model()).isEqualTo("claude-sonnet-4-5");
        assertThat(options.fallbackModel()).isEqualTo("claude-haiku-3-5");
        assertThat(options.betas()).containsExactly(SdkBeta.CONTEXT_1M);
        assertThat(options.cwd()).isEqualTo(Path.of("/tmp"));
        assertThat(options.cliPath()).isEqualTo(Path.of("/usr/local/bin/claude"));
        assertThat(options.settings()).isEqualTo("{\"key\": \"value\"}");
        assertThat(options.addDirs()).containsExactly(Path.of("/home"));
        assertThat(options.env()).containsEntry("KEY", "VALUE");
        assertThat(options.extraArgs()).containsEntry("debug", "true");
        assertThat(options.user()).isEqualTo("testuser");
        assertThat(options.includePartialMessages()).isTrue();
        assertThat(options.enableFileCheckpointing()).isTrue();
    }

    @Test
    void builder_withCanUseTool() {
        ClaudeAgentOptions.CanUseTool callback = (toolName, input, context) -> CompletableFuture
                .completedFuture(new PermissionResultAllow());

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .canUseTool(callback)
                .build();

        assertThat(options.canUseTool()).isNotNull();
    }

    @SuppressWarnings("null")
    @Test
    void builder_withHooks() {
        HookMatcher matcher = new HookMatcher(
                "Bash",
                List.of((input, context) -> CompletableFuture.completedFuture(HookOutput.empty())));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .hooks(Map.of(HookEvent.PRE_TOOL_USE, List.of(matcher)))
                .build();

        assertThat(options.hooks()).containsKey(HookEvent.PRE_TOOL_USE);
        assertThat(options.hooks().get(HookEvent.PRE_TOOL_USE)).hasSize(1);
    }

    @SuppressWarnings("null")
    @Test
    void builder_withAgents() {
        AgentDefinition agent = new AgentDefinition("Test", "Test prompt");

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .agents(Map.of("test-agent", agent))
                .build();

        assertThat(options.agents()).containsKey("test-agent");
        assertThat(options.agents().get("test-agent").description()).isEqualTo("Test");
    }

    @SuppressWarnings("null")
    @Test
    void builder_withSandbox() {
        SandboxSettings sandbox = new SandboxSettings(true);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sandbox(sandbox)
                .build();

        assertThat(options.sandbox()).isNotNull();
        assertThat(options.sandbox().enabled()).isTrue();
    }

    @SuppressWarnings("null")
    @Test
    void builder_withMcpServers() {
        Map<String, McpServerConfig> servers = Map.of(
                "test", new McpStdioServerConfig("node", List.of("server.js")));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .mcpServers(servers)
                .build();

        assertThat(options.mcpServers()).isNotNull();
    }

    @Test
    void builder_withPlugins() {
        ClaudeAgentOptions.SdkPluginConfig plugin = ClaudeAgentOptions.SdkPluginConfig.local("/path/to/plugin");

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .plugins(List.of(plugin))
                .build();

        assertThat(options.plugins()).hasSize(1);
        assertThat(options.plugins().get(0).type()).isEqualTo("local");
        assertThat(options.plugins().get(0).path()).isEqualTo("/path/to/plugin");
    }

    @Test
    void builder_withOutputFormat() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("result", Map.of("type", "string")));
        Map<String, Object> outputFormat = Map.of(
                "type", "json_schema",
                "schema", schema);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .outputFormat(outputFormat)
                .build();

        assertThat(options.outputFormat()).containsEntry("type", "json_schema");
    }

    @Test
    void withPermissionPromptToolName() {
        ClaudeAgentOptions original = ClaudeAgentOptions.builder()
                .maxTurns(5)
                .build();

        ClaudeAgentOptions modified = original.withPermissionPromptToolName("stdio");

        assertThat(modified.permissionPromptToolName()).isEqualTo("stdio");
        assertThat(modified.maxTurns()).isEqualTo(5); // Other options preserved
        assertThat(original.permissionPromptToolName()).isNull(); // Original unchanged
    }

    @Test
    void toBuilder() {
        ClaudeAgentOptions original = ClaudeAgentOptions.builder()
                .maxTurns(5)
                .model("claude-sonnet-4-5")
                .build();

        ClaudeAgentOptions modified = original.toBuilder()
                .maxTurns(10)
                .build();

        assertThat(modified.maxTurns()).isEqualTo(10);
        assertThat(modified.model()).isEqualTo("claude-sonnet-4-5"); // Preserved
        assertThat(original.maxTurns()).isEqualTo(5); // Original unchanged
    }

    @Test
    void truncatingResumeOptions_defaultToNull() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder().resume("s").build();
        assertThat(options.resumeSessionAt()).isNull();
        assertThat(options.resumeDropsTurn()).isNull();
    }

    @Test
    void truncatingResumeOptions_surviveToBuilderRoundTrip() {
        // The materialized-resume path rebuilds options via toBuilder(), so a
        // field missing from the copy would be silently dropped before the
        // transport ever sees it.
        ClaudeAgentOptions original = ClaudeAgentOptions.builder()
                .resume("session-1")
                .forkSession(true)
                .resumeSessionAt("entry-uuid")
                .resumeDropsTurn("prompt-uuid")
                .build();

        ClaudeAgentOptions copy = original.toBuilder().maxTurns(3).build();

        assertThat(copy.resumeSessionAt()).isEqualTo("entry-uuid");
        assertThat(copy.resumeDropsTurn()).isEqualTo("prompt-uuid");
        assertThat(copy.forkSession()).isTrue();
        assertThat(copy.resume()).isEqualTo("session-1");
        assertThat(copy.maxTurns()).isEqualTo(3);
    }

    @Test
    void resumeDropsTurn_distinguishesEmptyFromUnset() {
        // An empty declaration must survive as "" (the transport forwards it so
        // the CLI rejects it) rather than collapsing to unset.
        ClaudeAgentOptions empty = ClaudeAgentOptions.builder().resumeDropsTurn("").build();
        assertThat(empty.resumeDropsTurn()).isEmpty();
        assertThat(empty.toBuilder().build().resumeDropsTurn()).isEmpty();
    }

    @Test
    void defaults() {
        ClaudeAgentOptions defaults = ClaudeAgentOptions.defaults();
        assertThat(defaults).isNotNull();
    }

    @Test
    void immutability_lists() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .allowedTools(List.of("Bash"))
                .build();

        assertThatThrownBy(() -> options.allowedTools().add("Write"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void immutability_maps() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .env(Map.of("KEY", "VALUE"))
                .build();

        assertThatThrownBy(() -> options.env().put("NEW", "VALUE"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sessionStore_defaultsToNullAndDefaultLoadTimeout() {
        ClaudeAgentOptions defaults = ClaudeAgentOptions.defaults();
        assertThat(defaults.sessionStore()).isNull();
        assertThat(defaults.loadTimeoutMs()).isEqualTo(60_000L);
    }

    @Test
    void sessionStore_propagatedThroughBuilder() {
        InMemorySessionStore store = new InMemorySessionStore();
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sessionStore(store)
                .loadTimeoutMs(5_000L)
                .build();

        assertThat(options.sessionStore()).isSameAs(store);
        assertThat(options.loadTimeoutMs()).isEqualTo(5_000L);

        ClaudeAgentOptions copy = options.toBuilder().build();
        assertThat(copy.sessionStore()).isSameAs(store);
        assertThat(copy.loadTimeoutMs()).isEqualTo(5_000L);
    }

    @Test
    void sessionStoreFlush_defaultsToBatched() {
        ClaudeAgentOptions defaults = ClaudeAgentOptions.defaults();
        assertThat(defaults.sessionStoreFlush())
                .isEqualTo(in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode.BATCHED);
    }

    @Test
    void sessionStoreFlush_propagatedThroughBuilder() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .sessionStoreFlush(in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode.EAGER)
                .build();

        assertThat(options.sessionStoreFlush())
                .isEqualTo(in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode.EAGER);

        ClaudeAgentOptions copy = options.toBuilder().build();
        assertThat(copy.sessionStoreFlush())
                .isEqualTo(in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode.EAGER);
    }

    // ------------------------------------------------------------------
    // EffortLevel enum (Python parity: #951)
    // ------------------------------------------------------------------

    @Test
    void effortLevel_enumValues() {
        // EffortLevel is part of the public package API for downstream wrappers.
        assertThat(EffortLevel.values())
                .extracting(EffortLevel::getValue)
                .containsExactlyInAnyOrder("low", "medium", "high", "xhigh", "max");
    }

    @Test
    void effortLevel_fromValueRoundTrip() {
        for (EffortLevel level : EffortLevel.values()) {
            assertThat(EffortLevel.fromValue(level.getValue())).isEqualTo(level);
        }
    }

    @SuppressWarnings("null")
    @Test
    void effortLevel_fromValueUnknownThrows() {
        assertThatThrownBy(() -> EffortLevel.fromValue("ultra"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown effort level");
    }

    @Test
    void builder_effortLevelEnum() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .effort(EffortLevel.XHIGH)
                .build();

        assertThat(options.effort()).isEqualTo("xhigh");
    }

    @Test
    void builder_effortLevelEnumNullClears() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .effort(EffortLevel.HIGH)
                .effort((EffortLevel) null)
                .build();

        assertThat(options.effort()).isNull();
    }

}
