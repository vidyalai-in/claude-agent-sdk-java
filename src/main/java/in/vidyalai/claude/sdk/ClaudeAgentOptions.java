package in.vidyalai.claude.sdk;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.config.SandboxSettings;
import in.vidyalai.claude.sdk.types.config.SdkBeta;
import in.vidyalai.claude.sdk.types.config.SettingSource;
import in.vidyalai.claude.sdk.types.config.SystemPromptPreset;
import in.vidyalai.claude.sdk.types.config.ToolsPreset;
import in.vidyalai.claude.sdk.types.hook.HookEvent;
import in.vidyalai.claude.sdk.types.hook.HookMatcher;
import in.vidyalai.claude.sdk.types.mcp.McpServerConfig;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;
import in.vidyalai.claude.sdk.types.permission.PermissionResult;
import in.vidyalai.claude.sdk.types.permission.ToolPermissionContext;

/**
 * Configuration options for Claude SDK queries and clients.
 *
 * <p>
 * Use the builder pattern to construct options:
 * 
 * <pre>{@code
 * ClaudeAgentOptions options = ClaudeAgentOptions.builder()
 *         .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
 *         .maxTurns(5)
 *         .model("claude-sonnet-4-5")
 *         .build();
 * }</pre>
 */
public final class ClaudeAgentOptions {

    // Tool configuration
    @Nullable
    private final Object tools; // List<String> or ToolsPreset
    private final List<String> allowedTools;
    private final List<String> disallowedTools;

    // System prompt
    @Nullable
    private final Object systemPrompt; // String or SystemPromptPreset

    // MCP servers
    @Nullable
    private final Object mcpServers; // Map<String, McpServerConfig>, String, or Path

    // Permission settings
    @Nullable
    private final PermissionMode permissionMode;
    @Nullable
    private final String permissionPromptToolName;

    // Session management
    private final boolean continueConversation;
    @Nullable
    private final String resume;
    // When true resumed sessions will fork to a new session ID rather than
    // continuing the previous session
    private final boolean forkSession;

    // Limits
    @Nullable
    private final Integer maxTurns;
    @Nullable
    private final Double maxBudgetUsd;
    // Max bytes when buffering CLI stdout
    @Nullable
    private final Integer maxBufferSize;
    // Max tokens for thinking blocks
    @Nullable
    private final Integer maxThinkingTokens;
    // Max message queue size
    @Nullable
    private final Integer maxMsgQSize;

    // Model configuration
    @Nullable
    private final String model;
    @Nullable
    private final String fallbackModel;
    // Beta features - see https://docs.anthropic.com/en/api/beta-headers
    private final List<SdkBeta> betas;

    // Working directory and CLI
    @Nullable
    private final Path cwd;
    @Nullable
    private final Path cliPath;
    @Nullable
    private final String settings;
    private final List<Path> addDirs;

    // Environment
    private final Map<String, String> env;
    // Pass arbitrary CLI flags
    private final Map<String, String> extraArgs;

    // Tool permission callback
    @Nullable
    private final CanUseTool canUseTool;
    // Callback for stderr output from CLI
    @Nullable
    private final Consumer<String> stderrCallback;

    // Hook configurations
    @Nullable
    private final Map<HookEvent, List<HookMatcher>> hooks;

    // User identity
    @Nullable
    private final String user;

    // Partial message streaming support
    private final boolean includePartialMessages;

    // Agent definitions for custom agents
    @Nullable
    private final Map<String, AgentDefinition> agents;

    // Setting sources to load (user, project, local)
    @Nullable
    private final List<SettingSource> settingSources;

    // Sandbox configuration for bash command isolation.
    // Filesystem and network restrictions are derived from permission rules
    // (Read/Edit/WebFetch), not from these sandbox settings.
    @Nullable
    private final SandboxSettings sandbox;

    // Plugin configurations for custom plugins
    private final List<SdkPluginConfig> plugins;

    // Output format for structured outputs (matches Messages API structure)
    // Example: {"type": "json_schema", "schema": {"type": "object", "properties":
    // {...}}}
    @Nullable
    private final Map<String, Object> outputFormat;

    // Enable file checkpointing to track file changes during the session.
    // When enabled, files can be rewound to their state at any user message
    // using `ClaudeSDKClient.rewind_files()`.
    private final boolean enableFileCheckpointing;

    private ClaudeAgentOptions(Builder builder) {
        this.tools = builder.tools;
        // Default to empty lists (matching Python SDK's field(default_factory=list))
        this.allowedTools = ((builder.allowedTools != null) ? List.copyOf(builder.allowedTools) : List.of());
        this.disallowedTools = ((builder.disallowedTools != null) ? List.copyOf(builder.disallowedTools) : List.of());
        this.systemPrompt = builder.systemPrompt;
        this.mcpServers = builder.mcpServers;
        this.permissionMode = builder.permissionMode;
        this.permissionPromptToolName = builder.permissionPromptToolName;
        this.continueConversation = builder.continueConversation;
        this.resume = builder.resume;
        this.forkSession = builder.forkSession;
        this.maxTurns = builder.maxTurns;
        this.maxBudgetUsd = builder.maxBudgetUsd;
        this.maxBufferSize = builder.maxBufferSize;
        this.maxThinkingTokens = builder.maxThinkingTokens;
        this.maxMsgQSize = builder.maxMsgQSize;
        this.model = builder.model;
        this.fallbackModel = builder.fallbackModel;
        // Default to empty lists (matching Python SDK)
        this.betas = ((builder.betas != null) ? List.copyOf(builder.betas) : List.of());
        this.cwd = builder.cwd;
        this.cliPath = builder.cliPath;
        this.settings = builder.settings;
        // Default to empty list (matching Python SDK's field(default_factory=list))
        this.addDirs = ((builder.addDirs != null) ? List.copyOf(builder.addDirs) : List.of());
        // Default to empty maps (matching Python SDK)
        this.env = ((builder.env != null) ? Map.copyOf(builder.env) : Map.of());
        this.extraArgs = ((builder.extraArgs != null) ? Map.copyOf(builder.extraArgs) : Map.of());
        this.canUseTool = builder.canUseTool;
        this.stderrCallback = builder.stderrCallback;
        this.hooks = ((builder.hooks != null) ? Map.copyOf(builder.hooks) : null);
        this.user = builder.user;
        this.includePartialMessages = builder.includePartialMessages;
        this.agents = ((builder.agents != null) ? Map.copyOf(builder.agents) : null);
        this.settingSources = builder.settingSources;
        this.sandbox = builder.sandbox;
        // Default to empty list (matching Python SDK)
        this.plugins = ((builder.plugins != null) ? List.copyOf(builder.plugins) : List.of());
        this.outputFormat = builder.outputFormat;
        this.enableFileCheckpointing = builder.enableFileCheckpointing;
    }

    /**
     * Creates a new builder for ClaudeAgentOptions.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates default options.
     */
    public static ClaudeAgentOptions defaults() {
        return new Builder().build();
    }

    /**
     * Creates a copy of these options with a modified permission prompt tool name.
     */
    public ClaudeAgentOptions withPermissionPromptToolName(String toolName) {
        return toBuilder().permissionPromptToolName(toolName).build();
    }

    /**
     * Creates a builder initialized with this options' values.
     */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.tools = this.tools;
        builder.allowedTools = ((!this.allowedTools.isEmpty()) ? new ArrayList<>(this.allowedTools) : null);
        builder.disallowedTools = ((!this.disallowedTools.isEmpty()) ? new ArrayList<>(this.disallowedTools) : null);
        builder.systemPrompt = this.systemPrompt;
        builder.mcpServers = this.mcpServers;
        builder.permissionMode = this.permissionMode;
        builder.permissionPromptToolName = this.permissionPromptToolName;
        builder.continueConversation = this.continueConversation;
        builder.resume = this.resume;
        builder.forkSession = this.forkSession;
        builder.maxTurns = this.maxTurns;
        builder.maxBudgetUsd = this.maxBudgetUsd;
        builder.maxBufferSize = this.maxBufferSize;
        builder.maxThinkingTokens = this.maxThinkingTokens;
        builder.maxMsgQSize = this.maxMsgQSize;
        builder.model = this.model;
        builder.fallbackModel = this.fallbackModel;
        builder.betas = ((!this.betas.isEmpty()) ? new ArrayList<>(this.betas) : null);
        builder.cwd = this.cwd;
        builder.cliPath = this.cliPath;
        builder.settings = this.settings;
        builder.addDirs = ((!this.addDirs.isEmpty()) ? new ArrayList<>(this.addDirs) : null);
        builder.env = ((!this.env.isEmpty()) ? new HashMap<>(this.env) : null);
        builder.extraArgs = ((!this.extraArgs.isEmpty()) ? new HashMap<>(this.extraArgs) : null);
        builder.canUseTool = this.canUseTool;
        builder.stderrCallback = this.stderrCallback;
        builder.hooks = ((this.hooks != null) ? new HashMap<>(this.hooks) : null);
        builder.user = this.user;
        builder.includePartialMessages = this.includePartialMessages;
        builder.agents = ((this.agents != null) ? new HashMap<>(this.agents) : null);
        builder.settingSources = this.settingSources;
        builder.sandbox = this.sandbox;
        builder.plugins = ((!this.plugins.isEmpty()) ? new ArrayList<>(this.plugins) : null);
        builder.outputFormat = this.outputFormat;
        builder.enableFileCheckpointing = this.enableFileCheckpointing;
        return builder;
    }

    // Getters

    @Nullable
    public Object tools() {
        return tools;
    }

    public List<String> allowedTools() {
        return allowedTools;
    }

    public List<String> disallowedTools() {
        return disallowedTools;
    }

    @Nullable
    public Object systemPrompt() {
        return systemPrompt;
    }

    /**
     * Returns the MCP server configuration.
     *
     * @return MCP servers as Map, String, or Path, or null if not configured
     */
    @Nullable
    public Object mcpServers() {
        return mcpServers;
    }

    /**
     * Returns the permission mode for tool usage.
     *
     * @return the permission mode, or null if not set
     */
    @Nullable
    public PermissionMode permissionMode() {
        return permissionMode;
    }

    /**
     * Returns the tool name to use for permission prompts.
     *
     * @return the permission prompt tool name, or null if not set
     */
    @Nullable
    public String permissionPromptToolName() {
        return permissionPromptToolName;
    }

    public boolean continueConversation() {
        return continueConversation;
    }

    /**
     * Returns the session ID to resume.
     *
     * @return the session ID to resume, or null if not resuming
     */
    @Nullable
    public String resume() {
        return resume;
    }

    public boolean forkSession() {
        return forkSession;
    }

    /**
     * Returns the maximum number of turns (agent loops) to execute.
     *
     * @return the max turns limit, or null if not set
     */
    @Nullable
    public Integer maxTurns() {
        return maxTurns;
    }

    /**
     * Returns the maximum budget in USD for API calls.
     *
     * @return the max budget in USD, or null if not set
     */
    @Nullable
    public Double maxBudgetUsd() {
        return maxBudgetUsd;
    }

    /**
     * Returns the maximum bytes when buffering CLI stdout.
     *
     * @return the max buffer size in bytes, or null if not set
     */
    @Nullable
    public Integer maxBufferSize() {
        return maxBufferSize;
    }

    /**
     * Returns the maximum tokens for thinking blocks.
     *
     * @return the max thinking tokens, or null if not set
     */
    @Nullable
    public Integer maxThinkingTokens() {
        return maxThinkingTokens;
    }

    /**
     * Returns the maximum message queue size.
     *
     * @return the max message queue size, or null if not set
     */
    @Nullable
    public Integer maxMsgQSize() {
        return maxMsgQSize;
    }

    /**
     * Returns the model name to use for inference.
     *
     * @return the model name, or null if not set
     */
    @Nullable
    public String model() {
        return model;
    }

    @Nullable
    public String fallbackModel() {
        return fallbackModel;
    }

    public List<SdkBeta> betas() {
        return betas;
    }

    @Nullable
    public Path cwd() {
        return cwd;
    }

    @Nullable
    public Path cliPath() {
        return cliPath;
    }

    /**
     * Returns the settings file path or name.
     *
     * @return the settings identifier, or null if not set
     */
    @Nullable
    public String settings() {
        return settings;
    }

    public List<Path> addDirs() {
        return addDirs;
    }

    public Map<String, String> env() {
        return env;
    }

    public Map<String, String> extraArgs() {
        return extraArgs;
    }

    @Nullable
    public CanUseTool canUseTool() {
        return canUseTool;
    }

    @Nullable
    public Consumer<String> stderrCallback() {
        return stderrCallback;
    }

    /**
     * Returns the hook configurations.
     *
     * @return map of hook events to matchers, or null if not configured
     */
    @Nullable
    public Map<HookEvent, List<HookMatcher>> hooks() {
        return hooks;
    }

    @Nullable
    public String user() {
        return user;
    }

    /**
     * Returns whether to include partial messages in streaming responses.
     *
     * @return true if partial messages should be included
     */
    public boolean includePartialMessages() {
        return includePartialMessages;
    }

    @Nullable
    public Map<String, AgentDefinition> agents() {
        return agents;
    }

    @Nullable
    public List<SettingSource> settingSources() {
        return settingSources;
    }

    /**
     * Returns the sandbox configuration for bash command isolation.
     *
     * @return the sandbox settings, or null if not configured
     */
    @Nullable
    public SandboxSettings sandbox() {
        return sandbox;
    }

    /**
     * Returns the plugin configurations.
     *
     * @return list of plugin configs
     */
    public List<SdkPluginConfig> plugins() {
        return plugins;
    }

    /**
     * Returns the output format for structured outputs.
     *
     * @return the output format configuration, or null if not set
     */
    @Nullable
    public Map<String, Object> outputFormat() {
        return outputFormat;
    }

    public boolean enableFileCheckpointing() {
        return enableFileCheckpointing;
    }

    /**
     * Functional interface for tool permission callbacks.
     *
     * <p>
     * Example usage:
     * 
     * <pre>{@code
     * ClaudeAgentOptions.CanUseTool canUseTool = (toolName, input, context) -> {
     *     // context.suggestions() contains permission suggestions from CLI
     *     if (toolName.equals("Bash")) {
     *         return CompletableFuture.completedFuture(
     *                 new PermissionResultDeny("Bash not allowed", false));
     *     }
     *     return CompletableFuture.completedFuture(
     *             new PermissionResultAllow(input, null));
     * };
     * }</pre>
     */
    @FunctionalInterface
    public interface CanUseTool {

        /**
         * Called when Claude wants to use a tool and a permission decision is needed.
         *
         * @param toolName the name of the tool
         * @param input    the tool input parameters
         * @param context  additional context including permission suggestions from CLI
         * @return a future with the permission result
         */
        CompletableFuture<PermissionResult> apply(String toolName, Map<String, Object> input,
                ToolPermissionContext context);

    }

    /**
     * SDK plugin configuration.
     * Currently only local plugins are supported via the 'local' type.
     */
    public record SdkPluginConfig(String type, String path) {

        /**
         * Creates a local plugin config.
         *
         * @param path the file system path to the plugin
         * @return a new local plugin configuration
         */
        public static SdkPluginConfig local(String path) {
            return new SdkPluginConfig("local", path);
        }

    }

    /**
     * Builder for ClaudeAgentOptions.
     */
    public static final class Builder {

        @Nullable
        private Object tools;
        @Nullable
        private List<String> allowedTools;
        @Nullable
        private List<String> disallowedTools;
        @Nullable
        private Object systemPrompt;
        @Nullable
        private Object mcpServers;
        @Nullable
        private PermissionMode permissionMode;
        @Nullable
        private String permissionPromptToolName;
        private boolean continueConversation;
        @Nullable
        private String resume;
        private boolean forkSession;
        @Nullable
        private Integer maxTurns;
        @Nullable
        private Double maxBudgetUsd;
        @Nullable
        private Integer maxBufferSize;
        @Nullable
        private Integer maxThinkingTokens;
        @Nullable
        private Integer maxMsgQSize;
        @Nullable
        private String model;
        @Nullable
        private String fallbackModel;
        @Nullable
        private List<SdkBeta> betas;
        @Nullable
        private Path cwd;
        @Nullable
        private Path cliPath;
        @Nullable
        private String settings;
        @Nullable
        private List<Path> addDirs;
        @Nullable
        private Map<String, String> env;
        @Nullable
        private Map<String, String> extraArgs;
        @Nullable
        private CanUseTool canUseTool;
        @Nullable
        private Consumer<String> stderrCallback;
        @Nullable
        private Map<HookEvent, List<HookMatcher>> hooks;
        @Nullable
        private String user;
        private boolean includePartialMessages;
        @Nullable
        private Map<String, AgentDefinition> agents;
        @Nullable
        private List<SettingSource> settingSources;
        @Nullable
        private SandboxSettings sandbox;
        @Nullable
        private List<SdkPluginConfig> plugins;
        @Nullable
        private Map<String, Object> outputFormat;
        private boolean enableFileCheckpointing;

        private Builder() {
        }

        /**
         * Sets the tools configuration from a list of tool names.
         *
         * @param tools list of tool names to enable
         * @return this builder
         */
        public Builder tools(List<String> tools) {
            this.tools = tools;
            return this;
        }

        /**
         * Sets the tools configuration from a preset.
         *
         * @param preset the tools preset to use
         * @return this builder
         */
        public Builder tools(ToolsPreset preset) {
            this.tools = preset;
            return this;
        }

        /**
         * Sets the list of allowed tools.
         *
         * @param allowedTools list of tool names to allow
         * @return this builder
         */
        public Builder allowedTools(List<String> allowedTools) {
            this.allowedTools = allowedTools;
            return this;
        }

        /**
         * Sets the list of disallowed tools.
         *
         * @param disallowedTools list of tool names to disallow
         * @return this builder
         */
        public Builder disallowedTools(List<String> disallowedTools) {
            this.disallowedTools = disallowedTools;
            return this;
        }

        /**
         * Sets the system prompt from a string.
         *
         * @param systemPrompt the system prompt text
         * @return this builder
         */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /**
         * Sets the system prompt from a preset.
         *
         * @param preset the system prompt preset to use
         * @return this builder
         */
        public Builder systemPrompt(SystemPromptPreset preset) {
            this.systemPrompt = preset;
            return this;
        }

        /**
         * Sets the MCP server configuration from a map.
         *
         * @param mcpServers map of server names to configurations
         * @return this builder
         */
        public Builder mcpServers(Map<String, McpServerConfig> mcpServers) {
            this.mcpServers = mcpServers;
            return this;
        }

        /**
         * Sets the MCP server configuration from a file path.
         *
         * @param path path to MCP server config file
         * @return this builder
         */
        public Builder mcpServers(Path path) {
            this.mcpServers = path;
            return this;
        }

        /**
         * Sets the MCP server configuration from a file path.
         *
         * @param path path to MCP server config file
         * @return this builder
         */
        public Builder mcpServersPath(Path path) {
            this.mcpServers = path;
            return this;
        }

        /**
         * Sets the MCP server configuration from a JSON string.
         *
         * @param json JSON configuration string
         * @return this builder
         */
        public Builder mcpServersJson(String json) {
            this.mcpServers = json;
            return this;
        }

        /**
         * Sets the permission mode for tool usage.
         *
         * @param permissionMode the permission mode
         * @return this builder
         */
        public Builder permissionMode(PermissionMode permissionMode) {
            this.permissionMode = permissionMode;
            return this;
        }

        /**
         * Sets the tool name to use for permission prompts.
         *
         * @param permissionPromptToolName the permission prompt tool name
         * @return this builder
         */
        public Builder permissionPromptToolName(String permissionPromptToolName) {
            this.permissionPromptToolName = permissionPromptToolName;
            return this;
        }

        /**
         * Sets whether to continue an existing conversation.
         *
         * @param continueConversation true to continue conversation
         * @return this builder
         */
        public Builder continueConversation(boolean continueConversation) {
            this.continueConversation = continueConversation;
            return this;
        }

        /**
         * Sets the session ID to resume.
         *
         * @param resume the session ID to resume
         * @return this builder
         */
        public Builder resume(String resume) {
            this.resume = resume;
            return this;
        }

        /**
         * Sets whether to fork the resumed session.
         *
         * @param forkSession true to fork to a new session ID
         * @return this builder
         */
        public Builder forkSession(boolean forkSession) {
            this.forkSession = forkSession;
            return this;
        }

        /**
         * Sets the maximum number of turns to execute.
         *
         * @param maxTurns the max turns limit
         * @return this builder
         */
        public Builder maxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        /**
         * Sets the maximum budget in USD.
         *
         * @param maxBudgetUsd the max budget in USD
         * @return this builder
         */
        public Builder maxBudgetUsd(double maxBudgetUsd) {
            this.maxBudgetUsd = maxBudgetUsd;
            return this;
        }

        /**
         * Sets the maximum buffer size in bytes.
         *
         * @param maxBufferSize the max buffer size
         * @return this builder
         */
        public Builder maxBufferSize(int maxBufferSize) {
            this.maxBufferSize = maxBufferSize;
            return this;
        }

        /**
         * Sets the maximum thinking tokens.
         *
         * @param maxThinkingTokens the max thinking tokens
         * @return this builder
         */
        public Builder maxThinkingTokens(int maxThinkingTokens) {
            this.maxThinkingTokens = maxThinkingTokens;
            return this;
        }

        /**
         * Sets the maximum message queue size.
         *
         * @param maxMsgQSize the max message queue size
         * @return this builder
         */
        public Builder maxMsgQSize(int maxMsgQSize) {
            this.maxMsgQSize = maxMsgQSize;
            return this;
        }

        /**
         * Sets the model name.
         *
         * @param model the model name
         * @return this builder
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder fallbackModel(String fallbackModel) {
            this.fallbackModel = fallbackModel;
            return this;
        }

        public Builder betas(List<SdkBeta> betas) {
            this.betas = betas;
            return this;
        }

        public Builder cwd(Path cwd) {
            this.cwd = cwd;
            return this;
        }

        public Builder cliPath(Path cliPath) {
            this.cliPath = cliPath;
            return this;
        }

        /**
         * Sets the settings file path or name.
         *
         * @param settings the settings identifier
         * @return this builder
         */
        public Builder settings(String settings) {
            this.settings = settings;
            return this;
        }

        /**
         * Sets the additional directories to add.
         *
         * @param addDirs list of directory paths to add
         * @return this builder
         */
        public Builder addDirs(List<Path> addDirs) {
            this.addDirs = addDirs;
            return this;
        }

        /**
         * Sets the environment variables.
         *
         * @param env map of environment variable names to values
         * @return this builder
         */
        public Builder env(Map<String, String> env) {
            this.env = env;
            return this;
        }

        /**
         * Sets extra CLI arguments to pass.
         *
         * @param extraArgs map of argument names to values
         * @return this builder
         */
        public Builder extraArgs(Map<String, String> extraArgs) {
            this.extraArgs = extraArgs;
            return this;
        }

        /**
         * Sets the tool permission callback.
         *
         * @param canUseTool the permission callback
         * @return this builder
         */
        public Builder canUseTool(CanUseTool canUseTool) {
            this.canUseTool = canUseTool;
            return this;
        }

        /**
         * Sets the stderr output callback.
         *
         * @param stderrCallback the stderr callback
         * @return this builder
         */
        public Builder stderrCallback(Consumer<String> stderrCallback) {
            this.stderrCallback = stderrCallback;
            return this;
        }

        /**
         * Sets the hook configurations.
         *
         * @param hooks map of hook events to matchers
         * @return this builder
         */
        public Builder hooks(Map<HookEvent, List<HookMatcher>> hooks) {
            this.hooks = hooks;
            return this;
        }

        /**
         * Sets the user identity.
         *
         * @param user the user identifier
         * @return this builder
         */
        public Builder user(String user) {
            this.user = user;
            return this;
        }

        /**
         * Sets whether to include partial messages in streaming.
         *
         * @param includePartialMessages true to include partial messages
         * @return this builder
         */
        public Builder includePartialMessages(boolean includePartialMessages) {
            this.includePartialMessages = includePartialMessages;
            return this;
        }

        /**
         * Sets the agent definitions.
         *
         * @param agents map of agent names to definitions
         * @return this builder
         */
        public Builder agents(Map<String, AgentDefinition> agents) {
            this.agents = agents;
            return this;
        }

        /**
         * Sets the setting sources to load.
         *
         * @param settingSources list of setting sources
         * @return this builder
         */
        public Builder settingSources(List<SettingSource> settingSources) {
            this.settingSources = settingSources;
            return this;
        }

        /**
         * Sets the sandbox configuration.
         *
         * @param sandbox the sandbox settings
         * @return this builder
         */
        public Builder sandbox(SandboxSettings sandbox) {
            this.sandbox = sandbox;
            return this;
        }

        /**
         * Sets the plugin configurations.
         *
         * @param plugins list of plugin configs
         * @return this builder
         */
        public Builder plugins(List<SdkPluginConfig> plugins) {
            this.plugins = plugins;
            return this;
        }

        /**
         * Sets the output format for structured outputs.
         *
         * @param outputFormat the output format configuration
         * @return this builder
         */
        public Builder outputFormat(Map<String, Object> outputFormat) {
            this.outputFormat = outputFormat;
            return this;
        }

        public Builder enableFileCheckpointing(boolean enableFileCheckpointing) {
            this.enableFileCheckpointing = enableFileCheckpointing;
            return this;
        }

        public ClaudeAgentOptions build() {
            return new ClaudeAgentOptions(this);
        }

    }

}
