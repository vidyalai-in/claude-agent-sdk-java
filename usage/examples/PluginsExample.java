package examples;

import java.nio.file.Path;
import java.util.List;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeAgentOptions.SdkPluginConfig;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.SystemMessage;

/**
 * Example demonstrating how to use plugins with Claude Code SDK.
 * <p>
 * Plugins allow you to extend Claude Code with custom commands, agents, skills,
 * and hooks. This example shows how to load a local plugin and verify it's
 * loaded by checking the system message.
 * <p>
 * The demo plugin should be located in examples/plugins/demo-plugin/ and
 * provides
 * a custom /greet command.
 * <p>
 * <b>Plugin Structure:</b>
 * 
 * <pre>
 * examples/plugins/demo-plugin/
 * ├── plugin.json           # Plugin manifest
 * ├── commands/             # Custom slash commands
 * │   └── greet.md
 * ├── agents/               # Custom agents
 * └── hooks/                # Custom hooks
 * </pre>
 */
public class PluginsExample {

    public static void main(String[] args) {
        pluginExample();
    }

    /**
     * Example showing plugins being loaded in the system message.
     */
    static void pluginExample() {
        System.out.println("=== Plugin Example ===\n");

        // Get the path to the demo plugin
        // In production, you can use any path to your plugin directory
        Path base = Path.of(System.getProperty("user.dir"));
        Path pluginPath = base.resolve("usage/examples/plugins/demo-plugin");

        // Create plugin configuration
        SdkPluginConfig plugin = SdkPluginConfig.local(pluginPath.toString());

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .plugins(List.of(plugin))
                .maxTurns(1) // Limit to one turn for quick demo
                .build();

        System.out.println("Loading plugin from: " + pluginPath + "\n");

        boolean foundPlugins = false;
        List<Message> messages = ClaudeSDK.query("Hello!", options);

        for (Message msg : messages) {
            if ((msg instanceof SystemMessage system) && "init".equals(system.subtype())) {
                System.out.println("System initialized!");
                System.out.println("System message data keys: " + system.data().keySet() + "\n");

                // Check for plugins in the system message
                @SuppressWarnings("unchecked")
                List<Object> pluginsData = (List<Object>) system.get("plugins");

                if ((pluginsData != null) && (!pluginsData.isEmpty())) {
                    System.out.println("Plugins loaded:");
                    for (Object pluginObj : pluginsData) {
                        if (pluginObj instanceof java.util.Map<?, ?> pluginMap) {
                            String name = (String) pluginMap.get("name");
                            String path = (String) pluginMap.get("path");
                            System.out.println("  - " + name + " (path: " + path + ")");
                        }
                    }
                    foundPlugins = true;
                } else {
                    System.out.println("Note: Plugin was passed via CLI but may not appear in system message.");
                    System.out.println("Plugin path configured: " + pluginPath);
                    foundPlugins = true;
                }
            }
        }

        if (foundPlugins) {
            System.out.println("\nPlugin successfully configured!\n");
        }
    }

    /**
     * Example with multiple plugins from different sources.
     */
    static void multiplePluginsExample() {
        System.out.println("=== Multiple Plugins Example ===\n");

        // Create multiple plugin configurations
        SdkPluginConfig plugin1 = SdkPluginConfig.local("path/to/plugin1");
        SdkPluginConfig plugin2 = SdkPluginConfig.local("path/to/plugin2");

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .plugins(List.of(plugin1, plugin2))
                .maxTurns(1)
                .build();

        System.out.println("Loading multiple plugins...\n");

        List<Message> messages = ClaudeSDK.query("What plugins are loaded?", options);

        for (Message msg : messages) {
            if ((msg instanceof SystemMessage system) && "init".equals(system.subtype())) {
                @SuppressWarnings("unchecked")
                List<Object> pluginsData = (List<Object>) system.get("plugins");

                if (pluginsData != null) {
                    System.out.println("Number of plugins loaded: " + pluginsData.size());
                }
            }
        }

        System.out.println();
    }

    /**
     * Plugin configuration types.
     * <p>
     * Currently, only "local" type is supported, which loads plugins from
     * the local filesystem. Future versions may support remote plugins.
     */
    static void pluginTypes() {
        System.out.println("=== Plugin Types ===\n");

        // Local plugin (current support)
        SdkPluginConfig localPlugin = SdkPluginConfig.local("/path/to/plugin");
        System.out.println("Local plugin: type=" + localPlugin.type() + ", path=" + localPlugin.path());

        // Note: Future plugin types may include:
        // - Remote plugins (npm, git)
        // - Built-in plugins
        // - Registry plugins

        System.out.println();
    }

}
