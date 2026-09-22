# W3C Trace Context の伝播

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../feature-trace-context.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

OpenTelemetry がクラスパス上にあり、アクティブな span が存在する場合、SDK は W3C のトレース
ヘッダ（`TRACEPARENT` と `TRACESTATE`）を、生成する CLI サブプロセスの環境へ自動的に注入します。
これにより SDK の span と CLI の span が 1 本の分散トレースとしてつながります。

## 実行時依存ゼロ

SDK は **リフレクション**を使って OpenTelemetry とやり取りするため、`opentelemetry-api` が SDK の
実行時依存になることは**ありません**。OpenTelemetry がクラスパスにない場合、トレースの伝播は
黙って何もしません。

伝播を有効にするには、`opentelemetry-api`（とプロパゲータ）を SDK ではなく**あなたのアプリケー
ション**の依存に追加してください。

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <version>1.61.0</version>
</dependency>
```

## 仕組み

1. CLI サブプロセスを生成する直前に、`SubprocessCLITransport.applyEnvDefaults()` が
   `injectTraceContext()` を呼び出します。
2. `injectTraceContext()` は
   `GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()` をリフレクションで解決します。
3. 発行されたキーをローカルの `Map` に取り込むプロキシ `TextMapSetter` を伴って
   `inject(Context.current(), carrier, setter)` を呼び出します。
4. carrier に `traceparent` が含まれていれば、SDK は次を行います：
   - サブプロセス環境にすでにある `TRACEPARENT`/`TRACESTATE` を除去します（新しい
     `TRACEPARENT` と古い `TRACESTATE` が組み合わさるのを避けるため）。
   - carrier の値を（大文字にして）サブプロセス環境へ書き込みます。
5. `ClaudeAgentOptions.env()` で明示的に指定した値が常に優先されます。

リフレクションの対象は、パッケージプライベートな具象ラッパー
`GlobalOpenTelemetry$ObfuscatedOpenTelemetry` ではなく、**公開インターフェース**
（`OpenTelemetry`、`ContextPropagators`、`TextMapPropagator`）です。

## 挙動の一覧

| OTel の状態 | 継承された環境変数 | `applyEnvDefaults` の後 |
|---|---|---|
| クラスパスにない | _任意_ | 変化なし（除去も注入もしない） |
| クラスパスにあり、アクティブな span なし | 未設定 | 未設定 |
| クラスパスにあり、アクティブな span なし | `TRACEPARENT=stale` | `TRACEPARENT=stale`（そのまま通過） |
| クラスパスにあり、アクティブな span あり | 未設定 | `TRACEPARENT=<active>` |
| クラスパスにあり、アクティブな span あり | `TRACEPARENT=stale, TRACESTATE=x` | `TRACEPARENT=<active>`（古い `TRACESTATE` は除去） |
| クラスパスにあり、baggage のみのコンテキスト | `TRACEPARENT=stale` | `TRACEPARENT=stale`（`traceparent` が出ないため除去なし） |
| プロパゲータが例外を投げる | _任意_ | 変化なし（例外は握りつぶされる） |
| `options.env` が `TRACEPARENT=custom` を設定 | _任意_ | `TRACEPARENT=custom`（常に優先） |

## クイックスタート

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

## 複合プロパゲータ（TraceContext + Baggage）

本番でよくある構成は、`W3CTraceContextPropagator` と `W3CBaggagePropagator` の組み合わせです。
SDK は carrier に文字列キー `traceparent` が存在するかどうかで除去を行うかを判断します。したがって
baggage のみのコンテキスト（アクティブな span なし）は `baggage` しか発行せず、SDK は継承された
W3C の環境変数を正しくそのまま残します：

```java
OpenTelemetry otel = OpenTelemetry.propagating(
    ContextPropagators.create(TextMapPropagator.composite(
        W3CTraceContextPropagator.getInstance(),
        W3CBaggagePropagator.getInstance())));
GlobalOpenTelemetry.set(otel);
```

## ユーザー指定の環境変数が常に優先

`ClaudeAgentOptions.env()` に入れたものは、継承された環境変数とプロパゲータの出力の両方を
上書きします。これにより、単発の呼び出しに特定のトレースコンテキストを固定できます：

```java
var options = ClaudeAgentOptions.builder()
    .env(Map.of("TRACEPARENT", "00-<traceId>-<spanId>-01"))
    .build();
ClaudeSDK.query("...", options);
```

## 失敗時の挙動

プロパゲータが例外を投げた場合（設定ミス、クラスパスの競合など）、SDK は例外を握りつぶし、
`FINE` レベルでログを出し、`connect()` を続行します。**トレーシングが SDK を壊してはなりません。**

## 関連項目

- [トランスポート層](./feature-transport-layer.md) —— サブプロセス環境の構築方法
- [設定オプション](./feature-configuration-options.md) —— `env()` ビルダー
- [W3C Trace Context 仕様](https://www.w3.org/TR/trace-context/)
