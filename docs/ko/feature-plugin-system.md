# 플러그인 시스템

Claude Code 플러그인 — 디렉터리 하나에 묶인 사용자 정의 슬래시 명령, 에이전트, 스킬, 훅 — 을 SDK 세션에
로드합니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../feature-plugin-system.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

## 개요

플러그인은 Claude Code CLI가 시작할 때 로드하는 디렉터리입니다. SDK는 플러그인 코드를 직접 실행하지
않습니다: 각 플러그인의 디렉터리를 `--plugin-dir`로 CLI에 넘기고, 플러그인이 무엇을 제공하는지는 CLI가
찾아냅니다.

## SdkPluginConfig

`SdkPluginConfig`는 `ClaudeAgentOptions` 안에 중첩된 레코드입니다:

```java
public record SdkPluginConfig(String type, String path) {
    public static SdkPluginConfig local(String path);  // type = "local"
}
```

`"local"` 타입만 지원됩니다. 전송 계층은 `local` 플러그인마다 CLI 명령에 `--plugin-dir <path>`를
추가합니다. 다른 `type`을 가진 구성은 오류 없이 건너뛰므로, 구성은 항상 `local(...)`로 만드세요.

## 플러그인 구성

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeAgentOptions.SdkPluginConfig;

var options = ClaudeAgentOptions.builder()
    .plugins(List.of(
        SdkPluginConfig.local("/path/to/my-plugin"),
        SdkPluginConfig.local("/path/to/another-plugin")))
    .build();
```

플러그인은 목록 순서대로, 플러그인마다 `--plugin-dir` 하나씩 전달됩니다.

## 플러그인 레이아웃

저장소의 데모 플러그인은 CLI가 기대하는 최소한의 레이아웃을 보여 줍니다:

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

플러그인은 에이전트, 스킬, 훅도 제공할 수 있습니다. 전체 디렉터리 레이아웃은 Claude Code 플러그인 문서를
참고하세요.

## 플러그인 로드 여부 확인

CLI는 로드된 플러그인을 `init` 시스템 메시지의 `plugins` 필드에 `name`과 `path`를 가진 Map의 목록으로
보고합니다:

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

## 관련 항목
- [구성 옵션](./feature-configuration-options.md#plugins) — plugins 옵션
- [Plugins 예제](../../examples/src/main/java/examples/PluginsExample.java)
