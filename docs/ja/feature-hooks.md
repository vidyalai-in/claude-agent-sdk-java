# フックシステム

Claude の会話におけるライフサイクルイベントを傍受し、それに応答します。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../feature-hooks.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

## 概要

フックを使うと、エージェントループの特定の地点で独自のコードを実行できます。`HookEvent` に対して
コールバックを登録すると、CLI はその地点に達したときに SDK へフック要求を送り、SDK は型付きの
`HookInput` を渡してコールバックを呼び出します。コールバックは、許可、ブロック、またはコンテキストの追加を
行える `HookOutput` で応答します。

コールバックを登録できるフックイベントは 10 種類あります。

## フックイベント

`HookEvent`（パッケージ `in.vidyalai.claude.sdk.types.hook`）：

| 定数 | イベント名 | 発火するタイミング | 入力の型 |
|----------|------------|---------------|------------|
| `PRE_TOOL_USE` | `PreToolUse` | ツール実行の前 | `PreToolUseHookInput` |
| `POST_TOOL_USE` | `PostToolUse` | ツールが成功した後 | `PostToolUseHookInput` |
| `POST_TOOL_USE_FAILURE` | `PostToolUseFailure` | ツールが失敗した後 | `PostToolUseFailureHookInput` |
| `USER_PROMPT_SUBMIT` | `UserPromptSubmit` | プロンプトが送信されたとき | `UserPromptSubmitHookInput` |
| `STOP` | `Stop` | セッションが停止しようとしているとき | `StopHookInput` |
| `SUBAGENT_START` | `SubagentStart` | サブエージェントが開始したとき | `SubagentStartHookInput` |
| `SUBAGENT_STOP` | `SubagentStop` | サブエージェントが停止したとき | `SubagentStopHookInput` |
| `PRE_COMPACT` | `PreCompact` | コンテキストの圧縮の前 | `PreCompactHookInput` |
| `NOTIFICATION` | `Notification` | 通知が発生したとき | `NotificationHookInput` |
| `PERMISSION_REQUEST` | `PermissionRequest` | 権限が要求されたとき | `PermissionRequestHookInput` |

## 基本的な使い方

```java
HookMatcher.HookCallback logReads = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse) {
        System.out.println("About to read: " + preToolUse.toolInput().get("file_path"));
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};

var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(
        HookEvent.PRE_TOOL_USE, List.of(new HookMatcher("Read", List.of(logReads)))
    ))
    .build();
```

## HookMatcher

```java
record HookMatcher(
    @Nullable String matcher,        // which tools/events to match; null matches all
    List<HookCallback> hooks,        // callbacks to run
    @Nullable Double timeoutSeconds  // timeout for all hooks in this matcher (CLI default: 60)
)

new HookMatcher(List<HookCallback> hooks)                  // matcher = null
new HookMatcher(String matcher, List<HookCallback> hooks)  // timeoutSeconds = null
```

ツールのイベントでは、`matcher` はツール名（`"Bash"`）、`|` で連結した複数の名前（`"Write|MultiEdit|Edit"`）、
または `"*"` です。各イベントのマッチャーの構文については
[Claude Code のフックリファレンス](https://docs.anthropic.com/en/docs/claude-code/hooks#structure)
を参照してください。

コールバックは `HookMatcher.HookCallback` です：

```java
@FunctionalInterface
interface HookCallback extends BiFunction<HookInput, HookContext, CompletableFuture<HookOutput>> {}
```

コールバックは型付きの入力と `HookContext` を受け取ります：

```java
record HookContext(
    @Nullable String toolUseId,  // the tool use ID, for tool events
    @Nullable Object signal      // reserved for future abort support; always null
)
```

SDK は、返された future の完了を待ってから CLI に応答します。同期的な処理では
`CompletableFuture.completedFuture(...)` を返してください。

> **ディスパッチ順序：** 同じイベントに複数のマッチャーが登録されている場合、CLI は一致するフック
> コールバックを順番にではなく**並行に**（並列で）ディスパッチします。各フックは互いに独立するよう
> 設計してください —— あるフックが完了してから別のフックが始まることを当てにしないでください
> （たとえば、ゲートの順序を前提としたレートリミッタのフックを連鎖させないこと）。

## フック入力

`HookInput` は sealed インターフェースです。具体的なレコードに対して `instanceof` で分岐してください。
すべての入力は次のフィールドを持ちます：

| アクセサ | 型 | 説明 |
|----------|------|-------------|
| `hookEventName()` | `String` | イベント名（例：`"PreToolUse"`） |
| `sessionId()` | `String` | セッション ID |
| `transcriptPath()` | `String` | セッションのトランスクリプトへのパス |
| `cwd()` | `String` | 作業ディレクトリ |
| `permissionMode()` | `@Nullable String` | 現在の権限モード |
| `promptId()` | `@Nullable String` | 処理中のユーザープロンプトの UUID。OpenTelemetry イベントの `prompt.id` 属性と一致します。最初のユーザー入力までは null です |
| `effort()` | `@Nullable HookEffort` | 現在のターンで有効な effort。`HookEffort(String level)` として表されます（例：`"low"`、`"high"`、`"max"`。選択されたモデルに合わせて引き下げられた後の値）。effort をサポートするモデルで、ツール使用のコンテキスト内で発火するフックに存在します |

イベント固有のフィールド：

| 入力 | フィールド |
|-------|--------|
| `PreToolUseHookInput` | `toolName`、`toolInput`、`toolUseId`、`agentId`、`agentType` |
| `PostToolUseHookInput` | `toolName`、`toolInput`、`toolResponse`、`toolUseId`、`agentId`、`agentType`、`durationMs` |
| `PostToolUseFailureHookInput` | `toolName`、`toolInput`、`toolUseId`、`error`、`isInterrupt`、`agentId`、`agentType`、`durationMs` |
| `PermissionRequestHookInput` | `toolName`、`toolInput`、`permissionSuggestions`、`agentId`、`agentType` |
| `UserPromptSubmitHookInput` | `prompt` |
| `StopHookInput` | `stopHookActive`、`lastAssistantMessage`、`backgroundTasks`、`sessionCrons` |
| `SubagentStartHookInput` | `agentId`、`agentType` |
| `SubagentStopHookInput` | `stopHookActive`、`agentId`、`agentTranscriptPath`、`agentType`、`lastAssistantMessage`、`backgroundTasks`、`sessionCrons` |
| `PreCompactHookInput` | `trigger`（`CompactTriggerType`）、`customInstructions` |
| `NotificationHookInput` | `message`、`title`、`notificationType` |

CLI が省略することのあるフィールドは、存在しない場合 `null` になります：`permissionMode`、`promptId`、
`effort`、ツール入力の `agentId`／`agentType`、`isInterrupt`、`durationMs`、`lastAssistantMessage`、
`backgroundTasks`、`sessionCrons`、`permissionSuggestions`、`title`、`customInstructions`。

### サブエージェントの文脈

ツールに関係する入力（`PreToolUseHookInput`、`PostToolUseHookInput`、
`PostToolUseFailureHookInput`、`PermissionRequestHookInput`）は、サブエージェントの文脈を
表す 2 つの省略可能なフィールドを持ちます：

| フィールド | 型 | 説明 |
|-------|------|-------------|
| `agentId` | `@Nullable String` | サブエージェントの識別子。タスクから生成されたサブエージェントの内部でのみ存在し、メインスレッドでは null です。 |
| `agentType` | `@Nullable String` | エージェントの型名（例：`"general-purpose"`）。サブエージェントの内部、または `--agent` で起動したメインスレッドで存在します。 |

```java
HookMatcher.HookCallback subagentAware = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse && preToolUse.agentId() != null) {
        System.out.println("Tool used inside sub-agent: " + preToolUse.agentId());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};
```

### ツールの実行時間

`PostToolUseHookInput.durationMs()` と `PostToolUseFailureHookInput.durationMs()` は、ツールの実行時間を
ミリ秒で報告します。権限プロンプトとフックにかかった時間は含みません。

### Stop と SubagentStop でのバックグラウンド処理

`StopHookInput` と `SubagentStopHookInput` は、セッション（またはサブエージェント）が停止する時点で
まだ実行中のものを報告します：

- `lastAssistantMessage` —— 停止前の最後のアシスタントメッセージのテキスト。
- `backgroundTasks` —— 進行中のバックグラウンド処理。`BackgroundTaskSummary` レコードとして表され、
  `id`、`type`（例：`"shell"`、`"subagent"`、`"monitor"`、`"workflow"`）、`status`、`description` に加えて、
  `command`（シェルタスク）、`agentType`（サブエージェントタスク）、`server` と `tool`（モニター／MCP タスク）、
  `name`（ワークフロータスク）を持ちます。進行中のものがなければ空です。
- `sessionCrons` —— 後でセッションを再開する、セッションスコープのスケジュール済みウェイクアップ。
  `SessionCronSummary` レコードとして表され、`id`、`schedule`（cron 式）、`recurring`（1 回限りの
  ウェイクアップでは `false`）、`prompt` を持ちます。

サブエージェントの場合、どちらのリストも親セッションをスコープとします。`description`、`command`、および
cron の `prompt` は 1000 文字で打ち切られ、切り詰められた値は文字列内の `"… [+N chars]"` マーカーで終わります。

```java
HookMatcher.HookCallback onStop = (input, context) -> {
    if (input instanceof StopHookInput stop && stop.backgroundTasks() != null) {
        for (BackgroundTaskSummary task : stop.backgroundTasks()) {
            System.out.printf("Still running: %s (%s, %s)%n", task.description(), task.type(), task.status());
        }
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};
```

## HookOutput

コールバックは `HookOutput`（パッケージ `in.vidyalai.claude.sdk.types.hook.output`）を返します。これは
ビルダーで組み立てます：

```java
HookOutput.empty()                 // no fields: the CLI proceeds as normal
HookOutput.async(30_000)           // defer: {"async": true, "asyncTimeout": 30000}

HookOutput.builder()
    .shouldContinue(false)         // "continue": whether Claude proceeds after the hook
    .stopReason("Budget exhausted")// shown when continue is false
    .suppressOutput(true)          // hide stdout from transcript mode
    .decision("block")             // "block" to indicate blocking behavior
    .systemMessage("Heads up")     // warning displayed to the user
    .reason("Blocked by policy")   // feedback for Claude about the decision
    .hookSpecificOutput(specific)  // event-specific controls, see below
    .build();
```

送信されるのは設定したフィールドだけです。`async` が設定されている場合は、`async` と `asyncTimeout` だけが
送信されます。

## フック固有の出力

`HookSpecificOutput` は、イベントごとに 1 つのレコードを持つ sealed インターフェースです。各レコードは
`hookEventName` と、設定したフィールドとともにシリアライズされます（`null` のフィールドは省略されます）：

| レコード | フィールド |
|--------|--------|
| `PreToolUseHookSpecificOutput` | `permissionDecision`（`PermissionDecision`）、`permissionDecisionReason`、`updatedInput`、`additionalContext` |
| `PostToolUseHookSpecificOutput` | `additionalContext`、`updatedToolOutput`、`updatedMCPToolOutput` |
| `PostToolUseFailureHookSpecificOutput` | `additionalContext` |
| `UserPromptSubmitHookSpecificOutput` | `additionalContext`、`sessionTitle`、`suppressOriginalPrompt` |
| `NotificationHookSpecificOutput` | `additionalContext` |
| `SubagentStartHookSpecificOutput` | `additionalContext` |
| `PermissionRequestHookSpecificOutput` | `decision`（Map） |
| `StopHookSpecificOutput` | `additionalContext` |
| `SubagentStopHookSpecificOutput` | `additionalContext` |
| `SessionStartHookSpecificOutput` | `additionalContext`、`initialUserMessage`、`sessionTitle`、`watchPaths`、`reloadSkills` |
| `UserPromptExpansionHookSpecificOutput` | `additionalContext`、`suppressOriginalPrompt` |
| `PostToolBatchHookSpecificOutput` | `additionalContext` |
| `PermissionDeniedHookSpecificOutput` | `retry` |
| `PreModelSwitchHookSpecificOutput` | `permissionDecision`、`permissionDecisionReason` |
| `PostModelSwitchHookSpecificOutput` | `additionalContext` |
| `ElicitationHookSpecificOutput` | `action`（`ElicitationAction`）、`content` |
| `ElicitationResultHookSpecificOutput` | `action`（`ElicitationAction`）、`content` |
| `CwdChangedHookSpecificOutput` | `watchPaths` |
| `FileChangedHookSpecificOutput` | `watchPaths` |
| `WorktreeCreateHookSpecificOutput` | `worktreePath`（必須） |
| `MessageDisplayHookSpecificOutput` | `displayContent` |

> **コールバックが使えるのはどれか。** SDK のコールバックは上記 10 個の `HookEvent` にのみ登録できるため、
> コールバックが受け取りうるイベントに応答するのは最初の 9 個のレコードだけです。残り（`SessionStart` から
> `MessageDisplay` まで）は、`HookEvent` にまだ含まれていないイベント向けの出力を表します。これらは、同じ形を
> 定義している Python SDK および TypeScript SDK とのパリティのために型付けされています。

フィールドに関する注意：

- **`UserPromptSubmitHookSpecificOutput`** —— `sessionTitle` はセッションのタイトルを設定します。
  `suppressOriginalPrompt` は、出力の `decision` が `"block"` のとき、ブロックメッセージから元のプロンプトを
  省きます。1 引数のコンストラクタ `(additionalContext)` も残されています。
- **`StopHookSpecificOutput` / `SubagentStopHookSpecificOutput`** —— `additionalContext` はモデル（または
  サブエージェント）に届けられるエラーではないフィードバックです。会話は続行するので、モデルはそれに基づいて
  行動できます。
- **`SessionStartHookSpecificOutput`** —— `initialUserMessage` はセッションの最初のユーザーメッセージになります。
  `sessionTitle` は、セッションが `clear` または `compact` から開始された場合は無視されます。`watchPaths` は
  FileChanged イベントのために監視する絶対パスです。`reloadSkills` は、SessionStart フックの完了後にスキルと
  コマンドのディレクトリを再スキャンします。
- **`PermissionDeniedHookSpecificOutput`** —— `retry` は、拒否されたツール呼び出しを再試行してよいことを
  モデルに伝えます。分類器の判定を伴わない拒否では無視されます。
- **`PreModelSwitchHookSpecificOutput`** —— `ALLOW` は切り替えを進め、`DENY` は取り消します。`ASK` は
  対話型セッションの外では拒否として扱われます。コンストラクタは `PermissionDecision.DEFER` を受け付けません。
- **`PostModelSwitchHookSpecificOutput`** —— `additionalContext` は、新しいモデルが処理する次のリクエストとともに
  モデルに届きます。
- **`ElicitationHookSpecificOutput`** —— MCP の elicitation 要求を受諾、辞退、または取り消します。`content` は
  フォームのフィールド値を保持し、`ElicitationAction.ACCEPT` の場合にのみ使われます。
  `ElicitationResultHookSpecificOutput` は、応答が MCP サーバーに届く前にアクションまたは内容を上書きします。
- **`CwdChangedHookSpecificOutput` / `FileChangedHookSpecificOutput`** —— `watchPaths` は現在の動的な監視
  リストを置き換えます。
- **`MessageDisplayHookSpecificOutput`** —— 表示専用です：`displayContent` は、保存されたメッセージやモデルが
  見る内容を変えずに、画面上のテキストを置き換えます。省略すると元のテキストが表示されます。

### ツール呼び出しのブロック

```java
HookMatcher.HookCallback blockRm = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse
            && String.valueOf(preToolUse.toolInput().get("command")).contains("rm -rf")) {
        return CompletableFuture.completedFuture(HookOutput.builder()
            .hookSpecificOutput(new PreToolUseHookSpecificOutput(
                PermissionDecision.DENY,
                "rm -rf is not allowed",
                null,   // updatedInput
                null))  // additionalContext
            .build());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};

var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(HookEvent.PRE_TOOL_USE, List.of(new HookMatcher("Bash", List.of(blockRm)))))
    .build();
```

### Stop フックからモデルの処理を続けさせる

```java
HookMatcher.HookCallback keepGoing = (input, context) -> {
    if (input instanceof StopHookInput stop && !stop.stopHookActive()) {
        return CompletableFuture.completedFuture(HookOutput.builder()
            .hookSpecificOutput(new StopHookSpecificOutput("The test suite still fails; fix it before stopping."))
            .build());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};
```

`stopHookActive()` を確認することで、モデルを延々と作業に戻し続けることを防げます。これは、セッションが
すでに Stop フックによって続行している場合に `true` になります。

## PostToolUse による出力の差し替え

`PostToolUseHookSpecificOutput` を使うと、`PostToolUse` フックがツールの出力をモデルに届く前に
差し替えられます。

```java
record PostToolUseHookSpecificOutput(
    @Nullable String additionalContext,    // extra context for the model
    @Nullable Object updatedToolOutput,    // replacement for any tool's output
    @Nullable Object updatedMCPToolOutput  // replacement for MCP tool output only
)
```

- **`updatedToolOutput`** —— 任意のツール（組み込みを含む）の出力を差し替えます。組み込みツール
  では、値がそのツールの出力スキーマに一致していなければなりません（例：`Bash` なら
  `{"stdout": ..., "stderr": ..., "interrupted": ...}`）。形が合わない場合は拒否され、元の出力が
  保持されます。
- **`updatedMCPToolOutput`** —— MCP ツールの出力のみを差し替えます。すべてのツールで機能する
  `updatedToolOutput` の使用を推奨します。
- `updatedToolOutput` が存在する前に書かれたコードのために、後方互換の 2 引数コンストラクタ
  `(additionalContext, updatedMCPToolOutput)` が残されています。

```java
HookMatcher.HookCallback redact = (input, context) -> {
    // Redact secrets from Bash output before the model sees it.
    Map<String, Object> redacted = Map.of(
        "stdout", "[redacted]",
        "stderr", "",
        "interrupted", false
    );
    return CompletableFuture.completedFuture(
        HookOutput.builder()
            .hookSpecificOutput(new PostToolUseHookSpecificOutput(null, redacted, null))
            .build()
    );
};

var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(HookEvent.POST_TOOL_USE, List.of(new HookMatcher("Bash", List.of(redact)))))
    .build();
```

## 権限判断 `"defer"`

`PreToolUse` フックは（`PermissionDecision.DEFER` / `PreToolUseHookSpecificOutput` を通じて）
`permissionDecision: "defer"` を返すことで、ツールを実行せずに実行を停止できます。CLI は保留された
呼び出しを `ResultMessage.deferredToolUse` に載せるので、SDK の利用側がそれを調べ、再開するかどうか
判断できます。

```java
HookMatcher.HookCallback deferRisky = (input, context) -> {
    if (input instanceof PreToolUseHookInput preToolUse && looksDangerous(preToolUse.toolInput())) {
        return CompletableFuture.completedFuture(
            HookOutput.builder()
                .hookSpecificOutput(new PreToolUseHookSpecificOutput(
                    PermissionDecision.DEFER,
                    "Needs operator review",
                    null,
                    null))
                .build()
        );
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
};

// Caller side — inspect the deferred call from the result message.
for (Message msg : ClaudeSDK.query(prompt, options)) {
    if (msg instanceof ResultMessage r && r.deferredToolUse() != null) {
        DeferredToolUse d = r.deferredToolUse();
        System.out.printf("Deferred %s (id=%s) input=%s%n", d.name(), d.id(), d.input());
    }
}
```

`DeferredToolUse` は `id`、`name`、`input` を保持します。`ResultMessage` の完全な形については
[メッセージ型](./feature-message-types.md#resultmessage)を参照してください。

## ストリーム上のフックライフサイクルイベント

`ClaudeAgentOptions` に `includeHookEvents(true)` を設定すると、フックのライフサイクルイベントを
`HookEventMessage` オブジェクトとしてメッセージストリームでも受け取れます。観測したいイベントごとに
フックを登録しなくても、可観測性（フックの発火をすべて記録する）を確保できるので便利です。CLI は、
この設定にかかわらず `SessionStart` と `Setup` のフックイベントを送出します。

```java
var options = ClaudeAgentOptions.builder()
    .includeHookEvents(true)
    .hooks(Map.of(/* still register hooks normally */))
    .build();

for (Message msg : ClaudeSDK.query(prompt, options)) {
    if (msg instanceof HookEventMessage hook) {
        // subtype is "hook_started" or "hook_response"
        System.out.printf("[%s] %s session=%s%n",
            hook.subtype(), hook.hookEventName(), hook.sessionId());
        // Full raw payload (output, exit_code, outcome on hook_response)
        Object outcome = hook.get("outcome");
        if (outcome != null) {
            System.out.println("  outcome: " + outcome);
        }
    }
}
```

`HookEventMessage` のフィールド：

| フィールド | 型 | 説明 |
|-------|------|-------------|
| `subtype` | `String` | フックの開始時は `"hook_started"`、完了時は `"hook_response"` |
| `data` | `Map<String, Object>` | CLI から届いた生のイベント辞書全体（`hook_response` では `output`、`exit_code`、`outcome`） |
| `hookEventName` | `String` | フックイベント名（例：`"PreToolUse"`、`"PostToolUse"`、`"Stop"`） |
| `sessionId` | `@Nullable String` | このイベントが属するセッション ID |
| `uuid` | `@Nullable String` | イベントの一意な ID |

`HookEventMessage.type()` は `"system"` を返しますが、`instanceof SystemMessage` には一致**しません**。
`HookEventMessage` で直接分岐してください。

## 完全なサンプル

```java
public class HooksExample {
    public static void main(String[] args) {
        // Log every tool call before it runs
        HookMatcher.HookCallback logPre = (input, context) -> {
            if (input instanceof PreToolUseHookInput pre) {
                System.out.println("Tool: " + pre.toolName());
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        // Track tool results and timing
        HookMatcher.HookCallback logPost = (input, context) -> {
            if (input instanceof PostToolUseHookInput post) {
                System.out.println("Result: " + post.toolResponse() + " in " + post.durationMs() + "ms");
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        // Handle errors
        HookMatcher.HookCallback logFailure = (input, context) -> {
            if (input instanceof PostToolUseFailureHookInput failure) {
                System.err.println("Error: " + failure.error());
            }
            return CompletableFuture.completedFuture(HookOutput.empty());
        };

        var options = ClaudeAgentOptions.builder()
            .hooks(Map.of(
                HookEvent.PRE_TOOL_USE, List.of(new HookMatcher("*", List.of(logPre))),
                HookEvent.POST_TOOL_USE, List.of(new HookMatcher("*", List.of(logPost))),
                HookEvent.POST_TOOL_USE_FAILURE, List.of(new HookMatcher("*", List.of(logFailure)))
            ))
            .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect("List files in current directory");
            for (var msg : client.receiveResponse()) {
                // Process
            }
        }
    }
}
```

## バックグラウンドのサブエージェント終了後のフック

単発の `ClaudeSDK.query(...)` では、バックグラウンドのサブエージェントの完了が起こすターンでのフックは、
CLI がそれを呼び出す時点で stdin がまだ開いている場合にのみ実行されます。SDK は最初の result ではなく、
実行が終わるまで stdin を開いたままにします。規則と CLI バージョンに関する注意点については
[エージェント → バックグラウンドのサブエージェントとコールバック](./feature-agents.md#バックグラウンドのサブエージェントとコールバック)
を、実行可能なデモについては `BackgroundAgentHooksExample` を参照してください。

## 関連項目
- [設定オプション](./feature-configuration-options.md#フック)
- [権限](./feature-permissions.md)
- [Hooks のサンプル](../../examples/src/main/java/examples/Hooks.java)
