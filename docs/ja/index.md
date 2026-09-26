# Claude Agent SDK for Java - 技術ドキュメント

[English](../README.md) · [简体中文](../zh/index.md) · **日本語** · [한국어](../ko/index.md) · [Português](../pt/index.md) · [Español](../es/index.md)

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../README.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。詳細は [docs/TRANSLATIONS.md](../TRANSLATIONS.md) を参照してください。

Claude Agent SDK for Java の技術ドキュメントへようこそ。ここでは SDK のアーキテクチャ、機能、
使い方を包括的に説明します。

## 概要

Claude Agent SDK for Java は、Claude の AI 機能を Java アプリケーションに統合するための包括的な
ライブラリです。Claude Code CLI とやり取りするための型安全でモダンな Java API を提供し、単発の
シンプルなクエリから複雑なマルチターン会話までをサポートします。

**主なポイント：**
- 🎯 **型安全な API**：sealed インターフェースと record を使用しているため、Java 21+ の利用側コードで網羅的なパターンマッチングが機能します
- ⚡ **仮想スレッド**：バックグラウンド処理は Java 21+ では Project Loom の仮想スレッド上で、17〜20 ではデーモン・プラットフォームスレッド上で動作します
- 🔧 **柔軟なアーキテクチャ**：ステートレスなクエリとステートフルな会話の両方に対応
- 🛠️ **カスタムツール**：MCP（Model Context Protocol）を使って独自のツールを作成
- 🔌 **プラグイン**：ローカルディレクトリから Claude Code プラグイン（コマンド、エージェント、スキル、フック）を読み込み
- 🎨 **Builder パターン**：設定のための流暢な API

## ドキュメント索引

### アーキテクチャと設計
- **[アーキテクチャ概要](./architecture.md)** —— システムアーキテクチャ、設計パターン、内部構造
  - 高レベルのアーキテクチャ図
  - コアコンポーネント（API 層、設定、プロトコル、トランスポート）
  - 設計パターン（sealed インターフェース、builder、facade、仮想スレッド）
  - データフロー図と並行処理モデル
  - stdin のライフサイクル：実行がいつ終わるか（セッション状態、タスク台帳、ターン間の上限時間）
  - 型システムの階層と依存関係

### コア機能
- **[シンプルなクエリ](./feature-simple-queries.md)** —— ClaudeSDK ファサードによる単発クエリ
  - 基本的な使用例
  - クエリメソッドの概要
  - 設定オプション
  - メッセージ処理のパターン
  - ベストプラクティス

- **[対話的な会話](./feature-interactive-conversations.md)** —— ClaudeSDKClient によるマルチターン会話
  - 接続の管理
  - メッセージの送受信
  - 制御メソッド
  - セッション管理
  - スレッド安全性
  - 完全なサンプル

- **[設定オプション](./feature-configuration-options.md)** —— ClaudeAgentOptions ビルダー完全ガイド
  - 30 以上のすべての設定オプション
  - ツールの設定
  - システムプロンプトの形式と `snapshot`
  - 権限の設定
  - モデルの設定
  - 環境変数、および SDK 自身が設定する環境変数
  - `verbatimPrompts` —— `@path` 展開やスラッシュコマンドなしでプロンプトを届ける
  - フックとコールバック
  - よくあるパターンの完全なサンプル

- **[メッセージ型](./feature-message-types.md)** —— メッセージ型システムの理解
  - UserMessage、AssistantMessage、SystemMessage、ResultMessage、StreamEvent、RateLimitEvent
  - タスクのライフサイクルメッセージ（TaskStartedMessage、TaskProgressMessage、TaskNotificationMessage、TaskUpdatedMessage）
  - HookEventMessage（`includeHookEvents` が有効な場合）
  - ResultMessage 上の DeferredToolUse、`apiErrorStatus` HTTP ステータスフィールド
  - ConversationResetMessage —— セッション途中で会話が置き換えられた場合（例：`/clear`）
  - メッセージの出所 —— 自分のターンとセッションが差し込んだターンの見分け方
  - コンテンツブロック（Text、Thinking、ToolUse、ToolResult）
  - パターンマッチング
  - サンプルとベストプラクティス

- **[MCP サーバー](./feature-mcp-servers.md)** —— Model Context Protocol によるカスタムツールの作成
  - SDK MCP サーバー（インプロセス）
  - 外部 MCP サーバー（stdio/SSE/HTTP）
  - @Tool アノテーションの使い方
  - プログラムによるツールの作成
  - ツールスキーマと、ツールの `inputSchema` に対する引数の検証
  - 失敗時のセマンティクス（ツール呼び出しが遭遇するあらゆる事象に対する `isError` 結果）
  - `ToolCallContext` による実行中ツールのキャンセル
  - プロトコルの詳細（バージョンネゴシエーション、通知、メソッド）
  - `McpMessageHandler` によるカスタム MCP ハンドラ
  - 非同期実行のパターン
  - 完全なサンプル（電卓、データベース、API 連携）

- **[エージェント定義](./feature-agents.md)** —— 専用のプロンプト、ツール、モデルを持つカスタムサブエージェント
  - インラインのエージェント定義
  - ファイルシステムベースのエージェント
  - 大きなエージェントのサポート（stdin 経由で 260KB 超）
  - AgentDefinition API（skills、メモリスコープ、MCP サーバーのフィールドを含む）
  - サブエージェントの出力の観測と `forwardSubagentText`

- **[セッション履歴](./feature-session-history.md)** —— 過去の Claude Code 会話セッションをディスクから読み取り、管理する
  - 全プロジェクト横断、またはディレクトリで絞り込んだセッション一覧
  - ID による単一セッションの参照（`getSessionInfo`）
  - 会話トランスクリプト全体の読み取り
  - サブエージェントのトランスクリプトの読み取り（`listSubagents`、`getSubagentMessages`）。
    それらを生成した Agent の `tool_use` に紐づけられます
  - セッションのリネーム（`renameSession`）
  - 整理のためのセッションのタグ付け（`tagSession`）
  - セッションの削除（`deleteSession`）—— サブエージェントのトランスクリプトディレクトリも連鎖削除
  - UUID 再マッピングを伴うセッションのフォーク（`forkSession`）
  - 切り詰めレジューム（`resumeSessionAt` / `resumeDropsTurn`）—— 安全に過去の地点へ巻き戻す
  - SDKSessionInfo（tag、createdAt、null 許容の fileSize を含む）と SessionMessage 型
  - offset によるページネーションと worktree のサポート

- **[Session Store](./feature-session-store.md)** —— トランスクリプトを S3 / Postgres / Redis / 独自バックエンドへミラーする
  - 同期版と非同期版（`CompletableFuture`）を持つ `SessionStore` アダプタプロトコル
  - `SessionStoreExecutor` による仮想スレッドエグゼキュータの設定
  - 同梱の `InMemorySessionStore` リファレンスアダプタと `filePathToSessionKey` ヘルパー
  - 読み取り API：`listSessionsFromStore`、`getSessionInfoFromStore`、`getSessionMessagesFromStore`、`listSubagentsFromStore`、`getSubagentMessagesFromStore`
  - 変更 API：`renameSessionViaStore`、`tagSessionViaStore`、`deleteSessionViaStore`、`forkSessionViaStore`
  - ローカル→ストアの再生のための `importSessionToStore`、致命的でない追記失敗のための `MirrorErrorMessage`
  - 公開された `SessionStoreConformance` テストハーネス（14 の契約、フレームワーク非依存）
  - ストアからのレジューム（サブプロセスは一時的な `CLAUDE_CONFIG_DIR` を受け取ります）、トランスクリプトのミラーバッチャー

- **[Skills](./feature-skills.md)** —— メインセッション向けのトップレベル `skills` オプション
  - 3 つのモード：`skillsAll()`、`skills(List)`、`skills(List.of())`
  - `allowedTools` への `Skill(name)` の自動注入と `settingSources` の既定値設定
  - initialize 制御リクエストによるワイヤープロトコル上の伝播
  - 冪等な注入、明示的な設定が常に優先
  - Skill 名の検証 —— `--allowedTools` ルールの注入を阻止し、決してマッチし得ない名前を拒否

- **[W3C Trace Context の伝播](./feature-trace-context.md)** —— SDK と CLI をまたぐ分散トレーシング
  - CLI サブプロセスへの `TRACEPARENT`/`TRACESTATE` のベストエフォート注入
  - OpenTelemetry への実行時依存ゼロ（リフレクションベース）
  - 古い環境変数の除去、baggage のみのコンテキスト、プロパゲータのエラー

### 高度な機能
- **[拡張思考の設定](./feature-thinking-config.md)** —— Claude の推論の深さを制御する
  - ThinkingConfig の型（Adaptive、Enabled、Disabled）
  - エフォートレベル（low、medium、high、max）
  - 予算の制御と最適化
  - 完全な使用例

- **[フックシステム](./feature-hooks.md)** —— ライフサイクルイベントの傍受と応答
  - 10 のフックイベント
  - HookMatcher と HookOutput
  - PostToolUse の `updatedToolOutput`（任意のツールの出力を置き換え）と `updatedMCPToolOutput`
  - `PermissionDecision.DEFER` と ResultMessage 上の `DeferredToolUse`
  - `includeHookEvents` と HookEventMessage ストリーム
  - よくあるユースケースのサンプル

- **[権限システム](./feature-permissions.md)** —— カスタム権限コールバックとモード
  - 権限モード
  - カスタム権限コールバック（`"ask"` の判断時のみ発火）
  - シャドーイングと、`settingSources(List.of())` によるコールバックの決定性の維持
  - 拡充された `ToolPermissionContext`（`title`、`displayName`、`description`、`decisionReason`、`blockedPath`）
  - パスベース、時間ベース、ユーザー確認のサンプル

- **[ストリーミングイベント](./feature-streaming-events.md)** —— 部分メッセージのリアルタイム更新
  - ストリーミングの有効化
  - ストリームイベントの処理
  - UI 統合のサンプル

- **[トランスポート層](./feature-transport-layer.md)** —— 独自のトランスポート実装
  - Transport インターフェース
  - デフォルト実装
  - カスタムトランスポートのサンプル
  - Windows のバッチスクリプトの拒否と、npm の `claude.cmd` 配置向けの明示的なオプトイン

- **[プラグインシステム](./feature-plugin-system.md)** —— Claude Code プラグインの読み込み
  - `SdkPluginConfig.local(path)` → `--plugin-dir`
  - プラグインの構成と、プラグインが読み込まれたことの確認

### API リファレンス
- **[ClaudeSDK](./api-claude-sdk.md)** —— シンプルなクエリのための静的ファサード
  - クエリメソッド
  - クライアントのファクトリメソッド
  - MCP サーバーのファクトリメソッド
  - 便利メソッド

- **[ClaudeSDKClient](./api-claude-sdk-client.md)** —— 会話のための対話的クライアント
  - 接続メソッド
  - メッセージの送受信
  - 制御メソッド
  - スレッド安全性に関する注意

- **[ClaudeAgentOptions](./api-claude-agent-options.md)** —— 設定ビルダー
  - すべての設定オプション
  - ビルダーのメソッド

- **[メッセージ型](./api-message-types.md)** —— メッセージ型の完全な階層
  - すべてのメッセージ型とコンテンツブロック
  - フィールドのドキュメント

- **[例外の型](./api-exceptions.md)** —— エラー処理と例外
  - 例外の階層
  - `ResultException` と終端エラー結果のペイロード
  - 各例外が実際にどこで現れるか
  - エラー処理のサンプル

### プロジェクトのリソース
- **[CHANGELOG](../CHANGELOG.md)** —— バージョン履歴とリリースノート（英語のみ）
- **[Python SDK パリティ](../PYTHON_SDK_PARITY.md)** —— Python SDK との機能比較（英語のみ）
- **[翻訳について](../TRANSLATIONS.md)** —— 翻訳の範囲、同期の方針、貢献方法（英語）

## プロジェクト構成

これはマルチモジュールの Maven プロジェクトです：

```
claude-agent-sdk-java/
├── sdk/              # Core SDK library (published to Maven Central; mirrored to GitHub Packages)
│   ├── src/main/java/in/vidyalai/claude/sdk/
│   │   ├── ClaudeSDK.java              # Main facade
│   │   ├── ClaudeSDKClient.java        # Interactive client
│   │   ├── ClaudeAgentOptions.java     # Configuration builder
│   │   ├── exceptions/                 # Exception types
│   │   ├── transport/                  # Transport layer
│   │   ├── internal/                   # Internal implementation
│   │   ├── mcp/                        # MCP server support
│   │   └── types/                      # Type definitions
│   └── src/test/java/                  # SDK tests
└── examples/         # Usage examples (separate module)
    └── src/main/java/examples/
        ├── QuickStart.java
        ├── MultiTurnConversation.java
        ├── McpServer.java
        └── ... (15+ examples)
```

## はじめに

### 前提条件
- Java 17 以降（21 以降では仮想スレッドが自動的に使われます）
- Maven 3.6+
- 別途インストールした Claude Code CLI

### インストール

`pom.xml` に追加します —— リポジトリや認証の設定は不要です：

```xml
<dependency>
    <groupId>in.vidyalai</groupId>
    <artifactId>claude-agent-sdk-java</artifactId>
    <version>0.2.2</version>
</dependency>
```

すでに GitHub Packages を指している利用者のために、リリースはそちらにもミラーされています。その
経路は成果物が公開されているにもかかわらずパーソナルアクセストークンを必要とするため、特別な
理由がない限り Maven Central を優先してください —— リポジトリと認証の設定については
[ルート README](./README.md#代替手段github-packages) を参照してください。

### Hello World

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;

// Simple query
List<Message> messages = ClaudeSDK.query("What is 2 + 2?");
for (Message msg : messages) {
    if (msg instanceof AssistantMessage assistant) {
        System.out.println(assistant.getTextContent());
    }
}
```

### 対話的な会話

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

var options = ClaudeAgentOptions.builder()
    .maxTurns(5)
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();
    client.sendMessage("Hello!");

    for (var msg : client.receiveResponse()) {
        // Process messages
    }

    client.sendMessage("Tell me more");
    for (var msg : client.receiveResponse()) {
        // Process follow-up
    }
}
```

## サンプル

本 SDK には、次のような内容を網羅した 30 以上の実行可能なサンプルが含まれています：
- 基本的なクエリと会話
- カスタム MCP ツール
- 権限コールバック
- フックシステム（バックグラウンドサブエージェントの後続ターンで処理されるフックを含む。`BackgroundAgentHooksExample`）
- ストリーミングイベント
- エラー処理
- システムプロンプト（`snapshot` を含む。`SystemPromptExample`）
- プロンプトをそのまま届ける（`VerbatimPromptsExample`）
- 高度な機能（チェックポイント、サンドボックス、出力フォーマット）
- ほか多数

リポジトリの `examples/` ディレクトリを参照してください。

## このドキュメントの読み方

1. **はじめての方**：インストールとクイックスタートについては、この README から
2. **アーキテクチャの理解**：[アーキテクチャ概要](./architecture.md)を読む
3. **シンプルなユースケース**：[シンプルなクエリ](./feature-simple-queries.md)に従う
4. **カスタムツール**：[MCP サーバー](./feature-mcp-servers.md)について学ぶ
5. **高度な機能**：必要に応じて他の機能ガイドを参照する

## ドキュメントの状況

### ✅ 完了 —— すべてのコアドキュメント
- クイックスタートと概要を含むメイン README
- アーキテクチャ概要（図を含む包括的な内容、SessionStore サブシステム、制御リクエストの失敗処理、stdin のライフサイクルと実行終了の検出）
- すべての機能ガイド：
  - シンプルなクエリ
  - 対話的な会話
  - 設定オプション（`sessionStore`、`loadTimeoutMs`、`verbatimPrompts`、システムプロンプトの `snapshot` を含む）
  - メッセージ型（タスクメッセージ、サーバーツールブロック、MirrorErrorMessage）
  - MCP サーバー（ToolAnnotations、ツールタイトル、ステータス型、入力検証、失敗時セマンティクス、キャンセル、カスタムハンドラを含む）
  - エージェント定義
  - 拡張思考の設定（`ThinkingDisplay` を含む）
  - フックシステム（agentId/agentType フィールドを含む）
  - 権限システム
  - ストリーミングイベント
  - トランスポート層（`--session-mirror`、`--thinking-display` を含み、`--debug-to-stderr` を廃止）
  - プラグインシステム
  - セッション履歴（listSessions / getSessionMessages）
  - Session Store（トランスクリプトを S3/Postgres/Redis/独自バックエンドへミラー、上限付きエグゼキュータ）
- 完全な API リファレンス（5 つのドキュメント）：
  - ClaudeSDK（セッション履歴のメソッドを含む）
  - ClaudeSDKClient
  - ClaudeAgentOptions
  - メッセージ型
  - 例外の型
- コードサンプル（examples/ ディレクトリに 20 以上）
- Python SDK パリティのドキュメント

## ドキュメントへの貢献

新しいドキュメントを追加するときは：
1. 既存の構成と書式に従う
2. 実際に動作するコードサンプルを含める
3. すべてのコードサンプルを実装と照らし合わせて検証する
4. 関連ドキュメントへの相互参照を追加する
5. ドキュメント索引に新しい文書を登録する
6. ドキュメントの基準に従う：
   - 分かりやすい目次
   - 実践的なサンプル
   - ベストプラクティスのセクション
   - リンク付きの「関連項目」セクション

## ドキュメントの原則

本プロジェクトのすべてのドキュメントは、次の原則に従います：
1. **正確さ**：すべてのコードサンプルは動作し、実際の API と一致していること
2. **網羅性**：主要なユースケースとシナリオをすべて扱うこと
3. **明快さ**：分かりやすい言葉を使い、複雑な概念を説明すること
4. **サンプル**：実践的で実行可能なコードサンプルを含めること
5. **相互参照**：関連ドキュメントへリンクすること
6. **ベストプラクティス**：推奨パターンとアンチパターンを含めること
7. **最新性**：コードの変更に追随すること

## サポートとリソース

- **GitHub リポジトリ**：https://github.com/vidyalai-in/claude-agent-sdk-java
- **Issues**：バグ報告と機能要望は GitHub Issues へ
- **サンプルコード**：リポジトリの `examples/` ディレクトリを参照
- **MCP 仕様**：https://spec.modelcontextprotocol.io/
- **ライセンス**：MIT License
- **Python SDK**：比較用に https://github.com/anthropics/anthropic-sdk-python を参照
- **Claude Agent Python SDK ドキュメント**：https://platform.claude.com/docs/en/agent-sdk/python

## コントリビュート

貢献を歓迎します。貢献ガイドラインについてはリポジトリを参照してください。

## バージョン

現在のリリースは [Maven Central](https://central.sonatype.com/artifact/in.vidyalai/claude-agent-sdk-java)
に掲載されているものです —— 手作業で書き写したバージョンが 4 リリース分も古くなったことがあるため、
ここにはあえて再掲しません。

バージョン履歴とリリースノートは [CHANGELOG.md](../CHANGELOG.md)（英語のみ）を参照してください。
