# ClaudeSDKClient API リファレンス

双方向の会話のための対話的クライアントです。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../api-claude-sdk-client.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

## クラスの概要

```java
public class ClaudeSDKClient implements AutoCloseable
```

Claude とのステートフルで対話的な会話のためのクライアントです。

## コンストラクタ

```java
public ClaudeSDKClient()
public ClaudeSDKClient(ClaudeAgentOptions options)
```

## 接続メソッド

### connect()

```java
public void connect() throws CLIConnectionException
```

Claude Code CLI への接続を確立します。

**スレッド安全性**：スレッドセーフ、冪等

**送出**：close() の後に呼ばれた場合は `IllegalStateException`

### connect(String initialMessage)

```java
public void connect(String initialMessage) throws CLIConnectionException
```

接続して初期メッセージを送信します。

### isConnected()

```java
public boolean isConnected()
```

接続済みかどうかを確認します。

**戻り値**：`boolean`

### disconnect() / close()

```java
public void disconnect()
public void close()
```

接続を閉じ、リソースを片づけます。

**スレッド安全性**：スレッドセーフ、冪等

## メッセージの送信

### sendMessage(String prompt)

```java
public void sendMessage(String prompt)
```

メッセージを送信し、受信を続けます。

### sendMessage(String prompt, String sessionId)

```java
public void sendMessage(String prompt, String sessionId)
```

特定のセッションにメッセージを送信します。

### query(String prompt)

```java
public void query(String prompt)
```

メッセージを送信します。プロンプトが書き出された時点で戻ります —— 応答は
[`receiveResponse()`](#receiveresponse) または [`receiveMessages()`](#receivemessages) で
読み取ってください。

`query(prompt, "default")` と同等です。

### query(String prompt, String sessionId)

```java
public void query(String prompt, String sessionId)
```

特定のセッションに対してクエリを行います。

### query(Iterator&lt;Map&lt;String, Object&gt;&gt; messageStream)

```java
public void query(Iterator<Map<String, Object>> messageStream)
public void query(Iterator<Map<String, Object>> messageStream, String sessionId)
```

生のメッセージ map を送信します。文字列形式では組み立てられないフィールドがメッセージに必要なとき
—— `origin` の帰属、構造化されたコンテンツブロック、明示的な `uuid` —— には、`query(String)` では
なくこちらを使ってください：

```java
Map<String, Object> message = new HashMap<>();
message.put("type", "user");
message.put("message", Map.of("role", "user", "content", "Reply with exactly: one"));
message.put("parent_tool_use_id", null);
message.put("origin", Map.of("kind", "human"));   // attribute the turn

client.query(List.of(message).iterator());
for (Message msg : client.receiveResponse()) { /* ... */ }
```

`session_id` を省略したメッセージには `"default"` が —— 2 引数のオーバーロードでは `sessionId` が
—— 補われます。このフィールドを追加する際、呼び出し側の map は変更されずコピーされるので、不変の
map を渡しても安全です。

`query(String)` と同様、このメソッドは CLI の stdin を開いたままにするので、セッションを通じて
繰り返し呼び出せ、文字列版のオーバーロードと自由に混ぜられます。

> **v0.1.23 で変更。** このメソッドは以前、イテレータを内部のワンショットなストリーミング経路に
> 渡していました。その経路はイテレータを使い切ると stdin を閉じます。それによりセッションが終了し、
> CLI が終了して、次の `query()` や `sendMessage()` が
> `ProcessTransport is not ready for writing` で失敗していました。現在は Python SDK に合わせて
> 直接書き出します。セッションを終わらせるために古い挙動に頼っていた場合は、代わりに
> `disconnect()` を呼ぶ（または try-with-resources を使う）ようにしてください。

メッセージは呼び出しが戻る前に書き出されるため、連続した呼び出しの順序が保たれます。イテレータが
まだ値を生成している間に応答を読みたい場合は、遅延または無限のイテレータを自分のスレッドから
駆動してください。

## メッセージの受信

### receiveMessages()

```java
public Iterator<Message> receiveMessages()
```

すべてのメッセージを走査するイテレータを取得します（継続的）。

**スレッド安全性**：スレッドセーフですが、メッセージは複数のイテレータに分配されます

**戻り値**：`Iterator<Message>`

### receiveResponse()

```java
public Iterable<Message> receiveResponse()
```

次の ResultMessage までのメッセージを取得します（自動的に閉じます）。

**スレッド安全性**：スレッドセーフ

**戻り値**：`Iterable<Message>`

## 制御メソッド

### interrupt()

```java
public void interrupt()
```

現在の実行を中断します。

**スレッド安全性**：スレッドセーフ

### setModel(String model)

```java
public void setModel(String model)
```

AI モデルを変更します。

**引数**：`model` —— モデル名（例："claude-opus-4-6"）

### setPermissionMode(PermissionMode mode)

```java
public void setPermissionMode(PermissionMode mode)
```

権限モードを変更します。

**引数**：`mode` —— 新しい権限モード

### rewindFiles(String userMessageId)

```java
public void rewindFiles(String userMessageId)
```

ファイルを指定したユーザーメッセージ時点の状態に巻き戻します（チェックポイントが必要）。

**引数**：`userMessageId` —— 巻き戻し先のメッセージ ID

### getMcpStatus()

```java
public Map<String, Object> getMcpStatus()
```

MCP サーバーの接続状態を取得します。

**戻り値**：`Map<String, Object>` —— 状態の情報

### getContextUsage()

```java
public ContextUsageResponse getContextUsage()
```

現在のコンテキストウィンドウの使用状況をカテゴリ別に取得します。

CLI の `/context` コマンドが表示するのと同じデータを返します。カテゴリごとのトークン数、合計使用量、
MCP ツール・メモリファイル・エージェントの詳細な内訳が含まれます。

**戻り値**：次のフィールドを持つ `ContextUsageResponse`
- `categories` —— `ContextUsageCategory` のリスト（name、tokens、color）
- `totalTokens` —— コンテキストウィンドウ内の合計トークン数
- `maxTokens` —— 実効的なコンテキスト上限
- `percentage` —— 使用済みコンテキストの割合（0〜100）
- `model` —— モデル名
- 加えて省略可能なフィールド：`autoCompactThreshold`、`memoryFiles`、`mcpTools`、`agents` など

**送出**：接続していない場合は `CLIConnectionException`

### getServerInfo()

```java
public Map<String, Object> getServerInfo()
```

サーバーの初期化情報を取得します。

**戻り値**：`Map<String, Object>` —— サーバーの情報

## スレッド安全性

- **connect()**：スレッドセーフ、同期化済み
- **送信系メソッド**：スレッドセーフ
- **受信系メソッド**：スレッドセーフですが、キューを共有します
- **制御系メソッド**：スレッドセーフ
- **close()**：スレッドセーフ、冪等

## リソース管理

常に try-with-resources を使ってください：

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    // Use client
}
```

## 関連項目
- [対話的な会話のガイド](./feature-interactive-conversations.md)
