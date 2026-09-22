# シンプルなクエリ

シンプルなクエリは、`ClaudeSDK` ファサードを使って単発・ステートレスな操作で Claude とやり取り
するための素直な方法です。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../feature-simple-queries.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

## 目次
- [概要](#概要)
- [シンプルなクエリを使う場面](#シンプルなクエリを使う場面)
- [基本的な使い方](#基本的な使い方)
- [クエリのメソッド](#クエリのメソッド)
- [設定オプション](#設定オプション)
- [メッセージの処理](#メッセージの処理)
- [サンプル](#サンプル)
- [ベストプラクティス](#ベストプラクティス)

## 概要

`ClaudeSDK` クラスは、シンプルでステートレスなクエリのための静的メソッドを提供します。次の複雑さを
すべて引き受けます：
- トランスポートの生成と管理
- QueryHandler のセットアップ
- メッセージの解析
- リソースの後片づけ

**主な特徴：**
- **一方向**：メッセージをまとめて送り、応答をまとめて受け取ります
- **ステートレス**：各クエリは独立しています
- **シンプル**：投げっぱなしのスタイル
- **中断なし**：中断も追加メッセージの送信もできません
- **自動的な後片づけ**：リソースは内部で管理されます

## シンプルなクエリを使う場面

### ✅ 向いている用途

1. **単発の質問**
   ```java
   ClaudeSDK.query("What is the capital of France?");
   ```

2. **バッチ処理**
   ```java
   for (String prompt : prompts) {
       List<Message> result = ClaudeSDK.query(prompt, options);
       processResult(result);
   }
   ```

3. **コード生成**
   ```java
   String code = ClaudeSDK.queryForText(
       "Generate a Java function to reverse a string",
       options);
   ```

4. **CI/CD パイプライン**
   ```java
   String review = ClaudeSDK.queryForText(
       "Review this code for security issues: " + code,
       options);
   ```

5. **自動化スクリプト**
   ```java
   ResultMessage result = ClaudeSDK.queryForResult(
       "Analyze this log file",
       options);
   System.out.println("Cost: $" + result.totalCostUsd());
   ```

### ❌ 向いていない用途

1. **対話的な会話** —— 代わりに `ClaudeSDKClient` を使ってください
2. **チャット UI** —— マルチターンには `ClaudeSDKClient` を
3. **追加の質問** —— 文脈が必要なら `ClaudeSDKClient` を
4. **中断の機能** —— 制御が必要なら `ClaudeSDKClient` を
5. **長時間のセッション** —— 状態が必要なら `ClaudeSDKClient` を

## 基本的な使い方

### 最も単純なクエリ（既定のオプション）

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.Message;

List<Message> messages = ClaudeSDK.query("What is 2 + 2?");
```

### オプション付きのクエリ

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .maxTurns(1)
    .build();

List<Message> messages = ClaudeSDK.query("What is 2 + 2?", options);
```

### テキストだけを取得する

```java
String answer = ClaudeSDK.queryForText(
    "What is 2 + 2?",
    ClaudeAgentOptions.defaults()
);
System.out.println(answer); // "4"
```

### 結果メッセージを取得する

```java
ResultMessage result = ClaudeSDK.queryForResult(
    "What is 2 + 2?",
    ClaudeAgentOptions.defaults()
);
System.out.println("Cost: $" + result.totalCostUsd());
System.out.println("Stop reason: " + result.stopReason());
```

## クエリのメソッド

### 1. query(String prompt)

既定のオプションでクエリを実行します。

```java
List<Message> messages = ClaudeSDK.query("Hello, Claude!");
```

**戻り値**：`List<Message>` —— その会話のすべてのメッセージ

### 2. query(String prompt, ClaudeAgentOptions options)

独自のオプションでクエリを実行します。

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .maxTurns(1)
    .build();

List<Message> messages = ClaudeSDK.query("Hello!", options);
```

**引数**：
- `prompt` —— 質問または指示
- `options` —— 設定オプション

**戻り値**：`List<Message>` —— その会話のすべてのメッセージ

**送出**：
- `IllegalArgumentException` —— `canUseTool` と `permissionPromptToolName` の両方が設定されている場合
- `CLIConnectionException` —— 接続に失敗した場合
- `ProcessException` —— CLI プロセスが失敗した場合

### 3. query(Iterator<Map<String, Object>> messageStream, ClaudeAgentOptions options)

複数のメッセージでストリーミングのクエリを実行します。

```java
var messages = List.of(
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "First message")),
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "Follow-up"))
);

List<Message> responses = ClaudeSDK.query(messages.iterator(), options);
```

**引数**：
- `messageStream` —— メッセージ辞書のイテレータ
- `options` —— 設定オプション

**戻り値**：`List<Message>` —— その会話のすべてのメッセージ

**メッセージの形式**：
```java
{
    "type": "user",
    "session_id": "default",
    "message": {
        "role": "user",
        "content": "Your message here"
    }
}
```

### 4. queryForText(String prompt, ClaudeAgentOptions options)

アシスタントメッセージのテキスト内容だけを返す便利メソッドです。

```java
String text = ClaudeSDK.queryForText(
    "What is the capital of France?",
    ClaudeAgentOptions.defaults()
);
```

**戻り値**：`String` —— すべてのアシスタントメッセージのテキスト内容を結合したもの

### 5. queryForResult(String prompt, ClaudeAgentOptions options)

結果メッセージだけを返す便利メソッドです。

```java
ResultMessage result = ClaudeSDK.queryForResult(
    "Analyze this code",
    options
);

System.out.println("Cost: $" + result.totalCostUsd());
System.out.println("Input tokens: " + result.usageInput());
System.out.println("Output tokens: " + result.usageOutput());
```

**戻り値**：`ResultMessage` —— 最終的な結果。見つからなければ null

## 設定オプション

### シンプルなクエリで欠かせないオプション

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    // Model selection
    .model("claude-sonnet-4-5")
    .fallbackModel("claude-haiku-4-5")

    // Limits
    .maxTurns(5)
    .maxBudgetUsd(1.0)
    .maxThinkingTokens(10000)

    // System prompt
    .systemPrompt("You are a helpful assistant. Be concise.")

    // Tools
    .allowedTools(List.of("Read", "Grep"))
    .disallowedTools(List.of("Write", "Edit"))

    // Permissions
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)

    // Working directory
    .cwd(Path.of("/path/to/project"))

    // Environment
    .env(Map.of("KEY", "value"))

    .build();
```

### よくあるパターン

#### 素早い読み取り専用クエリ
```java
var options = ClaudeAgentOptions.builder()
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .allowedTools(List.of("Read", "Grep", "Glob"))
    .disallowedTools(List.of("Write", "Edit", "Bash"))
    .maxTurns(5)
    .build();
```

#### 予算に制約のあるクエリ
```java
var options = ClaudeAgentOptions.builder()
    .maxBudgetUsd(0.10)  // Limit to 10 cents
    .maxTurns(3)
    .model("claude-haiku-4-5")  // Use cheaper model
    .build();
```

#### 高速な 1 ターンのクエリ
```java
var options = ClaudeAgentOptions.builder()
    .maxTurns(1)
    .model("claude-haiku-4-5")
    .systemPrompt("Be extremely concise.")
    .build();
```

## メッセージの処理

### すべてのメッセージを処理する

```java
List<Message> messages = ClaudeSDK.query("Hello!", options);

for (Message msg : messages) {
    switch (msg) {
        case UserMessage user ->
            System.out.println("User: " + user.content());

        case AssistantMessage assistant -> {
            System.out.println("Claude: " + assistant.getTextContent());
            // Process tool uses
            for (ContentBlock block : assistant.content()) {
                if (block instanceof ToolUseBlock tool) {
                    System.out.println("Used tool: " + tool.name());
                }
            }
        }

        case ResultMessage result ->
            System.out.println("Cost: $" + result.totalCostUsd());

        case SystemMessage system ->
            System.out.println("System: " + system.subtype());

        case StreamEvent event ->
            // Usually not present in simple queries
            System.out.println("Stream event: " + event);
    }
}
```

### 特定の情報を取り出す

```java
List<Message> messages = ClaudeSDK.query(prompt, options);

// Get last assistant message
AssistantMessage lastAssistant = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .map(m -> (AssistantMessage) m)
    .reduce((first, second) -> second)
    .orElse(null);

// Get all text content
String allText = messages.stream()
    .filter(m -> m instanceof AssistantMessage)
    .map(m -> ((AssistantMessage) m).getTextContent())
    .collect(Collectors.joining("\n"));

// Get result
ResultMessage result = messages.stream()
    .filter(m -> m instanceof ResultMessage)
    .map(m -> (ResultMessage) m)
    .findFirst()
    .orElse(null);
```

## サンプル

### サンプル 1：コードレビュー

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

public class CodeReview {
    public static void main(String[] args) {
        String code = """
            public void processUser(User user) {
                db.save(user);  // No null check!
            }
            """;

        var options = ClaudeAgentOptions.builder()
            .systemPrompt("You are a code reviewer. Focus on bugs and security.")
            .maxTurns(1)
            .build();

        String review = ClaudeSDK.queryForText(
            "Review this code for issues:\n" + code,
            options
        );

        System.out.println(review);
    }
}
```

### サンプル 2：一括翻訳

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

public class Translator {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .model("claude-haiku-4-5")  // Fast and cheap
            .maxTurns(1)
            .systemPrompt("Translate to French. Return only the translation.")
            .build();

        List<String> phrases = List.of(
            "Hello, how are you?",
            "The weather is nice today.",
            "I love programming."
        );

        for (String phrase : phrases) {
            String translation = ClaudeSDK.queryForText(phrase, options);
            System.out.println(phrase + " -> " + translation);
        }
    }
}
```

### サンプル 3：ログの分析

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import java.nio.file.Path;

public class LogAnalyzer {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .cwd(Path.of("/path/to/logs"))
            .allowedTools(List.of("Read", "Grep"))
            .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
            .maxTurns(10)
            .build();

        String analysis = ClaudeSDK.queryForText(
            "Analyze error.log and summarize all ERROR level messages",
            options
        );

        System.out.println(analysis);
    }
}
```

### サンプル 4：コストを意識したクエリ

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.types.message.ResultMessage;

public class CostAwareQuery {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .maxBudgetUsd(0.05)  // 5 cent limit
            .build();

        ResultMessage result = ClaudeSDK.queryForResult(
            "Explain quantum computing",
            options
        );

        if (result != null) {
            System.out.println("Cost: $" + result.totalCostUsd());
            System.out.println("Input tokens: " + result.usageInput());
            System.out.println("Output tokens: " + result.usageOutput());
            System.out.println("Stop reason: " + result.stopReason());
        }
    }
}
```

## ベストプラクティス

### 1. 適切なオプションを使う

```java
// ✅ Good: Configure limits
var options = ClaudeAgentOptions.builder()
    .maxTurns(5)
    .maxBudgetUsd(1.0)
    .build();

// ❌ Bad: No limits
ClaudeSDK.query(longComplexTask);  // Could be expensive!
```

### 2. すべてのメッセージ型を扱う

```java
// ✅ Good: Pattern matching handles all types
switch (message) {
    case AssistantMessage a -> process(a);
    case ResultMessage r -> logCost(r);
    case UserMessage u -> log(u);
    case SystemMessage s -> log(s);
    case StreamEvent e -> log(e);
}

// ❌ Bad: Only handling one type
if (message instanceof AssistantMessage) {
    // Missing other types!
}
```

### 3. 適切な場面で便利メソッドを使う

```java
// ✅ Good: Simple use case
String answer = ClaudeSDK.queryForText(prompt, options);

// ❌ Overkill: Manual extraction
List<Message> messages = ClaudeSDK.query(prompt, options);
String answer = messages.stream()...  // Complex extraction
```

### 4. ファイル操作には作業ディレクトリを設定する

```java
// ✅ Good: Explicit working directory
var options = ClaudeAgentOptions.builder()
    .cwd(Path.of("/project/root"))
    .allowedTools(List.of("Read", "Write"))
    .build();

// ❌ Bad: Using current directory (unpredictable)
ClaudeSDK.query("Read config.json", options);
```

### 5. 適切なモデルを選ぶ

```java
// ✅ Good: Match model to task
var fastOptions = ClaudeAgentOptions.builder()
    .model("claude-haiku-4-5")  // Quick, simple tasks
    .build();

var complexOptions = ClaudeAgentOptions.builder()
    .model("claude-opus-4-6")  // Complex reasoning
    .build();

// ❌ Bad: Using opus for simple tasks (expensive)
ClaudeAgentOptions.builder()
    .model("claude-opus-4-6")
    .build();
ClaudeSDK.query("What is 2+2?", options);  // Overkill!
```

### 6. マルチターンにはストリーミングを使う

```java
// ✅ Good: Streaming for multiple messages
var messages = List.of(
    Map.of("type", "user", ...),
    Map.of("type", "user", ...)
);
ClaudeSDK.query(messages.iterator(), options);

// ❌ Bad: Multiple separate queries (loses context)
ClaudeSDK.query("First question", options);
ClaudeSDK.query("Follow-up", options);  // No context!
```

### 7. エラーを処理する

```java
// ✅ Good: Handle exceptions
try {
    List<Message> messages = ClaudeSDK.query(prompt, options);
    // Process messages
} catch (CLIConnectionException e) {
    System.err.println("Failed to connect: " + e.getMessage());
} catch (ProcessException e) {
    System.err.println("CLI process failed: " + e.getMessage());
}

// ❌ Bad: No error handling
ClaudeSDK.query(prompt, options);  // Could throw!
```

## 関連項目

- [対話的な会話](./feature-interactive-conversations.md) —— マルチターンの会話向け
- [設定オプション](./feature-configuration-options.md) —— オプションの完全ガイド
- [メッセージ型](./feature-message-types.md) —— メッセージを理解する
- [ClaudeSDK API リファレンス](./api-claude-sdk.md) —— 詳細な API ドキュメント
