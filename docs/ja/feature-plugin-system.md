# プラグインシステム

Claude Code プラグイン —— ディレクトリにまとめられたカスタムスラッシュコマンド、エージェント、スキル、
フック —— を SDK のセッションに読み込みます。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../feature-plugin-system.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

## 概要

プラグインとは、Claude Code CLI が起動時に読み込むディレクトリです。SDK 自身はプラグインのコードを
実行しません。各プラグインのディレクトリを `--plugin-dir` で CLI に渡し、プラグインが何を提供するかは
CLI が検出します。

## SdkPluginConfig

`SdkPluginConfig` は `ClaudeAgentOptions` の中に入れ子になったレコードです：

```java
public record SdkPluginConfig(String type, String path) {
    public static SdkPluginConfig local(String path);  // type = "local"
}
```

サポートされているのは `"local"` タイプだけです。トランスポートは `local` プラグインごとに CLI コマンドへ
`--plugin-dir <path>` を追加します。それ以外の `type` を持つ設定はエラーなしでスキップされるので、設定は
常に `local(...)` で作成してください。

## プラグインの設定

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeAgentOptions.SdkPluginConfig;

var options = ClaudeAgentOptions.builder()
    .plugins(List.of(
        SdkPluginConfig.local("/path/to/my-plugin"),
        SdkPluginConfig.local("/path/to/another-plugin")))
    .build();
```

プラグインはリストの順序で、プラグインごとに 1 つの `--plugin-dir` として渡されます。

## プラグインの構成

リポジトリのデモプラグインが、CLI が期待する最小限の構成を示しています：

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

プラグインはエージェント、スキル、フックも提供できます。ディレクトリ構成の全体については Claude Code の
プラグインのドキュメントを参照してください。

## プラグインが読み込まれたことの確認

CLI は、読み込んだプラグインを `init` システムメッセージの `plugins` フィールドで報告します。これは
`name` と `path` を持つ Map のリストです：

```java
for (Message msg : ClaudeSDK.query("Hello!", options)) {
    if (msg instanceof SystemMessage system && "init".equals(system.subtype())) {
        @SuppressWarnings("unchecked")
        List<Object> plugins = (List<Object>) system.get("plugins");
        if (plugins != null) {
            for (Object p : plugins) {
                if (p instanceof Map<?, ?> plugin) {
                    System.out.println(plugin.get("name") + " (" + plugin.get("path") + ")");
                }
            }
        }
    }
}
```

## 関連項目
- [設定オプション](./feature-configuration-options.md#plugins) —— plugins オプション
- [Plugins のサンプル](../../examples/src/main/java/examples/PluginsExample.java)
