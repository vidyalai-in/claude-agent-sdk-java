# 対話型の会話

対話型の会話では、`ClaudeSDKClient` クラスを使って Claude と複数ターンの状態付きやり取りを行えます。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../feature-interactive-conversations.md)より古い場合があります。内容が食い違う場合は英語版が優先されます。コードブロックは英語版と同一のまま、翻訳していません。

## 目次
- [概要](#概要)
- [ClaudeSDKClient を使うべき場面](#claudesdkclient-を使うべき場面)
- [基本的な使い方](#基本的な使い方)
- [接続の管理](#接続の管理)
- [メッセージの送信](#メッセージの送信)
- [メッセージの受信](#メッセージの受信)
- [制御メソッド](#制御メソッド)
- [セッション管理](#セッション管理)
- [スレッド安全性](#スレッド安全性)
- [リソース管理](#リソース管理)
- [例](#例)
- [ベストプラクティス](#ベストプラクティス)

## 概要

`ClaudeSDKClient` は Claude との双方向の会話を完全に制御する手段を提供します。単純な
`ClaudeSDK.query()` ファサードとは異なり、このクライアントは次の特徴を持ちます。

- **状態を保持**：会話のコンテキストが複数メッセージにわたって維持される
- **双方向**：いつでもメッセージを送受信できる
- **対話的**：応答に基づいて追加の質問を送れる
- **制御可能**：実行中に中断、モデル変更、権限変更ができる
- **セッション対応**：会話の再開とフォークをサポート

## ClaudeSDKClient を使うべき場面

### ✅ 最適なケース

1. **チャット UI**
   ```java
   try (var client = ClaudeSDK.createClient()) {
       client.connect();
       while (userInput = getUserInput()) {
           client.sendMessage(userInput);
           for (var msg : client.receiveResponse()) {
               display(msg);
           }
       }
   }
   ```

2. **REPL 風のインターフェース**
   ```java
   while (true) {
       String command = console.readLine();
       client.sendMessage(command);
       processResponse(client.receiveResponse());
   }
   ```

3. **複数ターンの会話**
   ```java
   client.sendMessage("What is Python?");
   // ... process response
   client.sendMessage("Show me a code example");
   // ... context preserved
   ```

4. **対話的なデバッグ**
   ```java
   client.sendMessage("Analyze this error");
   var response = client.receiveResponse();
   if (needsMoreInfo) {
       client.sendMessage("Here's more context...");
   }
   ```

5. **長時間のセッション**
   ```java
   try (var client = ClaudeSDK.createClient(options)) {
       client.connect();
       // Hours-long session with state
   }
   ```

### ❌ 適さないケース

- 単発の単純な質問 → `ClaudeSDK.query()` を使う
- バッチ処理 → `ClaudeSDK.query()` を使う
- 一度きりのスクリプト → `ClaudeSDK.query()` を使う

## 基本的な使い方

### 作成と接続

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeSDKClient;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

// Create with default options
ClaudeSDKClient client = ClaudeSDK.createClient();

// Or with custom options
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .maxTurns(20)
    .build();
ClaudeSDKClient client = ClaudeSDK.createClient(options);

// Connect (establishes subprocess)
client.connect();
```

### 単純な会話

```java
try (var client = ClaudeSDK.createClient()) {
    client.connect();

    // First message
    client.sendMessage("What is 2 + 2?");
    for (var msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }

    // Follow-up (context preserved)
    client.sendMessage("What about 3 + 3?");
    for (var msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### 初回メッセージ付きで接続する

```java
// Connect and send initial message in one call
client.connect("Hello, Claude!");

for (var msg : client.receiveResponse()) {
    // Process initial response
}
```

## 接続の管理

### connect()

Claude Code CLI への接続を確立します。

```java
client.connect();  // No initial message
client.connect("Initial prompt");  // With initial message
```

**スレッド安全性**：スレッドセーフかつ冪等です。並行呼び出しから保護されています。

**スロー**：
- `IllegalStateException` — `close()` 後に呼び出した場合
- `CLIConnectionException` — 接続に失敗した場合

### isConnected()

クライアントが接続済みかどうかを確認します。

```java
if (client.isConnected()) {
    client.sendMessage("Hello");
}
```

### disconnect() / close()

接続を閉じてリソースを解放します。

```java
client.disconnect();  // Explicit disconnect
// or
client.close();  // AutoCloseable

// Best practice: use try-with-resources
try (var client = ClaudeSDK.createClient()) {
    // Use client
}  // Automatically closed
```

**スレッド安全性**：スレッドセーフかつ冪等です。複数回呼び出しても安全です。

## メッセージの送信

### sendMessage(String prompt)

メッセージを送信し、受信を続けます。

```java
client.sendMessage("Hello, Claude!");
```

**使いどころ**：メッセージを送信しつつ、すべてのイベントを聞き続けたい場合。

### sendMessage(String prompt, String sessionId)

特定のセッションにメッセージを送信します。

```java
client.sendMessage("Hello!", "session-1");
```

### query(String prompt)

メッセージを送信し、その応答だけを受け取ります（ResultMessage までブロック）。

```java
List<Message> response = client.query("What is 2 + 2?");
```

**使いどころ**：リクエスト／レスポンス型のパターンが欲しい場合（送信して完全な応答を待つ）。

### query(String prompt, String sessionId)

特定のセッション ID に対してクエリを実行します。

```java
List<Message> response = client.query("Question", "session-1");
```

### query(Iterator<Map<String, Object>> messageStream)

複数のメッセージをストリームとして送信します。

```java
var messages = List.of(
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "First")),
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "Second"))
);

List<Message> responses = client.query(messages.iterator());
```

## メッセージの受信

### receiveMessages()

**すべての**メッセージを返すイテレーターを返します（継続的なストリーム）。

```java
Iterator<Message> messages = client.receiveMessages();

while (messages.hasNext()) {
    Message msg = messages.next();
    // Process each message as it arrives

    if (shouldStop(msg)) {
        break;
    }
}
```

**使いどころ**：
- メッセージを継続的に処理したい場合
- 複数のセッションを扱っている場合
- システムメッセージを含むすべてのイベントを見たい場合

**特徴**：
- イテレーターはメッセージが届くまでブロックする
- ストリームが終わるまでメッセージを返し続ける
- 複数のイテレーターは同じキューを共有する（メッセージが分散する）

### receiveResponse()

次の ResultMessage で停止するイテレーターを返します。

```java
Iterable<Message> response = client.receiveResponse();

for (Message msg : response) {
    // Process messages until ResultMessage
}
// Iterator auto-closes when ResultMessage received
```

**使いどころ**：
- リクエスト／レスポンス型のパターンが欲しい場合
- 1 回のクエリの完了を待ちたい場合
- ResultMessage で自動的に止めたい場合

**特徴**：
- メッセージが届くまでブロックする
- ResultMessage で停止し、自動的にクローズする
- 1 回の応答分のメッセージをすべて返す

### メッセージの処理

```java
for (Message msg : client.receiveResponse()) {
    switch (msg) {
        case UserMessage user ->
            System.out.println("User: " + user.content());

        case AssistantMessage assistant -> {
            System.out.println("Claude: " + assistant.getTextContent());

            // Process tool uses
            for (ContentBlock block : assistant.content()) {
                if (block instanceof ToolUseBlock tool) {
                    System.out.println("Tool: " + tool.name());
                }
            }
        }

        case ResultMessage result -> {
            System.out.println("Done! Cost: $" + result.totalCostUsd());
            System.out.println("Stop reason: " + result.stopReason());
        }

        case SystemMessage system ->
            System.out.println("System: " + system.subtype());

        case StreamEvent event ->
            System.out.println("Partial: " + event.delta());
    }
}
```

## 制御メソッド

### interrupt()

現在の実行を中断します。

```java
// In another thread
client.interrupt();
```

**使いどころ**：
- ユーザーによるキャンセル
- タイムアウトに到達したとき
- コストの高い処理を止めたいとき

### setModel(String model)

会話の途中で AI モデルを変更します。

```java
client.setModel("claude-opus-4-6");
```

**使いどころ**：
- 複雑なタスクではより高性能なモデルに切り替える
- 単純な質問ではより安価なモデルに切り替える

### setPermissionMode(PermissionMode mode)

会話中に権限モードを変更します。

```java
client.setPermissionMode(PermissionMode.ACCEPT_EDITS);
```

**利用可能なモード**：
- `DEFAULT` — 標準の権限動作（CLI のデフォルト）
- `ACCEPT_EDITS` — 編集は自動許可、それ以外は確認
- `PLAN` — 計画モード。ツールは実行されない
- `BYPASS_PERMISSIONS` — 権限チェックをすべてスキップ
- `DONT_ASK` — 許可ルールで事前承認されていないものはすべて拒否
- `AUTO` — モデルの分類器が各ツール呼び出しを承認または拒否

### rewindFiles(String userMessageId)

ファイルを特定のユーザーメッセージの時点に戻します（チェックポイントが有効な場合）。

```java
// Enable checkpointing in options
var options = ClaudeAgentOptions.builder()
    .enableFileCheckpointing(true)
    .extraArgs(Map.of("replay-user-messages", ""))  // so UserMessage carries a uuid
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();

    client.sendMessage("Create file.txt");
    for (var msg : client.receiveResponse()) {
        if (msg instanceof UserMessage user) {
            String messageId = user.uuid();
            // Save ID for later
        }
    }

    // Later: rewind to that message
    client.rewindFiles(messageId);
}
```

### getMcpStatus()

MCP サーバー接続の状態を取得します。

```java
McpStatusResponse status = client.getMcpStatus();
for (McpServerStatus server : status.mcpServers()) {
    System.out.println(server.name() + ": " + server.status());
}
```

### getServerInfo()

サーバーの初期化情報を取得します。

```java
Map<String, Object> info = client.getServerInfo();
System.out.println("Commands: " + info.get("commands"));
```

## セッション管理

### デフォルトセッション

デフォルトでは、すべてのメッセージが "default" セッションを使います。

```java
client.sendMessage("Hello");  // Uses "default" session
```

### 複数セッション

異なるセッションにメッセージを送ります。

```java
// Session 1
client.sendMessage("Analyze code.java", "session-1");

// Session 2
client.sendMessage("Write tests", "session-2");

// Receive from all sessions
for (var msg : client.receiveMessages()) {
    // Process messages from any session
}
```

### 以前のセッションを再開する

```java
// First conversation
var options1 = ClaudeAgentOptions.builder()
    .build();

try (var client = ClaudeSDK.createClient(options1)) {
    client.connect("What is Java?");
    // ... conversation
}

// Later: resume with context
var options2 = ClaudeAgentOptions.builder()
    .resume("previous-session-id")
    .build();

try (var client = ClaudeSDK.createClient(options2)) {
    client.connect("Tell me more");
    // Has context from previous session
}
```

### セッションのフォーク

フォークは既存のセッションから新しいセッションを作ります。

```java
var options = ClaudeAgentOptions.builder()
    .resume("previous-session-id")
    .forkSession(true)  // Fork instead of continue
    .build();
```

**違い**：
- `resume(id)` — 同じセッションを継続する
- `resume(id) + forkSession(true)` — 同じコンテキストで新しいセッションを作る

## スレッド安全性

`ClaudeSDKClient` は**部分的にスレッドセーフ**です。

### スレッドセーフな操作

```java
// ✅ Safe: Multiple threads can send
Thread t1 = Thread.startVirtualThread(() ->
    client.sendMessage("Query 1"));
Thread t2 = Thread.startVirtualThread(() ->
    client.sendMessage("Query 2"));

// ✅ Safe: Control methods
client.interrupt();
client.setModel("claude-sonnet-4-5");
client.setPermissionMode(PermissionMode.ACCEPT_ALL);

// ✅ Safe: connect() is synchronized
client.connect();  // Only one connection established
```

### 共有される状態

```java
// ⚠️ Warning: Multiple iterators share the queue
Iterator<Message> iter1 = client.receiveMessages();
Iterator<Message> iter2 = client.receiveMessages();

// Messages distributed across both iterators!
// Typically use only one iterator per client
```

### ベストプラクティス

```java
// ✅ Good: One receive loop per client
try (var client = ClaudeSDK.createClient()) {
    client.connect();

    // Dedicated receive thread
    Thread.ofVirtual().start(() -> {
        for (var msg : client.receiveMessages()) {
            processMessage(msg);
        }
    });

    // Main thread sends
    client.sendMessage("Question 1");
    client.sendMessage("Question 2");
}
```

## リソース管理

### AutoCloseable

常に try-with-resources を使ってください。

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    client.connect();
    // Use client
}  // Automatically cleaned up
```

### 手動でのクリーンアップ

try-with-resources を使わない場合：

```java
ClaudeSDKClient client = ClaudeSDK.createClient();
try {
    client.connect();
    // Use client
} finally {
    client.close();  // Important!
}
```

### 解放されるリソース

`close()` 時にクライアントは次を解放します。
- QueryHandler とスレッドプール
- ストリーミング用のエグゼキューター
- トランスポートと CLI サブプロセス
- メッセージキューとイテレーター

## 例

### 例 1：対話型チャット

```java
import java.util.Scanner;

public class Chat {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .model("claude-sonnet-4-5")
            .build();

        try (var client = ClaudeSDK.createClient(options);
             var scanner = new Scanner(System.in)) {

            client.connect();
            System.out.println("Chat started! Type 'exit' to quit.");

            while (true) {
                System.out.print("\nYou: ");
                String input = scanner.nextLine();

                if ("exit".equalsIgnoreCase(input)) {
                    break;
                }

                client.sendMessage(input);

                System.out.print("Claude: ");
                for (var msg : client.receiveResponse()) {
                    if (msg instanceof AssistantMessage assistant) {
                        System.out.print(assistant.getTextContent());
                    }
                }
                System.out.println();
            }
        }
    }
}
```

### 例 2：長時間の処理を中断する

```java
import java.util.concurrent.TimeUnit;

public class InterruptExample {
    public static void main(String[] args) {
        try (var client = ClaudeSDK.createClient()) {
            client.connect();

            // Start long operation in background
            Thread.ofVirtual().start(() -> {
                client.sendMessage("Analyze all files in this large codebase");
                for (var msg : client.receiveResponse()) {
                    System.out.println(msg);
                }
            });

            // Wait 5 seconds then interrupt
            TimeUnit.SECONDS.sleep(5);
            System.out.println("Interrupting...");
            client.interrupt();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### 例 3：モデルの動的な切り替え

```java
public class ModelSwitching {
    public static void main(String[] args) {
        try (var client = ClaudeSDK.createClient()) {
            client.connect();

            // Simple question with Haiku
            client.setModel("claude-haiku-4-5");
            client.sendMessage("What is 2+2?");
            processResponse(client.receiveResponse());

            // Complex question with Opus
            client.setModel("claude-opus-4-6");
            client.sendMessage("Explain quantum entanglement");
            processResponse(client.receiveResponse());

            // Back to Sonnet for balanced tasks
            client.setModel("claude-sonnet-4-5");
            client.sendMessage("Write a Java function");
            processResponse(client.receiveResponse());
        }
    }
}
```

### 例 4：複数セッションの管理

```java
public class MultiSession {
    public static void main(String[] args) {
        try (var client = ClaudeSDK.createClient()) {
            client.connect();

            // Start multiple tasks in different sessions
            client.sendMessage("Review code.java for bugs", "review");
            client.sendMessage("Write tests for util.java", "testing");
            client.sendMessage("Document api.java", "docs");

            // Process responses from all sessions
            for (var msg : client.receiveMessages()) {
                switch (msg) {
                    case AssistantMessage a ->
                        System.out.println("[Session] " + a.getTextContent());
                    case ResultMessage r ->
                        System.out.println("[Done] Cost: $" + r.totalCostUsd());
                    default -> {}
                }

                // Stop when all three sessions complete
                if (allSessionsComplete()) {
                    break;
                }
            }
        }
    }
}
```

### 例 5：ファイルのチェックポイント

```java
public class Checkpointing {
    public static void main(String[] args) {
        var options = ClaudeAgentOptions.builder()
            .enableFileCheckpointing(true)
            .extraArgs(Map.of("replay-user-messages", ""))
            .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect();

            String checkpointId = null;

            // Create a file and save checkpoint
            client.sendMessage("Create test.txt with 'Hello'");
            for (var msg : client.receiveResponse()) {
                if (msg instanceof UserMessage user) {
                    checkpointId = user.uuid();
                }
            }

            // Modify the file
            client.sendMessage("Append 'World' to test.txt");
            for (var msg : client.receiveResponse()) {}

            // Rewind to original state
            if (checkpointId != null) {
                client.rewindFiles(checkpointId);
                System.out.println("Rewound to checkpoint");
            }
        }
    }
}
```

## ベストプラクティス

### 1. try-with-resources を使う

```java
// ✅ Good
try (var client = ClaudeSDK.createClient()) {
    client.connect();
}

// ❌ Bad: Resource leak
var client = ClaudeSDK.createClient();
client.connect();
// Forgot to close!
```

### 2. 接続エラーを処理する

```java
// ✅ Good
try (var client = ClaudeSDK.createClient()) {
    try {
        client.connect();
    } catch (CLIConnectionException e) {
        System.err.println("Failed to connect: " + e.getMessage());
        return;
    }
    // Use client
}
```

### 3. リクエスト／レスポンスには receiveResponse() を使う

```java
// ✅ Good: Clean request/response
client.sendMessage("Question");
for (var msg : client.receiveResponse()) {
    // Processes until ResultMessage
}

// ❌ Bad: Manual ResultMessage checking
for (var msg : client.receiveMessages()) {
    if (msg instanceof ResultMessage) break;
}
```

### 4. 受信イテレーターを複数作らない

```java
// ✅ Good: Single iterator
Iterator<Message> messages = client.receiveMessages();

// ❌ Bad: Messages split across iterators
Iterator<Message> iter1 = client.receiveMessages();
Iterator<Message> iter2 = client.receiveMessages();
```

### 5. 適切な上限を設定する

```java
// ✅ Good: Configure limits
var options = ClaudeAgentOptions.builder()
    .maxTurns(50)  // Long conversation
    .maxBudgetUsd(5.0)
    .build();

// ❌ Bad: No limits in interactive session
var client = ClaudeSDK.createClient();  // Could be expensive!
```

### 6. すべてのメッセージ型を処理する

```java
// ✅ Good: Exhaustive pattern matching
for (var msg : client.receiveResponse()) {
    switch (msg) {
        case UserMessage u -> handleUser(u);
        case AssistantMessage a -> handleAssistant(a);
        case ResultMessage r -> handleResult(r);
        case SystemMessage s -> handleSystem(s);
        case StreamEvent e -> handleStream(e);
    }
}
```

### 7. 制御メソッドを適切に使う

```java
// ✅ Good: Switch models based on task complexity
if (isComplexTask) {
    client.setModel("claude-opus-4-6");
}

client.sendMessage(task);

// ✅ Good: Interrupt on timeout
CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS)
    .execute(() -> client.interrupt());
```

## 関連ドキュメント

- [単純なクエリ](./feature-simple-queries.md) — 単発のクエリ向け
- [設定オプション](./feature-configuration-options.md) — ClaudeAgentOptions のすべて
- [メッセージ型](./feature-message-types.md) — メッセージの理解
- [ClaudeSDKClient API リファレンス](./api-claude-sdk-client.md) — 完全な API ドキュメント
