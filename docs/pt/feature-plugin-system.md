# Sistema de plugins

Arquitetura extensível para funcionalidades próprias no SDK.

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../feature-plugin-system.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

## Visão geral

O sistema de plugins permite estender o comportamento do SDK com lógica própria. Plugins podem
interceptar e modificar operações do SDK.

## SdkPluginConfig

```java
public record SdkPluginConfig(
    String name,
    Map<String, Object> config
)
```

## Configurando plugins

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

## Casos de uso

### Plugin de log

Acompanhe todas as operações do SDK:

```java
new SdkPluginConfig("logger", Map.of(
    "level", "DEBUG",
    "output", "/var/log/claude-sdk.log"
))
```

### Plugin de métricas

Colete métricas de desempenho:

```java
new SdkPluginConfig("metrics", Map.of(
    "endpoint", "http://metrics-server/api",
    "interval", 60
))
```

### Plugin de cache

Armazene respostas em cache:

```java
new SdkPluginConfig("cache", Map.of(
    "ttl", 3600,
    "maxSize", 1000
))
```

## Exemplo

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

## Veja também
- [Opções de configuração](./feature-configuration-options.md#recursos-avançados) — opção plugins
- [Exemplo de Plugins](../../examples/src/main/java/examples/PluginsExample.java)
