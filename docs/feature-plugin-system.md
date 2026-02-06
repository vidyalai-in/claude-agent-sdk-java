# Plugin System

Extensible architecture for custom SDK functionality.

## Overview

The plugin system allows you to extend SDK behavior with custom logic. Plugins can intercept and modify SDK operations.

## SdkPluginConfig

```java
public record SdkPluginConfig(
    String name,
    Map<String, Object> config
)
```

## Configuring Plugins

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

## Use Cases

### Logging Plugin

Track all SDK operations:

```java
new SdkPluginConfig("logger", Map.of(
    "level", "DEBUG",
    "output", "/var/log/claude-sdk.log"
))
```

### Metrics Plugin

Collect performance metrics:

```java
new SdkPluginConfig("metrics", Map.of(
    "endpoint", "http://metrics-server/api",
    "interval", 60
))
```

### Cache Plugin

Cache responses:

```java
new SdkPluginConfig("cache", Map.of(
    "ttl", 3600,
    "maxSize", 1000
))
```

## Example

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

## See Also
- [Configuration Options](./feature-configuration-options.md#advanced-features) - plugins option
- [Plugins Example](../examples/src/main/java/examples/PluginsExample.java)
