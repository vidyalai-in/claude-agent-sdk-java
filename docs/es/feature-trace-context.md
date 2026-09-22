# Propagación de W3C Trace Context

> **Sobre esta traducción**: la documentación en inglés es la única autoritativa. Esta traducción puede estar desactualizada respecto al [original en inglés](../feature-trace-context.md); si hay discrepancias, prevalece el inglés. Los bloques de código se mantienen idénticos al original y no se han traducido.

Cuando OpenTelemetry está en el classpath y existe un span activo, el SDK inyecta automáticamente
las cabeceras de traza W3C (`TRACEPARENT` y `TRACESTATE`) en el entorno del subproceso del CLI que
se crea. Así, los spans del SDK y los del CLI se unen en una única traza distribuida.

## Cero dependencias en tiempo de ejecución

El SDK usa **reflexión** para hablar con OpenTelemetry, de modo que `opentelemetry-api` **nunca**
es una dependencia de runtime del SDK. Si OpenTelemetry no está en el classpath, la propagación de
trazas no hace nada en silencio.

Para habilitar la propagación, añade `opentelemetry-api` (y un propagador) a las dependencias de
**tu aplicación**, no a las del SDK.

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <version>1.61.0</version>
</dependency>
```

## Cómo funciona

1. Antes de crear cada subproceso del CLI, `SubprocessCLITransport.applyEnvDefaults()` llama a
   `injectTraceContext()`.
2. `injectTraceContext()` resuelve por reflexión
   `GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()`.
3. Llama a `inject(Context.current(), carrier, setter)` con un `TextMapSetter` proxy que recoge las
   claves emitidas en un `Map` local.
4. Si el carrier contiene `traceparent`, el SDK:
   - Elimina cualquier `TRACEPARENT`/`TRACESTATE` ya presente en el entorno del subproceso (para no
     emparejar un `TRACEPARENT` nuevo con un `TRACESTATE` obsoleto),
   - Escribe los valores del carrier (en mayúsculas) en el entorno del subproceso.
5. Los valores indicados explícitamente con `ClaudeAgentOptions.env()` siempre ganan.

La reflexión apunta a las **interfaces públicas** (`OpenTelemetry`, `ContextPropagators`,
`TextMapPropagator`) en lugar del envoltorio concreto
`GlobalOpenTelemetry$ObfuscatedOpenTelemetry`, que es package-private.

## Matriz de comportamiento

| Estado de OTel | Entorno heredado | Después de `applyEnvDefaults` |
|---|---|---|
| Fuera del classpath | _lo que sea_ | sin cambios (ni limpieza ni inyección) |
| En el classpath, sin span activo | sin definir | sin definir |
| En el classpath, sin span activo | `TRACEPARENT=stale` | `TRACEPARENT=stale` (pasa tal cual) |
| En el classpath, con span activo | sin definir | `TRACEPARENT=<active>` |
| En el classpath, con span activo | `TRACEPARENT=stale, TRACESTATE=x` | `TRACEPARENT=<active>` (se elimina el `TRACESTATE` obsoleto) |
| En el classpath, contexto solo con baggage | `TRACEPARENT=stale` | `TRACEPARENT=stale` (no se emite `traceparent`, así que no se limpia) |
| El propagador lanza una excepción | _lo que sea_ | sin cambios (los errores se tragan) |
| `options.env` define `TRACEPARENT=custom` | _lo que sea_ | `TRACEPARENT=custom` (siempre gana) |

## Inicio rápido

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

## Propagadores compuestos (TraceContext + Baggage)

Una configuración habitual en producción combina `W3CTraceContextPropagator` con
`W3CBaggagePropagator`. El SDK condiciona la limpieza a la presencia de la clave literal
`traceparent` en el carrier, de modo que un contexto solo con baggage (sin span activo) emite solo
`baggage` y el SDK deja correctamente intacto el entorno W3C heredado:

```java
OpenTelemetry otel = OpenTelemetry.propagating(
    ContextPropagators.create(TextMapPropagator.composite(
        W3CTraceContextPropagator.getInstance(),
        W3CBaggagePropagator.getInstance())));
GlobalOpenTelemetry.set(otel);
```

## El entorno indicado por el usuario siempre gana

Todo lo que pongas en `ClaudeAgentOptions.env()` prevalece tanto sobre el entorno heredado como
sobre la salida del propagador. Esto te permite fijar un contexto de traza concreto para una
llamada puntual:

```java
var options = ClaudeAgentOptions.builder()
    .env(Map.of("TRACEPARENT", "00-<traceId>-<spanId>-01"))
    .build();
ClaudeSDK.query("...", options);
```

## Modo de fallo

Si el propagador lanza una excepción (mala configuración, conflicto de classpath, etc.), el SDK se
traga la excepción, registra en nivel `FINE` y continúa con `connect()`. **El trazado nunca debe
romper el SDK.**

## Véase también

- [Capa de transporte](./feature-transport-layer.md): cómo se construye el entorno del subproceso
- [Opciones de configuración](./feature-configuration-options.md): el builder `env()`
- [Especificación de W3C Trace Context](https://www.w3.org/TR/trace-context/)
