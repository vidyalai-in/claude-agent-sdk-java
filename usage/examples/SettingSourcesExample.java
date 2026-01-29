package examples;

import java.nio.file.Path;
import java.util.List;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.types.config.SettingSource;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.SystemMessage;

/**
 * Example demonstrating setting sources control.
 * <p>
 * This example shows how to use the settings option to control which
 * settings are loaded, including custom slash commands, agents, and other
 * configurations.
 * <p>
 * Setting sources determine where Claude Code loads configurations from:
 * <ul>
 * <li>"user": Global user settings (~/.claude/)</li>
 * <li>"project": Project-level settings (.claude/ in project)</li>
 * <li>"local": Local gitignored settings (.claude-local/)</li>
 * </ul>
 * <p>
 * <b>IMPORTANT:</b> When settings is not provided (null), NO settings are
 * loaded
 * by default. This creates an isolated environment. To load settings,
 * explicitly
 * specify which sources to use.
 * <p>
 * By controlling which sources are loaded, you can:
 * <ul>
 * <li>Create isolated environments with no custom settings (default)</li>
 * <li>Load only user settings, excluding project-specific configurations</li>
 * <li>Combine multiple sources as needed</li>
 * </ul>
 * <p>
 * Usage:
 * 
 * <pre>
 * java examples.SettingSourcesExample              # List examples
 * java examples.SettingSourcesExample all          # Run all examples
 * java examples.SettingSourcesExample default      # Run specific example
 * </pre>
 */
public class SettingSourcesExample {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String exampleName = args[0];

        System.out.println("Starting Claude SDK Setting Sources Examples...");
        System.out.println("=".repeat(50) + "\n");

        switch (exampleName) {
            case "all" -> {
                exampleDefault();
                System.out.println("-".repeat(50) + "\n");
                exampleUserOnly();
                System.out.println("-".repeat(50) + "\n");
                exampleProjectAndUser();
            }
            case "default" -> exampleDefault();
            case "user_only" -> exampleUserOnly();
            case "project_and_user" -> exampleProjectAndUser();
            default -> {
                System.err.println("Error: Unknown example '" + exampleName + "'");
                printUsage();
                System.exit(1);
            }
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java examples.SettingSourcesExample <example_name>");
        System.out.println("\nAvailable examples:");
        System.out.println("  all                - Run all examples");
        System.out.println("  default            - Default behavior (no settings)");
        System.out.println("  user_only          - Load only user settings");
        System.out.println("  project_and_user   - Load project and user settings");
    }

    /**
     * Extract slash command names from system message.
     */
    private static List<String> extractSlashCommands(SystemMessage msg) {
        if ("init".equals(msg.subtype())) {
            List<String> commands = msg.get("slash_commands");
            return ((commands != null) ? commands : List.of());
        }
        return List.of();
    }

    /**
     * Default behavior - no settings loaded.
     */
    static void exampleDefault() {
        System.out.println("=== Default Behavior Example ===");
        System.out.println("Setting sources: null (default)");
        System.out.println("Expected: No custom slash commands will be available\n");

        // Use the SDK project directory
        Path sdkDir = Path.of(System.getProperty("user.dir"));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .cwd(sdkDir)
                .build();

        try (ClaudeSDKClient client = ClaudeSDK.createClient(options)) {
            client.connect("What is 2 + 2?");

            for (Message msg : client.receiveResponse()) {
                if ((msg instanceof SystemMessage system) && "init".equals(system.subtype())) {
                    List<String> commands = extractSlashCommands(system);
                    System.out.println("Available slash commands: " + commands);
                    if (commands.contains("commit")) {
                        System.out.println("❌ /commit is available (unexpected)");
                    } else {
                        System.out.println("✓ /commit is NOT available (expected - no settings loaded)");
                    }
                    break;
                }
            }
        }

        System.out.println();
    }

    /**
     * Load only user-level settings, excluding project settings.
     */
    static void exampleUserOnly() {
        System.out.println("=== User Settings Only Example ===");
        System.out.println("Setting sources: ['user']");
        System.out.println("Expected: Project slash commands (like /commit) will NOT be available\n");

        // Use the SDK repo directory which has .claude/commands/commit.md
        Path sdkDir = Path.of(System.getProperty("user.dir"));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .settingSources(List.of(SettingSource.USER))
                .cwd(sdkDir)
                .build();

        try (ClaudeSDKClient client = ClaudeSDK.createClient(options)) {
            client.connect("What is 2 + 2?");

            for (Message msg : client.receiveResponse()) {
                if ((msg instanceof SystemMessage system) && "init".equals(system.subtype())) {
                    List<String> commands = extractSlashCommands(system);
                    System.out.println("Available slash commands: " + commands);
                    if (commands.contains("commit")) {
                        System.out.println("❌ /commit is available (unexpected)");
                    } else {
                        System.out.println("✓ /commit is NOT available (expected)");
                    }
                    break;
                }
            }
        }

        System.out.println();
    }

    /**
     * Load both project and user settings.
     */
    static void exampleProjectAndUser() {
        System.out.println("=== Project + User Settings Example ===");
        System.out.println("Setting sources: ['user', 'project']");
        System.out.println("Expected: Project slash commands (like /commit) WILL be available\n");

        Path sdkDir = Path.of(System.getProperty("user.dir"));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .settingSources(List.of(SettingSource.USER, SettingSource.PROJECT))
                .cwd(sdkDir)
                .build();

        try (ClaudeSDKClient client = ClaudeSDK.createClient(options)) {
            client.connect("What is 2 + 2?");

            for (Message msg : client.receiveResponse()) {
                if ((msg instanceof SystemMessage system) && "init".equals(system.subtype())) {
                    List<String> commands = extractSlashCommands(system);
                    System.out.println("Available slash commands: " + commands);
                    if (commands.contains("commit")) {
                        System.out.println("✓ /commit is available (expected)");
                    } else {
                        System.out.println("❌ /commit is NOT available (unexpected)");
                    }
                    break;
                }
            }
        }

        System.out.println();
    }

}
