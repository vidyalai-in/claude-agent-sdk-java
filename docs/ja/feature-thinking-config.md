# 拡張思考の設定

きめ細かな設定オプションで Claude の拡張思考の挙動を制御します。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../feature-thinking-config.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

## 目次
- [概要](#概要)
- [ThinkingConfig の型](#thinkingconfig-の型)
- [思考の表示](#思考の表示)
- [エフォートレベル](#エフォートレベル)
- [使用例](#使用例)
- [パターンマッチング](#パターンマッチング)
- [ベストプラクティス](#ベストプラクティス)
- [API リファレンス](#api-リファレンス)

## 概要

拡張思考を使うと、Claude は応答を生成する前に追加の推論トークンを使えます。SDK は 2 つの設定
オプションを提供します：

1. **ThinkingConfig** —— 思考を有効にするかどうかと、トークン予算を制御します
2. **Effort** —— 思考の深さ／強度のレベルを設定します

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(16000))  // 16K thinking tokens
    .effort("high")                               // High effort level
    .build();
```

**注意**：`thinking()` は非推奨の `maxThinkingTokens()` オプションより優先されます。

## ThinkingConfig の型

ThinkingConfig は 3 つのバリアントを持つ sealed インターフェースです：

### ThinkingConfigAdaptive

どれだけ思考するかをシステムが自動的に決める適応的思考を使います。CLI に `--thinking adaptive` を
渡します。

```java
// Default — no display override
ThinkingConfig config = new ThinkingConfigAdaptive();

// With explicit display
ThinkingConfig display = new ThinkingConfigAdaptive(ThinkingDisplay.SUMMARIZED);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigAdaptive())
    .build();
```

**引数**：
- `display`（省略可能、`null` でも可）—— 下の[思考の表示](#思考の表示)を参照。設定すると
  `--thinking-display <value>` として転送されます。

**向いている用途**：
- 複雑な推論タスク
- 答えの開かれた問題
- 思考の深さを Claude に決めさせたいとき
- 調査・分析のタスク

### ThinkingConfigEnabled

特定のトークン予算で思考を有効にします。CLI に `--max-thinking-tokens <budgetTokens>` を渡します。
`display` が設定されていれば `--thinking-display <value>` も渡します。

```java
// Default — no display override
ThinkingConfig config = new ThinkingConfigEnabled(10000);  // 10K tokens

// With explicit display
ThinkingConfig display = new ThinkingConfigEnabled(10000, ThinkingDisplay.OMITTED);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigEnabled(8000))  // 8K token budget
    .build();
```

**引数**：
- `budgetTokens`（int）—— 思考トークンの上限（正の値である必要があります）
- `display`（省略可能、`null` でも可）—— 下の[思考の表示](#思考の表示)を参照

**送出**：`budgetTokens ≤ 0` の場合は `IllegalArgumentException`

**向いている用途**：
- 予算を意識するアプリケーション
- 予測可能なコスト管理
- 複雑さの度合いが分かっているとき
- テストやベンチマーク

### ThinkingConfigDisabled

拡張思考を完全に無効にします。CLI に `--thinking disabled` を渡します。

```java
ThinkingConfig config = new ThinkingConfigDisabled();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .thinking(new ThinkingConfigDisabled())
    .build();
```

**向いている用途**：
- 単純なクエリと応答
- レイテンシが決定的に重要なとき
- コストに敏感な処理
- 推論を要しない素直なタスク

## 思考の表示

`ThinkingDisplay` は、モデルが思考テキストを返すのか、署名ブロックだけを返すのかを制御します。
CLI には `--thinking-display <value>` として転送されます。

```java
public enum ThinkingDisplay {
    SUMMARIZED("summarized"),  // Return thinking text in the assistant stream
    OMITTED("omitted");        // Omit thinking text; return signature blocks only
}
```

**設定すべきとき**：
- Opus 4.7 以降は既定が `omitted`（署名のみ）です。ストリームにテキストが欲しい場合は
  `SUMMARIZED` を渡してください。
- 古いモデルはどちらも渡せます —— adaptive／enabled のどちらも `display` フィールドを尊重します。

**互換性**：
- 転送されるのは `ThinkingConfigAdaptive` と `ThinkingConfigEnabled` のときだけです。
  `ThinkingConfigDisabled` は決して `--thinking-display` を出しません。
- `display` を `null` のままにする（引数なしのコンストラクタ）と、CLI のモデル固有の既定値が
  使われます。

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

## エフォートレベル

`effort` オプションは思考の深さ／強度を制御します。有効な値：

| レベル | 説明 | 用途 |
|-------|-------------|----------|
| `"low"` | 最小限の思考、最速の応答 | 単純なクエリ、素早い応答 |
| `"medium"` | ほどほどの思考 | 汎用のタスク |
| `"high"` | 深い推論（既定） | 複雑な問題、詳細な分析 |
| `"xhigh"` | さらに深い推論（Opus 4.7 のみ） | Opus 4.7 での最難問 |
| `"max"` | 最大のエフォート | 調査、重要な推論 |

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

**補足**：

- エフォートレベルは ThinkingConfig と組み合わせて働きます。ThinkingConfig を明示的に設定しなくても
  effort だけを使えます。
- `"xhigh"` は **Opus 4.7 専用**で、他のモデルでは `"high"` にフォールバックします。内部のフィールドは
  素の `String` のままなので、将来の effort 値も SDK を上げずに渡せます。
- `in.vidyalai.claude.sdk.types.config.EffortLevel` の `EffortLevel` 列挙型は、Python SDK が公開して
  いる `EffortLevel` 型エイリアスに対応しており、新しいコードではこちらを推奨します。`String` の
  オーバーロードは、完全な柔軟性のため（そして列挙型にまだない将来のレベルのため）残されています。
  詳細は [EffortLevel 列挙型](feature-configuration-options.md#effortlevel-列挙型)を参照してください。

## 使用例

### 適応的思考を使った単純なクエリ

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

### 予算を制御した思考

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

### 速度のために思考を無効にする

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

### 高エフォートの調査タスク

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

### 思考を伴う対話的な会話

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

### 拡張思考とベータ機能の併用

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

## パターンマッチング

Java のパターンマッチングで ThinkingConfig の型ごとに処理を分けます：

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

型安全な確認：

```java
if (config instanceof ThinkingConfigEnabled enabled) {
    int budget = enabled.budgetTokens();
    System.out.println("Thinking budget: " + budget);
}
```

## ベストプラクティス

### どの型をいつ使うか

**ThinkingConfigAdaptive**：
- ✅ 複雑な推論タスク
- ✅ 問題の複雑さが不明
- ✅ 調査と分析
- ❌ 予算に敏感なアプリケーション
- ❌ 単純なクエリ

**ThinkingConfigEnabled**：
- ✅ 予算の制御が必要
- ✅ 複雑さの度合いが既知
- ✅ 本番のアプリケーション
- ✅ テストとベンチマーク
- ❌ 最適な予算が不明なとき

**ThinkingConfigDisabled**：
- ✅ 単純なクエリ
- ✅ レイテンシが重要なアプリケーション
- ✅ コストの最小化
- ❌ 複雑な推論が必要
- ❌ 調査のタスク

### thinking と effort の組み合わせ

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

### コストの最適化

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

### maxThinkingTokens からの移行

`thinking()` オプションは非推奨の `maxThinkingTokens()` を置き換えます：

```java
// Old (deprecated)
.maxThinkingTokens(10000)

// New (recommended)
.thinking(new ThinkingConfigEnabled(10000))

// Note: thinking() takes precedence if both are set
```

## API リファレンス

### ThinkingConfig インターフェース

```java
public sealed interface ThinkingConfig
    permits ThinkingConfigAdaptive, ThinkingConfigEnabled, ThinkingConfigDisabled

String type()  // Returns "adaptive", "enabled", or "disabled"
```

### ThinkingConfigAdaptive レコード

```java
public record ThinkingConfigAdaptive(@Nullable ThinkingDisplay display) implements ThinkingConfig {
    public ThinkingConfigAdaptive() { this(null); }   // convenience: no display override
}
```

CLI フラグ：`--thinking adaptive`（常に）、`--thinking-display <value>`（`display != null` のとき）。

### ThinkingConfigEnabled レコード

```java
public record ThinkingConfigEnabled(
    int budgetTokens,
    @Nullable ThinkingDisplay display
) implements ThinkingConfig {
    public ThinkingConfigEnabled(int budgetTokens) { this(budgetTokens, null); }
}
```

**引数**：
- `budgetTokens` —— 思考トークンの上限（> 0 である必要があります）
- `display`（省略可能、`null` でも可）—— 下の `ThinkingDisplay` を参照

**送出**：`budgetTokens ≤ 0` の場合は `IllegalArgumentException`

CLI フラグ：`--max-thinking-tokens <budgetTokens>`（常に）、`--thinking-display <value>`
（`display != null` のとき）。

### ThinkingConfigDisabled レコード

```java
public record ThinkingConfigDisabled() implements ThinkingConfig
```

CLI フラグ：`--thinking disabled`。disabled のバリアントでは `--thinking-display` は決して
出力されません。

### ThinkingDisplay 列挙型

```java
public enum ThinkingDisplay {
    SUMMARIZED("summarized"),
    OMITTED("omitted");
}
```

`--thinking-display` の値として転送されます。

### ClaudeAgentOptions のメソッド

```java
// Builder methods
ClaudeAgentOptions.Builder thinking(ThinkingConfig thinking)
ClaudeAgentOptions.Builder effort(String effort)

// Getter methods
ThinkingConfig thinking()
String effort()
```

## 関連機能

- [設定オプション](./feature-configuration-options.md) —— すべての設定オプション
- [メッセージ型](./feature-message-types.md) —— ThinkingBlock メッセージ
- [ストリーミングイベント](./feature-streaming-events.md) —— 思考ブロックのストリーミング
- [ベータ機能](./feature-configuration-options.md#betas) —— 拡張コンテキストと思考

## サンプルコード

完全なデモについては次のサンプルを参照してください：
- `examples/AdvancedFeatures.java` —— betaFeatures() と completeConfiguration() メソッド
- `sdk/src/test/java/in/vidyalai/claude/sdk/ClaudeAgentOptionsTest.java` —— 単体テスト
