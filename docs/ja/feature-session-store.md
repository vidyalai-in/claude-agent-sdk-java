# Session Store（トランスクリプトの外部ミラーリング）

Claude Code のセッショントランスクリプトを外部ストア（S3、Postgres、Redis、独自バックエンド）へミラー
することで、セッションをローカルディスクを超えて永続化し、どこからでも再開できるようにします。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../feature-session-store.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

## 目次
- [概要](#概要)
- [SessionStore を使う場面](#sessionstore-を使う場面)
- [クイックスタート](#クイックスタート)
- [SessionStore インターフェース](#sessionstore-インターフェース)
- [同期 API と非同期 API](#同期-api-と非同期-api)
- [非同期エグゼキュータの設定](#非同期エグゼキュータの設定)
- [同梱のリファレンスアダプタ](#同梱のリファレンスアダプタ)
- [SessionStore を使う読み取り API](#sessionstore-を使う読み取り-api)
- [SessionStore を使う変更操作](#sessionstore-を使う変更操作)
- [ストアからのレジューム](#ストアからのレジューム)
- [ミラーのエラー](#ミラーのエラー)
- [フラッシュモード（BATCHED と EAGER）](#フラッシュモードbatched-と-eager)
- [ローカルのセッションをストアへ取り込む](#ローカルのセッションをストアへ取り込む)
- [適合性テストハーネス](#適合性テストハーネス)
- [内部のランタイム要素](#内部のランタイム要素)
- [ベストプラクティス](#ベストプラクティス)
- [API リファレンス](#api-リファレンス)

## 概要

既定では、Claude Code CLI はすべてのセッションを `~/.claude/projects/` 以下の JSONL ファイルとして
書き出します。SDK はさらに、トランスクリプトの各行をあなたが選んだ外部ストアへミラーできます ——
次のような場面で役立ちます：

- **長時間セッションの永続化** —— サーバーレスやオートスケーリングの基盤では、ローカルディスクは
  一時的です。
- **マルチホストでのレジューム** —— ホスト A で始めたセッションをホスト B で再開する。
- **監査／コンプライアンスの保管** —— 独自の TTL ポリシーを適用する（S3 のライフサイクル、Postgres の
  パーティション、Redis の TTL）。
- **マルチテナントの配備** —— `project_key` でトランスクリプトの範囲を区切り、テナントを分離する。

SDK が提供するもの：

- `SessionStore` インターフェース（同期＋非同期のバリアント）
- `InMemorySessionStore` リファレンスアダプタ
- ランタイムのミラー統合（透過的 —— options に `sessionStore` を設定すれば、残りは SDK が面倒を見ます）
- 既存のディスク上セッションを移行するための `importSessionToStore()`

ローカルディスクのトランスクリプトは常に先に書かれます。ミラーリングは二次的な永続化経路です。ミラーの
失敗がセッションを止めることはありません —— 致命的でない `MirrorErrorMessage` として現れます。

## SessionStore を使う場面

| シナリオ | 推奨 |
|---|---|
| ワークステーション上の単一ユーザー CLI | 不要 —— ローカルの JSONL で十分 |
| 長時間動くサーバーで、セッションが複数リクエストにまたがる | `SessionStore` を使う |
| コンプライアンス／規制された保管要件 | ネイティブのライフサイクルポリシーを持つ `SessionStore` を使う |
| マルチホストの群／クラウドのオートスケーリング | どのホストからでも再開できるよう `SessionStore` を使う |
| 多数のセッションにまたがる監査／リプレイ | 集中的にクエリするため `SessionStore` を使う |

## クイックスタート

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;

InMemorySessionStore store = new InMemorySessionStore();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)            // mirror every transcript line here
    .build();

ClaudeSDK.query("Hello!", options);
// All transcript entries from this turn are now in `store`
```

SDK は CLI の起動に `--session-mirror` を加え、CLI の stdout から `transcript_mirror` フレームを
取り出し、バッチで `store.append(...)` に転送します。

別のホストでストアから再開するには：

```java
ClaudeAgentOptions resumeOptions = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .resume("previous-session-uuid")
    .build();

ClaudeSDK.query("Continue where we left off", resumeOptions);
```

SDK は保存済みのトランスクリプトを一時的な `CLAUDE_CONFIG_DIR` に読み込み、CLI サブプロセスが会話を
引き継げるようにします。

## SessionStore インターフェース

`in.vidyalai.claude.sdk.types.session.SessionStore` は Java のインターフェースです。2 つのメソッドが
必須で、残りは省略可能です。`implements*()` という判定フラグがあるので、呼び出し側は `instanceof` を
使わずに何がサポートされているか判断できます。

### 必須メソッド

```java
void append(SessionKey key, List<SessionStoreEntry> entries);

@Nullable
List<SessionStoreEntry> load(SessionKey key);
```

- `append` —— トランスクリプトのエントリをバッチでミラーします。ローカルディスクへの書き込みが成功した
  *後*に呼ばれるので、永続性はすでにローカルで保証されています。アダプタは `entry.uuid()` を冪等キーと
  して扱ってください（カスタムタイトルやタグのように `uuid` を持たないエントリは、重複排除せずに追記
  してください）。
- `load` —— そのキーのすべてのエントリを返します（追記したものと深く等しいこと。バイト等価である必要は
  ありません）。一度も書き込まれていないキーには `null` を返します。

### 省略可能なメソッド（既定では `UnsupportedOperationException` を送出）

```java
default List<SessionStoreListEntry> listSessions(String projectKey);
default List<SessionSummaryEntry> listSessionSummaries(String projectKey);
default void delete(SessionKey key);
default List<String> listSubkeys(SessionListSubkeysKey key);
```

### 機能の判定

```java
default boolean implementsListSessions() { return false; }
default boolean implementsListSessionSummaries() { return false; }
default boolean implementsDelete() { return false; }
default boolean implementsListSubkeys() { return false; }
```

対応する省略可能メソッドを実装したら、これらをオーバーライドして `true` を返してください。SDK は
（`try/catch` ではなく）この判定を使って、省略可能メソッドを呼ぶかどうかを決めます。

### キーの型

```java
public record SessionKey(
    String projectKey,             // caller-defined scope (default: sanitized cwd)
    String sessionId,              // session UUID
    @Nullable String subpath        // null for main; "subagents/agent-x" for subagent
);

public record SessionListSubkeysKey(String projectKey, String sessionId);

public record SessionStoreListEntry(String sessionId, long mtime);

public record SessionSummaryEntry(String sessionId, long mtime, Map<String, Object> data);
```

`SessionStoreEntry` は `Map<String, Object>` の薄いラッパーで、`type` フィールドを必須とします。その他は
不透明なまま素通しされます：

```java
SessionStoreEntry entry = SessionStoreEntry.of(Map.of(
    "type", "user",
    "uuid", "u1",
    "message", Map.of(
        "content", List.of(Map.of("type", "text", "text", "Hello"))),
    "timestamp", "2026-04-27T00:00:00Z"
));

entry.type();      // "user"
entry.uuid();      // "u1"
entry.timestamp(); // "2026-04-27T00:00:00Z"
entry.<String>get("custom_field"); // typed convenience accessor
entry.asMap();     // unmodifiable map view
```

## 同期 API と非同期 API

`SessionStore` のすべてのメソッドには、同期版と `*Async` 版（`CompletableFuture` を返す）があります：

```java
// Sync (required to implement; or default to *Async().join() if you only override async)
void append(SessionKey key, List<SessionStoreEntry> entries);

// Async with default executor
default CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries);

// Async with explicit executor
default CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries, Executor executor);
```

内部のミラーバッチャーとレジュームのマテリアライザは `*Async` 版を呼びます —— そのため、ネイティブに
ノンブロッキングなクライアント（AWS SDK v2 async、R2DBC、Lettuce reactive）を持つアダプタは `*Async`
をオーバーライドすれば、端から端まで並列性を保てます。

### 既定の委譲

- **同期**メソッドだけをオーバーライドした場合（JDBC、Jedis、ブロッキングな S3 SDK v1 での典型）、
  `*Async` の既定実装が、設定されたエグゼキュータ上で `CompletableFuture.supplyAsync(...)` により
  あなたの同期呼び出しを包みます（タスクごとに 1 スレッド。Java 21+ では仮想スレッド）。
- **非同期**メソッドだけをオーバーライドした場合（AWS SDK v2 async / Lettuce reactive / R2DBC で推奨）、
  同期メソッドは `appendAsync(key, entries).join()` として実装してください。そうすれば双方の呼び出し元で
  動作します。

```java
public class S3AsyncStore implements SessionStore {
    private final S3AsyncClient s3;

    @Override
    public void append(SessionKey key, List<SessionStoreEntry> entries) {
        appendAsync(key, entries).join();
    }

    @Override
    public CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries) {
        // Native async — no thread hop
        return s3.putObject(/* ... */).thenApply(r -> null);
    }

    @Override
    public List<SessionStoreEntry> load(SessionKey key) { /* ... */ }
}
```

## 非同期エグゼキュータの設定

既定では、非同期のラッパーは**タスクごとに 1 スレッド**で、`session-store-<n>` という名前のスレッド上で
動きます。Java 21+ では仮想スレッド、Java 17-20 では SDK が無制限のキャッシュプールから取るデーモンの
プラットフォームスレッドにフォールバックします。SDK は Java 17 を対象にしつつ実行時により良い方を選ぶ
ので、仮想スレッドを必須にせずにその恩恵を受けられます。

起動時に `SessionStoreExecutor` で一度だけ上書きできます：

```java
import in.vidyalai.claude.sdk.types.session.SessionStoreExecutor;

// Your own virtual-thread executor (needs Java 21+ in *your* project)
ExecutorService mine = Executors.newThreadPerTaskExecutor(
    Thread.ofVirtual().name("my-store-", 0).factory());
SessionStoreExecutor.setDefault(mine);

// Or a bounded platform-thread pool
SessionStoreExecutor.setDefault(Executors.newFixedThreadPool(8));

// Reset to the built-in default
SessionStoreExecutor.reset();
```

呼び出しごとにエグゼキュータを渡すこともできます：

```java
store.appendAsync(key, entries, customExecutor)
```

設定されたエグゼキュータは、明示的なエグゼキュータを取らないすべての `*Async` 既定実装で使われます。
`*Async` を直接オーバーライドするアダプタはこれを完全に迂回します —— エグゼキュータは同期→非同期の
ラッピング経路にしか適用されません。

## 同梱のリファレンスアダプタ

### `InMemorySessionStore`

テストやプロトタイピングに適した、スレッドセーフなインメモリ実装です：

```java
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;

InMemorySessionStore store = new InMemorySessionStore();

// All optional methods implemented
store.implementsListSessions();         // true
store.implementsListSessionSummaries(); // true
store.implementsDelete();               // true
store.implementsListSubkeys();          // true

// Test helpers
store.size();             // count of main-transcript sessions
store.snapshotSummaries();// LinkedHashMap snapshot of summary sidecars
store.clear();            // wipe everything
```

`append()` の中で増分の `SessionSummaryEntry` サイドカーを維持するので、`listSessionSummaries()` は
O(1) で動き、トランスクリプトを読み直すことはありません。

### パス → キー のヘルパー

`InMemorySessionStore.filePathToSessionKey(filePath, projectsDir)` は、ディスク上のトランスクリプトの
パスを `SessionKey` に戻す静的ヘルパーです：

```java
SessionKey k = InMemorySessionStore.filePathToSessionKey(
    "/home/u/.claude/projects/myproj/abc-123.jsonl",
    "/home/u/.claude/projects");
// k = SessionKey("myproj", "abc-123", null)

SessionKey sub = InMemorySessionStore.filePathToSessionKey(
    "/home/u/.claude/projects/myproj/abc-123/subagents/agent-x.jsonl",
    "/home/u/.claude/projects");
// sub = SessionKey("myproj", "abc-123", "subagents/agent-x")
```

`projectsDir` の外のパスや認識できないレイアウトには `null` を返します。ミラーバッチャーが内部で使って
おり、同じマッピングを必要とするアダプタ実装のために公開されています。

### 本番向けアダプタ（S3、Redis、Postgres など）

SDK は本番向けアダプタを同梱しません —— それらは重量級のクライアントライブラリ（AWS SDK、Lettuce、
JDBC、R2DBC）に依存し、推移的依存として持ち込みたくないからです。ご自身で実装し、`SessionStoreConformance`
（後述）で検証してください。プロトコルは小さく安定しています。

## SessionStore を使う読み取り API

CLI を介さずにストアから直接セッションを読みます：

```java
import in.vidyalai.claude.sdk.ClaudeSDK;

// List all sessions in the store for the current cwd
List<SDKSessionInfo> sessions =
    ClaudeSDK.listSessionsFromStore(store, /* directory */ null, /* limit */ 50, /* offset */ 0);

// Single-session metadata
SDKSessionInfo info = ClaudeSDK.getSessionInfoFromStore(store, sessionId, null);

// Full transcript
List<SessionMessage> messages =
    ClaudeSDK.getSessionMessagesFromStore(store, sessionId, null, null, 0);

// Subagent transcript discovery + reading
List<String> agentIds = ClaudeSDK.listSubagentsFromStore(store, sessionId, null);
List<SessionMessage> subAgent =
    ClaudeSDK.getSubagentMessagesFromStore(store, sessionId, agentIds.get(0), null, null, 0);

// Each message is attributed to the Agent tool_use that spawned the subagent,
// read from the mirrored `agent_metadata` entry (null if it is absent).
String spawnedBy = subAgent.get(0).parentToolUseId();
```

ストアが `listSessionSummaries` を実装している場合、`listSessionsFromStore` には高速経路があります。
1 回のバッチ要約呼び出しと、安価な `listSessions` の列挙で、サイドカーが欠けていたり古かったりする
セッションだけを埋め合わせます。`listSessionSummaries` が未実装のときは、セッションごとに 1 回の
`loadAsync()` にフォールバックしますが、**同時実行は 16 件に制限**されます（Python SDK に合わせて
います）。大きなプロジェクトの一覧でアダプタの接続プールを枯渇させないためです。

`listSessions` と `listSessionSummaries` の両方が未実装なら、この呼び出しは `IllegalStateException` を
送出します。アダプタの `loadAsync` の失敗は、リスト全体を失敗させるのではなく、その行だけを空の要約
エントリへ降格させます。

## SessionStore を使う変更操作

ディスク上の変更 API と同じ形ですが、書き込み先はストアです：

```java
ClaudeSDK.renameSessionViaStore(store, sessionId, "My New Title", null);
ClaudeSDK.tagSessionViaStore(store, sessionId, "important", null);
ClaudeSDK.tagSessionViaStore(store, sessionId, null, null);   // clear tag
ClaudeSDK.deleteSessionViaStore(store, sessionId, null);

ForkSessionResult fork = ClaudeSDK.forkSessionViaStore(
    store, sessionId, /* directory */ null,
    /* upToMessageId */ null,                  // null copies full transcript
    /* title */ "My Fork");
```

内部の動き：

- `renameSessionViaStore` は `custom-title` エントリを追記します。
- `tagSessionViaStore` は `tag` エントリを追記します。`null` は空文字列によって消去します。
- ストアが `delete()` を実装していない場合、`deleteSessionViaStore` は何もしません（素の S3 のような
  WORM／追記専用のバックエンドに適切です）。
- `forkSessionViaStore` はディスク上のフォークと同じ UUID 振り直しの変換を行います（共通の
  `SessionMutations.buildForkLines`）—— ストレージ層でのコピーだけでは**不十分**です。

`listSubagentsFromStore` は `listSubkeys()` を必要とし、なければ `IllegalStateException` を送出します。

## ストアからのレジューム

`options.sessionStore` が `options.resume`（または `options.continueConversation`）と一緒に設定されて
いると、SDK は次のことを行います：

1. 要求されたセッション ID に対して `store.load()` を呼びます（`continueConversation` の場合は
   `store.listSessions()` で、サイドチェーンでない最も新しいセッションを選びます）。
2. エントリを `~/.claude/` とまったく同じレイアウトの一時ディレクトリに書き出します。
3. サブプロセスが認証でき、いつもどおり振る舞えるように、本物の設定ディレクトリからその一時ディレクトリ
   へ種を撒きます —— `.credentials.json`（`refreshToken` は削除）、`.claude.json`、そしてユーザーの
   `settings.json` / `cowork_settings.json`。[何が撒かれるか](#何が撒かれるか)を参照してください。
4. ストアからサブエージェントのトランスクリプトと `.meta.json` サイドカーをマテリアライズします
   （`listSubkeys` が実装されている場合）。
5. `CLAUDE_CONFIG_DIR=<temp dir>` を与えて CLI を起動し、通常どおりローカルディスクから再開させます。
6. 切断時に一時ディレクトリを片づけます（Windows のウイルス対策／インデクサによる一時的なロックには
   リトライします）。

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .resume(previousSessionId)
    .loadTimeoutMs(60_000)        // per-call timeout for store.load() / listSubkeys()
    .build();
```

`loadTimeoutMs` オプション（既定 60 000）は、マテリアライズ中の個々のストア呼び出しに上限を設けます。
アダプタがこの時間内に決着しなければ、イテレータをぶら下げたままにせず、明確なエラーでクエリが素早く
失敗します。

### 何が撒かれるか

サブプロセスはリダイレクトされた `CLAUDE_CONFIG_DIR` の下で動くため、そのままではあなたの設定が一切
見えません。SDK は呼び出し元の設定ディレクトリから 4 つのファイルをコピーします —— 設定ディレクトリは
`options.env["CLAUDE_CONFIG_DIR"]` → プロセス環境 → `~/.claude` の順で解決されます（設定されている場合
`.claude.json` は `$CLAUDE_CONFIG_DIR/.claude.json` にあり、そうでなければ `~/.claude.json` で、
*`~/.claude/.claude.json` ではありません*）：

| ファイル | なぜ重要か |
|------|----------------|
| `.credentials.json` | OAuth の資格情報。`claudeAiOauth.refreshToken` は取り除かれます |
| `.claude.json` | ユーザーレベルの CLI 状態 |
| `settings.json` | `apiKeyHelper` と、あなたの `env`、`hooks`、`permissions` |
| `cowork_settings.json` | cowork-plugins モードで読まれる代替の設定ファイル名 |

`settings.json` を撒くことは見た目以上に重要です。`apiKeyHelper` は、資格情報ファイル・macOS の
キーチェーン・環境変数と並ぶ第 4 の認証手段です。v0.1.23 より前はコピーされていなかったため、
`apiKeyHelper` だけで認証していたホストは、ストアから再開した途端に **「Not logged in」** で失敗して
いました。

両方の設定ファイルは、リダイレクトされた設定ディレクトリの下で不都合を起こすキーだけを落とす変換を
通ります：

- `enabledPlugins` と `extraKnownMarketplaces` —— 常に空の一時プラグインキャッシュと突き合わせが行われ、
  レジュームのたびに宣言されたすべてのマーケットプレースをネットワーク越しにインストールしてしまいます。
- `env.CLAUDE_CONFIG_DIR` —— サブプロセスの設定読み取りを一時ディレクトリの外へ引き戻してしまいます。

それ以外はすべて保たれます。UTF-8 の BOM（PowerShell は付けます）は許容され、有効な UTF-8 でない内容や
JSON オブジェクトとして解析できない内容は、サブプロセスが CLI と同じものを見られるようにバイト単位で
コピーされます。ファイルは所有者専用（`0700`）の一時ディレクトリの中に、所有者専用（`0600`）で書かれます。

種撒きはベストエフォートです。「存在しない」以外の理由で読めないファイル —— 権限エラーや、ファイルが
あるはずの場所のディレクトリや FIFO —— は、本来成功するはずのレジュームを中断させるのではなく、記録して
スキップされます。途中で失敗したコピーは、サブプロセスが切り詰められたファイルを誤って解析しないよう、
途中までの出力先を削除します。

### 検証のガード

サブプロセスの処理を始める前に、SDK は無効な組み合わせを拒否します：

- `continueConversation + sessionStore` には `store.implementsListSessions()` が必要です。
- `sessionStore + enableFileCheckpointing` は拒否されます —— チェックポイントはローカルディスク専用で、
  ミラーされたトランスクリプトと食い違ってしまいます。

これらは即座に `IllegalArgumentException` を送出します。

## ミラーのエラー

ミラーの追記失敗は致命的ではありません —— ローカルディスクのトランスクリプトはすでに永続化されているので、
セッションは影響を受けずに続きます。SDK は各バッチを `[200ms, 800ms]` のバックオフで最大 3 回まで再試行し、
その後は破棄して、あなたのメッセージストリームに `MirrorErrorMessage` を流します：

```java
import in.vidyalai.claude.sdk.types.message.MirrorErrorMessage;

for (Message msg : ClaudeSDK.query("Hello", options)) {
    switch (msg) {
        case MirrorErrorMessage err -> {
            // Non-fatal — log and consider importing the local file later
            System.err.println("Mirror error for " + (err.key() != null
                    ? err.key().sessionId() : "<unknown>")
                    + ": " + err.error());
        }
        case AssistantMessage a -> System.out.println(a.getTextContent());
        // ... other cases
        default -> { /* ignore */ }
    }
}
```

`MirrorErrorMessage` は `Message` sealed インターフェースの一員です（`AssistantMessage`、
`SystemMessage` などと並びます）。`subtype` は常に `"mirror_error"`、`error` は失敗のメッセージ、
`key`（null 可）は失敗したバッチが対象としていた `SessionKey` です。

タイムアウトは再試行**されません**（進行中の呼び出しが後から着地する可能性があり、再試行すると並行する
重複を生むためです）。アダプタは `entry.uuid()` で重複排除し、部分的に成功したあとの再試行が重複安全に
なるようにしてください。

## フラッシュモード（BATCHED と EAGER）

既定では `TranscriptMirrorBatcher` はすべての `transcript_mirror` フレームをバッファし、1 ターンごとに
（`result` メッセージで）、または保留中のバッファが `MAX_PENDING_ENTRIES=500` エントリ /
`MAX_PENDING_BYTES=1 MiB` を超えたときにフラッシュします。これによりアダプタのレイテンシをストリーミングの
ホットパスから外せるので、ほとんどすべての配備で正しい選択です。

エントリを 1 秒未満のレイテンシでストアに着地させたい場合、`sessionStoreFlush` オプションで即時ミラーへ
切り替えられます：

```java
import in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .sessionStoreFlush(SessionStoreFlushMode.EAGER)
    .build();
```

| モード | いつフラッシュされるか | 使いどころ |
|---|---|---|
| `BATCHED`（既定） | `result` メッセージごとに 1 回、または保留が 500 エントリ / 1 MiB を超えたとき | ほぼすべての本番ワークロード —— アダプタのレイテンシをストリーミングのホットパスから外せます |
| `EAGER` | フレームをキューに入れるたびにバックグラウンドの排出をスケジュール | クライアントへのライブなトランスクリプト配信、リアルタイムの監査パイプライン、`result` まで待てない非常に大きなターン |

`EAGER` はバッチャーの保留しきい値をゼロにします —— フレームをキューに入れるたびに、設定された
`SessionStoreExecutor` 経由でバックグラウンドのフラッシュがスケジュールされます（タスクごとに名前付きの
スレッド 1 本。Java 21+ では仮想スレッド）。追記はキューの順序どおり直列のままです。遅いアダプタが読み取り
ループを止めることはありませんが、忙しい間はフレームがまとめられて見えます。`sessionStore` が未設定の
場合、このオプションは無視されます。

## ローカルのセッションをストアへ取り込む

既存のディスク上セッションをストアへ移行したり、`MirrorErrorMessage` が示した欠落をあとから埋めたり
します：

```java
ClaudeSDK.importSessionToStore(sessionId, store, /* directory */ null);
// or with explicit options:
ClaudeSDK.importSessionToStore(
    sessionId, store, /* directory */ null,
    /* includeSubagents */ true,
    /* batchSize */ 500);
```

このヘルパーは：

- ローカルの JSONL を 1 行ずつストリーム読みします（空行はスキップ）。
- `batchSize` エントリごと（既定 500）、または行のバイト数が 1 MiB に達するごとの、いずれか早い方で
  `store.append(key, batch)` を呼びます。
- `includeSubagents=true` のときは、`<sessionDir>/subagents/**/*.jsonl` と `.meta.json` サイドカーを
  再帰的に取り込みます（`.meta.json` は `agent_metadata` エントリになります）。
- 不正な UUID には `IllegalArgumentException`、セッションファイルが見つからないときは
  `NoSuchFileException` を送出します。

取り込み先の `project_key` は、ディスク上のプロジェクトディレクトリ名 —— `filePathToSessionKey` が
生成するのと同じキー —— なので、取り込まれたセッションはライブでミラーされたものと区別がつかず、元の
`cwd` から再開できます。

アダプタは `entry.uuid()` を冪等キーとして扱い、再取り込みが重複安全になるようにしてください。

## 適合性テストハーネス

`in.vidyalai.claude.sdk.testing.SessionStoreConformance` は公開された、テストフレームワークに依存しない
テストハーネスで、すべてのアダプタが満たすべき 14 の振る舞いの契約を検証します。ご自身の実装の検証に
使ってください：

```java
import in.vidyalai.claude.sdk.testing.SessionStoreConformance;
import org.junit.jupiter.api.Test;

class MyRedisStoreConformanceTest {
    @Test
    void satisfiesContract() {
        SessionStoreConformance.run(MyRedisStore::new);
    }
}
```

実装していない省略可能メソッドを飛ばすには：

```java
SessionStoreConformance.run(WormStore::new,
    EnumSet.of(SessionStoreConformance.OptionalMethod.DELETE));
```

ハーネスは素の `AssertionError` を使う（テストフレームワークに依存しない）ので、JUnit、TestNG、Spock、
さらには単なる `main()` のスモークテストでも動きます。

14 の契約が扱う内容：

| # | 契約 |
|---|---|
| 1 | `append` のあとの `load` が同じ順序で同じエントリを返す |
| 2 | 未知のキーへの `load` が `null` を返す |
| 3 | 複数回の `append` が順序を保つ |
| 4 | `append([])` が no-op である |
| 5 | subpath 付きのキーが main とは独立に保存される |
| 6 | `project_key` の分離 |
| 7 | `listSessions` がプロジェクトのセッション ID を返し、mtime がエポックミリ秒である |
| 8 | `listSessions` がサブエージェントの subpath を除外する |
| 9 | `delete` のあとの `load` が `null` を返す |
| 10 | main キーの `delete` がサブキーへ連鎖する |
| 11 | subpath 付きの `delete` がそのサブキーだけを消す |
| 12 | `listSubkeys` が subpath を返す |
| 13 | `listSubkeys` が main のトランスクリプトを除外する |
| 14 | `listSessionSummaries` が `foldSessionSummary` を通じて往復する |

## 内部のランタイム要素

これらは `in.vidyalai.claude.sdk.internal` にあり公開 API ではありませんが、理解しておくとミラーの挙動を
デバッグするのに役立ちます。

### `TranscriptMirrorBatcher`

CLI が stdout に出す `transcript_mirror` フレームをバッファし、`store.appendAsync(...)` へフラッシュ
します：

- 即時フラッシュのしきい値：`MAX_PENDING_ENTRIES=500`、`MAX_PENDING_BYTES=1 MiB`。
  `sessionStoreFlush(EAGER)` では両方がゼロになり、キューに入れたすべてのフレームがバックグラウンドの
  排出をスケジュールします（[フラッシュモード](#フラッシュモードbatched-と-eager)を参照）。
- 各 `result` メッセージの前に明示的にフラッシュし、ストリーム終端／クローズ時にもう一度行います。
- `filePath` ごとにフレームをまとめ、フラッシュごとに一意なファイルにつき `append` 呼び出しを 1 回に
  します。
- パスが `projectsDir` の外にあるフレームは警告付きで破棄されます（親プロセスとサブプロセスで
  `CLAUDE_CONFIG_DIR` が異なると起こります）。
- `MIRROR_APPEND_MAX_ATTEMPTS=3` 回、`[200ms, 800ms]` のバックオフで再試行します。タイムアウトは再試行
  しません。
- `maxPendingEntries()` / `maxPendingBytes()` というテスト用アクセサが設定済みのしきい値を公開します
  （Python の公開属性に対応）。

### `SessionResume`

保存済みのセッションを一時的な `CLAUDE_CONFIG_DIR` にマテリアライズし、CLI が再開できるようにします：

- `materializeResumeSession(options)` —— 主な入口。
- `applyMaterializedOptions(options, materialized)` —— `CLAUDE_CONFIG_DIR` を注入し、`resume` を設定し、
  `continueConversation` を解除した options のコピーを作ります。
- `buildMirrorBatcher(store, materialized, env, onError)` —— 正しい `projectsDir` でバッチャーを構築
  します（既定は `BATCHED` フラッシュモード）。5 引数のオーバーロード
  `buildMirrorBatcher(store, materialized, env, onError, flushMode)` は `flushMode == EAGER` のとき
  バッチャーのしきい値をゼロにします。
- `MaterializedResume.cleanup()` —— ベストエフォートの再帰削除。Windows のウイルス対策／インデクサに
  よる一時的なロックにはリトライします。

### `SessionStoreValidation`

サブプロセス起動前に呼ばれる事前のオプション検査です（設定ミスを `IllegalArgumentException` で拒否）。

### `SessionSummary`

トランスクリプトを読み直さずに増分の要約サイドカーを維持できるよう、アダプタが `append()` の中で使える
純粋なヘルパーです：

```java
SessionSummaryEntry folded = SessionSummary.foldSessionSummary(
    /* prev */ existing, key, entries);
// stamp folded.mtime() with the adapter's storage write time, then persist.
```

`SessionSummary.summaryEntryToSdkInfo(entry, projectPath)` は、一覧のためにサイドカーを
`SDKSessionInfo` へ戻します。

## ベストプラクティス

### アダプタの実装

- **`append` と `load` は必ず実装してください。** 必須です。
- バックエンドが一覧操作をサポートするなら、`append()` の中で `SessionSummary.foldSessionSummary` により
  **要約サイドカーを維持**してください。これで `listSessionsFromStore` が O(N) 回の load ではなく O(1) に
  なります。`subpath` 付きのキーでは fold を飛ばしてください —— サブエージェントのトランスクリプトは
  main セッションの要約に寄与してはいけません。
- **`entry.uuid()` を冪等キーとして扱ってください。** upsert か「あればスキップ」の動作にします。SDK は
  失敗したバッチを再試行し、部分的に成功することがあります。
- **要約の `mtime` にはあなたのストレージの書き込み時刻を刻んでください**。エントリのタイムスタンプでは
  ありません。高速経路の鮮度チェックは、同じセッションの `listSessions().mtime` と要約の mtime を比べます
  —— エントリのタイムスタンプを使うと、すべてのサイドカーが古く見えてしまいます。
- main トランスクリプトのキーからすべてのサブキー（サブエージェントのトランスクリプト）へ
  **削除を連鎖**させてください。
- **CI で適合性ハーネスを走らせてください。**

### `*Async` メソッドをオーバーライドすべきとき

- クライアントがネイティブに非同期（AWS SDK v2 async、R2DBC、Lettuce reactive）—— スレッドの跳躍を
  避けるため `*Async` をオーバーライドします。
- クライアントが同期（JDBC、Jedis、AWS SDK v1）—— 同期メソッドだけ実装すれば、既定の `*Async` ラッパーで
  十分です。

### `importSessionToStore` を使うとき

- 既存のローカルセッションをストアへ一度だけ移行するとき。
- `MirrorErrorMessage` のあとに追いつくとき（ローカルファイルを再取り込み。`uuid` による冪等性のおかげで
  安全です）。

### 避けること

- `sessionStore` と `enableFileCheckpointing` の併用（いずれにせよ検証時に拒否されます —— チェックポイントは
  ローカル専用です）。
- 保管の制御なしに秘密情報や個人情報を保存すること。SDK は自動削除しません。ストアのライフサイクルを
  設定してください。
- `load()` にバイト等価のシリアライズを期待すること。契約は深い等価です。たとえば Postgres の `jsonb` は
  キーの順序を並べ替えます。

## API リファレンス

### `SessionStore` インターフェース

`in.vidyalai.claude.sdk.types.session.SessionStore`

| メソッド | 必須 | 既定 | 備考 |
|---|---|---|---|
| `void append(SessionKey, List<SessionStoreEntry>)` | ✅ | — | バッチをミラー。ローカル書き込みの後に呼ばれる |
| `List<SessionStoreEntry> load(SessionKey)` | ✅ | — | エントリまたは `null` を返す |
| `List<SessionStoreListEntry> listSessions(String)` | 省略可 | 送出 | subpath 付きのエントリを除外 |
| `List<SessionSummaryEntry> listSessionSummaries(String)` | 省略可 | 送出 | `listSessionsFromStore` の高速経路 |
| `void delete(SessionKey)` | 省略可 | 送出 | main キーはサブキーへ連鎖 |
| `List<String> listSubkeys(SessionListSubkeysKey)` | 省略可 | 送出 | レジュームのマテリアライズで使用 |
| `boolean implementsListSessions()` | — | `false` | サポートを宣言するためオーバーライド |
| `boolean implementsListSessionSummaries()` | — | `false` | サポートを宣言するためオーバーライド |
| `boolean implementsDelete()` | — | `false` | サポートを宣言するためオーバーライド |
| `boolean implementsListSubkeys()` | — | `false` | サポートを宣言するためオーバーライド |
| `CompletableFuture<Void> appendAsync(...)` | 省略可 | 同期を包む | ネイティブ非同期クライアント向けにオーバーライド |
| `CompletableFuture<List<SessionStoreEntry>> loadAsync(...)` | 省略可 | 同期を包む | ネイティブ非同期クライアント向けにオーバーライド |
| `CompletableFuture<List<SessionStoreListEntry>> listSessionsAsync(...)` | 省略可 | 同期を包む | — |
| `CompletableFuture<List<SessionSummaryEntry>> listSessionSummariesAsync(...)` | 省略可 | 同期を包む | — |
| `CompletableFuture<Void> deleteAsync(...)` | 省略可 | 同期を包む | — |
| `CompletableFuture<List<String>> listSubkeysAsync(...)` | 省略可 | 同期を包む | — |

各 `*Async` メソッドには、引数なしのオーバーロード（設定済みの既定エグゼキュータを使用）と、`Executor`
を取るオーバーロード（呼び出しごとの制御）の両方があります。

### `ClaudeSDK` の静的メソッド

| メソッド | 説明 |
|---|---|
| `String projectKeyForDirectory(@Nullable Path)` | ディレクトリを `project_key` に正規化する |
| `List<SDKSessionInfo> listSessionsFromStore(SessionStore, @Nullable Path, @Nullable Integer, int)` | ストア内のセッションを一覧する |
| `SDKSessionInfo getSessionInfoFromStore(SessionStore, String, @Nullable Path)` | 単一セッションのメタデータを読む |
| `List<SessionMessage> getSessionMessagesFromStore(SessionStore, String, @Nullable Path, @Nullable Integer, int)` | トランスクリプト全体を読む |
| `List<String> listSubagentsFromStore(SessionStore, String, @Nullable Path)` | サブエージェントの ID を見つける |
| `List<SessionMessage> getSubagentMessagesFromStore(SessionStore, String, String, @Nullable Path, @Nullable Integer, int)` | サブエージェントのトランスクリプトを読む |
| `void renameSessionViaStore(SessionStore, String, String, @Nullable Path)` | `custom-title` エントリを追記 |
| `void tagSessionViaStore(SessionStore, String, @Nullable String, @Nullable Path)` | `tag` エントリを追記。`null` は消去 |
| `void deleteSessionViaStore(SessionStore, String, @Nullable Path)` | 削除（`delete` 未実装なら no-op） |
| `ForkSessionResult forkSessionViaStore(SessionStore, String, @Nullable Path, @Nullable String, @Nullable String)` | UUID を振り直すフォーク |
| `void importSessionToStore(String, SessionStore, @Nullable Path)` | ローカル→ストアの再生（既定オプション） |
| `void importSessionToStore(String, SessionStore, @Nullable Path, boolean, int)` | `includeSubagents` と `batchSize` を明示した再生 |

### `ClaudeAgentOptions` のビルダーメソッド

| メソッド | 既定 | 説明 |
|---|---|---|
| `Builder sessionStore(@Nullable SessionStore)` | `null` | トランスクリプトをこのストアへミラーする |
| `Builder loadTimeoutMs(long)` | `60_000` | レジュームのマテリアライズ中の呼び出しごとのタイムアウト |

### `SessionStoreExecutor`

`in.vidyalai.claude.sdk.types.session.SessionStoreExecutor`

| メソッド | 説明 |
|---|---|
| `Executor getDefault()` | 現在の既定エグゼキュータ |
| `void setDefault(Executor)` | 上書き。`null` で組み込みに戻る |
| `void reset()` | 組み込みの `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("session-store-", 0).factory())` に戻す |

### `SessionStoreConformance`

`in.vidyalai.claude.sdk.testing.SessionStoreConformance`

| メソッド | 説明 |
|---|---|
| `void run(Supplier<SessionStore>)` | 14 の契約をすべて実行 |
| `void run(Supplier<SessionStore>, Set<OptionalMethod>)` | 挙げた省略可能メソッドを飛ばす |

`OptionalMethod` 列挙型：`LIST_SESSIONS`、`LIST_SESSION_SUMMARIES`、`DELETE`、`LIST_SUBKEYS`。

## 関連項目

- [セッション履歴](./feature-session-history.md) —— ローカルディスクの等価物（`listSessions`、`getSessionMessages` など）
- [メッセージ型](./feature-message-types.md) —— `MirrorErrorMessage` の統合
- [ClaudeAgentOptions](./api-claude-agent-options.md) —— `sessionStore` と `loadTimeoutMs`
- [ClaudeSDK](./api-claude-sdk.md) —— 公開 API の入口
- `examples/` モジュールの `SessionStoreExample.java`
