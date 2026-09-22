# 확장 사고 구성

세밀한 구성 옵션으로 Claude의 확장 사고 동작을 제어합니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../feature-thinking-config.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

## 목차
- [개요](#개요)
- [ThinkingConfig 타입](#thinkingconfig-타입)
- [사고 표시](#사고-표시)
- [노력 수준](#노력-수준)
- [사용 예제](#사용-예제)
- [패턴 매칭](#패턴-매칭)
- [모범 사례](#모범-사례)
- [API 레퍼런스](#api-레퍼런스)

## 개요

확장 사고를 쓰면 Claude가 응답을 생성하기 전에 추가 추론 토큰을 사용할 수 있습니다. SDK는 두 가지
구성 옵션을 제공합니다:

1. **ThinkingConfig** — 사고를 켤지 여부와 토큰 예산을 제어합니다
2. **Effort** — 사고의 깊이/강도 수준을 설정합니다

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(16000))  // 16K thinking tokens
    .effort("high")                               // High effort level
    .build();
```

**참고**: `thinking()`은 더 이상 권장되지 않는 `maxThinkingTokens()` 옵션보다 우선합니다.

## ThinkingConfig 타입

ThinkingConfig는 세 가지 변형을 가진 sealed 인터페이스입니다:

### ThinkingConfigAdaptive

얼마나 사고할지를 시스템이 자동으로 정하는 적응형 사고를 사용합니다. CLI에
`--thinking adaptive`를 전달합니다.

```java
// Default — no display override
ThinkingConfig config = new ThinkingConfigAdaptive();

// With explicit display
ThinkingConfig display = new ThinkingConfigAdaptive(ThinkingDisplay.SUMMARIZED);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigAdaptive())
    .build();
```

**매개변수**:
- `display`(선택, `null` 가능) — 아래 [사고 표시](#사고-표시)를 참고하세요. 설정하면
  `--thinking-display <value>`로 전달됩니다.

**적합한 경우**:
- 복잡한 추론 작업
- 답이 열려 있는 문제
- 사고 깊이를 Claude가 정하길 바랄 때
- 조사 및 분석 작업

### ThinkingConfigEnabled

특정 토큰 예산으로 사고를 활성화합니다. CLI에 `--max-thinking-tokens <budgetTokens>`를 전달합니다.
`display`가 설정되면 `--thinking-display <value>`도 전달합니다.

```java
// Default — no display override
ThinkingConfig config = new ThinkingConfigEnabled(10000);  // 10K tokens

// With explicit display
ThinkingConfig display = new ThinkingConfigEnabled(10000, ThinkingDisplay.OMITTED);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(8000))  // 8K token budget
    .build();
```

**매개변수**:
- `budgetTokens`(int) — 최대 사고 토큰 수(양수여야 합니다)
- `display`(선택, `null` 가능) — 아래 [사고 표시](#사고-표시)를 참고하세요

**던짐**: `budgetTokens ≤ 0`이면 `IllegalArgumentException`

**적합한 경우**:
- 예산을 신경 쓰는 애플리케이션
- 예측 가능한 비용 관리
- 복잡도 수준을 이미 알고 있을 때
- 테스트와 벤치마킹

### ThinkingConfigDisabled

확장 사고를 완전히 끕니다. CLI에 `--thinking disabled`를 전달합니다.

```java
ThinkingConfig config = new ThinkingConfigDisabled();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigDisabled())
    .build();
```

**적합한 경우**:
- 단순한 질의와 응답
- 지연 시간이 결정적으로 중요한 때
- 비용에 민감한 작업
- 추론이 필요 없는 단순한 작업

## 사고 표시

`ThinkingDisplay`는 모델이 사고 텍스트를 반환할지, 서명 블록만 반환할지를 제어합니다. CLI에는
`--thinking-display <value>`로 전달됩니다.

```java
public enum ThinkingDisplay {
    SUMMARIZED("summarized"),  // Return thinking text in the assistant stream
    OMITTED("omitted");        // Omit thinking text; return signature blocks only
}
```

**설정이 필요한 때**:
- Opus 4.7 이상은 기본값이 `omitted`(서명만)입니다. 스트림에 텍스트가 필요하면 `SUMMARIZED`를
  넘기세요.
- 이전 모델은 둘 다 넘길 수 있습니다 — adaptive와 enabled 모두 `display` 필드를 존중합니다.

**호환성**:
- `ThinkingConfigAdaptive`와 `ThinkingConfigEnabled`에서만 전달됩니다. `ThinkingConfigDisabled`는
  결코 `--thinking-display`를 내보내지 않습니다.
- `display`를 `null`로 두면(인자 없는 생성자) CLI의 모델별 기본값이 사용됩니다.

```java
// Force summarized output regardless of model default
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigAdaptive(ThinkingDisplay.SUMMARIZED))
    .build();

// Suppress thinking text on a fixed budget
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(20000, ThinkingDisplay.OMITTED))
    .build();
```

## 노력 수준

`effort` 옵션은 사고의 깊이/강도를 제어합니다. 유효한 값:

| 수준 | 설명 | 사용 사례 |
|-------|-------------|----------|
| `"low"` | 최소한의 사고, 가장 빠른 응답 | 단순 질의, 빠른 응답 |
| `"medium"` | 적당한 사고 | 범용 작업 |
| `"high"` | 깊은 추론(기본값) | 복잡한 문제, 상세한 분석 |
| `"xhigh"` | 더 깊은 추론(Opus 4.7 전용) | Opus 4.7에서 가장 어려운 문제 |
| `"max"` | 최대 노력 | 조사, 중요한 추론 |

```java
// Raw string overload
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .effort("xhigh")        // Opus 4.7-specific; falls back to "high" on other models
    .model("claude-opus-4-7")
    .build();

// Type-safe EffortLevel enum overload (recommended)
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .effort(EffortLevel.XHIGH)
    .model("claude-opus-4-7")
    .build();
```

**참고**:

- 노력 수준은 ThinkingConfig와 함께 작동합니다. ThinkingConfig를 명시적으로 설정하지 않고 effort만
  사용할 수도 있습니다.
- `"xhigh"`는 **Opus 4.7 전용**이며 다른 모델에서는 `"high"`로 대체됩니다. 내부 필드는 그냥
  `String`이므로, 앞으로 추가될 effort 값도 SDK를 올리지 않고 넘길 수 있습니다.
- `in.vidyalai.claude.sdk.types.config.EffortLevel`의 `EffortLevel` 열거형은 Python SDK가 내보내는
  `EffortLevel` 타입 별칭에 대응하며, 새 코드에서는 이쪽을 권장합니다. `String` 오버로드는 완전한
  유연성을 위해(그리고 아직 열거형에 없는 앞으로의 수준을 위해) 남아 있습니다. 자세한 내용은
  [EffortLevel 열거형](feature-configuration-options.md#effortlevel-열거형)을 참고하세요.

## 사용 예제

### 적응형 사고를 곁들인 단순 질의

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.config.ThinkingConfigAdaptive;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigAdaptive())
    .build();

List<Message> messages = ClaudeSDK.query(
    "Explain the halting problem in computer science",
    options
);
```

### 예산을 통제한 사고

```java
import in.vidyalai.claude.sdk.types.config.ThinkingConfigEnabled;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(5000))  // Max 5K tokens
    .effort("medium")
    .maxBudgetUsd(1.0)  // Also limit total cost
    .build();

List<Message> messages = ClaudeSDK.query(
    "What are the key differences between Java and Python?",
    options
);
```

### 속도를 위해 사고 끄기

```java
import in.vidyalai.claude.sdk.types.config.ThinkingConfigDisabled;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigDisabled())
    .build();

// Fast response for simple query
String response = ClaudeSDK.queryForText(
    "What is the capital of France?",
    options
);
```

### 높은 노력의 조사 작업

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigAdaptive())
    .effort("max")  // Maximum thinking depth
    .maxTurns(20)   // Allow extended conversation
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect("Research the latest developments in quantum computing");

    for (Message msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### 사고를 곁들인 대화형 세션

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(12000))
    .effort("high")
    .includePartialMessages(true)  // Stream thinking blocks
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();

    client.sendMessage("Help me debug this algorithm");
    for (Message msg : client.receiveResponse()) {
        switch (msg) {
            case ThinkingBlock thinking ->
                System.out.println("Thinking: " + thinking.thinking());
            case AssistantMessage assistant ->
                System.out.println("Response: " + assistant.getTextContent());
            default -> {}
        }
    }

    // Continue conversation
    client.sendMessage("Now optimize it for performance");
    // ... receive response
}
```

### 확장 사고와 베타 기능 함께 쓰기

```java
import in.vidyalai.claude.sdk.types.config.SdkBeta;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .betas(List.of(SdkBeta.CONTEXT_1M))  // Extended context
    .thinking(new ThinkingConfigEnabled(16000))
    .effort("high")
    .build();

// Large context with deep thinking
List<Message> messages = ClaudeSDK.query(
    "Analyze this entire codebase and suggest improvements",
    options
);
```

## 패턴 매칭

Java의 패턴 매칭으로 ThinkingConfig 타입별로 처리하세요:

```java
ThinkingConfig config = options.thinking();

if (config != null) {
    switch (config) {
        case ThinkingConfigAdaptive adaptive ->
            System.out.println("Using adaptive thinking (32K default)");
        case ThinkingConfigEnabled enabled ->
            System.out.println("Budget: " + enabled.budgetTokens() + " tokens");
        case ThinkingConfigDisabled disabled ->
            System.out.println("Thinking disabled");
    }
}
```

타입 안전한 확인:

```java
if (config instanceof ThinkingConfigEnabled enabled) {
    int budget = enabled.budgetTokens();
    System.out.println("Thinking budget: " + budget);
}
```

## 모범 사례

### 각 타입을 언제 쓸까

**ThinkingConfigAdaptive**:
- ✅ 복잡한 추론 작업
- ✅ 문제의 복잡도를 모를 때
- ✅ 조사와 분석
- ❌ 예산에 민감한 애플리케이션
- ❌ 단순 질의

**ThinkingConfigEnabled**:
- ✅ 예산 통제가 필요할 때
- ✅ 복잡도 수준을 알 때
- ✅ 운영 애플리케이션
- ✅ 테스트와 벤치마킹
- ❌ 최적 예산을 모를 때

**ThinkingConfigDisabled**:
- ✅ 단순 질의
- ✅ 지연 시간이 중요한 애플리케이션
- ✅ 비용 최소화
- ❌ 복잡한 추론이 필요할 때
- ❌ 조사 작업

### thinking과 effort 조합하기

```java
// Low complexity - disable thinking
.thinking(new ThinkingConfigDisabled())
.effort("low")

// Medium complexity - fixed budget
.thinking(new ThinkingConfigEnabled(8000))
.effort("medium")

// High complexity - adaptive with high effort
.thinking(new ThinkingConfigAdaptive())
.effort("high")

// Maximum reasoning - adaptive with max effort
.thinking(new ThinkingConfigAdaptive())
.effort("max")
```

### 비용 최적화

```java
// Optimize for cost
ClaudeAgentOptions costOptimized = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(3000))  // Low token budget
    .effort("low")
    .maxBudgetUsd(0.50)  // Hard cost limit
    .maxTurns(5)  // Limit conversation length
    .build();

// Optimize for quality
ClaudeAgentOptions qualityOptimized = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigAdaptive())  // Full adaptive thinking
    .effort("max")  // Maximum effort
    .maxTurns(50)  // Allow extended reasoning
    .build();

// Balanced approach
ClaudeAgentOptions balanced = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(10000))  // Moderate budget
    .effort("medium")  // Standard effort
    .maxBudgetUsd(2.0)  // Reasonable limit
    .build();
```

### maxThinkingTokens에서 옮겨 가기

`thinking()` 옵션이 더 이상 권장되지 않는 `maxThinkingTokens()`를 대체합니다:

```java
// Old (deprecated)
.maxThinkingTokens(10000)

// New (recommended)
.thinking(new ThinkingConfigEnabled(10000))

// Note: thinking() takes precedence if both are set
```

## API 레퍼런스

### ThinkingConfig 인터페이스

```java
public sealed interface ThinkingConfig
    permits ThinkingConfigAdaptive, ThinkingConfigEnabled, ThinkingConfigDisabled

String type()  // Returns "adaptive", "enabled", or "disabled"
```

### ThinkingConfigAdaptive 레코드

```java
public record ThinkingConfigAdaptive(@Nullable ThinkingDisplay display) implements ThinkingConfig {
    public ThinkingConfigAdaptive() { this(null); }   // convenience: no display override
}
```

CLI 플래그: `--thinking adaptive`(항상), `--thinking-display <value>`(`display != null`일 때).

### ThinkingConfigEnabled 레코드

```java
public record ThinkingConfigEnabled(
    int budgetTokens,
    @Nullable ThinkingDisplay display
) implements ThinkingConfig {
    public ThinkingConfigEnabled(int budgetTokens) { this(budgetTokens, null); }
}
```

**매개변수**:
- `budgetTokens` — 최대 사고 토큰 수(> 0이어야 합니다)
- `display`(선택, `null` 가능) — 아래 `ThinkingDisplay` 참고

**던짐**: `budgetTokens ≤ 0`이면 `IllegalArgumentException`

CLI 플래그: `--max-thinking-tokens <budgetTokens>`(항상), `--thinking-display <value>`
(`display != null`일 때).

### ThinkingConfigDisabled 레코드

```java
public record ThinkingConfigDisabled() implements ThinkingConfig
```

CLI 플래그: `--thinking disabled`. disabled 변형에서는 `--thinking-display`가 절대 나가지 않습니다.

### ThinkingDisplay 열거형

```java
public enum ThinkingDisplay {
    SUMMARIZED("summarized"),
    OMITTED("omitted");
}
```

`--thinking-display`의 값으로 전달됩니다.

### ClaudeAgentOptions 메서드

```java
// Builder methods
ClaudeAgentOptions.Builder thinking(ThinkingConfig thinking)
ClaudeAgentOptions.Builder effort(String effort)

// Getter methods
ThinkingConfig thinking()
String effort()
```

## 관련 기능

- [구성 옵션](./feature-configuration-options.md) — 모든 구성 옵션
- [메시지 타입](./feature-message-types.md) — ThinkingBlock 메시지
- [스트리밍 이벤트](./feature-streaming-events.md) — 사고 블록 스트리밍
- [베타 기능](./feature-configuration-options.md#betas) — 확장 컨텍스트와 사고

## 예제 코드

전체 시연은 다음 예제를 참고하세요:
- `examples/AdvancedFeatures.java` — betaFeatures()와 completeConfiguration() 메서드
- `sdk/src/test/java/in/vidyalai/claude/sdk/ClaudeAgentOptionsTest.java` — 단위 테스트
