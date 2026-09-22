# 플러그인 시스템

SDK 기능을 사용자가 확장할 수 있는 아키텍처입니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../feature-plugin-system.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

## 개요

플러그인 시스템을 사용하면 사용자 정의 로직으로 SDK 동작을 확장할 수 있습니다. 플러그인은
SDK 작업을 가로채고 수정할 수 있습니다.

## SdkPluginConfig

```java
public record SdkPluginConfig(
    String name,
    Map<String, Object> config
)
```

## 플러그인 구성

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

## 사용 사례

### 로깅 플러그인

모든 SDK 작업을 추적합니다:

```java
new SdkPluginConfig("logger", Map.of(
    "level", "DEBUG",
    "output", "/var/log/claude-sdk.log"
))
```

### 메트릭 플러그인

성능 메트릭을 수집합니다:

```java
new SdkPluginConfig("metrics", Map.of(
    "endpoint", "http://metrics-server/api",
    "interval", 60
))
```

### 캐시 플러그인

응답을 캐시합니다:

```java
new SdkPluginConfig("cache", Map.of(
    "ttl", 3600,
    "maxSize", 1000
))
```

## 예제

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

## 관련 항목
- [구성 옵션](./feature-configuration-options.md#고급-기능) — plugins 옵션
- [Plugins 예제](../../examples/src/main/java/examples/PluginsExample.java)
