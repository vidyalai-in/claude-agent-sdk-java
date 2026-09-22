# Propagação do W3C Trace Context

> **Sobre esta tradução**: a documentação em inglês é a única autoritativa. Esta tradução pode estar desatualizada em relação ao [original em inglês](../feature-trace-context.md); em caso de divergência, o inglês prevalece. Os blocos de código são mantidos idênticos ao original e não foram traduzidos.

Quando o OpenTelemetry está no classpath e existe um span ativo, o SDK injeta automaticamente os
cabeçalhos de trace do W3C (`TRACEPARENT` e `TRACESTATE`) no ambiente do subprocesso do CLI que é
criado. Isso conecta os spans do SDK e do CLI em um único trace distribuído.

## Zero dependência em tempo de execução

O SDK usa **reflexão** para conversar com o OpenTelemetry, então `opentelemetry-api` **nunca** é
uma dependência de runtime do SDK. Se o OpenTelemetry não estiver no classpath, a propagação de
trace é silenciosamente um no-op.

Para habilitar a propagação, adicione `opentelemetry-api` (e um propagador) às dependências da
**sua aplicação**, não às do SDK.

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <version>1.61.0</version>
</dependency>
```

## Como funciona

1. Antes de cada subprocesso do CLI ser criado, `SubprocessCLITransport.applyEnvDefaults()` chama
   `injectTraceContext()`.
2. `injectTraceContext()` resolve por reflexão
   `GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()`.
3. Ele chama `inject(Context.current(), carrier, setter)` com um `TextMapSetter` proxy que captura
   as chaves emitidas em um `Map` local.
4. Se o carrier contiver `traceparent`, o SDK:
   - Remove qualquer `TRACEPARENT`/`TRACESTATE` já presente no ambiente do subprocesso (para
     evitar emparelhar um `TRACEPARENT` novo com um `TRACESTATE` obsoleto),
   - Grava os valores do carrier (em maiúsculas) no ambiente do subprocesso.
5. Valores fornecidos explicitamente via `ClaudeAgentOptions.env()` sempre prevalecem.

A reflexão tem como alvo as **interfaces públicas** (`OpenTelemetry`, `ContextPropagators`,
`TextMapPropagator`) em vez do wrapper concreto `GlobalOpenTelemetry$ObfuscatedOpenTelemetry`, que
é package-private.

## Matriz de comportamento

| Estado do OTel | Ambiente herdado | Depois de `applyEnvDefaults` |
|---|---|---|
| Fora do classpath | _qualquer coisa_ | inalterado (sem remoção, sem injeção) |
| No classpath, sem span ativo | não definido | não definido |
| No classpath, sem span ativo | `TRACEPARENT=stale` | `TRACEPARENT=stale` (passa adiante) |
| No classpath, com span ativo | não definido | `TRACEPARENT=<active>` |
| No classpath, com span ativo | `TRACEPARENT=stale, TRACESTATE=x` | `TRACEPARENT=<active>` (o `TRACESTATE` obsoleto é removido) |
| No classpath, contexto só com baggage | `TRACEPARENT=stale` | `TRACEPARENT=stale` (nenhum `traceparent` emitido, então nada é removido) |
| O propagador lança exceção | _qualquer coisa_ | inalterado (erros engolidos) |
| `options.env` define `TRACEPARENT=custom` | _qualquer coisa_ | `TRACEPARENT=custom` (sempre prevalece) |

## Início rápido

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

## Propagadores compostos (TraceContext + Baggage)

Um arranjo comum em produção combina `W3CTraceContextPropagator` com `W3CBaggagePropagator`. O SDK
condiciona a remoção à presença da chave literal `traceparent` no carrier — então um contexto só
com baggage (sem span ativo) emite apenas `baggage` e o SDK deixa corretamente intacto o ambiente
W3C herdado:

```java
OpenTelemetry otel = OpenTelemetry.propagating(
    ContextPropagators.create(TextMapPropagator.composite(
        W3CTraceContextPropagator.getInstance(),
        W3CBaggagePropagator.getInstance())));
GlobalOpenTelemetry.set(otel);
```

## O ambiente fornecido pelo usuário sempre prevalece

Qualquer coisa que você colocar em `ClaudeAgentOptions.env()` sobrepõe tanto o ambiente herdado
quanto a saída do propagador. Isso permite fixar um contexto de trace específico para uma chamada
pontual:

```java
var options = ClaudeAgentOptions.builder()
    .env(Map.of("TRACEPARENT", "00-<traceId>-<spanId>-01"))
    .build();
ClaudeSDK.query("...", options);
```

## Modo de falha

Se o propagador lançar uma exceção (configuração errada, conflito de classpath etc.), o SDK engole
a exceção, registra em nível `FINE` e segue com `connect()`. **O tracing nunca pode quebrar o SDK.**

## Veja também

- [Camada de transporte](./feature-transport-layer.md) — como o ambiente do subprocesso é construído
- [Opções de configuração](./feature-configuration-options.md) — o builder `env()`
- [Especificação do W3C Trace Context](https://www.w3.org/TR/trace-context/)
