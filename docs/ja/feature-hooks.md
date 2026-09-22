# フックシステム

Claude の会話におけるライフサイクルイベントを傍受し、それに応答します。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../feature-hooks.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

## 概要

フックを使うと、会話のライフサイクルの特定の地点で独自のコードを実行できます。リッスンできる
フックイベントは 10 種類あります。

## フックイベント

- **PRE_TOOL_USE** —— ツール実行の前
- **POST_TOOL_USE** —— ツールが成功裏に実行された後
- **POST_TOOL_USE_FAILURE** —— ツールの実行が失敗した後
- **USER_PROMPT_SUBMIT** —— ユーザーがメッセージを送信したとき
- **STOP** —— セッションが停止したとき
- **SUBAGENT_START** —— サブエージェントが開始したとき
- **SUBAGENT_STOP** —— サブエージェントが停止したとき
- **PRE_COMPACT** —— メッセージの圧縮の前
- **NOTIFICATION** —— 通知イベントが発生したとき
- **PERMISSION_REQUEST** —— 権限が要求されたとき

## 基本的な使い方

```java
var options = ClaudeAgentOptions.builder()
    .hooks(Map.of(
        HookEvent.PRE_TOOL_USE, List.of(
            new HookMatcher(null, "Read", context -> {
                System.out.println("About to read: " + context.input());
                return CompletableFuture.completedFuture(
                    HookOutput.logs(List.of("Pre-tool log"))
                );
            })
        )
    ))
    .build();
```

## HookMatcher

```java
new HookMatcher(
    String toolName,           // null for all tools
    String matchPattern,       // Tool name pattern
    Function<HookContext, CompletableFuture<HookOutput>> handler
)
```

> **ディスパッチ順序：** 同じイベントに複数のマッチャーが登録されている場合、CLI は一致するフック
> コールバックを順番にではなく**並行に**（並列で）ディスパッチします。各フックは互いに独立するよう
> 設計してください —— あるフックが完了してから別のフックが始まることを当てにしないでください
> （たとえば、ゲートの順序を前提としたレートリミッタのフックを連鎖させないこと）。

## フック入力のフィールド

ツールに関係するフック入力（`PreToolUseHookInput`、`PostToolUseHookInput`、
`PostToolUseFailureHookInput`、`PermissionRequestHookInput`）はすべて、サブエージェントの文脈を
表す 2 つの省略可能なフィールドを持ちます：

| フィールド | 型 | 説明 |
|-------|------|-------------|
| `agentId` | `@Nullable String` | サブエージェントの識別子。タスクから生成されたサブエージェントの内部でのみ存在し、メインスレッドでは null です。 |
| `agentType` | `@Nullable String` | エージェントの型名（例：`"general-purpose"`）。サブエージェントの内部、または `--agent` で起動したメインスレッドで存在します。 |

```java
new HookMatcher(null, null, context -> {
    PreToolUseHookInput input = (PreToolUseHookInput) context.input();
    if (input.agentId() != null) {
        System.out.println("Tool used inside sub-agent: " + input.agentId());
    }
    return CompletableFuture.completedFuture(HookOutput.empty());
})
```

## HookOutput

```java
// Empty output
HookOutput.empty()

// With logs
HookOutput.logs(List.of("Log message"))

// With messages
HookOutput.messages(List.of(
    Map.of("role", "user", "content", "Message")
))

// With permission updates
HookOutput.permissionUpdates(List.of(update))

// Combined
HookOutput.builder()
    .logs(List.of("Log"))
    .messages(List.of(message))
    .build()
```

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
HookEvent.POST_TOOL_USE, List.of(
    new HookMatcher(null, "Bash", context -> {
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
    })
)
```

## 権限判断 `"defer"`

`PreToolUse` フックは（`PermissionDecision.DEFER` / `PreToolUseHookSpecificOutput` を通じて）
`permissionDecision: "defer"` を返すことで、ツールを実行せずに実行を停止できます。CLI は保留された
呼び出しを `ResultMessage.deferredToolUse` に載せるので、SDK の利用側がそれを調べ、再開するかどうか
判断できます。

```java
HookEvent.PRE_TOOL_USE, List.of(
    new HookMatcher(null, "Bash", context -> {
        PreToolUseHookInput input = (PreToolUseHookInput) context.input();
        if (looksDangerous(input.toolInput())) {
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
    })
)

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
フックを登録しなくても、可観測性（フックの発火をすべて記録する）を確保できるので便利です。

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
        var options = ClaudeAgentOptions.builder()
            .hooks(Map.of(
                // Log all tool uses
                HookEvent.PRE_TOOL_USE, List.of(
                    new HookMatcher(null, null, context -> {
                        PreToolUseHookInput input = (PreToolUseHookInput) context.input();
                        System.out.println("Tool: " + input.toolName());
                        return CompletableFuture.completedFuture(
                            HookOutput.logs(List.of(
                                "Executing: " + input.toolName()
                            ))
                        );
                    })
                ),
                
                // Track tool results
                HookEvent.POST_TOOL_USE, List.of(
                    new HookMatcher(null, null, context -> {
                        PostToolUseHookInput input = (PostToolUseHookInput) context.input();
                        System.out.println("Result: " + input.result());
                        return CompletableFuture.completedFuture(
                            HookOutput.empty()
                        );
                    })
                ),
                
                // Handle errors
                HookEvent.POST_TOOL_USE_FAILURE, List.of(
                    new HookMatcher(null, null, context -> {
                        PostToolUseFailureHookInput input = 
                            (PostToolUseFailureHookInput) context.input();
                        System.err.println("Error: " + input.error());
                        return CompletableFuture.completedFuture(
                            HookOutput.logs(List.of("Tool failed"))
                        );
                    })
                )
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

## 関連項目
- [設定オプション](./feature-configuration-options.md#フック)
- [Hooks のサンプル](../../examples/src/main/java/examples/Hooks.java)
