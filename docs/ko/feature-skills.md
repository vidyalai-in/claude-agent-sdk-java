# Skills

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../feature-skills.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

`ClaudeAgentOptions`의 `skills` 옵션은 메인 세션에서 Claude Code의 skill을 활성화하는 단 하나의
지점입니다. SDK가 `allowedTools`와 `settingSources`를 자동으로 연결해 주므로, 호출하는 쪽에서 둘 다
직접 구성할 필요가 없습니다.

> **skill이란?** skill은 `.claude/skills/<name>/SKILL.md`(프로젝트 범위) 또는
> `~/.claude/skills/<name>/SKILL.md`(사용자 범위)에 설치되는 재사용 가능한 기능 묶음입니다. skill은
> 내장 `Skill` 도구로 호출합니다. skill 작성법은 Claude Code 문서를 참고하세요.

## 빠른 시작

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

// Mode 1 — enable every discovered skill
var options = ClaudeAgentOptions.builder()
    .skillsAll()
    .build();

// Mode 2 — enable only specific skills
var options = ClaudeAgentOptions.builder()
    .skills(List.of("commit", "review"))
    .build();

// Mode 3 — suppress every skill from the listing
var options = ClaudeAgentOptions.builder()
    .skills(List.of())
    .build();

// Mode 4 (default) — no SDK auto-configuration; CLI defaults apply
var options = ClaudeAgentOptions.builder().build();
```

## 모드

| 빌더 호출 | `allowedTools` 주입 | `settingSources` 기본값 | initialize 와이어 필드 |
|---|---|---|---|
| _생략_ | 없음 | 없음 | 생략 |
| `.skillsAll()` | 맨 `Skill` 추가 | `[user, project]` | 생략 |
| `.skills(List.of("a", "b"))` | `Skill(a)`, `Skill(b)` 추가 | `[user, project]` | `["a", "b"]` |
| `.skills(List.of())` | 없음 | `[user, project]` | `[]` |

참고:

- **`null`은 skill 끄기가 아닙니다.** 옵션을 생략하면 CLI 기본값이 그대로 유지됩니다. 모델의 목록에서
  모든 skill을 없애려면 빈 목록을 넘기세요.
- **`"all"`과 생략은 와이어 수준에서 동등합니다.** 둘 다 initialize 제어 요청에서 `skills` 필드를
  생략합니다. CLI는 생략을 "필터 없음"으로 취급합니다.
- **빈 목록은 실제로 와이어에 전송됩니다.** `List.of()`는 initialize 요청에서 `"skills": []`가 되어,
  이를 지원하는 CLI에게 시스템 프롬프트에 skill을 하나도 싣지 말라고 알립니다.

## 자동 연결이 동작하는 방식

`SubprocessCLITransport.applySkillsDefaults()`가 서브프로세스를 생성하기 전에 실제로 적용될 CLI
플래그를 구성합니다. 원본 `ClaudeAgentOptions`는 결코 변경되지 않습니다.

`skillsAll()`의 경우:

- `allowedTools`에 아직 `"Skill"`이 없으면 맨 도구가 추가됩니다.
- `settingSources`가 null이면 CLI가 설치된 skill을 찾을 수 있도록 기본값이 `[USER, PROJECT]`가 됩니다.
- 명시적인 `settingSources(...)`는 언제나 기본값보다 우선합니다.

`skills(List.of(...))`의 경우:

- 각 이름 `n`에 대해 `allowedTools`에 `Skill(n)`을 추가합니다(기존 항목과 중복 제거).
- `settingSources` 기본값 동작은 동일합니다.

`skills(List.of())`의 경우:

- `allowedTools`는 그대로입니다.
- `settingSources` 기본값 동작은 동일합니다.

## 이름 검증 (0.1.22)

`skills(List.of(...))`에 넘긴 이름은 CLI의 `--allowedTools` 값으로 조립되기 전에 검증됩니다. 거부되는
경우는 모두 **연결 시점**에 `IllegalArgumentException`을 던집니다 — CLI 서브프로세스가 생성되기 전,
`buildCommand()`에서 발생합니다.

검증이 필요한 이유는 `--allowedTools`가 하나의 문자열이고 CLI가 괄호 밖의 쉼표와 공백을 기준으로 이를
권한 규칙으로 나누는데, 그 토크나이저가 어떤 이스케이프 시퀀스도 인정하지 않기 때문입니다. 이스케이프는
규칙 단위 문법에만 존재하며 분할 *이후에* 적용되므로, 구분자를 품은 이름은 안정적으로 전달될 수
없습니다. 어떻게 토큰화되는지는 그 주변에 무엇이 있느냐에 달려 있습니다:

```java
// Before 0.1.22 this emitted --allowedTools "Skill(x),Bash(*)",
// silently granting the session unrestricted Bash.
ClaudeAgentOptions.builder()
    .skills(List.of("x),Bash(*"))
    .build();

// 0.1.22: IllegalArgumentException at connect()
// "Invalid skill name 'x),Bash(*': parentheses, commas, control characters,
//  and byte-order marks are not allowed. ..."
```

### 거부되는 형태

| 형태 | 예 | 이유 |
|---|---|---|
| 괄호나 쉼표 | `"x),Bash(*"`, `"a,b"`, `"()"` | 규칙 구분자 — 위의 주입 경로 |
| 제어 문자 | C0(`\n`, `\t`, `\u0000`), DEL(`\u007F`), C1(`\u0080`–`\u009F`) | skill 디렉터리 이름에는 절대 나타나지 않음 |
| 바이트 순서 표식 | 이름 어디든 들어 있는 `﻿` | CLI가 U+FEFF를 공백으로 잘라내므로 규칙이 다른 skill을 가리키게 됨 |
| 비었거나 공백뿐 | `""`, `" "`, `"  \t "` | 아무것도 가리키지 않음 |
| 맨 와일드카드 | `"*"` | 대신 `skillsAll()`을 사용하세요 |
| 와일드카드 접미사 | `"pdf:*"`, `"my skill *"` | skill을 정확한 이름으로 하나씩 나열하세요 |
| 앞뒤 공백 | `" pdf"`, `"pdf "` | 결코 일치할 수 없음 — `Skill` 도구가 호출된 이름을 다듬음 |
| 앞의 `/` | `"/commit"` | 이 옵션은 슬래시 명령 형태가 아니라 표준 이름을 받습니다 |
| 연속된 백슬래시 | `"mid\\\\dle"` | 규칙 파서가 이를 접기 때문에 규칙이 다른 skill을 가리키게 됨 |
| 끝의 짝 없는 백슬래시 | `"name\\"` | 매달린 이스케이프 |
| 짝 없는 서로게이트 | 단독 `\ud800` | CLI가 발견한 어떤 이름과도 일치할 수 없음 |

주입 경로인 것은 처음 세 줄뿐입니다. 나머지는 깔끔하게 토큰화되지만 여러분이 지정한 skill과 결코
일치할 수 없는 규칙을 만들게 됩니다 — skill이 세션에서 조용히 빠지는 대신 `connect()`에서 크게
실패하도록 거부하는 것입니다. 위의 문자열 예시는 Java 소스 리터럴 표기이므로 `"mid\\\\dle"`는 백슬래시
두 개를 담은 이름이고, `"dir\\sub"`(허용됨)는 하나입니다.

### 허용되는 이름

평범한 이름은 영향을 받지 않습니다. 아래는 모두 예전과 똑같은 argv를 만듭니다:

```java
.skills(List.of(
    "pdf-tools",          // hyphens
    "my_skill.v2",        // underscores, dots
    "myplugin:pdf",       // plugin-qualified
    "skill with spaces",  // interior spaces are fine; only surrounding ones are not
    "dir\\sub",           // a single backslash
    "日本語スキル"          // non-ASCII
))
```

### 호환성이 깨지는 변경

이전에는 허용되던 두 형태가 이제 예외를 던집니다:

| 이전 | 예전 동작 | 지금 |
|---|---|---|
| `skills(List.of("*"))`, `skills(List.of("plugin:*"))` | 와일드카드 규칙을 만들었음 | 예외를 던집니다 — `skillsAll()`을 쓰거나, 접두사 일치를 위해 `allowedTools`에 `Skill(...)` 항목을 직접 추가하세요 |
| `skills(List.of(" name"))`, `skills(List.of("/name"))` | 아무것과도 일치하지 않는 규칙을 만들어 skill이 **조용히 사용 불가**였음 | 예외를 던지며 문제를 알려 줍니다 |

### Java 고유의 동작

두 가지 검사는 Python SDK와 의도적으로 다릅니다. 두 언어가 문자열을 모델링하는 방식이 다르기
때문입니다:

- **서로게이트.** Python은 모든 서로게이트 코드 포인트를 거부합니다 — Python의 `str`은 코드 포인트를
  담고 확장 평면 문자는 서로게이트가 아닌 단일 항목이므로, 서로게이트가 있다면 구조상 반드시 짝이
  없다는 점에서 타당합니다. Java 문자열은 UTF-16이고, 확장 평면 문자는 정당하게 상위/하위 *쌍*입니다.
  그래서 Java는 **단독** 서로게이트만 거부하며, `"𝕤kill"` 같은 이름은 허용됩니다.
- **공백.** `String.strip()`은 `Character.isWhitespace`를 따르므로, Python의 `str.strip()`이 제거하는
  줄바꿈 없는 공백(U+00A0, U+2007, U+202F)이 남습니다. 패딩 검사는 여기에 `Character.isSpaceChar`를
  합집합으로 더해 그것들도 잡아냅니다. U+FEFF는 둘 중 어디에도 들어가지 않으며, Python에서와 똑같이
  잘못된 문자로 거부됩니다.

`skillsAll()`은 이름 검사를 하지 않으며(검사할 이름이 없습니다), `skills(List.of())`는 여전히 유효한
no-op입니다.

## Initialize 와이어 프로토콜

skills는 `SDKControlInitializeRequest.skills`를 통해 SDK 제어 프로토콜로도 전달됩니다. 명시적인 목록만
전송되며, `"all"`과 `null`은 둘 다 필드를 생략합니다.

`skills` initialize 필드를 모르는 예전 CLI는 이를 무시합니다 — 자동 주입된 `allowedTools` 항목은 여전히
존중됩니다.

> **더 이상 권장되지 않음:** `allowedTools(...)`(또는 `AgentDefinition.tools`)에 맨 `"Skill"` 토큰을
> 넘기는 것은 **권장되지 않습니다**. 대신 `skillsAll()` / `skills(List.of(...))`를 사용하세요 — 필요한
> 모든 것(`Skill` 도구 허용 포함)을 구성해 주며, `allowedTools`와 와이어 수준 `skills` 필드가 어긋나는
> 것을 막아 줍니다.

## 예제

### 명시적 `allowedTools`와 섞어 쓰기

skills는 기존 허용 목록을 보강할 뿐, 대체하지 않습니다:

```java
var options = ClaudeAgentOptions.builder()
    .allowedTools(List.of("Read", "Write"))
    .skills(List.of("commit"))
    .build();
// effective allowedTools: [Read, Write, Skill(commit)]
```

### 멱등적 주입

이미 `Skill`이나 `Skill(name)`을 허용 목록에 추가했다면 SDK는 중복해서 넣지 않습니다:

```java
var options = ClaudeAgentOptions.builder()
    .allowedTools(List.of("Skill(pdf)"))
    .skills(List.of("pdf"))
    .build();
// effective allowedTools: [Skill(pdf)]   (not [Skill(pdf), Skill(pdf)])
```

### 명시적 `settingSources` 보존하기

```java
var options = ClaudeAgentOptions.builder()
    .skillsAll()
    .settingSources(List.of(SettingSource.LOCAL))
    .build();
// effective settingSources: [LOCAL]   (your value wins over the [USER, PROJECT] default)
```

## 보안 참고

`skills` 옵션은 **컨텍스트 필터이지 샌드박스가 아닙니다.** 목록에 없는 skill은 모델의 skill 목록에서
숨겨지고 `Skill` 도구로 호출할 수도 없지만, 그 파일들은 디스크에 그대로 남아 있습니다 — `Read`나
`Bash`를 가진 세션은 `.claude/skills/**`에 직접 접근할 수 있습니다.

강한 격리가 필요하다면:

- `.claude/skills/`에 원하는 부분집합만 들어 있는 디렉터리를 `cwd`로 지정하거나, **또는**
- skill 경로에 대해 `Read`/`Bash` 거부 규칙을 추가하세요.

기본 제공 skill과 설치된 플러그인의 skill은 `settingSources`와 무관하게 발견됩니다. `skills` 허용
목록은 그것들을 모델의 목록에서 숨기는 유일한 수단입니다.

**skill 파일에 비밀 정보를 저장하지 마세요.**

## 전체 예제

세 가지 모드를 모두 실행해 볼 수 있는 데모는
[`examples/SkillsExample.java`](../../examples/src/main/java/examples/SkillsExample.java)를 참고하세요.

```java
package examples;

import java.util.List;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;

public class SkillsExample {
    public static void main(String[] args) throws Exception {
        var options = ClaudeAgentOptions.builder()
            .skillsAll()
            .maxTurns(1)
            .build();
        ClaudeSDK.query("List the skills you have available.", options);
    }
}
```

## 관련 항목

- [구성 옵션](./feature-configuration-options.md) — 전체 빌더 API
- [에이전트 정의](./feature-agents.md) — `AgentDefinition`의 `skills` 필드(서브에이전트별 허용 목록)
- [세션 히스토리](./feature-session-history.md) — 디스크에서 트랜스크립트 읽기
