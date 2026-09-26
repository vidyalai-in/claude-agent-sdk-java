# エージェント定義

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../feature-agents.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

カスタムエージェントを使うと、独自のシステムプロンプト、ツール、モデルを持つ専用のサブエージェント
を定義できます。Claude は会話中にこれらのエージェントを生成し、特定のタスクを処理させられます。

## 目次
- [概要](#概要)
- [AgentDefinition レコード](#agentdefinition-レコード)
- [インラインのエージェント定義](#インラインのエージェント定義)
- [ファイルシステムベースのエージェント](#ファイルシステムベースのエージェント)
- [大きなエージェント定義](#大きなエージェント定義)
- [サブエージェントの出力の観測](#サブエージェントの出力の観測)
- [バックグラウンドのサブエージェントとコールバック](#バックグラウンドのサブエージェントとコールバック)
- [サンプル](#サンプル)

## 概要

エージェントは、Claude が会話中に使える名前付きのサブエージェントです。各エージェントは次を持ちます：

- **description** —— そのエージェントが何をするか（どのエージェントを使うか判断する際に Claude に示されます）
- **システムプロンプト** —— そのエージェントの振る舞いに関する指示
- **ツール** —— そのエージェントが使用を許可されるツールの一覧（null なら親から継承）
- **モデル** —— そのエージェントが動作する Claude モデルのバリアント（null なら親から継承）
- **Skills** —— そのエージェントが利用できる skill 名の一覧（null なら親から継承）
- **Memory** —— そのエージェントのメモリスコープ（null なら親から継承）
- **MCP サーバー** —— そのエージェントが使える MCP サーバーの参照（null なら親から継承）

エージェントは `ClaudeAgentOptions.agents()` に `Map<String, AgentDefinition>` として登録します。
キーがエージェントの名前です。

## AgentDefinition レコード

```java
import in.vidyalai.claude.sdk.types.config.AgentDefinition;
import in.vidyalai.claude.sdk.types.config.AIModel;
import in.vidyalai.claude.sdk.types.config.MemoryScope;

// Full constructor
AgentDefinition agent = new AgentDefinition(
    "Reviews code for quality and bugs",   // description
    "You are a code review expert...",     // system prompt
    List.of("Read", "Grep"),               // tools (null = inherit)
    "sonnet",                              // model (null = inherit)
    List.of("commit", "review"),           // skills (null = inherit)
    MemoryScope.PROJECT,                   // memory scope (null = inherit)
    List.of("my-mcp-server")              // MCP servers (null = inherit)
);

// Shorthand: description + prompt only (all other fields inherit from parent)
AgentDefinition simple = new AgentDefinition(
    "Summarizes text",
    "You are a concise summarizer."
);

// Backwards-compatible: description, prompt, tools, model
AgentDefinition compat = new AgentDefinition(
    "Reviews code",
    "You are a code reviewer.",
    List.of("Read", "Grep"),
    "sonnet"   // model can be "sonnet", "opus", "haiku", or full model ID
);
```

**フィールド：**

| フィールド | 型 | 説明 |
|-------|------|-------------|
| `description` | `String` | Claude に示される人間可読な説明 |
| `prompt` | `String` | エージェントの振る舞いを定めるシステムプロンプト |
| `tools` | `List<String>`（null 可） | 許可するツール名。null なら親のツールを継承 |
| `disallowedTools` | `List<String>`（null 可） | そのエージェントが使えないツール。null なら該当なし |
| `model` | `String`（null 可） | モデルのエイリアス（"sonnet"、"opus"、"haiku"、"inherit"）または完全なモデル ID |
| `skills` | `List<String>`（null 可） | そのエージェントが使える skill 名。null なら継承 |
| `memory` | `MemoryScope`（null 可） | メモリスコープ。null なら親から継承 |
| `mcpServers` | `List<Object>`（null 可） | MCP サーバーの参照（名前またはインライン設定）。null なら継承 |
| `initialPrompt` | `String`（null 可） | エージェント開始時に送られる初期プロンプト |
| `maxTurns` | `Integer`（null 可） | そのエージェントの最大ターン数。null なら無制限 |
| `background` | `Boolean`（null 可） | そのエージェントをバックグラウンドで実行する |
| `effort` | `String`（null 可） | エフォートレベル：`"low"`、`"medium"`、`"high"`、`"xhigh"`、`"max"`。`"xhigh"` は Opus 4.7 専用で、他のモデルでは `"high"` にフォールバックします。[`EffortLevel`](feature-configuration-options.md#effortlevel-列挙型) 列挙型も参照してください。 |
| `permissionMode` | `String`（null 可） | そのエージェントの権限モード |

**model フィールド：** `model` フィールドは短いエイリアス（`"sonnet"`、`"opus"`、`"haiku"`、
`"inherit"`）または完全なモデル ID（例：`"claude-sonnet-4-5"`）を受け付けます。

### MemoryScope 列挙型

エージェントがどのメモリスコープで動作するかを制御します：

```java
import in.vidyalai.claude.sdk.types.config.MemoryScope;

MemoryScope.USER     // "user" — user-level memory
MemoryScope.PROJECT  // "project" — project-scoped memory
MemoryScope.LOCAL    // "local" — local/session-scoped memory
```

## インラインのエージェント定義

`ClaudeAgentOptions` を通じてプログラムからエージェントを登録します：

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.config.AgentDefinition;

AgentDefinition codeReviewer = new AgentDefinition(
    "Reviews code for best practices and potential issues",
    """
    You are a code reviewer. Analyze code for bugs, performance issues,
    security vulnerabilities, and adherence to best practices.
    Provide constructive feedback.
    """,
    List.of("Read", "Grep"),
    "sonnet"   // model can be "sonnet", "opus", "haiku", or full model ID
);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(Map.of("code-reviewer", codeReviewer))
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();
    client.sendMessage("Use the code-reviewer agent to review MyClass.java");

    for (Message msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### 複数のエージェント

1 つのセッションで複数のエージェントを定義できます：

```java
AgentDefinition analyzer = new AgentDefinition(
    "Analyzes code structure and patterns",
    "You are a code analyzer. Examine code structure, patterns, and architecture.",
    List.of("Read", "Grep", "Glob"),
    null  // inherit model from parent
);

AgentDefinition tester = new AgentDefinition(
    "Creates and runs tests",
    "You are a testing expert. Write comprehensive tests and ensure code quality.",
    List.of("Read", "Write", "Bash"),
    "sonnet"
);

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(Map.of(
        "analyzer", analyzer,
        "tester", tester
    ))
    .build();
```

## ファイルシステムベースのエージェント

エージェントは `settingSources` を使って、ディスク上の markdown ファイルから読み込むこともできます。
エージェント定義ファイルをプロジェクトディレクトリの `.claude/agents/` に置きます：

```
.claude/
  agents/
    code-reviewer.md
    test-writer.md
```

そのうえでファイルシステムからのエージェント読み込みを有効にします：

```java
import in.vidyalai.claude.sdk.types.config.SettingSource;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .settingSources(List.of(SettingSource.PROJECT))
    .cwd(Path.of("/path/to/project"))
    .build();

try (ClaudeSDKClient client = new ClaudeSDKClient(options)) {
    client.connect();
    // Agents defined in .claude/agents/*.md are now available
}
```

どのエージェントが読み込まれたかは、`SystemMessage` の init イベントで確認できます：

```java
for (Message msg : client.receiveResponse()) {
    if (msg instanceof SystemMessage system && "init".equals(system.subtype())) {
        List<String> agents = system.get("agents");
        System.out.println("Loaded agents: " + agents);
    }
}
```

## 大きなエージェント定義

エージェントは CLI 引数としてではなく、SDK 制御プロトコルの initialize リクエスト（stdin 経由）で
送られます。つまりエージェント定義に**サイズ上限はありません** —— 260KB 超のエージェントデータも
安全に渡せます。

この挙動は TypeScript と Python の SDK 実装と一致しており、プラットフォーム固有のコマンドライン
引数長の制限（ARG_MAX）を回避します。

```java
// Large agents work reliably via stdin
Map<String, AgentDefinition> agents = new HashMap<>();
for (int i = 0; i < 20; i++) {
    String largePrompt = "You are agent #" + i + ". " + "x".repeat(13 * 1024);
    agents.put("agent-" + i, new AgentDefinition("Agent " + i, largePrompt));
}

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(agents)
    .maxTurns(1)
    .build();

// Works for both query() and createClient()
for (Message msg : ClaudeSDK.query("List available agents", options)) {
    // ...
}
```

## サブエージェントの出力の観測

サブエージェントは自分自身の会話を進めますが、親のメッセージストリームに届くのはその一部だけです。
届くものは通常の `AssistantMessage` / `UserMessage` オブジェクトとして現れ、その
`parentToolUseId` はそのサブエージェントを生成した Agent の `tool_use` ブロックの id です ——
サブエージェントのメッセージと本体の会話のメッセージを見分け、複数が動いているときにどのサブ
エージェントのものかを判断するのは、このフィールドです。

既定ではサブエージェントの `tool_use` と `tool_result` ブロックだけが転送されます。進行している
ことを示すには十分ですが、何を言ったかを描画するには足りません。`forwardSubagentText(true)` を
設定すると、テキストブロックと思考ブロックも同じように転送されます：

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .forwardSubagentText(true)
    .agents(Map.of("greeter", greeter))
    .build();

for (Message msg : ClaudeSDK.query(prompt, options)) {
    if (msg instanceof AssistantMessage assistant && assistant.parentToolUseId() != null) {
        // From a subagent — attribute it to the spawning Agent tool_use id.
        System.out.println("[" + assistant.parentToolUseId() + "] " + assistant.getTextContent());
    }
}
```

このオプションは CLI フラグではなく `initialize` 制御リクエストで、しかも有効なときにだけ送られる
ため、古い CLI には影響しません。

*完了した*サブエージェントのトランスクリプト全体を読むのは別の経路です —— `listSubagents()` と
`getSubagentMessages()` については[セッション履歴](./feature-session-history.md)を参照してください。
それらの結果も同じ `parentToolUseId` を持ち、入れ子のサブエージェントには `parentAgentId` も付きます。

## バックグラウンドのサブエージェントとコールバック

`run_in_background` で起動したサブエージェントは、親のターンが終わった後も動き続け、終わるとその完了が
親を起こして後続のターンが走ります。フック、`canUseTool` コールバック、SDK MCP サーバーのいずれかを
持つ単発の `ClaudeSDK.query(...)` では、後続ターンでのそれらのコールバックは stdin がまだ開いている
間しか動きません。そのため SDK は最初の `result` で stdin を閉じません：

- `local_agent` または `local_workflow` のタスクがまだ進行中の間は、stdin は開いたままです。
- CLI がセッション状態を報告する場合、stdin は result の後の `idle` で閉じられるので、result の
  *直前*に終わったサブエージェントが求める後続ターンも処理されます。
- ターン間の待ち時間は `CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS`（既定 10 分、`0` で無制限）で制限されます。

SDK は `CLAUDE_CODE_SDK_READS_SESSION_STATE` で CLI にセッション状態を求めます。Claude Code 2.1.283
はまだこれに対応していません。そのような CLI では、タスクが何も進行中でない最初の result で stdin が
閉じられ、後続ターンのコールバックが動くかどうかはタイミング次第になります。`env()` で
`CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1` を設定すると、現時点でも CLI に状態を報告させられますが、
その代わりに `session_state_changed` フレームがあなたのイテレータにも届くようになります：

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .agents(Map.of("worker", worker))
    .hooks(hooks)
    .env(Map.of("CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS", "1"))
    .build();
```

`ClaudeSDKClient` は影響を受けません。切断するまで stdin を開いたままにするからです。規則の全体は
[アーキテクチャ → stdin のライフサイクル](./architecture.md#stdin-のライフサイクルと実行の終了)にあります。

## サンプル

実行できる完全なデモについてはサンプルファイルを参照してください：

- [`AgentsExample.java`](../../examples/src/main/java/examples/AgentsExample.java) —— コードレビュアー、ドキュメント作成者、複数エージェント
- [`FilesystemAgentsExample.java`](../../examples/src/main/java/examples/FilesystemAgentsExample.java) —— `.claude/agents/` のファイルからエージェントを読み込む
- [`LargeAgentsExample.java`](../../examples/src/main/java/examples/LargeAgentsExample.java) —— 260KB 超のエージェントペイロードによるストレステスト
- [`ForwardSubagentTextExample.java`](../../examples/src/main/java/examples/ForwardSubagentTextExample.java) —— サブエージェントのテキスト転送をオフ／オンにした同じ実行
- [`BackgroundAgentHooksExample.java`](../../examples/src/main/java/examples/BackgroundAgentHooksExample.java) —— バックグラウンドサブエージェントの完了が起こす後続ターンで処理される `PreToolUse` フック

## 関連項目

- [設定オプション](./feature-configuration-options.md) —— `agents` と `settingSources` オプション
- [対話的な会話](./feature-interactive-conversations.md) —— マルチターンのセッションでエージェントを使う
