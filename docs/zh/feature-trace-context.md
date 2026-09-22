# W3C Trace Context 传播

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../feature-trace-context.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

当 OpenTelemetry 位于 classpath 上且存在活动 span 时，SDK 会自动把 W3C 追踪头
（`TRACEPARENT` 与 `TRACESTATE`）注入所派生的 CLI 子进程环境中。这会把 SDK 的 span 与 CLI 的
span 串联成同一条分布式链路。

## 运行时零依赖

SDK 通过**反射**与 OpenTelemetry 通信，因此 `opentelemetry-api` **绝不会**成为 SDK 的运行时
依赖。如果 OpenTelemetry 不在 classpath 上，链路传播会静默地成为空操作。

要启用传播，请把 `opentelemetry-api`（以及一个传播器）加入**你的应用**的依赖，而不是 SDK 的。

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <version>1.61.0</version>
</dependency>
```

## 工作原理

1. 在每次派生 CLI 子进程之前，`SubprocessCLITransport.applyEnvDefaults()` 会调用
   `injectTraceContext()`。
2. `injectTraceContext()` 通过反射解析
   `GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()`。
3. 它以一个代理 `TextMapSetter` 调用 `inject(Context.current(), carrier, setter)`，该 setter 会把
   发出的键收集到一个本地 `Map` 中。
4. 如果 carrier 中包含 `traceparent`，SDK 会：
   - 清除子进程环境中已有的任何 `TRACEPARENT`/`TRACESTATE`（避免把新的 `TRACEPARENT` 与陈旧的
     `TRACESTATE` 配对），
   - 把 carrier 中的值（转为大写）写入子进程环境。
5. 通过 `ClaudeAgentOptions.env()` 显式提供的值始终优先。

反射的目标是**公开接口**（`OpenTelemetry`、`ContextPropagators`、`TextMapPropagator`），而不是
包级私有的具体包装类 `GlobalOpenTelemetry$ObfuscatedOpenTelemetry`。

## 行为对照表

| OTel 状态 | 继承的环境变量 | `applyEnvDefaults` 之后 |
|---|---|---|
| 不在 classpath 上 | _任意_ | 保持不变（既不清除也不注入） |
| 在 classpath 上，无活动 span | 未设置 | 未设置 |
| 在 classpath 上，无活动 span | `TRACEPARENT=stale` | `TRACEPARENT=stale`（原样透传） |
| 在 classpath 上，有活动 span | 未设置 | `TRACEPARENT=<active>` |
| 在 classpath 上，有活动 span | `TRACEPARENT=stale, TRACESTATE=x` | `TRACEPARENT=<active>`（陈旧的 `TRACESTATE` 被清除） |
| 在 classpath 上，仅含 baggage 的上下文 | `TRACEPARENT=stale` | `TRACEPARENT=stale`（未发出 `traceparent`，因此不清除） |
| 传播器抛出异常 | _任意_ | 保持不变（异常被吞掉） |
| `options.env` 设置了 `TRACEPARENT=custom` | _任意_ | `TRACEPARENT=custom`（始终优先） |

## 快速上手

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

## 复合传播器（TraceContext + Baggage）

生产环境中常见的做法是把 `W3CTraceContextPropagator` 与 `W3CBaggagePropagator` 组合起来。SDK
以 carrier 中是否存在字面量键 `traceparent` 作为是否清除的判据 —— 因此仅含 baggage 的上下文
（没有活动 span）只会发出 `baggage`，SDK 会正确地保持继承而来的 W3C 环境变量不变：

```java
OpenTelemetry otel = OpenTelemetry.propagating(
    ContextPropagators.create(TextMapPropagator.composite(
        W3CTraceContextPropagator.getInstance(),
        W3CBaggagePropagator.getInstance())));
GlobalOpenTelemetry.set(otel);
```

## 用户提供的环境变量始终优先

你放进 `ClaudeAgentOptions.env()` 的任何内容都会覆盖继承的环境变量与传播器的输出。这让你可以
为一次性调用固定某个特定的追踪上下文：

```java
var options = ClaudeAgentOptions.builder()
    .env(Map.of("TRACEPARENT", "00-<traceId>-<spanId>-01"))
    .build();
ClaudeSDK.query("...", options);
```

## 失败模式

如果传播器抛出异常（配置错误、classpath 冲突等），SDK 会吞掉该异常，以 `FINE` 级别记录日志，
并继续执行 `connect()`。**追踪绝不应该让 SDK 出问题。**

## 另见

- [传输层](./feature-transport-layer.md) —— 子进程环境是如何构建的
- [配置选项](./feature-configuration-options.md) —— `env()` builder 方法
- [W3C Trace Context 规范](https://www.w3.org/TR/trace-context/)
