# 插件系统

把 Claude Code 插件 —— 打包在一个目录中的自定义斜杠命令、agent、技能和钩子 —— 加载到 SDK 会话中。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../feature-plugin-system.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## 概览

插件是 Claude Code CLI 在启动时加载的一个目录。SDK 本身不运行插件代码：它通过 `--plugin-dir` 把每个插件的目录传给 CLI，由 CLI 发现插件提供了哪些内容。

## SdkPluginConfig

`SdkPluginConfig` 是嵌套在 `ClaudeAgentOptions` 中的一个 record：

```java
public record SdkPluginConfig(String type, String path) {
    public static SdkPluginConfig local(String path);  // type = "local"
}
```

只支持 `"local"` 类型。对于每个 `local` 插件，传输层都会在 CLI 命令中添加 `--plugin-dir <path>`；`type` 为其他值的配置会被跳过且不报错，所以请始终用 `local(...)` 构建配置。

## 配置插件

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeAgentOptions.SdkPluginConfig;

var options = ClaudeAgentOptions.builder()
    .plugins(List.of(
        SdkPluginConfig.local("/path/to/my-plugin"),
        SdkPluginConfig.local("/path/to/another-plugin")))
    .build();
```

插件按列表顺序传递，每个插件对应一个 `--plugin-dir`。

## 插件目录结构

仓库中的演示插件展示了 CLI 所期望的最小目录结构：

```
examples/src/main/java/examples/plugins/demo-plugin/
├── .claude-plugin/
│   └── plugin.json       # Manifest: name, description, version, author
└── commands/
    └── greet.md          # A custom /greet slash command
```

`plugin.json`：

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

插件还可以提供 agent、技能和钩子；完整的目录结构请参阅 Claude Code 的插件文档。

## 验证插件已加载

CLI 会在 `init` 系统消息中报告已加载的插件。请通过 `SystemMessage.initData()` 读取它们，其中还会列出加载失败的插件：

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

`SystemInitPlugin.version()` 是插件清单中声明的版本；若未声明则为 `null`。对于完全没有加载成功的插件目录，
`SystemInitPluginError.plugin()` 可能是 `inline[0]` 这样的位置标签，而 `path()` 是该条目相对于 cwd 解析后的路径。
参见[消息类型 → init 消息](./feature-message-types.md#init-消息systeminitdata)。

## 另见
- [配置选项](./feature-configuration-options.md#plugins) —— plugins 选项
- [Plugins 示例](../../examples/src/main/java/examples/PluginsExample.java)
