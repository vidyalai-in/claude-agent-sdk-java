# W3C Trace Context Propagation

When OpenTelemetry is on the classpath and an active span exists, the SDK automatically injects W3C trace headers (`TRACEPARENT` and `TRACESTATE`) into the spawned CLI subprocess environment. This connects SDK spans and CLI spans into a single distributed trace.

## Zero Runtime Dependency

The SDK uses **reflection** to talk to OpenTelemetry, so `opentelemetry-api` is **never** a runtime dependency of the SDK. If OpenTelemetry is not on the classpath, trace propagation is a silent no-op.

To enable propagation, add `opentelemetry-api` (and a propagator) to **your application's** dependencies — not the SDK's.

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <version>1.61.0</version>
</dependency>
```

## How It Works

1. Before each CLI subprocess is spawned, `SubprocessCLITransport.applyEnvDefaults()` calls `injectTraceContext()`.
2. `injectTraceContext()` reflectively resolves `GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()`.
3. It calls `inject(Context.current(), carrier, setter)` with a proxy `TextMapSetter` that captures emitted keys into a local `Map`.
4. If the carrier contains `traceparent`, the SDK:
   - Scrubs any `TRACEPARENT`/`TRACESTATE` already in the subprocess environment (to avoid pairing a fresh `TRACEPARENT` with a stale `TRACESTATE`),
   - Writes the carrier values (uppercased) into the subprocess environment.
5. Values explicitly supplied via `ClaudeAgentOptions.env()` always win.

Reflection targets the **public interfaces** (`OpenTelemetry`, `ContextPropagators`, `TextMapPropagator`) instead of the concrete `GlobalOpenTelemetry$ObfuscatedOpenTelemetry` wrapper, which is package-private.

## Behavior Matrix

| OTel state | Inherited env | After `applyEnvDefaults` |
|---|---|---|
| Not on classpath | _anything_ | unchanged (no scrub, no inject) |
| On classpath, no active span | unset | unset |
| On classpath, no active span | `TRACEPARENT=stale` | `TRACEPARENT=stale` (passes through) |
| On classpath, active span | unset | `TRACEPARENT=<active>` |
| On classpath, active span | `TRACEPARENT=stale, TRACESTATE=x` | `TRACEPARENT=<active>` (stale `TRACESTATE` scrubbed) |
| On classpath, baggage-only context | `TRACEPARENT=stale` | `TRACEPARENT=stale` (no `traceparent` emitted, so no scrub) |
| Propagator throws | _anything_ | unchanged (errors swallowed) |
| `options.env` sets `TRACEPARENT=custom` | _anything_ | `TRACEPARENT=custom` (always wins) |

## Quick Start

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

## Composite Propagators (TraceContext + Baggage)

A common production setup combines `W3CTraceContextPropagator` with `W3CBaggagePropagator`. The SDK gates scrubbing on the presence of the literal `traceparent` key in the carrier — so a baggage-only context (no active span) emits `baggage` only and the SDK correctly leaves the inherited W3C env untouched:

```java
OpenTelemetry otel = OpenTelemetry.propagating(
    ContextPropagators.create(TextMapPropagator.composite(
        W3CTraceContextPropagator.getInstance(),
        W3CBaggagePropagator.getInstance())));
GlobalOpenTelemetry.set(otel);
```

## User-Supplied Env Always Wins

Anything you put in `ClaudeAgentOptions.env()` overrides both the inherited environment and the propagator output. This lets you pin a specific trace context for a one-off call:

```java
var options = ClaudeAgentOptions.builder()
    .env(Map.of("TRACEPARENT", "00-<traceId>-<spanId>-01"))
    .build();
ClaudeSDK.query("...", options);
```

## Failure Mode

If the propagator throws (misconfiguration, classpath conflict, etc.), the SDK swallows the exception, logs at `FINE` level, and proceeds with `connect()`. **Tracing must never break the SDK.**

## See Also

- [Transport Layer](./feature-transport-layer.md) — how the subprocess environment is constructed
- [Configuration Options](./feature-configuration-options.md) — `env()` builder
- [W3C Trace Context spec](https://www.w3.org/TR/trace-context/)
