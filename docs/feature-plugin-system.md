# Plugin System

Load Claude Code plugins — custom slash commands, agents, skills and hooks
packaged in a directory — into an SDK session.

## Overview

A plugin is a directory the Claude Code CLI loads at startup. The SDK does not
run plugin code itself: it passes each plugin's directory to the CLI with
`--plugin-dir`, and the CLI discovers what the plugin provides.

## SdkPluginConfig

`SdkPluginConfig` is a record nested in `ClaudeAgentOptions`:

```java
public record SdkPluginConfig(String type, String path) {
    public static SdkPluginConfig local(String path);  // type = "local"
}
```

Only the `"local"` type is supported. For each `local` plugin the transport
adds `--plugin-dir <path>` to the CLI command; a config with any other `type`
is skipped without an error, so always build configs with `local(...)`.

## Configuring Plugins

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeAgentOptions.SdkPluginConfig;

var options = ClaudeAgentOptions.builder()
    .plugins(List.of(
        SdkPluginConfig.local("/path/to/my-plugin"),
        SdkPluginConfig.local("/path/to/another-plugin")))
    .build();
```

Plugins are passed in list order, one `--plugin-dir` per plugin.

## Plugin Layout

The repository's demo plugin shows the minimal layout the CLI expects:

```
examples/src/main/java/examples/plugins/demo-plugin/
├── .claude-plugin/
│   └── plugin.json       # Manifest: name, description, version, author
└── commands/
    └── greet.md          # A custom /greet slash command
```

`plugin.json`:

```json
{
  "name": "demo-plugin",
  "description": "A demo plugin showing how to extend Claude Code with custom commands",
  "version": "1.0.0",
  "author": {
    "name": "Claude Code Team"
  }
}
```

Plugins can also contribute agents, skills and hooks; see the Claude Code
plugin documentation for the full directory layout.

## Verifying a Plugin Loaded

The CLI reports loaded plugins in the `init` system message. Read them through
`SystemMessage.initData()`, which also lists plugins that failed to load:

```java
for (Message msg : ClaudeSDK.query("Hello!", options)) {
    if (msg instanceof SystemMessage system && system.initData() != null) {
        SystemInitData init = system.initData();
        for (SystemInitPlugin plugin : init.plugins()) {
            System.out.println(plugin.name() + " (" + plugin.path() + ")");
        }
        if (init.pluginErrors() != null) {
            for (SystemInitPluginError error : init.pluginErrors()) {
                System.out.println("Failed: " + error.plugin() + " - " + error.type() + ": " + error.message());
            }
        }
    }
}
```

`SystemInitPlugin.version()` is the version declared in the plugin's manifest,
or `null` when it declares none. For a plugin directory that did not load at
all, `SystemInitPluginError.plugin()` may be a positional tag such as
`inline[0]`, and `path()` is that entry's path resolved against the cwd. See
[Message Types → The init message](./feature-message-types.md#the-init-message-systeminitdata).

## See Also
- [Configuration Options](./feature-configuration-options.md#plugins) - plugins option
- [Plugins Example](../examples/src/main/java/examples/PluginsExample.java)
