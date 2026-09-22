# プラグインシステム

SDK の機能を独自に拡張するためのアーキテクチャです。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../feature-plugin-system.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

## 概要

プラグインシステムを使うと、独自のロジックで SDK の挙動を拡張できます。プラグインは SDK の
操作を傍受して変更できます。

## SdkPluginConfig

```java
public record SdkPluginConfig(
    String name,
    Map<String, Object> config
)
```

## プラグインの設定

```java
var options = ClaudeAgentOptions.builder()
    .plugins(List.of(
        new SdkPluginConfig(
            "my-plugin",
            Map.of(
                "setting1", "value1",
                "setting2", 123
            )
        )
    ))
    .build();
```

## ユースケース

### ロギングプラグイン

すべての SDK 操作を追跡します：

```java
new SdkPluginConfig("logger", Map.of(
    "level", "DEBUG",
    "output", "/var/log/claude-sdk.log"
))
```

### メトリクスプラグイン

パフォーマンスのメトリクスを収集します：

```java
new SdkPluginConfig("metrics", Map.of(
    "endpoint", "http://metrics-server/api",
    "interval", 60
))
```

### キャッシュプラグイン

応答をキャッシュします：

```java
new SdkPluginConfig("cache", Map.of(
    "ttl", 3600,
    "maxSize", 1000
))
```

## サンプル

```java
public class PluginsExample {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .plugins(List.of(
                new SdkPluginConfig("logger", Map.of(
                    "level", "INFO",
                    "format", "json"
                )),
                new SdkPluginConfig("metrics", Map.of(
                    "enabled", true
                ))
            ))
            .build();

        List<Message> messages = ClaudeSDK.query(
            "What is Java?",
            options
        );
    }
}
```

## 関連項目
- [設定オプション](./feature-configuration-options.md#高度な機能) —— plugins オプション
- [Plugins のサンプル](../../examples/src/main/java/examples/PluginsExample.java)
