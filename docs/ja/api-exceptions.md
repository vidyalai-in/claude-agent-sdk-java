# 例外型 API リファレンス

エラー処理と例外の階層です。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../api-exceptions.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

## 例外の階層

```
ClaudeSDKException (RuntimeException)
├── CLIConnectionException
├── CLINotFoundException
├── ProcessException
│   └── ResultException
├── CLIJSONDecodeException
├── MessageParseException
└── QueryFailedException
```

## ClaudeSDKException

すべての SDK エラーの基底例外です。

```java
public class ClaudeSDKException extends RuntimeException {
    public ClaudeSDKException(String message);
    public ClaudeSDKException(String message, Throwable cause);
}
```

## CLIConnectionException

Claude Code CLI への接続に失敗しました。

```java
public class CLIConnectionException extends ClaudeSDKException {
    public CLIConnectionException(String message);
    public CLIConnectionException(String message, Throwable cause);
}
```

**原因**：
- CLI が見つからない
- プロセスの起動に失敗した
- 接続のタイムアウト
- ネットワークの問題（リモートトランスポート）

## CLINotFoundException

Claude Code CLI の実行ファイルが見つかりません。

```java
public class CLINotFoundException extends ClaudeSDKException {
    public CLINotFoundException(String message);
}
```

**対処**：
- Claude Code CLI をインストールする
- `.cliPath()` で独自のパスを指定する

## ProcessException

CLI プロセスが失敗またはクラッシュしました。

```java
public class ProcessException extends ClaudeSDKException {
    public ProcessException(String message);
    public ProcessException(String message, Throwable cause);
}
```

**原因**：
- CLI のクラッシュ
- 不正な引数
- リソースの枯渇

**エラー結果による終了後の実用的なエラー**：CLI が `isError=true` の `ResultMessage`（たとえば
`error_max_turns`、`error_during_execution`、あるいは `apiErrorStatus` が設定された `success`
サブタイプ）を発行すると、そのあと意図的に非ゼロで終了します。後続の `ProcessException` は
`"Command failed with exit code N"` しか持たず実用的でないため、リーダーはそれを `ResultException`
に置き換えます（下記参照）。この置き換えはターン単位で、実行の後半で新たに起きたクラッシュは元の
`ProcessException` のメッセージを保ちます。

## ResultException

CLI が終端エラー結果を報告して終了しました。`ProcessException` のサブクラスなので、既存の
`catch (ProcessException e)` ハンドラはそのまま動作します。

```java
public class ResultException extends ProcessException {
    public ResultException(String message, @Nullable Map<String, Object> data,
                           @Nullable Integer exitCode);

    @Nullable public String subtype();          // "error_max_turns", "error_during_execution",
                                                // ... or "success" for a mid-turn API failure
    public List<String> errors();               // never null; empty for API failures
    @Nullable public String result();           // result text; the "API Error: ..." prose
    @Nullable public Integer apiErrorStatus();  // HTTP status of the failing API call
    @Nullable public String terminalReason();   // e.g. "api_error", "max_turns"
    @Nullable public String sessionId();
    public Map<String, Object> data();          // raw result payload, unmodifiable
}
```

メッセージは `"Claude Code returned an error result: <text>"` に `ProcessException` の
`" (exit code: N)"` という接尾辞が付いたものです。`<text>` は結果の `errors` 配列を `"; "` で
連結したもので、なければ結果テキスト、次に `success` でない `subtype`、最後に
`"API error (HTTP <status>)"` へとフォールバックします。非ゼロ終了に対応する元の
`ProcessException` が `getCause()` です。

テキストではなくペイロードで分岐してください：

```java
} catch (ResultException e) {
    if ("api_error".equals(e.terminalReason())) {
        retry();
    } else if ("error_max_turns".equals(e.subtype())) {
        // ...
    }
}
```

**どこで現れるか：**

- 収集を行う `ClaudeSDK.query(...)` 系は、失敗前に受信したメッセージを失わないよう、これを
  `QueryFailedException` でラップします。`ResultException` はその例外の `getCause()` です。通常は
  この形で目にします。
- 制御リクエストの失敗から直接 —— とくに重要なのは、CLI が起動時に拒否する `initialize`
  （`resumeDropsTurn` によって拒否されたレジューム）です。これはメッセージを 1 つも収集する前に
  起きるため、ラップされません。
- `ClaudeSDKClient.receiveResponse()` からは現れ**ません**：これは `ResultMessage` で終了するため
  （Python SDK の `receive_response()` とまったく同じ）、CLI の終了を観測しません。そこでは代わりに
  `ResultMessage.isError()` を確認してください。`receiveMessages()` はストリーム終端まで走るので
  送出しますが、生きているクライアントでは stdin が開いたままなので、セッション途中のエラー結果が
  ストリームを終わらせることはありません。

## CLIJSONDecodeException

CLI からの JSON の解析に失敗しました。

```java
public class CLIJSONDecodeException extends ClaudeSDKException {
    public CLIJSONDecodeException(String message, Throwable cause);
}
```

**原因**：
- 不正な JSON
- 想定外の形式
- CLI のバージョン不一致

## MessageParseException

メッセージを型付きオブジェクトへ解析するのに失敗しました。

```java
public class MessageParseException extends ClaudeSDKException {
    public MessageParseException(String message, Throwable cause);
}
```

**原因**：
- 未知のメッセージ型
- 必須フィールドの欠落
- 型変換のエラー

## QueryFailedException

収集を行うクエリがエラー結果で終わりました。すでに届いていたメッセージを保持します。

```java
public class QueryFailedException extends ClaudeSDKException {
    public QueryFailedException(String message, Throwable cause, List<Message> partialMessages);

    public List<Message> partialMessages();   // never null; unmodifiable
    public ResultMessage resultMessage();     // last ResultMessage received, or null
}
```

**原因**：
- `error_max_turns` —— `maxTurns` に到達
- `error_max_budget_usd` —— `maxBudgetUsd` に到達
- `error_during_execution` —— `resumeDropsTurn` に拒否されたレジュームを含む

**なぜ存在するのか**：CLI はこれらの状況を、*完全な*ターン —— アシスタントメッセージと、サブタイプ・
コスト・使用量を持つ最終的な `ResultMessage` —— を発行し、そのあとで意図的に非ゼロ終了することに
よって報告します（シェルの利用者のためです）。ストリーミング API
（`ClaudeSDKClient.receiveMessages()` と `receiveResponse()`）は、それぞれのメッセージを届いた
そばから消費者に渡し、最後にだけ送出するので、そこでは何も失われません。収集を行う呼び出しは
リストを返すか例外を投げるかの二択なので、この例外を投げてエラーとメッセージの両方を運びます。
こうして `ClaudeSDK.query(...)` はストリーミング経路と同じだけの情報量を保ちます。

送出するのは収集を行う `ClaudeSDK.query(...)` 系だけです（それに委譲する `queryForText` と
`queryForResult` を含みます）。`ClaudeSDKException` を継承しているため、既存の
`catch (ClaudeSDKException e)` ブロックは変更なしで動作し続けます。

```java
try {
    List<Message> messages = ClaudeSDK.query("Summarize the README", options);
    // ... normal path
} catch (QueryFailedException e) {
    // The turn is usually complete — inspect what actually happened.
    ResultMessage result = e.resultMessage();
    if (result != null && "error_max_budget_usd".equals(result.subtype())) {
        System.out.printf("Stopped by the budget cap after $%.4f%n", result.totalCostUsd());
    }
    for (Message msg : e.partialMessages()) {
        if (msg instanceof AssistantMessage a) {
            System.out.println(a.getTextContent());
        }
    }
}
```

実行が何も生み出す前に失敗した場合（起動できなかった CLI など）、`partialMessages()` は空です。
これはシリアライズされません —— `Message` が `Serializable` と宣言されていないため、デシリアライズ
されたインスタンスは null ではなく空リストを報告します。

`maxTurns` や `maxBudgetUsd` を設定したときは必ずこれを捕捉してください。自分で設定した上限に達する
ことはクラッシュではなく、想定内の結果です。

## エラー処理のサンプル

### 基本的な try-catch

```java
try {
    List<Message> messages = ClaudeSDK.query(prompt, options);
} catch (CLINotFoundException e) {
    System.err.println("Claude CLI not installed");
} catch (CLIConnectionException e) {
    System.err.println("Connection failed: " + e.getMessage());
} catch (ProcessException e) {
    System.err.println("CLI crashed: " + e.getMessage());
} catch (QueryFailedException e) {
    // Run stopped at a limit; the messages so far are still available.
    System.err.println("Run ended early: " + e.getMessage());
} catch (ClaudeSDKException e) {
    System.err.println("SDK error: " + e.getMessage());
}
```

順序が重要です：`QueryFailedException` は `ClaudeSDKException` のサブクラスなので、その前に
捕捉しなければなりません。

### リソース管理との併用

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    client.connect();
    // Use client
} catch (CLIConnectionException e) {
    log.error("Failed to connect", e);
    throw new ApplicationException("Service unavailable", e);
} catch (ClaudeSDKException e) {
    log.error("SDK error", e);
    throw new ApplicationException("Internal error", e);
}
```

### リトライのロジック

```java
int maxRetries = 3;
for (int i = 0; i < maxRetries; i++) {
    try {
        return ClaudeSDK.query(prompt, options);
    } catch (CLIConnectionException e) {
        if (i == maxRetries - 1) throw e;
        Thread.sleep(1000 * (i + 1));  // Exponential backoff
    }
}
```

## 関連項目
- [エラー処理のサンプル](../../examples/src/main/java/examples/ErrorHandling.java)
