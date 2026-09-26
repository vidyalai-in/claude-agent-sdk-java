# 에이전트 정의

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../feature-agents.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

사용자 정의 에이전트를 쓰면 각자의 시스템 프롬프트, 도구, 모델을 가진 전용 서브에이전트를 정의할 수
있습니다. Claude는 대화 중에 이런 에이전트를 생성해 특정 작업을 처리하게 할 수 있습니다.

## 목차
- [개요](#개요)
- [AgentDefinition 레코드](#agentdefinition-레코드)
- [인라인 에이전트 정의](#인라인-에이전트-정의)
- [파일시스템 기반 에이전트](#파일시스템-기반-에이전트)
- [대용량 에이전트 정의](#대용량-에이전트-정의)
- [서브에이전트 출력 관찰하기](#서브에이전트-출력-관찰하기)
- [백그라운드 서브에이전트와 콜백](#백그라운드-서브에이전트와-콜백)
- [예제](#예제)

## 개요

에이전트는 Claude가 대화 중에 사용할 수 있는 이름 붙은 서브에이전트입니다. 각 에이전트는 다음을
가집니다:

- **description** — 그 에이전트가 무엇을 하는지(어떤 에이전트를 쓸지 판단할 때 Claude에게 보여집니다)
- **시스템 프롬프트** — 그 에이전트의 행동 지침
- **도구** — 그 에이전트가 사용할 수 있는 도구 목록(null이면 부모에서 상속)
- **모델** — 그 에이전트가 실행되는 Claude 모델 변형(null이면 부모에서 상속)
- **Skills** — 그 에이전트가 쓸 수 있는 skill 이름 목록(null이면 부모에서 상속)
- **Memory** — 그 에이전트의 메모리 범위(null이면 부모에서 상속)
- **MCP 서버** — 그 에이전트가 쓸 수 있는 MCP 서버 참조(null이면 부모에서 상속)

에이전트는 `ClaudeAgentOptions.agents()`에 `Map<String, AgentDefinition>` 형태로 등록하며, 키가
에이전트의 이름입니다.

## AgentDefinition 레코드

```java
import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.config.AIModel;
import in.vidyalai.claude.sdk.types.config.MemoryScope;

// Full constructor
AgentDefinition agent = new AgentDefinition(
    "Reviews code for quality and bugs",   // description
    "You are a code review expert...",     // system prompt
    List.of("Read", "Grep"),               // tools (null = inherit)
    "sonnet",                              // model (null = inherit)
    List.of("commit", "review"),           // skills (null = inherit)
    MemoryScope.PROJECT,                   // memory scope (null = inherit)
    List.of("my-mcp-server")              // MCP servers (null = inherit)
);

// Shorthand: description + prompt only (all other fields inherit from parent)
AgentDefinition simple = new AgentDefinition(
    "Summarizes text",
    "You are a concise summarizer."
);

// Backwards-compatible: description, prompt, tools, model
AgentDefinition compat = new AgentDefinition(
    "Reviews code",
    "You are a code reviewer.",
    List.of("Read", "Grep"),
    "sonnet"   // model can be "sonnet", "opus", "haiku", or full model ID
);
```

**필드:**

| 필드 | 타입 | 설명 |
|-------|------|-------------|
| `description` | `String` | Claude에게 보여지는 사람이 읽을 수 있는 설명 |
| `prompt` | `String` | 에이전트의 동작을 정의하는 시스템 프롬프트 |
| `tools` | `List<String>`(null 허용) | 허용할 도구 이름. null이면 부모의 도구를 상속 |
| `disallowedTools` | `List<String>`(null 허용) | 그 에이전트가 쓸 수 없는 도구. null이면 없음 |
| `model` | `String`(null 허용) | 모델 별칭("sonnet", "opus", "haiku", "inherit") 또는 전체 모델 ID |
| `skills` | `List<String>`(null 허용) | 그 에이전트가 쓸 수 있는 skill 이름. null이면 상속 |
| `memory` | `MemoryScope`(null 허용) | 메모리 범위. null이면 부모에서 상속 |
| `mcpServers` | `List<Object>`(null 허용) | MCP 서버 참조(이름 또는 인라인 구성). null이면 상속 |
| `initialPrompt` | `String`(null 허용) | 에이전트가 시작될 때 보내는 초기 프롬프트 |
| `maxTurns` | `Integer`(null 허용) | 그 에이전트의 최대 턴 수. null이면 무제한 |
| `background` | `Boolean`(null 허용) | 그 에이전트를 백그라운드로 실행 |
| `effort` | `String`(null 허용) | 노력 수준: `"low"`, `"medium"`, `"high"`, `"xhigh"`, `"max"`. `"xhigh"`는 Opus 4.7 전용이며 다른 모델에서는 `"high"`로 대체됩니다. [`EffortLevel`](feature-configuration-options.md#effortlevel-열거형) 열거형도 참고하세요. |
| `permissionMode` | `String`(null 허용) | 그 에이전트의 권한 모드 |

**model 필드:** `model` 필드는 짧은 별칭(`"sonnet"`, `"opus"`, `"haiku"`, `"inherit"`) 또는 전체
모델 ID(예: `"claude-sonnet-4-5"`)를 받습니다.

### MemoryScope 열거형

에이전트가 어느 메모리 범위에서 동작할지 제어합니다:

```java
import in.vidyalai.claude.sdk.types.config.MemoryScope;

MemoryScope.USER     // "user" — user-level memory
MemoryScope.PROJECT  // "project" — project-scoped memory
MemoryScope.LOCAL    // "local" — local/session-scoped memory
```

## 인라인 에이전트 정의

`ClaudeAgentOptions`를 통해 프로그래밍 방식으로 에이전트를 등록합니다:

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.config.AgentDefinition;

AgentDefinition codeReviewer = new AgentDefinition(
    "Reviews code for best practices and potential issues",
    """
    You are a code reviewer. Analyze code for bugs, performance issues,
    security vulnerabilities, and adherence to best practices.
    Provide constructive feedback.
    """,
    List.of("Read", "Grep"),
    "sonnet"   // model can be "sonnet", "opus", "haiku", or full model ID
);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(Map.of("code-reviewer", codeReviewer))
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();
    client.sendMessage("Use the code-reviewer agent to review MyClass.java");

    for (Message msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### 여러 에이전트

한 세션에서 여러 에이전트를 정의할 수 있습니다:

```java
AgentDefinition analyzer = new AgentDefinition(
    "Analyzes code structure and patterns",
    "You are a code analyzer. Examine code structure, patterns, and architecture.",
    List.of("Read", "Grep", "Glob"),
    null  // inherit model from parent
);

AgentDefinition tester = new AgentDefinition(
    "Creates and runs tests",
    "You are a testing expert. Write comprehensive tests and ensure code quality.",
    List.of("Read", "Write", "Bash"),
    "sonnet"
);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(Map.of(
        "analyzer", analyzer,
        "tester", tester
    ))
    .build();
```

## 파일시스템 기반 에이전트

`settingSources`를 사용해 디스크의 마크다운 파일에서 에이전트를 불러올 수도 있습니다. 에이전트 정의
파일을 프로젝트 디렉터리의 `.claude/agents/`에 두세요:

```
.claude/
  agents/
    code-reviewer.md
    test-writer.md
```

그런 다음 파일시스템 에이전트 로딩을 활성화합니다:

```java
import in.vidyalai.claude.sdk.types.config.SettingSource;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .settingSources(List.of(SettingSource.PROJECT))
    .cwd(Path.of("/path/to/project"))
    .build();

try (ClaudeSDKClient client = new ClaudeSDKClient(options)) {
    client.connect();
    // Agents defined in .claude/agents/*.md are now available
}
```

어떤 에이전트가 로드되었는지는 `SystemMessage`의 init 이벤트로 확인할 수 있습니다:

```java
for (Message msg : client.receiveResponse()) {
    if (msg instanceof SystemMessage system && "init".equals(system.subtype())) {
        List<String> agents = system.get("agents");
        System.out.println("Loaded agents: " + agents);
    }
}
```

## 대용량 에이전트 정의

에이전트는 CLI 인자가 아니라 SDK 제어 프로토콜의 initialize 요청(stdin 경유)으로 전송됩니다. 따라서
에이전트 정의에는 **크기 제한이 없습니다** — 260KB가 넘는 에이전트 데이터도 안전하게 넘길 수
있습니다.

이 동작은 TypeScript 및 Python SDK 구현과 일치하며, 플랫폼마다 다른 명령줄 인자 길이 제한
(ARG_MAX)을 피합니다.

```java
// Large agents work reliably via stdin
Map<String, AgentDefinition> agents = new HashMap<>();
for (int i = 0; i < 20; i++) {
    String largePrompt = "You are agent #" + i + ". " + "x".repeat(13 * 1024);
    agents.put("agent-" + i, new AgentDefinition("Agent " + i, largePrompt));
}

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(agents)
    .maxTurns(1)
    .build();

// Works for both query() and createClient()
for (Message msg : ClaudeSDK.query("List available agents", options)) {
    // ...
}
```

## 서브에이전트 출력 관찰하기

서브에이전트는 자신만의 대화를 진행하며, 그중 일부만 부모 메시지 스트림에 도달합니다. 도달하는 것은
평범한 `AssistantMessage` / `UserMessage` 객체이며, 그 `parentToolUseId`는 해당 서브에이전트를 생성한
Agent `tool_use` 블록의 id입니다 — 서브에이전트의 메시지를 본 대화의 메시지와 구별하고, 여러 개가
동시에 돌아갈 때 어느 서브에이전트의 것인지 알아내는 수단이 바로 이 필드입니다.

기본적으로는 서브에이전트의 `tool_use`와 `tool_result` 블록만 전달됩니다. 진행 중임을 보여주기에는
충분하지만, 무슨 말을 했는지 표시하기에는 부족합니다. `forwardSubagentText(true)`를 설정하면 텍스트
블록과 사고 블록도 같은 방식으로 전달됩니다:

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .forwardSubagentText(true)
    .agents(Map.of("greeter", greeter))
    .build();

for (Message msg : ClaudeSDK.query(prompt, options)) {
    if (msg instanceof AssistantMessage assistant && assistant.parentToolUseId() != null) {
        // From a subagent — attribute it to the spawning Agent tool_use id.
        System.out.println("[" + assistant.parentToolUseId() + "] " + assistant.getTextContent());
    }
}
```

이 옵션은 CLI 플래그가 아니라 `initialize` 제어 요청으로, 그것도 활성화되었을 때만 전송되므로 예전
CLI에는 영향이 없습니다.

*끝난* 서브에이전트의 전체 트랜스크립트를 읽는 것은 다른 경로입니다 — `listSubagents()`와
`getSubagentMessages()`에 대해서는 [세션 히스토리](./feature-session-history.md)를 참고하세요. 그
결과에도 동일한 `parentToolUseId`가 담기며, 중첩된 서브에이전트에는 `parentAgentId`도 함께 옵니다.

## 백그라운드 서브에이전트와 콜백

`run_in_background`로 실행한 서브에이전트는 부모의 턴이 끝난 뒤에도 계속 돌고, 끝나면 그 완료가 부모를
깨워 후속 턴을 돌게 합니다. 훅, `canUseTool` 콜백, SDK MCP 서버가 있는 일회성 `ClaudeSDK.query(...)`에서는
후속 턴의 그 콜백들이 stdin이 아직 열려 있을 때만 동작합니다. 그래서 SDK는 첫 `result`에서 stdin을 닫지
않습니다:

- `local_agent` 또는 `local_workflow` 작업이 아직 진행 중이면 stdin을 열어 둡니다.
- CLI가 세션 상태를 보고하면 result 이후 `idle`에서 stdin을 닫으므로, result *직전에* 끝난
  서브에이전트가 요구하는 후속 턴도 여전히 처리됩니다.
- 턴 사이의 대기는 `CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS`(기본 10분, `0`이면 제한 없음)로 제한됩니다.

SDK는 `CLAUDE_CODE_SDK_READS_SESSION_STATE`로 CLI에 세션 상태를 요청합니다. Claude Code 2.1.283은 아직
이를 따르지 않습니다. 그런 CLI에서는 진행 중인 작업이 없는 첫 result에서 stdin이 닫히며, 후속 턴의 콜백이
실행되는지는 타이밍에 달려 있습니다. `env()`에 `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1`을 설정하면 지금도
CLI가 상태를 보고하게 할 수 있지만, 그 대가로 `session_state_changed` 프레임도 여러분의 이터레이터에
도달합니다:

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(Map.of("worker", worker))
    .hooks(hooks)
    .env(Map.of("CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS", "1"))
    .build();
```

`ClaudeSDKClient`는 영향을 받지 않습니다: 연결을 끊을 때까지 stdin을 열어 둡니다. 전체 규칙은
[아키텍처 → stdin 수명 주기](./architecture.md#stdin-수명-주기와-실행의-끝)에 있습니다.

## 예제

실행 가능한 완전한 데모는 예제 파일을 참고하세요:

- [`AgentsExample.java`](../../examples/src/main/java/examples/AgentsExample.java) — 코드 리뷰어, 문서 작성자, 여러 에이전트
- [`FilesystemAgentsExample.java`](../../examples/src/main/java/examples/FilesystemAgentsExample.java) — `.claude/agents/` 파일에서 에이전트 불러오기
- [`LargeAgentsExample.java`](../../examples/src/main/java/examples/LargeAgentsExample.java) — 260KB 이상 에이전트 페이로드 스트레스 테스트
- [`ForwardSubagentTextExample.java`](../../examples/src/main/java/examples/ForwardSubagentTextExample.java) — 서브에이전트 텍스트 전달을 끈 경우와 켠 경우의 같은 실행
- [`BackgroundAgentHooksExample.java`](../../examples/src/main/java/examples/BackgroundAgentHooksExample.java) — 백그라운드 서브에이전트의 완료가 깨운 후속 턴에서 처리되는 `PreToolUse` 훅

## 관련 항목

- [구성 옵션](./feature-configuration-options.md) — `agents` 및 `settingSources` 옵션
- [대화형 세션](./feature-interactive-conversations.md) — 멀티턴 세션에서 에이전트 사용하기
