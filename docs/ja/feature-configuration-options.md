# 設定オプション

`ClaudeAgentOptions` で Claude SDK の動作を設定するための完全ガイド。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../feature-configuration-options.md)より古い場合があります。内容が食い違う場合は英語版が優先されます。コードブロックは英語版と同一のまま、翻訳していません。

## 目次
- [概要](#概要)
- [ビルダーパターン](#ビルダーパターン)
- [ツールの設定](#ツールの設定)
- [システムプロンプト](#システムプロンプト)
- [MCP サーバー](#mcp-サーバー)
- [権限の設定](#権限の設定)
- [セッション管理](#セッション管理)
- [上限](#上限)
- [モデルの設定](#モデルの設定)
- [作業ディレクトリと CLI](#作業ディレクトリと-cli)
- [環境変数](#環境変数)
- [コールバック](#コールバック)
- [フック](#フック)
- [高度な機能](#高度な機能)
- [完全な例](#完全な例)

## 概要

`ClaudeAgentOptions` は Claude SDK の動作を制御する 30 以上の設定項目を提供します。型安全な設定のために、イミュータブルなビルダーパターンを採用しています。

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .maxTurns(10)
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .build();
```

## ビルダーパターン

### オプションの作成

```java
// Start with builder
ClaudeAgentOptions.Builder builder = ClaudeAgentOptions.builder();

// Configure
builder.model("claude-sonnet-4-5")
       .maxTurns(10);

// Build immutable instance
ClaudeAgentOptions options = builder.build();
```

### デフォルトのオプション

```java
// Use defaults
ClaudeAgentOptions options = ClaudeAgentOptions.defaults();
```

### 既存のオプションを変更する

```java
// Create from existing
ClaudeAgentOptions modified = options.toBuilder()
    .maxTurns(20)
    .model("claude-opus-4-6")
    .build();
```

## ツールの設定

### tools()

Claude が使えるツールを指定します。

```java
// Use all available tools (default)
.tools(null)

// Specify list of tool names
.tools(List.of("Read", "Write", "Bash"))

// Use preset
.tools(new ToolsPreset("code-editing"))
```

**ツール名**：
- `Read` — ファイルの読み取り
- `Write` — ファイルの書き込み／作成
- `Edit` — 既存ファイルの編集
- `Bash` — bash コマンドの実行
- `Grep` — ファイル内容の検索
- `Glob` — パターンによるファイル検索
- `Task` — サブエージェントの起動
- `WebFetch` — Web コンテンツの取得
- `WebSearch` — Web 検索
- MCP ツール：`mcp__<server>__<tool>`

### allowedTools()

特定のツールをホワイトリストに登録します。

```java
.allowedTools(List.of(
    "Read",
    "Grep",
    "Glob",
    "mcp__calc__add"
))
```

### disallowedTools()

特定のツールをブラックリストに登録します。

```java
.disallowedTools(List.of(
    "Bash",      // Block shell access
    "Write",     // Block file writing
    "WebFetch"   // Block web access
))
```

**優先順位**：`disallowedTools` が `allowedTools` より優先されます。

## システムプロンプト

### systemPrompt()

Claude の振る舞いを導くカスタムシステムプロンプトを設定します。

```java
// String prompt
.systemPrompt("You are a code reviewer. Focus on security and performance.")

// Multi-line prompt
.systemPrompt("""
    You are a helpful coding assistant.
    - Be concise
    - Provide working code examples
    - Explain your reasoning
    """)

// Use Claude Code preset
.systemPrompt(SystemPromptPreset.claudeCode())

// Use Claude Code preset with additional instructions
.systemPrompt(SystemPromptPreset.claudeCode("Always respond in JSON format."))

// Use Claude Code preset with exclude_dynamic_sections for cross-user caching
.systemPrompt(SystemPromptPreset.claudeCode("Custom instructions", true))

// Use prompt from file
.systemPrompt(new SystemPromptFile("/path/to/prompt.md"))
```

## MCP サーバー

### mcpServers()

カスタムツール用に Model Context Protocol サーバーを設定します。

```java
// SDK MCP server (in-process)
McpSdkServerConfig sdkServer = ClaudeSDK.createSdkMcpServer(
    "my-tools",
    new MyTools()
);

.mcpServers(Map.of("tools", sdkServer))

// External stdio server
McpStdioServerConfig externalServer = new McpStdioServerConfig(
    "node",
    List.of("server.js"),
    Map.of("NODE_ENV", "production")
);

.mcpServers(Map.of(
    "sdk", sdkServer,
    "external", externalServer
))

// From file path
.mcpServers(Path.of("~/.claude/mcp_servers.json"))

// From JSON string
.mcpServers("""
    {
        "server1": {"type": "stdio", "command": "node", "args": ["server.js"]}
    }
    """)
```

## 権限の設定

### permissionMode()

ツールの権限をどう扱うかを制御します。

```java
.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
```

**モード**：
- `PROMPT`（デフォルト）— 権限ごとに確認する
- `ACCEPT_ALL` — すべての権限を自動的に許可
- `ACCEPT_EDITS` — ファイル編集は自動許可、それ以外は確認
- `BYPASS_PERMISSIONS` — 権限チェックを完全にスキップ
- `DONT_ASK` — 確認せずにすべてのツールを許可
- `AUTO` — 適切な権限モードを自動的に判断

### permissionPromptToolName()

権限プロンプト用のツールを指定します（上級者向け。通常は自動設定）。

```java
.permissionPromptToolName("stdio")
```

## セッション管理

### continueConversation()

前回の会話を継続します。

```java
.continueConversation(true)  // Continue from last session
.continueConversation(false) // Start fresh (default)
```

### resume()

ID を指定して特定のセッションを再開します。

```java
.resume("session-12345")
```

### sessionId()

新しいセッションのセッション ID を指定します。

```java
.sessionId("my-custom-session-id")
```

### forkSession()

再開したセッションを新しいセッションにフォークします（コンテキストを保持し、ID は新規）。

```java
.resume("session-12345")
.forkSession(true)
```

### sessionStore()

セッションのトランスクリプトを外部ストア（S3、Postgres、Redis、独自バックエンド）にミラーします。設定すると、SDK は CLI 起動時に `--session-mirror` を追加し、すべてのトランスクリプト行を `store.appendAsync(...)` に転送します。再開時に `sessionStore` を併用すると、ストアから一時的な `CLAUDE_CONFIG_DIR` に内容を展開し、CLI がローカルで会話を引き継げるようにします。機能の全容は [Session Store ガイド](./feature-session-store.md) を参照してください。

```java
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;

InMemorySessionStore store = new InMemorySessionStore();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .build();
```

**バリデーションガード**（サブプロセス起動前に `IllegalArgumentException` で拒否）：
- `continueConversation + sessionStore` には `store.implementsListSessions()` が必要です。
- `sessionStore + enableFileCheckpointing` は拒否されます — チェックポイントはローカルディスク専用です。

### sessionStoreFlush()

設定した `sessionStore` にトランスクリプトミラーのエントリをいつフラッシュするかを制御します。デフォルトは `SessionStoreFlushMode.BATCHED`（ターンごと、またはバッファ溢れ時に 1 回フラッシュ）。`SessionStoreFlushMode.EAGER` を使うと、フレームごとにバックグラウンドのフラッシュをスケジュールし、ほぼリアルタイムで送信します — 追加はエンキュー順に直列化されたままですが、遅いアダプターが読み取りループを止めることはありません。`sessionStore` が未設定の場合は無視されます。

```java
import in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .sessionStoreFlush(SessionStoreFlushMode.EAGER)
    .build();
```

トレードオフは[フラッシュモード（BATCHED と EAGER）](./feature-session-store.md#フラッシュモードbatched-と-eager)を参照してください。

### loadTimeoutMs()

再開時の展開処理における `store.loadAsync()` と `listSubkeysAsync()` の呼び出しごとのタイムアウト（ミリ秒）。デフォルトは `60_000`。アダプターがこの時間内に完了しない場合、イテレーターがハングするのではなく、明確なエラーでクエリが失敗します。

```java
.loadTimeoutMs(30_000)  // 30 seconds
```

## 上限

### maxTurns()

会話の最大ターン数。

```java
.maxTurns(10)  // Limit to 10 turns
```

**ユースケース**：
- 予算の管理
- 会話の暴走防止
- 短いクエリ：`.maxTurns(1)`

### maxBudgetUsd()

米ドルでの最大コスト。

```java
.maxBudgetUsd(1.0)  // Limit to $1.00
```

予算を超えると実行を停止します。

### taskBudget()

トークン単位の API 側タスク予算。設定すると、モデルは残りのトークン予算を認識します。

```java
.taskBudget(new TaskBudget(100000))  // 100K token budget
```

### maxBufferSize()

CLI の stdout をバッファリングする最大バイト数。

```java
.maxBufferSize(10 * 1024 * 1024)  // 10MB
```

デフォルト：100MB。出力が大きい場合は増やしてください。

### thinking()

**新機能**：拡張思考の挙動をきめ細かく設定します。

```java
// Adaptive thinking (32K token default)
.thinking(new ThinkingConfigAdaptive())

// Fixed token budget
.thinking(new ThinkingConfigEnabled(10000))

// Disable thinking
.thinking(new ThinkingConfigDisabled())
```

**型**：
- `ThinkingConfigAdaptive` — 適応的思考、デフォルト 32,000 トークン
- `ThinkingConfigEnabled(int budgetTokens)` — 固定トークン予算（0 より大きい必要があります）
- `ThinkingConfigDisabled` — 思考トークンなし

**注意**：このオプションは非推奨の `maxThinkingTokens()` より優先されます。

完全なガイドは[拡張思考の設定](./feature-thinking-config.md)を参照してください。

### effort()

思考の深さ／強度のレベルを設定します。オーバーロードが 2 つあり、生の文字列か、
型安全な [`EffortLevel`](#effortlevel-列挙型) 列挙型のどちらかを渡せます。

```java
// String overload
.effort("low")     // Minimal thinking, fastest responses
.effort("medium")  // Moderate thinking
.effort("high")    // Deep reasoning (default)
.effort("xhigh")   // Extended depth (Opus 4.7 only; falls back to "high")
.effort("max")     // Maximum reasoning

// Enum overload (recommended for type safety)
.effort(EffortLevel.HIGH)
.effort(EffortLevel.XHIGH)
.effort((EffortLevel) null)  // clear
```

**有効な値**：`"low"`、`"medium"`、`"high"`、`"xhigh"`、`"max"`

`"xhigh"` は Opus 4.7 専用で、他のモデルでは `"high"` にフォールバックします。

`thinking()` と組み合わせて推論の深さを制御します。

例は[拡張思考の設定](./feature-thinking-config.md)を参照してください。

### EffortLevel 列挙型

`in.vidyalai.claude.sdk.types.config.EffortLevel` にある公開列挙型で、Python の
`EffortLevel` 型エイリアスに対応します。下流の SDK ラッパーがこの型を直接
参照できるよう公開されています。

| 定数 | ワイヤ値 | 説明 |
|----------|------------|-------------|
| `EffortLevel.LOW` | `"low"` | 最小限の思考、最速の応答 |
| `EffortLevel.MEDIUM` | `"medium"` | 中程度の思考 |
| `EffortLevel.HIGH` | `"high"` | 深い推論（デフォルト） |
| `EffortLevel.XHIGH` | `"xhigh"` | 拡張推論（Opus 4.7 のみ。他では `HIGH` にフォールバック） |
| `EffortLevel.MAX` | `"max"` | 最大の労力 |

ヘルパー：
- `EffortLevel.getValue()` は小文字のワイヤ値を返します（`@JsonValue` のシリアライザーでもあります）。
- `EffortLevel.fromValue(String)` はワイヤ値を列挙定数に戻します。未知の値には `IllegalArgumentException` をスローします。

```java
EffortLevel level = EffortLevel.fromValue("xhigh");
String wire = level.getValue(); // "xhigh"
```

### maxThinkingTokens()

**非推奨**：代わりに `thinking()` を使ってください。

思考ブロックの最大トークン数。

```java
.maxThinkingTokens(10000)  // Deprecated - use thinking() instead
```

### maxMsgQSize()

メッセージキューの最大サイズ。

```java
.maxMsgQSize(1000)
```

高スループットのシナリオでは増やしてください。

## モデルの設定

### model()

AI モデルを設定します。

```java
.model("claude-sonnet-4-5")
```

**利用可能なモデル**：
- `claude-opus-4-6` — 最も高性能、高価
- `claude-sonnet-4-5` — バランス型（デフォルト）
- `claude-haiku-4-5` — 高速、低コスト

### fallbackModel()

主モデルが利用できない場合のフォールバック。

```java
.model("claude-opus-4-6")
.fallbackModel("claude-sonnet-4-5")
```

### betas()

ベータ機能を有効にします。

```java
.betas(List.of(
    SdkBeta.PROMPT_CACHING,
    SdkBeta.EXTENDED_THINKING
))
```

[Anthropic API Beta Headers](https://docs.anthropic.com/en/api/beta-headers) を参照してください。

## 作業ディレクトリと CLI

### cwd()

ファイル操作の作業ディレクトリを設定します。

```java
.cwd(Path.of("/path/to/project"))
```

**重要**：パスを正しく解決するため、ファイル操作では必ず設定してください。

### cliPath()

Claude Code CLI へのカスタムパス。

```java
.cliPath(Path.of("/custom/path/to/claude"))
```

デフォルト：システムの PATH を検索します。

**Windows**：`.bat`/`.cmd` のパス（npm の `claude.cmd` シム）は拒否されます — OS がそれを `cmd.exe` 経由で実行し、`cmd.exe` がコマンドラインを再解析するためです。`claude.exe` を指すか、下記の `allowUnsafeWindowsBatchCli()` を参照してください。

### allowUnsafeWindowsBatchCli()

ネイティブの `claude.exe` に移行できない環境向けに、Windows のバッチスクリプト拒否を免除します。デフォルトは `false`。

```java
.cliPath(Path.of("C:\\Users\\Administrator\\AppData\\Roaming\\npm\\claude.cmd"))
.allowUnsafeWindowsBatchCli(true)
```

これは単純なバイパスでは**ありません** — 素の免除では `cmd.exe` の再解析の穴がそのまま戻ってしまいます。有効にすると、さらに次のことを行います。

1. **JVM に `-Djdk.lang.Process.allowAmbiguousCommands=false` を要求します**。このプロパティのデフォルトは `true` で、その場合 JDK はバッチ起動時に空白の周りしかクォートしません。`false` にすると `" < > & | ^` をクォートし、引用符を含む引数を拒否します。フラグが無い場合、`connect()` は `CLIConnectionException` をスローします。
2. **すべての CLI 引数で `& | < > ^ % ! "` と CR/LF を拒否し**、問題のあるオプション名を示す `IllegalArgumentException` をスローします。`%` と `!` は JDK のエスケープ対象に含まれておらず、クォートしても `%VAR%` の展開は止まりません。
3. **受け入れたリスクを示す `WARNING` をログに出します**。

**残存リスク**：cmd.exe は依然として環境から `%VAR%` を展開します。CLI のパスとすべての引数値が管理者の管理下にある場合にのみ使用してください。POSIX では無視されます。[トランスポート層 → バッチ CLI のオプトイン](./feature-transport-layer.md#windowsバッチ-cli-のオプトイン0122)を参照してください。

### settings()

設定 JSON ファイルへのパス。

```java
.settings("/path/to/settings.json")
```

### addDirs()

コンテキストに追加するディレクトリ。

```java
.addDirs(List.of(
    Path.of("/path/to/lib"),
    Path.of("/path/to/docs")
))
```

## 環境変数

### env()

CLI プロセスの環境変数を設定します。

```java
.env(Map.of(
    "API_KEY", "secret-key",
    "DEBUG", "true",
    "NODE_ENV", "production"
))
```

### extraArgs()

任意の CLI フラグを渡します。

```java
.extraArgs(Map.of(
    "--verbose", "",
    "--config", "custom.json"
))
```

## コールバック

### canUseTool()

ツール用のカスタム権限コールバック。`permissionPromptToolName` とは排他です。

```java
.canUseTool((toolName, input, context) -> {
    // Check permission
    if (isAllowed(toolName)) {
        return CompletableFuture.completedFuture(
            new PermissionResultAllow()
        );
    } else {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Tool not allowed")
        );
    }
})
```

**シグネチャ**：
```java
BiFunction<String, Object, ToolPermissionContext, CompletableFuture<PermissionResult>>
```

### stderrCallback()

CLI の stderr 出力を受け取ります。CLI が出力するたびに stderr 1 行ごとに呼ばれます（このコールバックを設定したときだけパイプされます）。

```java
.stderrCallback(line -> {
    System.err.println("CLI stderr: " + line);
})
```

**例外の隔離**：コールバックが例外をスローしても、その例外は捕捉され、`FINE` レベルでログに記録され（`java.util.logging`）、stderr の読み取りは続きます。バグのあるコールバックが読み取りループを黙って終了させ、以降のセッション中すべての stderr 行を落とすことはもうありません。

## フック

### hooks()

ライフサイクルイベントにフックコールバックを登録します。

```java
.hooks(Map.of(
    HookEvent.PRE_TOOL_USE, List.of(
        new HookMatcher(null, "Read", (context) -> {
            System.out.println("About to read file");
            return CompletableFuture.completedFuture(
                HookOutput.empty()
            );
        })
    )
))
```

**利用可能なイベント**：
- `PRE_TOOL_USE` — ツール実行前
- `POST_TOOL_USE` — ツール成功後
- `POST_TOOL_USE_FAILURE` — ツール失敗後
- `USER_PROMPT_SUBMIT` — ユーザーがメッセージを送信
- `STOP` — セッションの停止
- `SUBAGENT_START` — サブエージェントの開始
- `SUBAGENT_STOP` — サブエージェントの停止
- `PRE_COMPACT` — メッセージ圧縮の前
- `NOTIFICATION` — 通知イベント
- `PERMISSION_REQUEST` — 権限の要求

## 高度な機能

### user()

トラッキング用のユーザー識別子を設定します。

```java
.user("user-12345")
```

### includePartialMessages()

部分メッセージのストリーミングを有効にします。

```java
.includePartialMessages(true)
```

コンテンツの生成に伴い、差分を含む `StreamEvent` メッセージを受け取ります。

### forwardSubagentText()

サブエージェントのテキストブロックと思考ブロックをメッセージストリームに転送します。

```java
.forwardSubagentText(true)
```

デフォルトでは、親のストリームに届くのはサブエージェントの `tool_use` / `tool_result`
ブロックだけで、`parentToolUseId` がそのサブエージェントを生成した Agent の `tool_use`
ブロックの id である `AssistantMessage` / `UserMessage` オブジェクトとして届きます。
進捗のハートビートには十分ですが、サブエージェントが何を言ったかを表示するには足りません。
これを有効にすると、テキストブロックと思考ブロックも同じ形で届きます。

これはフラグではなく `initialize` 制御リクエストで CLI に送られ、有効なときだけ送信されます。
古い CLI は無視します。
[Agents → サブエージェントの出力の観測](./feature-agents.md#サブエージェントの出力の観測)を参照してください。

### agents()

カスタムエージェントの設定を定義します。

```java
.agents(Map.of(
    "my-agent", new AgentDefinition(
        "Custom agent",
        "claude-sonnet-4-5",
        List.of("Read", "Write"),
        "You are a specialized agent"
    )
))
```

### settingSources()

読み込む設定ファイルを制御します。

```java
.settingSources(List.of(
    SettingSource.USER,     // ~/.claude/
    SettingSource.PROJECT,  // .claude/ in project
    SettingSource.LOCAL     // .claude.local/
))
```

**空のリストはすべてのソースを無効にします。** `List.of()` を渡すと CLI に `--setting-sources=`（空）が送られ、ファイルシステム上のあらゆる設定ソースが抑制されます。オプションを**完全に省略**した場合（デフォルト）は `--setting-sources` フラグ自体が付かず、CLI が独自のデフォルトを適用します。

### skills() / skillsAll()

メインセッション用のトップレベルなスキル許可リスト。SDK は一致する `Skill(name)` エントリを `allowedTools` に自動注入し、`settingSources` を user/project にデフォルト設定するため、CLI は追加の配線なしでインストール済みスキルを検出できます。このリストは initialize 制御リクエストでも伝えられ、対応する CLI はシステムプロンプトに読み込むスキルを絞り込めます（古い CLI はこのフィールドを無視します）。

```java
// Enable every discovered skill
.skillsAll()

// Enable only the listed skills
.skills(List.of("commit", "review"))

// Suppress every skill from the listing
.skills(List.of())
```

3 つのモード：

| ビルダー呼び出し | `allowedTools` への注入 | `settingSources` のデフォルト | initialize のワイヤフィールド |
|---|---|---|---|
| _省略_（null） | なし | なし | 省略 |
| `.skillsAll()` | 素の `Skill` を追加 | `[user, project]` | 省略 |
| `.skills(List.of("a", "b"))` | `Skill(a)`、`Skill(b)` を追加 | `[user, project]` | `["a", "b"]` |
| `.skills(List.of())` | なし | `[user, project]` | `[]` |

挙動の詳細：
- **冪等な注入** — `allowedTools` にすでに `Skill` や `Skill(name)` があれば、SDK は重複させません。
- **非破壊的** — スキルのデフォルト適用は新しいリストを構築します。元の `ClaudeAgentOptions` は変更されません。
- **明示的な `settingSources` が優先** — `.skills(...)` と併せて `.settingSources(...)` を設定した場合、その値が保持されます。
- **名前は検証されます**（0.1.22）— 列挙する各名前は、スキルの SKILL.md の `name` かディレクトリ名、または `plugin:skill` である必要があります。ルールの区切り文字（丸括弧、カンマ）、制御文字、ワイルドカード（`"*"`、`"pdf:*"`）、先頭の `/`、前後の空白は `connect()` で `IllegalArgumentException` をスローします。**破壊的変更**：`skills(List.of("*"))` と `skills(List.of("plugin:*"))` は以前はワイルドカードルールを構築していましたが、現在はスローします — `.skillsAll()` を使ってください。[Skills → 名前の検証](./feature-skills.md#名前の検証0122)を参照してください。
- **サンドボックスではなくコンテキストフィルター** — 列挙されていないスキルはモデルの一覧から隠され、`Skill` ツールからは呼び出せませんが、ファイルはディスク上に残ります。`Read`/`Bash` を持つセッションは `.claude/skills/**` に直接アクセスできます。

### sandbox()

bash コマンドのサンドボックスを設定します。

```java
// Minimal: just enable sandboxing.
.sandbox(new SandboxSettings(true))
```

より細かく制御するには、完全なレコードを渡します。

```java
SandboxNetworkConfig network = new SandboxNetworkConfig(
    List.of("api.example.com", "*.npmjs.org"),  // allowedDomains
    List.of("malicious.example.com"),           // deniedDomains (always blocked)
    /* allowManagedDomainsOnly */ false,
    List.of("/tmp/ssh-agent.sock"),             // allowUnixSockets
    /* allowAllUnixSockets */ false,
    /* allowLocalBinding */ true,
    List.of("com.apple.PowerManagement.control"),  // allowMachLookup (macOS only)
    /* httpProxyPort */ null,
    /* socksProxyPort */ null);

SandboxSettings sandbox = new SandboxSettings(
    /* enabled */ true,
    /* autoAllowBashIfSandboxed */ true,
    /* excludedCommands */ List.of("git"),
    /* allowUnsandboxedCommands */ null,
    network,
    /* ignoreViolations */ null,
    /* enableWeakerNestedSandbox */ false);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sandbox(sandbox)
    .build();
```

`SandboxNetworkConfig` のフィールド：

- `allowedDomains` — サンドボックス内のプロセスが到達できるドメイン。
- `deniedDomains` — 常にブロックする上書き設定。拒否が許可に優先します。
- `allowManagedDomainsOnly` — 管理設定で `true` のとき、管理設定の `allowedDomains` のみが有効になります。
- `allowMachLookup` — macOS 専用の XPC/Mach サービス名。末尾のワイルドカードに対応。
- `allowUnixSockets`、`allowAllUnixSockets`、`allowLocalBinding`、`httpProxyPort`、`socksProxyPort` — 既存のフィールド。

ドメイン許可リストや Mach ルックアップのフィールドを必要としない呼び出し側のために、後方互換の 5 引数コンストラクター `(allowUnixSockets, allowAllUnixSockets, allowLocalBinding, httpProxyPort, socksProxyPort)` が残されています（それらのフィールドは `null` になります）。

### plugins()

カスタムプラグインを追加します。

```java
.plugins(List.of(
    new SdkPluginConfig("my-plugin", config)
))
```

### outputFormat()

構造化出力のフォーマット（Messages API スタイル）。

```java
.outputFormat(Map.of(
    "type", "json_schema",
    "schema", Map.of(
        "type", "object",
        "properties", Map.of(
            "name", Map.of("type", "string"),
            "age", Map.of("type", "integer")
        ),
        "required", List.of("name")
    )
))
```

### checkpointFiles()

巻き戻し用のファイルチェックポイントを有効にします。

```java
.checkpointFiles(true)
```

`ClaudeSDKClient.rewindFiles()` を使えるようになります。

## 完全な例

### 例 1：読み取り専用のコード分析

```java
var options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .cwd(Path.of("/path/to/codebase"))
    .allowedTools(List.of("Read", "Grep", "Glob"))
    .disallowedTools(List.of("Write", "Edit", "Bash"))
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .maxTurns(10)
    .maxBudgetUsd(0.50)
    .systemPrompt("You are a code analyzer. Only read and analyze code.")
    .build();
```

### 例 2：対話的な開発

```java
var calcServer = ClaudeSDK.createSdkMcpServer("calc", new Calculator());

var options = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .cwd(Path.of("/project"))
    .allowedTools(List.of(
        "Read", "Write", "Edit", "Grep", "Glob",
        "mcp__calc__add", "mcp__calc__multiply"
    ))
    .mcpServers(Map.of("calc", calcServer))
    .permissionMode(PermissionMode.ACCEPT_EDITS)
    .maxTurns(50)
    .checkpointFiles(true)
    .systemPrompt("""
        You are a development assistant.
        - Write clean, tested code
        - Follow project conventions
        - Ask before major changes
        """)
    .build();
```

### 例 3：予算を意識したバッチ処理

```java
var options = ClaudeAgentOptions.builder()
    .model("claude-haiku-4-5")  // Cheapest model
    .maxTurns(1)                // Single turn only
    .maxBudgetUsd(0.10)         // 10 cent limit
    .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
    .systemPrompt("Be extremely concise.")
    .build();

for (String item : batchItems) {
    String result = ClaudeSDK.queryForText(item, options);
    processResult(result);
}
```

### 例 4：フック付きのカスタムツール

```java
var tools = new MyCustomTools();
var server = ClaudeSDK.createSdkMcpServer("tools", tools);

var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("tools", server))
    .allowedTools(List.of("mcp__tools__process"))
    .hooks(Map.of(
        HookEvent.PRE_TOOL_USE, List.of(
            new HookMatcher(null, "mcp__tools__process", context -> {
                log("Processing: " + context.input());
                return CompletableFuture.completedFuture(
                    HookOutput.logs(List.of("Started processing"))
                );
            })
        ),
        HookEvent.POST_TOOL_USE, List.of(
            new HookMatcher(null, "mcp__tools__process", context -> {
                log("Completed processing");
                return CompletableFuture.completedFuture(
                    HookOutput.empty()
                );
            })
        )
    ))
    .build();
```

### 例 5：セッションの再開

```java
// First session
var options1 = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .build();

try (var client = ClaudeSDK.createClient(options1)) {
    client.connect("What is Java?");
    // ... conversation
    // Note session ID from messages
}

// Resume later with context
var options2 = ClaudeAgentOptions.builder()
    .model("claude-sonnet-4-5")
    .resume("previous-session-id")
    .build();

try (var client = ClaudeSDK.createClient(options2)) {
    client.connect("Tell me more about lambdas");
    // Has context from previous session
}
```

### 例 6：権限コールバック付きのストリーミング

```java
var options = ClaudeAgentOptions.builder()
    .canUseTool((toolName, input, context) -> {
        // Custom permission logic
        boolean allowed = checkPermission(toolName, context.path());

        if (allowed) {
            return CompletableFuture.completedFuture(
                new PermissionResultAllow()
            );
        } else {
            return CompletableFuture.completedFuture(
                new PermissionResultDeny("Access denied to " + context.path())
            );
        }
    })
    .build();

// Must use streaming mode
var messages = List.of(
    Map.of("type", "user", "session_id", "default",
           "message", Map.of("role", "user", "content", "Read sensitive.txt"))
);

List<Message> responses = ClaudeSDK.query(messages.iterator(), options);
```

## ベストプラクティス

### 1. ファイル操作では必ず作業ディレクトリを設定する

```java
// ✅ Good
.cwd(Path.of("/project/root"))

// ❌ Bad: Undefined behavior
// No cwd set, files relative to CLI process directory
```

### 2. 適切なモデルを使う

```java
// ✅ Good: Match model to task
.model("claude-haiku-4-5")  // Simple tasks
.model("claude-sonnet-4-5") // Balanced
.model("claude-opus-4-6")   // Complex reasoning

// ❌ Bad: Always using most expensive
.model("claude-opus-4-6")  // For everything!
```

### 3. 予算の上限を設定する

```java
// ✅ Good: Protect against unexpected costs
.maxBudgetUsd(1.0)
.maxTurns(10)

// ❌ Bad: No limits
// Could get expensive!
```

### 4. ツールを適切に設定する

```java
// ✅ Good: Explicit tool control
.allowedTools(List.of("Read", "Grep"))
.disallowedTools(List.of("Bash"))

// ❌ Bad: All tools allowed by default
// Potential security risk
```

### 5. システムプロンプトを使う

```java
// ✅ Good: Guide behavior
.systemPrompt("You are a code reviewer. Focus on security.")

// ❌ Bad: No guidance
// Claude may not understand context
```

### 6. ファイル操作ではチェックポイントを有効にする

```java
// ✅ Good: Enable for safety
.checkpointFiles(true)

// Allows rewinding if mistakes
client.rewindFiles(checkpointId);
```

### 7. 機密データは慎重に扱う

```java
// ✅ Good: Don't pass secrets in env
.env(Map.of("CONFIG_PATH", "/path/to/config"))

// ❌ Bad: Secrets in environment
.env(Map.of("API_KEY", "secret-123"))  // Logged!
```

## 関連ドキュメント

- [単純なクエリ](./feature-simple-queries.md) — クエリでのオプションの使い方
- [対話型の会話](./feature-interactive-conversations.md) — クライアントでのオプションの使い方
- [MCP サーバー](./feature-mcp-servers.md) — MCP サーバーの設定
- [フック](./feature-hooks.md) — フックの設定
- [権限](./feature-permissions.md) — 権限システム
