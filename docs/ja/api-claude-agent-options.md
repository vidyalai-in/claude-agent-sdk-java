# ClaudeAgentOptions API リファレンス

設定オプションのビルダーです。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../api-claude-agent-options.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

## クラスの概要

```java
public final class ClaudeAgentOptions
```

ビルダーパターンを用いた不変の設定オブジェクトです。

## オプションの作成

```java
// Builder
ClaudeAgentOptions.builder()
    .option1(value)
    .build()

// Defaults
ClaudeAgentOptions.defaults()

// From existing
options.toBuilder()
    .modifiedOption(newValue)
    .build()
```

## すべての設定オプション

### ツールの設定
- `tools(Object)` —— ツールのリストまたはプリセット
- `allowedTools(List<String>)` —— 許可リスト
- `disallowedTools(List<String>)` —— 拒否リスト

### システムプロンプト
- `systemPrompt(String)` —— カスタムシステムプロンプト（`--system-prompt`）
- `systemPrompt(SystemPromptPreset)` —— Claude Code プリセット。必要に応じて `append`、`excludeDynamicSections`、`snapshot` を指定可能
- `systemPrompt(SystemPromptCustom)` —— `snapshot` も設定できるカスタムプロンプト。`SystemPromptCustom.of(prompt, snapshot)`
- `systemPrompt(SystemPromptFile)` —— ファイルから読み込むプロンプト（`--system-prompt-file`）

`snapshot`（プリセット形式とカスタム形式のみ）は、セッションが最初のリクエストで記録したプロンプトを保持し続ける（`true`）か、リクエストごとに再構築する（`false`）かを制御します。`initialize` リクエストで `systemPromptSnapshot` として送られ、`null` の場合は省略されます。Claude Code 2.1.257 以降が必要です。[設定オプション → スナップショット](./feature-configuration-options.md#スナップショット)を参照してください。

### MCP サーバー
- `mcpServers(Map<String, McpServerConfig>)` —— サーバーの設定。`--mcp-config` 用に `{"mcpServers": {...}}` としてシリアライズされます
- `mcpServers(Path)` / `mcpServersPath(Path)` —— MCP 設定ファイル。そのまま `--mcp-config` に渡されます
- `mcpServersJson(String)` —— インラインの MCP 設定 JSON。そのまま `--mcp-config` に渡されます
- `strictMcpConfig(boolean)` —— `true` の場合、CLI はプロジェクトの `.mcp.json`、ユーザー／グローバル設定、プラグイン提供の MCP サーバーを無視し、`mcpServers(...)` で渡されたサーバーだけを読み込みます。`--strict-mcp-config` に対応します。

### 権限
- `permissionMode(PermissionMode)` —— 権限モード
- `permissionPromptToolName(String)` —— プロンプト用のツール
- `canUseTool(CanUseTool)` —— カスタムコールバック。**`"ask"` の判断時のみ発火します** —— `allowedTools`、`permissionMode`、`permissions.allow` ルールによってすでに許可されているツール呼び出しでは発火しません。判断にかかわらずすべての呼び出しを制御したい場合は `PreToolUse` フックを使ってください。このコールバックがツール全体を許可する `allowedTools` エントリや `BYPASS_PERMISSIONS` によって明らかに覆い隠されている場合、SDK は接続時に助言的な `WARNING` をログに出します。[シャドーイングの警告](feature-permissions.md#シャドーイングの警告)を参照してください。

### セッション
- `continueConversation(boolean)` —— 直前のセッションを継続
- `resume(String)` —— 特定のセッションを再開
- `forkSession(boolean)` —— 再開したセッションをフォーク
- `resumeSessionAt(String)` —— 切り詰めレジューム：再開する会話を、このトランスクリプトエントリ UUID までを含む範囲だけ読み込み、それより前の地点から分岐します。`resume` と、通常は `forkSession` と併用します。任意のトランスクリプトエントリ UUID を受け付けます —— 典型的にはライブで観測した `AssistantMessage.uuid()` か、`ClaudeSDK.getSessionMessages(...)` から得た `SessionMessage.uuid()` です。`--resume-session-at=<value>` として送出されます。[切り詰めレジューム](./feature-session-history.md#切り詰めレジューム)を参照してください。
- `resumeDropsTurn(String)` —— `resumeSessionAt` と併用：この切り詰めで破棄しようとしているターンのユーザープロンプトの UUID です。CLI は読み込み時に、分岐点より後の*すべての*エントリがそのターンに属することを検証し、そうでなければ拒否します —— セッションがターンの途中で取り込んだ、キュー中のユーザーメッセージやタスク通知が黙って捨てられることは決してありません。拒否は例外として現れ、そのメッセージには `Resume rejected by --resume-drops-turn:` が含まれます。これは決定論的な結果として扱い、再試行せず普通にレジュームしてください。null でない限り常に転送されるため、空文字列も CLI に届き、そこで不正な形式として拒否されます（ガードが黙って無効化されることはありません）。`--resume-drops-turn=<value>` として送出されます。
- `sessionStore(SessionStore)` —— トランスクリプトを外部ストアにミラーし、そこからレジュームします（[Session Store](./feature-session-store.md) を参照）。設定すると SDK は CLI に `--session-mirror` を渡し、`transcript_mirror` フレームを `store.appendAsync(...)` にルーティングします。事前検証では、`listSessions()` をサポートしない状態での `continueConversation + sessionStore` と、`sessionStore + enableFileCheckpointing` が拒否されます。遅いストアは各 `ResultMessage` の前のフラッシュの間リーダーを止め（制御呼び出しもそれに合わせて待ちます）、`close()` は最後のフラッシュを待ちます。いずれも既定では最大 181 秒です。
- `sessionStoreFlush(SessionStoreFlushMode)` —— トランスクリプトミラーのエントリを `sessionStore` にフラッシュするタイミング。`BATCHED`（既定）はエントリをまとめ、1 ターンごと、またはバッファが 500 エントリ／1 MiB を超えたときに一度フラッシュします。`EAGER` はフレームごとにバックグラウンドのフラッシュをスケジュールし、ほぼリアルタイムで届けます。`sessionStore` が未設定の場合は無視されます。[フラッシュモード](./feature-session-store.md#フラッシュモードbatched-と-eager)を参照してください。
- `loadTimeoutMs(long)` —— レジュームのマテリアライズ中における `store.loadAsync()` / `listSubkeysAsync()` の 1 回あたりのタイムアウト（ミリ秒、既定 `60_000`）。`0` は即時タイムアウトを意味し、十分大きな値は実質的に無効化します。

### 上限
- `maxTurns(Integer)` —— 会話ターンの上限。`0` は未設定として扱われ、`--max-turns` は送られません
- `maxBudgetUsd(Double)` —— 上限コスト（米ドル）
- `maxBufferSize(Integer)` —— stdout バッファの最大バイト数
- `thinking(ThinkingConfig)` —— 拡張思考の設定
- `effort(String)` —— 思考の深さのレベル（`"low"`、`"medium"`、`"high"`、`"xhigh"`、`"max"`）。`"xhigh"` は Opus 4.7 専用で、他のモデルでは `"high"` にフォールバックします。
- `effort(EffortLevel)` —— 上と同じですが、[`EffortLevel`](feature-configuration-options.md#effortlevel-列挙型) 列挙型（`LOW`、`MEDIUM`、`HIGH`、`XHIGH`、`MAX`）を使う型安全な版です。`null` を渡すとクリアされます。
- `maxThinkingTokens(Integer)` —— **非推奨**。`thinking()` を使ってください。新しいモデルでは値はオン／オフとして扱われます（0 = 無効、それ以外 = 適応型）
- `maxMsgQSize(Integer)` —— メッセージキューの最大サイズ（既定は 1000、最小 1）。キューが満杯になると、メッセージを消費するまでリーダーが読み取りを止めます（バックプレッシャー）。メッセージが破棄されることはありません。これはソフトリミットです：送信側の制御リクエスト（`interrupt()`、`setModel()` など）が応答を待っている間は、応答が届くようにリーダーは上限を超えて読み続けます

### モデル
- `model(String)` —— AI モデル名。空文字列の場合は `--model` を送りません（`fallbackModel`、`permissionPromptToolName`、`resume`、`sessionId`、`settings` も同様）
- `fallbackModel(String)` —— フォールバックモデル
- `betas(List<SdkBeta>)` —— ベータ機能

### 環境
- `cwd(Path)` —— 作業ディレクトリ
- `cliPath(Path)` —— 独自の CLI パス（Windows の `.bat`/`.cmd` パスは拒否されます。下記参照）。未設定の場合、SDK は CLI を探索します。Windows では `claude.exe` だけを、絶対パスの `PATH` エントリ、続いて `~/.local/bin/claude.exe` の順に探します
- `allowUnsafeWindowsBatchCli(boolean)` —— Windows のバッチスクリプト拒否を解除します。あわせて `-Djdk.lang.Process.allowAmbiguousCommands=false` が必要で、すべての引数における cmd.exe のメタ文字を拒否します（既定 `false`）。`claude.exe` が存在しない場合に、CLI の探索が `claude.cmd`/`claude.bat` のシムを返せるようにもします
- `settings(String)` —— 設定ファイルのパス、またはインラインの JSON 文字列。そのまま `--settings` に渡されるか、サンドボックスが設定されている場合は `sandbox` とマージして 1 つの JSON 文字列にされます。相対パスは `cwd` を基準に解決されます。このマージのために設定を読み込めない場合、`connect()` は `CLIConnectionException` で失敗します
- `addDirs(List<Path>)` —— 追加のコンテキストディレクトリ
- `env(Map<String, String>)` —— 環境変数。継承した環境の上にマージされます。SDK は `CLAUDE_CODE_ENTRYPOINT`（上書き可能）、`CLAUDE_AGENT_SDK_VERSION` も設定し、すでに名前がある場合を除いて `CLAUDE_CODE_SDK_READS_SESSION_STATE=1` を設定し、継承した `CLAUDE_AGENT_SDK_DERIVED_CLIENT_APP` を取り除きます。[env()](./feature-configuration-options.md#env) を参照してください
- `clientApp(String)` —— アプリ／ライブラリの識別子（例：`"my-app/1.0.0"`）。API リクエストで `CLAUDE_AGENT_SDK_CLIENT_APP` として送られます。`env` や継承した値より優先され、`""` の場合は何も送りません。[clientApp()](./feature-configuration-options.md#clientapp) を参照してください
- `extraArgs(Map<String, ? extends @Nullable String>)` —— 追加の CLI フラグ。キーは先頭の `--` を付けず、値なしのフラグには `null` または空白の値を指定します。マップの反復順で渡されます（順序が重要な場合は `LinkedHashMap` を使ってください）。[extraArgs()](./feature-configuration-options.md#extraargs) を参照してください

### コールバック
- `stderrCallback(Consumer<String>)` —— CLI の stderr の 1 行ごとに 1 回呼ばれます（`\n` で分割し、末尾の空白を取り除き、空行はスキップ）。設定しない場合、CLI は JVM の stderr を継承します。[stderrCallback()](./feature-configuration-options.md#stderrcallback) を参照してください

### フック
- `hooks(Map<HookEvent, List<HookMatcher>>)` —— フックのコールバック。`HookMatcher` には `timeoutSeconds` と `onFailure` ポリシー（`HookOnFailure.CONTINUE` または `BLOCK`）を設定できます。[フック → 失敗ポリシー](./feature-hooks.md#失敗ポリシーonfailure)を参照してください
- `includeHookEvents(boolean)` —— `true` の場合、CLI はフックのライフサイクルイベント（`PreToolUse`、`PostToolUse`、`Stop` など）を `HookEventMessage` オブジェクトとしてメッセージストリームに流します。`SessionStart` と `Setup` のフックイベントはこの設定にかかわらず送出されます。`--include-hook-events` に対応します。[フック → ストリーム上のフックライフサイクルイベント](./feature-hooks.md#ストリーム上のフックライフサイクルイベント)を参照してください。

### 高度な設定
- `user(String)` —— **非推奨、未サポート。** Java では CLI を別の OS ユーザーとして実行できないため、これが設定されていると `connect()` は `CLIConnectionException` を投げます。[user()](./feature-configuration-options.md#user) を参照してください
- `includePartialMessages(boolean)` —— API のストリームイベントごとに `StreamEvent` を出します（`--include-partial-messages`）
- `verbatimPrompts(boolean)` —— SDK が送るすべてのユーザーメッセージに `client_composed` の印を付け、Claude Code が書かれたとおりに届けるようにします：`@path` の展開もスラッシュコマンドのディスパッチも行いません。有効な間はメッセージごとの値を上書きします。Claude Code 2.1.248 以降が必要です（古い CLI では SDK が警告します）。[verbatimPrompts()](./feature-configuration-options.md#verbatimprompts) を参照してください
- `forwardSubagentText(boolean)` —— `true` の場合、サブエージェントのテキストブロックと思考ブロックが、常に転送される `tool_use` / `tool_result` ブロックと並んでメッセージストリームに転送されます。`initialize` 制御リクエストで送信されます（CLI フラグはありません）。[エージェント → サブエージェントの出力の観測](./feature-agents.md#サブエージェントの出力の観測)を参照してください。
- `agents(Map<String, AgentDefinition>)` —— カスタムエージェント
- `settingSources(List<SettingSource>)` —— 設定の取得元（空リストは `--setting-sources=` によってすべての取得元を無効化し、セッションをファイルシステム上の設定から隔離します。省略した場合は CLI の既定値が維持され、すべての取得元が読み込まれます）
- `skills(List<String>)` —— Skills の許可リスト（`allowedTools` に `Skill(name)` を自動注入し、`settingSources` を user/project に既定設定します）。名前は厳密に一致している必要があり、ワイルドカード、ルールの区切り文字、前後の空白は `connect()` 時に `IllegalArgumentException` を投げます
- `skillsAll()` —— 検出されたすべての skill を有効化します（裸の `Skill` ツールを自動注入）
- `sandbox(SandboxSettings)` —— bash コマンドのサンドボックスの設定。その `network` キーはサンドボックス自身のネットワーク分離を設定します（ツールレベルの制限は権限ルールのままです）
- `plugins(List<SdkPluginConfig>)` —— ローカルのプラグインディレクトリ。`SdkPluginConfig.local(path)` → `--plugin-dir`。それ以外の型は `connect()` 時に `IllegalArgumentException` を投げます
- `outputFormat(Map<String, Object>)` —— 出力フォーマット
- `enableFileCheckpointing(boolean)` —— `rewindFiles()` のためのファイルチェックポイントを有効化。`sessionStore` とは併用できません

## 関連項目
- [設定オプションガイド](./feature-configuration-options.md)
