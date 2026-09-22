# W3C Trace Context 전파

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../feature-trace-context.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

OpenTelemetry가 클래스패스에 있고 활성 span이 존재하면, SDK는 W3C 추적 헤더(`TRACEPARENT`와
`TRACESTATE`)를 생성되는 CLI 서브프로세스 환경에 자동으로 주입합니다. 이렇게 하면 SDK의 span과
CLI의 span이 하나의 분산 추적으로 연결됩니다.

## 런타임 의존성 없음

SDK는 **리플렉션**으로 OpenTelemetry와 통신하므로, `opentelemetry-api`는 **결코** SDK의 런타임
의존성이 되지 않습니다. OpenTelemetry가 클래스패스에 없으면 추적 전파는 조용히 아무 일도 하지
않습니다.

전파를 활성화하려면 `opentelemetry-api`(그리고 전파기)를 SDK가 아니라 **여러분 애플리케이션**의
의존성에 추가하세요.

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <version>1.61.0</version>
</dependency>
```

## 동작 방식

1. CLI 서브프로세스를 생성하기 전에 `SubprocessCLITransport.applyEnvDefaults()`가
   `injectTraceContext()`를 호출합니다.
2. `injectTraceContext()`는
   `GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()`를 리플렉션으로 해석합니다.
3. 발행된 키를 지역 `Map`에 담는 프록시 `TextMapSetter`와 함께
   `inject(Context.current(), carrier, setter)`를 호출합니다.
4. carrier에 `traceparent`가 들어 있으면 SDK는 다음을 수행합니다:
   - 서브프로세스 환경에 이미 있는 `TRACEPARENT`/`TRACESTATE`를 제거합니다(새 `TRACEPARENT`가
     오래된 `TRACESTATE`와 짝지어지는 것을 막기 위해).
   - carrier의 값을 대문자로 바꿔 서브프로세스 환경에 기록합니다.
5. `ClaudeAgentOptions.env()`로 명시적으로 준 값은 항상 우선합니다.

리플렉션은 패키지 전용인 구체 래퍼 `GlobalOpenTelemetry$ObfuscatedOpenTelemetry`가 아니라
**공개 인터페이스**(`OpenTelemetry`, `ContextPropagators`, `TextMapPropagator`)를 대상으로 합니다.

## 동작 표

| OTel 상태 | 상속된 환경 변수 | `applyEnvDefaults` 이후 |
|---|---|---|
| 클래스패스에 없음 | _무엇이든_ | 변화 없음(제거도 주입도 하지 않음) |
| 클래스패스에 있음, 활성 span 없음 | 미설정 | 미설정 |
| 클래스패스에 있음, 활성 span 없음 | `TRACEPARENT=stale` | `TRACEPARENT=stale`(그대로 통과) |
| 클래스패스에 있음, 활성 span 있음 | 미설정 | `TRACEPARENT=<active>` |
| 클래스패스에 있음, 활성 span 있음 | `TRACEPARENT=stale, TRACESTATE=x` | `TRACEPARENT=<active>`(오래된 `TRACESTATE` 제거) |
| 클래스패스에 있음, baggage만 있는 컨텍스트 | `TRACEPARENT=stale` | `TRACEPARENT=stale`(`traceparent`가 나오지 않아 제거 없음) |
| 전파기가 예외를 던짐 | _무엇이든_ | 변화 없음(예외는 삼켜짐) |
| `options.env`가 `TRACEPARENT=custom` 설정 | _무엇이든_ | `TRACEPARENT=custom`(항상 우선) |

## 빠른 시작

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import in.vidyalai.claude.sdk.ClaudeSDK;

// 1. Register a global OpenTelemetry instance with W3C propagator
OpenTelemetry otel = OpenTelemetry.propagating(
    ContextPropagators.create(W3CTraceContextPropagator.getInstance()));
GlobalOpenTelemetry.set(otel);

// 2. Run SDK code inside an active span
var tracer = otel.getTracer("my-app");
var span = tracer.spanBuilder("call-claude").startSpan();
try (var scope = span.makeCurrent()) {
    // The CLI subprocess spawned here will inherit TRACEPARENT
    ClaudeSDK.query("What is 2 + 2?");
} finally {
    span.end();
}
```

## 복합 전파기 (TraceContext + Baggage)

운영 환경에서 흔한 구성은 `W3CTraceContextPropagator`와 `W3CBaggagePropagator`를 결합하는
것입니다. SDK는 carrier에 문자 그대로의 `traceparent` 키가 있는지를 기준으로 제거 여부를
판단합니다. 따라서 baggage만 있는 컨텍스트(활성 span 없음)는 `baggage`만 발행하고, SDK는 상속된
W3C 환경 변수를 올바르게 그대로 둡니다:

```java
OpenTelemetry otel = OpenTelemetry.propagating(
    ContextPropagators.create(TextMapPropagator.composite(
        W3CTraceContextPropagator.getInstance(),
        W3CBaggagePropagator.getInstance())));
GlobalOpenTelemetry.set(otel);
```

## 사용자가 지정한 환경 변수가 항상 우선

`ClaudeAgentOptions.env()`에 넣은 값은 상속된 환경 변수와 전파기의 출력을 모두 덮어씁니다.
이를 이용해 일회성 호출에 특정 추적 컨텍스트를 고정할 수 있습니다:

```java
var options = ClaudeAgentOptions.builder()
    .env(Map.of("TRACEPARENT", "00-<traceId>-<spanId>-01"))
    .build();
ClaudeSDK.query("...", options);
```

## 실패 시 동작

전파기가 예외를 던지면(설정 오류, 클래스패스 충돌 등) SDK는 예외를 삼키고 `FINE` 수준으로
로그를 남긴 뒤 `connect()`를 계속 진행합니다. **추적이 SDK를 망가뜨려서는 안 됩니다.**

## 관련 항목

- [전송 계층](./feature-transport-layer.md) — 서브프로세스 환경이 구성되는 방식
- [구성 옵션](./feature-configuration-options.md) — `env()` 빌더
- [W3C Trace Context 명세](https://www.w3.org/TR/trace-context/)
