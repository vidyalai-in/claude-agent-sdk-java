# 插件系统

面向自定义 SDK 功能的可扩展架构。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../feature-plugin-system.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## 概览

插件系统让你可以用自定义逻辑扩展 SDK 的行为。插件可以拦截并修改 SDK 的操作。

## SdkPluginConfig

```java
public record SdkPluginConfig(
    String name,
    Map<String, Object> config
)
```

## 配置插件

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

## 使用场景

### 日志插件

跟踪所有 SDK 操作：

```java
new SdkPluginConfig("logger", Map.of(
    "level", "DEBUG",
    "output", "/var/log/claude-sdk.log"
))
```

### 指标插件

收集性能指标：

```java
new SdkPluginConfig("metrics", Map.of(
    "endpoint", "http://metrics-server/api",
    "interval", 60
))
```

### 缓存插件

缓存响应：

```java
new SdkPluginConfig("cache", Map.of(
    "ttl", 3600,
    "maxSize", 1000
))
```

## 示例

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

## 另见
- [配置选项](./feature-configuration-options.md#高级功能) —— plugins 选项
- [Plugins 示例](../../examples/src/main/java/examples/PluginsExample.java)
