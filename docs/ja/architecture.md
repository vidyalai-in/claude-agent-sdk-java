# アーキテクチャ概要

このドキュメントでは、Claude Agent SDK for Java のアーキテクチャ、設計パターン、内部構造を包括的に
説明します。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../architecture.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックと構成図は英語の原文と完全に同一で、翻訳していません。

## 目次
- [高レベルのアーキテクチャ](#高レベルのアーキテクチャ)
- [コアコンポーネント](#コアコンポーネント)
- [設計パターン](#設計パターン)
- [データフロー](#データフロー)
- [並行処理モデル](#並行処理モデル)
- [型システム](#型システム)
- [依存関係](#依存関係)

## 高レベルのアーキテクチャ

SDK は、関心事をはっきり分けた階層アーキテクチャを採っています：

```
┌─────────────────────────────────────────────────────────┐
│           Public API Layer                              │
│  ┌──────────────────┐    ┌────────────────────────┐   │
│  │   ClaudeSDK      │    │  ClaudeSDKClient       │   │
│  │   (Facade)       │    │  (Interactive Client)  │   │
│  └──────────────────┘    └────────────────────────┘   │
│            │                        │                   │
│            └────────────────────────┘                   │
│                     │                                   │
├─────────────────────┼───────────────────────────────────┤
│                     ▼                                   │
│           Configuration Layer                           │
│  ┌─────────────────────────────────────────────────┐  │
│  │       ClaudeAgentOptions (Builder)              │  │
│  └─────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────┤
│           Protocol & Control Layer                      │
│  ┌──────────────────┐    ┌────────────────────────┐   │
│  │   QueryHandler   │◄───┤   MessageParser        │   │
│  │  (Control Proto) │    │   (JSON Parsing)       │   │
│  └──────────────────┘    └────────────────────────┘   │
│            │                                            │
├─────────────┼────────────────────────────────────────────┤
│            ▼                                            │
│           Transport Layer                               │
│  ┌─────────────────────────────────────────────────┐  │
│  │  Transport Interface                            │  │
│  │  └─ SubprocessCLITransport (default impl)      │  │
│  └─────────────────────────────────────────────────┘  │
│            │                                            │
├─────────────┼────────────────────────────────────────────┤
│            ▼                                            │
│           External Process                              │
│  ┌─────────────────────────────────────────────────┐  │
│  │         Claude Code CLI Process                 │  │
│  │         (stdin/stdout communication)            │  │
│  └─────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

## コアコンポーネント

### 1. 公開 API 層

#### ClaudeSDK（ファサード）
- **目的**：シンプルでステートレスなクエリのための静的ファサード
- **ユースケース**：単発の質問、バッチ処理、投げっぱなしの操作
- **主なメソッド**：
  - `query(String prompt)` —— 既定値でのシンプルなクエリ
  - `query(String prompt, ClaudeAgentOptions options)` —— 独自オプション付きのクエリ
  - `query(Iterator<Map> stream, ClaudeAgentOptions options)` —— ストリーミングのクエリ
  - `queryForText()` / `queryForResult()` —— 便利メソッド
  - `createClient()` —— ClaudeSDKClient のファクトリメソッド
  - `createSdkMcpServer()` —— MCP サーバーのファクトリ

**設計パターン**：Facade + Factory

#### ClaudeSDKClient
- **目的**：マルチターンの会話のための対話的でステートフルなクライアント
- **ユースケース**：チャット UI、REPL のようなやり取り、長時間のセッション
- **主なメソッド**：
  - `connect()` —— 接続を確立
  - `sendMessage()` / `query()` —— メッセージを送信
  - `receiveMessages()` / `receiveResponse()` —— メッセージを受信
  - 制御メソッド：`interrupt()`、`setModel()`、`setPermissionMode()` など
- **スレッド安全性**：文書化された保証のもとで部分的にスレッドセーフ
- **リソース管理**：適切な後片づけのために AutoCloseable を実装

**設計パターン**：Builder + リソース管理（try-with-resources）

### 2. 設定層

#### ClaudeAgentOptions
- **目的**：ビルダーパターンを用いた不変の設定オブジェクト
- **特徴**：
  - 30 以上の設定オプション
  - 列挙型と sealed インターフェースによる型安全な API
  - 変更用の `toBuilder()` を備えた流暢なビルダー
- **主な設定領域**：
  - ツール：`tools()`、`allowedTools()`、`disallowedTools()`
  - 権限：`permissionMode()`、`canUseTool()`
  - セッション：`continueConversation()`、`resume()`、`forkSession()`、`sessionStore()`、`loadTimeoutMs()`
  - 上限：`maxTurns()`、`maxBudgetUsd()`、`maxThinkingTokens()`
  - モデル：`model()`、`fallbackModel()`、`betas()`
  - 環境：`cwd()`、`env()`、`cliPath()`
  - フック：`hooks()`
  - MCP：`mcpServers()`
  - エージェント：`agents()`（stdin の initialize リクエストで送信、サイズ上限なし）
  - システムプロンプト：`systemPrompt()` —— 文字列、`SystemPromptPreset`、`SystemPromptCustom`、または `SystemPromptFile`
  - 高度な設定：`sandbox()`、`outputFormat()`、`enableFileCheckpointing()`、`forwardSubagentText()`、`verbatimPrompts()`

**設計パターン**：Builder + 不変オブジェクト

### 3. プロトコルと制御の層

#### QueryHandler
- **目的**：Transport の上で双方向の制御プロトコルを管理する
- **担当**：
  - 制御リクエスト／レスポンスのルーティング
  - フックのコールバック
  - ツール権限のコールバック
  - メッセージのストリーミング
  - 初期化のハンドシェイク（フック、エージェント定義、`excludeDynamicSections`、`systemPromptSnapshot`、skills の許可リスト、`forwardSubagentText` を含む）
  - **プロンプトへのスタンプ**：`verbatimPrompts` が有効な場合、`streamInput()` が書き込むすべてのユーザー
    メッセージに `client_composed: true` を付けます（`stampUserMessage`。元を変更せずコピーします）。
    `ClaudeSDKClient` も自身の書き込みに同じようにスタンプを付けます
  - **実行終了の追跡**：CLI の `session_state_changed` フレーム、進行中タスクの台帳、ターン間の上限時間から
    stdin を閉じてよいタイミングを判断し、SDK が要求した `sdk_host_only` の状態フレームを破棄します
    （[stdin のライフサイクル](#stdin-のライフサイクルと実行の終了)を参照）
  - MCP サーバーのライフサイクル管理
  - **実用的なエラーへの置き換え**：ストリームを読みながら直近のエラー結果のペイロードを追跡します。
    `is_error=true` の result の後に `ProcessException` が続いた場合、それは汎用の
    `"Command failed with exit code N"` ではなく、そのペイロードと
    `"Claude Code returned an error result: <text>"` というメッセージ（result の `errors` 配列、
    次に `result` テキスト、次に `success` でない `subtype`、最後に API エラーステータスから構築）を
    持つ `ResultException` に置き換えられます。result でも `session_state_changed` でもない通信が
    あればリセットされます。この例外オブジェクトは合成された `{"type":"error"}` フレームに載るため、
    利用側のイテレータは型とペイロードをそのままに再送出します。
- **スレッド安全性**：アトミック操作と同期により完全にスレッドセーフ
- **主な機能**：
  - CompletableFuture を用いた非同期の制御プロトコル
  - リクエスト ID の生成と追跡
  - サイズを設定できるメッセージキュー
  - バックグラウンドの読み取りスレッド（Java 21+ では仮想スレッド）
  - 非同期コールバックのための制御エグゼキュータ

**設計パターン**：非同期リクエスト／レスポンス + Observer（フック用）

#### MessageParser
- **目的**：CLI からの JSON メッセージを解析し、型付きの Message オブジェクトへ変換する
- **特徴**：
  - Jackson ベースの JSON 解析
  - すべてのメッセージ型に対応（user、assistant、system、result、stream_event）
  - コンテンツブロックの解析（text、thinking、tool_use、tool_result）
  - エラー処理と検証

**設計パターン**：Parser + Factory

### 4. トランスポート層

#### Transport インターフェース
- **目的**：Claude Code との通信のための抽象的な I/O 層
- **既定の実装**：SubprocessCLITransport
- **独自実装**：リモートの Claude Code への接続を可能にする
- **主なメソッド**：
  - `connect()` —— 接続を確立
  - `write(String data)` —— データを送信
  - `readMessages()` —— メッセージをイテレータとして受信
  - `endInput()` —— 入力ストリームを閉じる
  - `isReady()` —— 接続状態を確認
  - `close()` —— リソースを片づける

**設計パターン**：Strategy + Template Method

#### SubprocessCLITransport
- **目的**：Claude Code CLI をサブプロセスとして使う既定のトランスポート
- **特徴**：
  - CLI サブプロセスのライフサイクル管理
  - stdin/stdout の通信
  - 上限を設定できるバッファ付き読み取り
  - 行単位で例外を隔離する stderr コールバックのサポート（例外を投げるユーザーコールバックが読み取り
    ループを殺すことはもうありません）
  - プロセスの自動的な後片づけ
  - **JVM のシャットダウンフック**：静的な `ConcurrentHashMap.newKeySet()` が生成された各 `Process` を
    追跡し、クラス初期化時に登録された `Runtime.addShutdownHook` が生きている子プロセスそれぞれに
    `destroy()` を呼びます。これにより、親 JVM が `close()` より先に終了しても、はぐれた `claude`
    サブプロセスが漏れ出しません。Python SDK の `atexit` ハンドラに対応します。
- **実装の詳細**：
  - サブプロセス管理に ProcessBuilder を使用
  - stdout の読み取りに専用スレッド（Java 21+ では仮想スレッド）
  - BufferedReader による行単位の解析
  - JSON のシリアライズ／デシリアライズに Jackson

**設計パターン**：サブプロセス管理 + バッファ付き I/O

### 5. MCP（Model Context Protocol）のサポート

#### SdkMcpServer
- **目的**：独自ツールのためのインプロセス MCP サーバー
- **外部サーバーに対する利点**：
  - IPC のオーバーヘッドがない（同一プロセス）
  - デプロイが簡単
  - デバッグが容易
  - アプリケーションの状態へ直接アクセスできる
- **特徴**：
  - ツールの登録と実行
  - @Tool 注釈からのスキーマ自動生成
  - CompletableFuture ベースの非同期実行
  - サーバー情報と、`2025-06-18` / `2024-11-05` の間での `initialize` バージョンネゴシエーション
  - MCP プロトコルのメッセージ（`initialize`、`ping`、`tools/list`、`tools/call`）
  - 各ツールの `inputSchema` に対する引数の検証（サーバー構築時に一度だけコンパイル）
  - キャンセル：`notifications/cancelled` が保留中の呼び出しを決着させ、ハンドラに知らせます
- **メッセージの分類**：`id` を伴う `method` はリクエストであり応答されます。`id` のない `method` は
  通知であり、JSON-RPC が求めるとおり*決して*応答されません —— 代わりに外側の制御リクエストが確認
  されます。`method` を持たないメッセージはレスポンスかゴミであり、無視されます。このサーバーは CLI に
  リクエストを送らないので、その形で届くものは何も照合すべきものではありません。
- **失敗の分類**：`tools/call` が遭遇しうるものはすべて*ツール実行エラー* —— `isError: true` を持つ
  結果 —— です。未知のツール、スキーマに合わない引数、例外を投げたハンドラも含みます。JSON-RPC の
  エラーは、*モデル*が引き起こすこともなく目にすることもないものに限られます：未実装のメソッド
  （`-32601`）、不正な `params`（`-32602`）、CLI がキャンセルした呼び出し（`-32800`）です。`isError`
  の結果はモデルが読んで直せるツール出力として届きますが、JSON-RPC のエラーは、そのリクエストがそもそも
  処理できなかったことを意味します。
- **フェイルクローズの検証**：各 `inputSchema` は構築時に、それ自身の方言のメタスキーマに照らして
  チェックされ、通らなかったツールは記録され呼び出し不能になります。そうしないと、検証器は不正な
  スキーマを受け入れ、それに基づいて誤った検証を行ってしまいます —— `{"type": "bogus"}` は何にも
  一致せず、`"properties": "a string"` は無視されます —— その結果、誰も検査していない引数でハンドラが
  走るか、あるいはすべての呼び出しが見当違いの理由で失敗します。

#### McpMessageHandler
- **目的**：`McpSdkServerConfig` が実際に保持している継ぎ目であり、アプリケーションが `SdkMcpServer`
  を使わずに自分で MCP を提供できるようにします —— リソース、プロンプト、補完、あるいはサードパーティの
  MCP ライブラリへのアダプタなど。
- **契約**：`handleMessage` はリクエストに対して JSON-RPC のレスポンスを返し、返信を期待しないものには
  `null` を返します。`close()` は「あなたを使っていた接続がなくなる」という意味であって「停止しろ」では
  ありません。1 つのハンドラが複数のクライアントに仕えうるので、冪等で、使い続けられる必要があります。

#### ToolCallContext
- **目的**：実行中のツールが、自分の呼び出しがキャンセルされたことを知れるようにします。
- **なぜ必要か**：`CompletableFuture.cancel(true)` は実行中のタスクを割り込みません —— future を完了
  させるだけで、作業は続きます。明示的なシグナルがなければ、キャンセルは*待ち*を止めても*作業*は
  止めず、副作用のあるツールは CLI が諦めた後もそれを適用し続けてしまいます。

#### SdkMcpTool
- **目的**：ツールの定義と実行のラッパー
- **作成方法**：
  - `SdkMcpTool.create()` —— プログラムによる作成
  - `@Tool` 注釈 —— 宣言的な作成
- **特徴**：
  - 入力のジェネリック型パラメータ
  - CompletableFuture ベースの非同期実行
  - 入力検証のための JSON Schema
  - パラメータの自動抽出

**設計パターン**：Command + Factory + 注釈処理

### 6. SessionStore サブシステム

#### SessionStore（アダプタのプロトコル）
- **目的**：セッションのトランスクリプトを外部ストレージ（S3、Postgres、Redis、独自バックエンド）へ
  ミラーし、セッションをローカルディスクを超えて永続化し、ホストをまたいで再開できるようにします。
- **必須メソッド**：`append(SessionKey, List<SessionStoreEntry>)`、`load(SessionKey)`。
- **省略可能なメソッド**（`implements*()` の機能判定つき）：`listSessions`、
  `listSessionSummaries`、`delete`、`listSubkeys`。
- **同期 + 非同期 API**：すべてのメソッドに `*Async`（`CompletableFuture`）版があります。ネイティブに
  ノンブロッキングなクライアントを持つアダプタ（AWS SDK v2 async、R2DBC、Lettuce reactive）は
  `*Async` を直接オーバーライドしてスレッドの跳躍を避けられます。既定のエグゼキュータは
  `SessionStoreExecutor` で設定します（タスクごとに 1 スレッド。Java 21+ では仮想、それ以外では
  デーモンのプラットフォームスレッド）。

**設計パターン**：Adapter + 機能ネゴシエーション + 二重 API（同期／非同期）

#### TranscriptMirrorBatcher（内部）
- **目的**：CLI が stdout に出す `transcript_mirror` フレームをバッファし、
  `store.appendAsync(...)` へフラッシュします。
- **主な挙動**：
  - 即時フラッシュのしきい値：`MAX_PENDING_ENTRIES=500`、`MAX_PENDING_BYTES=1 MiB`。
  - 各 `result` メッセージの前と、ストリーム終端／クローズ時の明示的なフラッシュ。
  - `filePath` ごとにフレームをまとめ、フラッシュごとに一意なファイルにつき `append` 呼び出しを 1 回に
    します。
  - 上限つきの再試行：`MIRROR_APPEND_MAX_ATTEMPTS=3` 回、`[200ms, 800ms]` のバックオフ。タイムアウトは
    再試行しません（進行中の呼び出しが後から着地しうるため）。
  - パスが設定された `projectsDir` の外にあるフレームは警告付きで破棄されます。
  - 失敗は利用側のストリームに `MirrorErrorMessage` として現れます —— 会話を止めることはありません。

**設計パターン**：生産者-消費者バッファ + 指数バックオフ付きリトライ

#### SessionResume（内部）
- **目的**：保存されたセッションを一時的な `CLAUDE_CONFIG_DIR` にマテリアライズし、CLI サブプロセスが
  ローカルディスクから再開できるようにします。
- **流れ**：
  1. `store.loadAsync()` でエントリを読み込みます（`continueConversation` の場合は、サイドチェーンで
     ない最も新しいセッションを選びます）。
  2. `~/.claude/` のようにレイアウトした一時ディレクトリへ JSONL を書き出します。
  3. `.credentials.json`（一時ディレクトリからのトークン消費を防ぐため `refreshToken` は削除）と
     `.claude.json` をコピーします。
  4. ストアが `listSubkeys` を実装している場合、サブエージェントのトランスクリプトと `.meta.json`
     サイドカーをマテリアライズします。
  5. `CLAUDE_CONFIG_DIR=<temp dir>` で CLI を起動します。
  6. 切断時に片づけます。Windows のウイルス対策／インデクサによる一時的なロックにはリトライします。

**設計パターン**：マテリアライズドビュー + リトライ付きクリーンアップ

#### SessionStoreValidation（内部）
- **目的**：サブプロセス生成前の事前オプション検査。無効な組み合わせを `IllegalArgumentException` で
  拒否します：
  - `continueConversation + sessionStore` には `store.implementsListSessions()` が必要です。
  - `sessionStore + enableFileCheckpointing` は拒否されます（チェックポイントはローカル専用）。

**設計パターン**：フェイルファストな検証

#### SessionStoreConformance（公開のテスト補助）
- **場所**：`in.vidyalai.claude.sdk.testing.SessionStoreConformance`
- **目的**：`SessionStore` アダプタのための、フレームワークに依存しない 14 契約の振る舞いテストスイート。
  素の `AssertionError` を使うので、どのテストフレームワークでも動きます（JUnit、TestNG、Spock、素の
  `main`）。

**設計パターン**：契約テスト

## 設計パターン

### 1. sealed インターフェース（パターンマッチング）
型安全なメッセージ処理に広く使われています：

```java
sealed interface Message permits UserMessage, AssistantMessage,
    SystemMessage, TaskStartedMessage, TaskProgressMessage,
    TaskNotificationMessage, TaskUpdatedMessage, MirrorErrorMessage,
    HookEventMessage, ResultMessage, StreamEvent, RateLimitEvent {}

// Usage with pattern matching
switch (message) {
    case UserMessage u -> handleUser(u);
    case AssistantMessage a -> handleAssistant(a);
    case ResultMessage r -> handleResult(r);
    case MirrorErrorMessage m -> handleMirrorError(m);
    case HookEventMessage h -> handleHookEvent(h);
    case SystemMessage s -> handleSystem(s);
    case StreamEvent e -> handleStreamEvent(e);
    // ... task and rate-limit cases
}
```

**利点**：
- コンパイル時の網羅的なパターンマッチング
- default ケースが不要
- 型安全が保証される
- 型階層が明快

### 2. ビルダーパターン
設定オブジェクトに使われます：

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .maxTurns(10)
    .permissionMode(PermissionMode.ACCEPT_EDITS)
    .build();

// Modify existing options
ClaudeAgentOptions modified = options.toBuilder()
    .maxTurns(20)
    .build();
```

**利点**：
- 読みやすい設定
- 省略可能なパラメータ
- 不変オブジェクト
- 連鎖できる API

### 3. ファサードパターン
ClaudeSDK が簡潔なインターフェースを提供します：

```java
// Simple facade
List<Message> messages = ClaudeSDK.query("Hello");

// Hides complexity of:
// - Transport creation
// - QueryHandler setup
// - Message parsing
// - Resource cleanup
```

**利点**：
- よくあるケースの API が簡単
- 内部の複雑さを隠す
- 入口が 1 つ

### 4. 仮想スレッド（並行処理）
ランタイムが提供する場合に、Project Loom を活かして軽量な並行処理を行います。

SDK は Java 17 を対象にコンパイルされ、そこには `Thread.ofVirtual()` が存在しないため、すべての
スレッドとエグゼキュータは `internal.Threads` を通じて作られます。これは Java 21 のエントリポイントを
一度だけリフレクションで解決して `static final` のメソッドハンドルに収め、それらがない場合は名前付きの
デーモン・プラットフォームスレッドにフォールバックします：

```java
// Background reader thread
Thread reader = Threads.start("ClaudeSDK-Reader-", () -> readLoop());

// Executor for control protocol
ExecutorService executor = Threads.newSingleThreadExecutor("ClaudeSDK-Reader-");
```

スレッド名はどちらの経路でも同一なので、ランタイムにかかわらずスレッドダンプは同じように読めます。
`-Dclaude.sdk.virtualThreads=false` を設定すれば、どの JDK でもプラットフォーム経路を強制できます。

**Java 21+ での利点**：
- 軽量なスレッド（数千でも可能）
- スレッドプールを枯渇させずにブロッキング I/O
- 非同期コードが単純になる
- リソースの利用効率が良い

**Java 17-20 では**：同じコードがデーモンのプラットフォームスレッド上で動きます。エグゼキュータは固定
プールではなく*無制限*のままです —— `QueryHandler` の制御エグゼキュータは SDK MCP ツール呼び出しの間
スレッドを占有し、それを終わらせるキャンセルは別のタスクとして届くため、有界のプールではデッドロック
します。代償は、進行中の制御リクエストごとに仮想スレッドではなく OS スレッドを 1 本使うことです。

### 5. CompletableFuture（非同期操作）
非同期コールバックと制御プロトコルに使われます：

```java
// Permission callback
CompletableFuture<PermissionResult> future =
    canUseTool.apply(toolName, input, context);

// Control protocol request/response
CompletableFuture<ControlResponse> response =
    sendControlRequest(request);
```

**利点**：
- ノンブロッキングな操作
- 組み合わせ可能な非同期チェーン
- エラー処理
- タイムアウトのサポート

## データフロー

### クエリ実行の流れ

```
User Code
    │
    ├─► ClaudeSDK.query(prompt, options)
    │       │
    │       ├─► Validate options
    │       ├─► Create Transport
    │       ├─► Create QueryHandler
    │       │       │
    │       │       ├─► Start reader thread
    │       │       └─► Process messages
    │       │               │
    │       │               ├─► Parse JSON
    │       │               ├─► Handle control protocol
    │       │               ├─► Invoke hooks
    │       │               ├─► Check permissions
    │       │               └─► Add to message queue
    │       │
    │       └─► Collect messages
    │               │
    └─────────────► Return List<Message>
```

### 対話的クライアントの流れ

```
User Code
    │
    ├─► ClaudeSDKClient.connect()
    │       │
    │       ├─► Create Transport
    │       ├─► Create QueryHandler
    │       ├─► Start reader thread
    │       └─► Initialize (control protocol handshake)
    │
    ├─► client.sendMessage("Hello")
    │       │
    │       └─► Write to transport stdin
    │
    ├─► client.receiveResponse()
    │       │
    │       └─► Iterator reads from message queue
    │               │
    │               ├─► Blocks until message available
    │               ├─► Returns messages until ResultMessage
    │               └─► Auto-closes iterator
    │
    └─► client.close()
            │
            ├─► Close QueryHandler
            ├─► Close Transport
            └─► Cleanup resources
```

### フック呼び出しの流れ

```
CLI Process
    │
    ├─► Sends hook request via stdout
    │       │
    │       └─► {"type": "control", "method": "sdk.hook_callback", ...}
    │
QueryHandler
    │
    ├─► Receives hook request
    │       │
    │       ├─► Parse hook event and input
    │       ├─► Match against registered hooks
    │       ├─► Invoke matching hooks in parallel
    │       │       │
    │       │       └─► CompletableFuture.allOf(...)
    │       │
    │       └─► Collect results
    │               │
    │               └─► Combine outputs (logs, messages, updates)
    │
    └─► Send hook response via stdin
            │
            └─► {"id": "...", "result": {...}}
```

### 制御リクエストの失敗処理

入ってくる `control_request` はそれぞれ自分のスレッドで処理され、`ExecutorService.submit(...)` で
投入されます —— その `Future` を読む者はいません。そのため、ハンドラから抜け出した `Throwable` は
かつて痕跡も残さず消えており、CLI は対応する `control_response` を受け取るまでブロックするので、実行は
どちら側にも診断のないまま固まっていました。

そこで `handleControlRequest` は `Exception` ではなく `Throwable` を捕捉します。どんな失敗も記録され
（通常の例外は `WARNING`、`Error` は `SEVERE`）、エラーの制御レスポンスで応答されるので CLI が待たされ
続けることはありません。本物の `Error` はそのあと握りつぶさずに再送出されます。復旧可能な `request_id`
を持たずに届いたリクエストは、応答すべき相手がないので記録するしかありません。

これは机上の話ではありません。「unresolved compilation problem」という `Error` を抱えた、IDE が
コンパイルした古いクラスのせいで、この捕捉範囲を広げるまで、すべての SDK MCP 制御リクエストが黙って
固まっていました。

### stdin のライフサイクルと実行の終了

> **`controlExecutor` はタスクごとにスレッドでなければなりません。** SDK MCP のツール呼び出しは、
> ツールが答えるまで制御スレッドを占有し、それを終わらせる `notifications/cancelled` は*別の*制御
> リクエストとして届きます。どんな有界プールでも、そのキャンセルはまさに自分がキャンセルすべき呼び出しの
> 後ろに並び、デッドロックします。テストでは捕まえられません —— 大きさ 2 の固定プールはすべて通過します。

フック、SDK MCP サーバー、`canUseTool` の権限コールバックのいずれかが登録されているとき、制御プロトコルは
CLI がコールバックしてくる可能性がある間ずっと stdin を開いておく必要があります。そのため
`QueryHandler.streamInput()` は、**実行の終了**を待ってから `transport.endInput()` を呼びます。3 つとも
同じように扱われます —— CLI が `control_request` を書き、SDK が対応する `control_response` を stdin に
書くまでブロックする —— ので、3 つとも双方向の必要（`hasBidirectionalNeeds()`）として数えられます。
どれも無い場合、stdin はプロンプトを書き終えた時点ですぐに閉じられます。早く閉じるのは無害ではなく、
クローズを単に `close()` まで先送りすることもできません。stream-json モードの CLI は stdin の EOF で
**のみ**終了するため、それでは単発の `query()` が永遠に固まります。

微妙なのは、**`result` フレームが終わらせるのは 1 ターンであって、実行全体ではない**という点です。
バックグラウンドのサブエージェントはそれを越えて動き続け、終わるとその完了が親を起こし、追加のターンが
走ります。そのターンのフック、権限、SDK MCP のリクエストにも stdin が必要です。早く閉じると、それらの
リクエストは `"Stream closed"` で失敗し、さらに —— もっと静かに —— `PreToolUse` フックが飛ばされるので、
組み込みツールはコールバックなしで実行され、拒否ゲートのフックはゲートとして働かなくなりました。

`QueryHandler` は、実行が終わったかどうかを 2 つの情報源から判断します。

**1. CLI のセッション状態（主）。** トランスポートは CLI プロセスに
`CLAUDE_CODE_SDK_READS_SESSION_STATE=1` を設定します（呼び出し側の `options.env` または継承した環境が、
大文字小文字を問わずすでにその名前を持つ場合を除く）。これに対応した CLI は、`sdk_host_only: true` の
印が付いた `system` / `session_state_changed` フレームを送ります。バックグラウンドエージェントが生きている
間、あるいはその完了のためのターンがまだ残っている間は `running`、ホストを待っている間は
`requires_action` のままで、それ以上ターンが残っていなくなると `idle` を報告します。リーダーは最新の状態を
追跡し、利用側に届く前に **`sdk_host_only` の印が付いたフレームを破棄します**。印の無いフレーム ——
呼び出し側が `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS` でオプトインしたために送られるもの —— も同じように
実行終了を決め、そのまま通過します。

**2. タスク台帳（フォールバック、かつガード）。** `QueryHandler` は、`system` のタスクライフサイクル
フレームから作られる進行中タスクの台帳を持ちます：

```
system: task_started (task_type ∈ DEFERRING_TASK_TYPES)  ─►  add task_id
system: task_notification                                ─►  remove task_id
system: task_updated (patch.status ∈ TERMINAL_TASK_STATUSES) ─► remove task_id
```

実行終了の規則は、すべて 1 つの `runLock` の下で扱われます：

```
result frame
    ├─ state is null (CLI sends none) or "idle", or no bidirectional needs
    │      └─ ledger empty      ─►  end the run  ─►  endInput()
    │      └─ ledger non-empty  ─►  keep stdin open (log at FINE)
    ├─ state is "requires_action" ─►  wait (a request is being answered)
    └─ state is "running"         ─►  wait, and arm the run-end ceiling

session_state_changed
    ├─ "idle" after a result  ─►  end the run, unless the ledger is non-empty
    ├─ "idle" before a result ─►  nothing (the prompt's run has not produced a result yet)
    ├─ "requires_action"      ─►  reopen the run if it had ended; stop the ceiling
    └─ "running" (or other)   ─►  reopen the run if it had ended; restart the ceiling if past a result
```

ホストによっては result の*直前*に `idle` を送るものがあり、その場合は result が実行を終わらせます。
状態をまったく送らない CLI（古い CLI、および `CLAUDE_CODE_SDK_READS_SESSION_STATE` にまだ対応していない
Claude Code 2.1.283）では状態が `null` のままなので、台帳が空の最初の result で実行が終わります ——
0.2.3 より前の挙動です。

**実行終了の上限時間。** CLI 自身のバックグラウンド待機の上限時間は stdin が閉じられてから数え始めるので、
独自の上限がなければ、終わらない作業が `running` を —— そして stdin を —— 永遠に開いたままにしてしまいます。
CLI がまだ `running` を報告している状態で result が届くと、`QueryHandler` は `Threads` のスレッド上で
スリーパーを開始し、それが目覚めるまでに新しいターンが始まらなければ実行を終わらせます。その長さは
`CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS` で、CLI から見えるのと同じ順序 —— まず `options.env`、次に
プロセスの環境 —— で読み取られます。`0` は無制限を意味し、非負の整数でない値は CLI の既定値である
600000 ms（10 分）にフォールバックし、`Integer.MAX_VALUE` ms（約 24.8 日）を超える値はそこに切り詰め
られます。上限時間はターンの**間**の待ち時間だけを数えます：

- メインスレッドの `assistant` または `stream_event` フレーム（`parent_tool_use_id == null`）は、ターンが
  進行中であることを示します。上限時間は止まり、上限時間がすでに実行を終わらせていた場合でも実行が再開
  されます。サブエージェント自身のメッセージでは止まり**ません** —— それこそが上限時間で区切る対象の作業です。
- result で再始動します。result の後に届いた `running` でも同様です。
- `requires_action` は、その後に続く `running` まで上限時間を止めます。
- 発火した時点でまだ進行中の追跡対象エージェントは打ち切られません。台帳が空になった時点で上限時間が
  最初から数え直されます。

各スリーパーは世代番号を持ち、上限時間をクリアまたは再設定すると世代番号が進んでスリーパーに割り込みが
かかるので、遅れて目覚めたスリーパーは何もしません。

**再開。** 実行を終わらせると `CompletableFuture` が完了します。その後で CLI が取りかかる作業（完了した
バックグラウンドタスクが CLI を起こす場合など）は新しい future に差し替えるので、まだ待ち始めていない
待機者 —— プロンプトを書き込み中の `streamInput` —— も、その新しい作業を待ちます。`streamInput` が書く
各プロンプトは、それぞれ自分の実行を持ちます。書き込む前に実行を再開し、「result を受信済み」をリセット
するので、複数メッセージのプロンプトは最初のプロンプトではなく*最後の*プロンプトの実行を待ちます。
stdin が閉じられるか、リーダーが終了すると、その実行は確定して終了したままになり、CLI が終了処理をして
いる間に届くフレームに対しては上限時間は設定されません。`close()` とリーダーの `finally` ブロックの
どちらも実行を終わらせるので、待機者が固まることはありません。

この待機には他のタイムアウトはありません。以前の Java 版は `CLAUDE_CODE_STREAM_CLOSE_TIMEOUT`（60 秒）
でも待機を打ち切っていたため、1 分を超えて動くバックグラウンドエージェントはすべて途中で切られていました。
その上限は取り除かれ、`CLAUDE_CODE_STREAM_CLOSE_TIMEOUT` は現在 `initialize` のタイムアウトを設定する
だけです。

`DEFERRING_TASK_TYPES` は `{"local_agent", "local_workflow"}` です。除外は見落としではなく意図的です。
バックグラウンドシェル（`local_bash`）とモニターは設計上いつまでも動き、teammate は生涯にわたって
`running` のままなので、どれも終端状態に確実には到達しません。そのどれかを追跡すれば、クローズは短く
遅れるのではなく*永遠に*保留され —— しかもプロセスが終了しないので、リーダーの `finally` すら走りません。
この集合に加えるものは、確実に終了する型でなければなりません。

`background_tasks_changed` フレームは双方向で無視されます。そのペイロードは生きている*バックグラウンド*の
集合ですが、サブエージェントはフォアグラウンドで登録され、後から 2 度目の `task_started` なしで
バックグラウンドへ移るだけなので、それを使って絞り込むと、まさにこの台帳が守ろうとしているエージェントを
落としてしまいますし、それを使って広げると、後続のどのフレームも消さない id を招き入れかねません。

既知の制限：セッション状態を報告しない CLI では、複数メッセージのプロンプトイテレータのうち前の
プロンプトに対する result が、後のプロンプトがすでに CLI 側のキューに入っていても実行を終わらせてしまう
ため、その後のターンからの制御リクエストが閉じた stdin に当たることがあります。単一メッセージや文字列の
プロンプトといった、よくある単発の形はすべてカバーされています。

## 並行処理モデル

### スレッド構成

SDK はマルチスレッドの構成を採ります（Java 21+ では仮想スレッド、17-20 ではデーモンのプラットフォーム
スレッド）：

1. **メインスレッド**：ユーザーアプリケーションのスレッド
2. **リーダースレッド**：CLI の stdout を読む
3. **制御エグゼキュータ**：非同期の制御プロトコル操作のためのスレッドプール
4. **ストリーミングエグゼキュータ**：入力メッセージをストリーミングする省略可能なスレッド
5. **フックのエグゼキュータ**：進行中のフックやツール呼び出しごとに 1 スレッド

### スレッド安全性

- **AtomicBoolean**：接続状態とクローズ状態に使用
- **volatile**：QueryHandler と Transport の可視性のために使用
- **同期**：競合状態を防ぐため connect() で使用
- **BlockingQueue**：スレッドセーフなメッセージキュー
- **ConcurrentHashMap**：スレッドセーフな制御リクエストの追跡

### リソース管理

すべてのリソースが AutoCloseable を実装します：

```java
try (ClaudeSDKClient client = ClaudeSDK.createClient()) {
    // Use client
} // Automatic cleanup: QueryHandler, Transport, Executors
```

## 型システム

### メッセージ型の階層

```
Message (sealed interface)
    ├── UserMessage (record)
    ├── AssistantMessage (record)
    │       └── content: List<ContentBlock>   (sealed interface)
    │               ├── TextBlock
    │               ├── ThinkingBlock
    │               ├── ToolUseBlock
    │               ├── ToolResultBlock
    │               ├── ServerToolUseBlock
    │               ├── ServerToolResultBlock
    │               ├── ImageBlock        - PDF page render
    │               ├── DocumentBlock     - whole PDF
    │               └── UnknownBlock      - forward-compat fallback
    ├── SystemMessage (record)
    ├── ResultMessage (record)
    │       └── modelUsage: Map<String, ModelUsage>
    └── StreamEvent (record)
```

どちらの sealed 階層も網羅的な `switch` と相性がよく、つまりメンバーの追加は呼び出し側にとって意図的な
ソース互換性の破壊です。`ContentBlock` は 0.1.20 で 3 つのメンバーを得ました。`UnknownBlock` の存在に
よって、*モデル化されていない*型はもはや SDK の変更をまったく必要としません —— パーサはそれらを丸ごと
保持し、型ごとに 1 回記録するだけで、例外を投げません。

### 設定の型

```
ClaudeAgentOptions
    ├── PermissionMode (enum)
    ├── ToolsPreset (record)
    ├── SystemPromptPreset (record)
    ├── SdkBeta (enum)
    ├── SettingSource (enum)
    ├── SandboxSettings (record)
    ├── ThinkingConfig (sealed interface)
    │       ├── ThinkingConfigAdaptive (record)
    │       ├── ThinkingConfigEnabled (record)
    │       └── ThinkingConfigDisabled (record)
    ├── McpServerConfig (sealed interface)
    │       ├── McpStdioServerConfig
    │       ├── McpSseServerConfig
    │       ├── McpHttpServerConfig
    │       └── McpSdkServerConfig
    ├── HookEvent (enum) - 10 events
    ├── HookMatcher (record)
    └── AgentDefinition (record)
```

### 権限の型

```
PermissionResult (sealed interface)
    ├── PermissionResultAllow (record)
    └── PermissionResultDeny (record)
            └── reason: String
```

### フックの型

```
HookInput (sealed interface)
    ├── PreToolUseHookInput
    ├── PostToolUseHookInput
    ├── PostToolUseFailureHookInput
    ├── UserPromptSubmitHookInput
    ├── StopHookInput
    ├── SubagentStopHookInput
    ├── SubagentStartHookInput
    ├── PreCompactHookInput
    ├── NotificationHookInput
    └── PermissionRequestHookInput
```

## 依存関係

### 実行時の依存

1. **Jackson**（2.21.0）
   - `jackson-databind` —— JSON のシリアライズ／デシリアライズ
   - `jackson-annotations` —— JSON の注釈
   - 目的：CLI の JSON メッセージの解析、制御プロトコルのシリアライズ

2. **JSpecify**（1.0.0）
   - null 可能性の注釈（`@Nullable`、`@NonNull`）
   - 目的：より良い null 安全性と IDE のサポート

3. **networknt json-schema-validator**（2.0.4）
   - 目的：MCP 仕様がサーバーに求めるとおり、ハンドラが走る前に SDK MCP ツールの引数をツールが宣言した
     `inputSchema` に照らして検証する
   - 2.x 系に意図的に固定：3.x は Jackson 3（`tools.jackson`）向けにビルドされており、上記の Jackson 2 の
     隣にもう 1 つ完全な JSON スタックを置くことになります。2.0.4 は私たちの databind を再利用する最新の
     リリースです。
   - その YAML スキーマリーダーは除外されています（ツールのスキーマは解析済みの map として届きます）。
     誤って compile スコープで宣言されている Surefire のレポートフォーマッタも同様です
   - `slf4j-api`（2.0.17）を推移的に持ち込みます。SDK は `java.util.logging` を通じてログを出し、SLF4J の
     バインディングは**同梱しません** —— どれを選ぶかはアプリケーションの判断です。プロバイダのない
     アプリケーションは、最初に SDK MCP サーバーを構築したときに標準エラーへ一度だけ
     `No SLF4J providers were found` という通知を見ます。何らかのバインディングを追加すれば消えます。

### テストの依存

1. **JUnit 5**（6.0.2）
   - テストフレームワーク
   - 目的：単体テストと統合テスト

2. **AssertJ**（3.27.7）
   - 流暢なアサーションライブラリ
   - 目的：読みやすいテストのアサーション

3. **Mockito**（5.21.0）
   - モックのフレームワーク
   - 目的：テストで依存をモックする

### ビルドの依存

1. **Maven Compiler Plugin**（3.14.1）
   - `-parameters` フラグ付きの Java 17 コンパイル（`<release>17</release>`）
   - 目的：@Tool 注釈のためにパラメータ名を保持する

2. **Flatten Maven Plugin**（1.7.3）
   - `${revision}` プロパティを解決
   - 目的：CI フレンドリーなバージョニング

3. **Templating Maven Plugin**（3.1.0）
   - テンプレートから SdkVersion.java を生成
   - 目的：ビルド時にバージョンを注入する

## 設計原則

1. **型安全**：Java の型システム（sealed インターフェース、record、列挙型）を活かす
2. **不変性**：設定オブジェクトは不変
3. **スレッド安全性**：スレッド安全性の保証を文書化し、守らせる
4. **リソース管理**：適切な後片づけのための AutoCloseable
5. **ビルダーパターン**：流暢で読みやすい設定
6. **フェイルファスト**：早めに検証し、意味のある例外を投げる
7. **パターンマッチング**：より簡潔なコードのために現代の Java 機能を使う
8. **仮想スレッド**：Java 21+ では透過的に軽量な並行処理を
9. **関心の分離**：層の境界を明確に
10. **拡張性**：プラグインシステムと独自トランスポート

## 性能上の考慮

1. **仮想スレッド**：Java 21+ では数千の並行操作が可能
2. **バッファ付き I/O**：サブプロセス通信のシステムコールを減らす
3. **メッセージキュー**：メモリとスループットのバランスを取れるサイズ設定
4. **遅延初期化**：QueryHandler は必要になってから作られる
5. **リソースの再利用**：ExecutorService を操作間で使い回す
6. **ダイレクトメモリ**：Jackson が効率的なバッファ処理を行う
7. **コピーの最小化**：メッセージオブジェクトは record（防御的コピーなし）

## 将来の拡張性

このアーキテクチャは将来の強化に対応します：

1. **独自トランスポート**：リモートの Claude Code のために Transport インターフェースを実装
2. **メッセージ型の追加**：sealed インターフェースの階層に追加
3. **新しいフックイベント**：HookEvent 列挙型に追加
4. **プラグインシステム**：独自拡張のための SdkPluginConfig
5. **代替プロトコル**：制御プロトコルの実装を差し替え
6. **ストリーミングの改善**：部分メッセージのサポート強化
7. **キャッシュ**：SDK と CLI の間にキャッシュ層を追加
8. **メトリクス**：テレメトリと性能監視の追加
