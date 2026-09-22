# 権限システム

ツール利用に対する独自の権限制御です。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../feature-permissions.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

## 概要

権限システムは、Claude がどのツールを使えるか、そして権限リクエストをどう扱うかを制御します。

## 権限モード

```java
.permissionMode(PermissionMode.BYPASS_PERMISSIONS)
```

### 利用できるモード

- **PROMPT**（既定）—— 権限ごとにユーザーへ確認する
- **ACCEPT_ALL** —— すべての権限を自動で承認する
- **ACCEPT_EDITS** —— ファイル編集は自動承認し、それ以外は確認する
- **BYPASS_PERMISSIONS** —— 権限チェックを完全に省略する
- **DONT_ASK** —— 確認せずにすべてのツールを許可する
- **AUTO** —— 適切な権限モードを自動的に判断する

## カスタム権限コールバック

きめ細かく制御するには `canUseTool` コールバックを使います。これはどのエントリポイントでも機能
します —— 文字列のプロンプトも内部的には stdin 経由でストリーミングされるため、権限リクエストを
運ぶ制御プロトコルがそこでも利用できるからです。`permissionPromptToolName` と併用することは
できません：

```java
.canUseTool((toolName, input, context) -> {
    // Custom logic
    if (shouldAllow(toolName, context.blockedPath())) {
        return CompletableFuture.completedFuture(
            new PermissionResultAllow()
        );
    } else {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Access denied: " + context.blockedPath())
        );
    }
})
```

> **`canUseTool` は `"ask"` の判断でのみ発火します。** このコールバックは、対話的な権限プロンプトの
> SDK における代替であり、CLI の権限ルールが `"ask"` と評価されたときにだけ実行されます。
> `allowedTools`、`permissionMode`（例：`ACCEPT_EDITS`、`BYPASS_PERMISSIONS`）、設定ファイルの
> `permissions.allow` ルールによってすでに許可されているツール呼び出しでは**呼ばれません** ——
> それらはプロンプトに到達しないからです。権限ルールにかかわらず**すべての**ツール呼び出しを
> 観測・制御したい場合は、代わりに `hooks(...)` で `PreToolUse` フックを登録してください。

### シャドーイングの警告

`canUseTool` は他のオプションですでに許可された呼び出しでは決して発火しないため、SDK は明らかに
覆い隠されているコールバックを検出すると、接続時に**助言的な警告**を出します。このチェックは
クエリの構築ごとに 1 回実行され（`ClaudeSDKClient.connect()` の開始時、または任意の
`ClaudeSDK.query(...)` のセットアップ時）、`java.util.logging` を通じて
`in.vidyalai.claude.sdk.internal.CanUseToolShadow` という名前のロガーに `WARNING` を記録します。

`canUseTool` コールバックは、次のいずれかと併せて設定されていると「覆い隠されている」と報告されます：

- **`permissionMode(PermissionMode.BYPASS_PERMISSIONS)`** —— コールバックに問い合わせる前に、
  すべてのツール呼び出しが自動承認されます（明示的な拒否ルールを除く）。
- **ツール*全体*を許可する `allowedTools` エントリ** —— 指定子のないもの（`"Read"`）、指定子が
  空のもの（`"Read()"`）、指定子が単独のワイルドカードのもの（`"Read(*)"`）です。`"Bash(ls:*)"`
  のような絞り込みの指定子はコールバックを**覆い隠しません**。一致しない呼び出しはコールバックへ
  流れてくるからです。警告には覆い隠されたツールが列挙されます。

`skills("all")`（`Builder.skillsAll()` 経由）も考慮されます。これはトランスポートに裸の `Skill`
許可ルールを注入させるため、手書きの `"Skill"` エントリとまったく同じようにコールバックを覆い
隠します。名前付きの skill（`skills(List.of("reviewer"))`）は `Skill(name)` 指定子を注入するので
覆い隠しません。

この警告は**助言にすぎず、例外を投げることはありません**。覆い隠しは意図的なこともあります
（たとえば、`allowedTools` に*含まれていない*ツールのためだけに使うコールバックなど）。権限ルールに
かかわらずすべてのツール呼び出しを観測・制御したい場合は、代わりに `PreToolUse` フックを使って
ください —— ただし、*許可*の判断を返す `PreToolUse` フックもまた `canUseTool` をスキップさせる点に
注意してください。設定ファイル内の許可ルールもコールバックを覆い隠しますが、このチェックからは
見えません。

警告を抑制するには、`in.vidyalai.claude.sdk.internal.CanUseToolShadow` ロガーのレベルを `WARNING`
より上に引き上げてください：

```java
java.util.logging.Logger
    .getLogger("in.vidyalai.claude.sdk.internal.CanUseToolShadow")
    .setLevel(java.util.logging.Level.SEVERE);
```

### コールバックを決定論的に保つ

設定ファイルのルールは、この助言が見通せない唯一の覆い隠しケースであり、もっとも足をすくわれや
すいものでもあります。`~/.claude/settings.json` の `permissions.allow` に裸の `Write(*)` が 1 行
あるだけで、昨日まで動いていたマシン上で `canUseTool` コールバックがまったく発火しなくなります。
エラーは何も出ません —— コールバックは単に問い合わせられず、処理したプロンプト数を数えるコードは
ゼロを報告します。

コールバックが予測どおりに発火してほしい場面 —— デモ、テスト、再現可能なスクリプト —— では、設定
ファイルを一切読み込まないようにします：

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
        .canUseTool(callback)
        // Load no settings files: an allow rule in the user's or the project's
        // settings.json shadows the callback exactly like an allowedTools
        // entry does, and the advisory above cannot see those rules.
        .settingSources(List.of())
        .permissionMode(PermissionMode.DEFAULT)
        .build();
```

また、`allowedTools` に載っているツールはプロンプトに到達しないため、そのツールを制御するつもりの
コールバックは、そのツールをリストから外しておかなければなりません。examples モジュールの
`PermissionCallbacks.java` は両方を行っています。

> **イディオムに関する注記：** Python SDK はこの状況を `CanUseToolShadowedWarning`
> （`UserWarning` のサブクラス）として、TypeScript SDK は `CLAUDE_SDK_CAN_USE_TOOL_SHADOWED`
> プロセス警告として報告します。Java SDK は専用の警告型の代わりに、慣用的な警告チャネルである
> `java.util.logging` を使います。

## ToolPermissionContext

CLI は権限コンテキストを拡充し、コールバックが生のツール入力から組み立て直さなくても意味のある
プロンプトを描画できるようにします：

```java
record ToolPermissionContext(
    @Nullable Object signal,                 // reserved for future abort signal support (always null today)
    List<PermissionUpdate> suggestions,      // permission suggestions from the CLI
    @Nullable String toolUseId,              // unique tool call ID within the assistant message
    @Nullable String agentId,                // sub-agent's ID if running inside a sub-agent
    @Nullable String blockedPath,            // file path that triggered the request (e.g. Bash hitting a denied path)
    @Nullable String decisionReason,         // why this prompt was triggered (e.g. PreToolUse hook's permissionDecisionReason)
    @Nullable String title,                  // full prompt sentence ("Claude wants to read foo.txt") — use as primary prompt text
    @Nullable String displayName,            // short noun phrase ("Read file") for buttons / compact UI
    @Nullable String description             // human-readable subtitle for the permission UI
)
```

拡充フィールドが存在する前に書かれたコードのために、後方互換のコンストラクタが残されています：

- `new ToolPermissionContext()` —— 空のコンテキスト
- `new ToolPermissionContext(suggestions)` —— suggestions のみ
- `new ToolPermissionContext(signal, suggestions)` —— signal + suggestions
- `new ToolPermissionContext(signal, suggestions, toolUseId, agentId)` —— 拡充前の 4 引数形式

```java
.canUseTool((toolName, input, context) -> {
    // Prefer the CLI-supplied prompt text when present.
    String prompt = context.title() != null
        ? context.title()
        : "Allow " + toolName + "?";
    String why = context.decisionReason();
    if (why != null) prompt += " (" + why + ")";

    boolean ok = askUser(prompt);
    return CompletableFuture.completedFuture(
        ok ? new PermissionResultAllow()
           : new PermissionResultDeny("user declined"));
})
```

## PermissionDecision（PreToolUse フック内）

`PermissionDecision` は、`PreToolUse` フックが
`PreToolUseHookSpecificOutput.permissionDecision` から返す値です：

| 定数 | ワイヤー値 | 効果 |
|----------|------------|--------|
| `ALLOW` | `"allow"` | 確認せずにツールを実行します。 |
| `DENY` | `"deny"` | ツールをブロックします。 |
| `ASK` | `"ask"` | SDK の `canUseTool` コールバック（または CLI のプロンプト）を起動します。 |
| `DEFER` | `"defer"` | ツールを実行せずに実行を停止します。保留された呼び出しは `ResultMessage.deferredToolUse` に現れます。[フック → 権限判断 `"defer"`](./feature-hooks.md#権限判断-defer)を参照してください。 |

## PermissionResult

```java
// Allow
new PermissionResultAllow()

// Deny with reason
new PermissionResultDeny("Reason for denial")
```

## サンプル

### パスに基づく権限

```java
.canUseTool((toolName, input, context) -> {
    // blockedPath is set by the CLI when the request was triggered by a
    // path violation (e.g. a Bash command touching a denied directory).
    // For tools like Read / Write the path is in `input` instead.
    String path = context.blockedPath() != null
        ? context.blockedPath()
        : (String) input.get("file_path");

    // Allow read-only in /src
    if (toolName.equals("Read") && path != null && path.startsWith("/src")) {
        return CompletableFuture.completedFuture(
            new PermissionResultAllow()
        );
    }

    // Deny write to sensitive dirs
    if (toolName.equals("Write") && path != null && path.contains("/config")) {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Cannot write to config")
        );
    }

    // Default allow
    return CompletableFuture.completedFuture(
        new PermissionResultAllow()
    );
})
```

### 時間に基づく権限

```java
.canUseTool((toolName, input, context) -> {
    // Only allow during business hours
    int hour = LocalTime.now().getHour();
    if (hour < 9 || hour > 17) {
        return CompletableFuture.completedFuture(
            new PermissionResultDeny("Outside business hours")
        );
    }
    
    return CompletableFuture.completedFuture(
        new PermissionResultAllow()
    );
})
```

### ユーザーによる確認

```java
.canUseTool((toolName, input, context) -> {
    // Prompt user for dangerous operations
    if (toolName.equals("Bash")) {
        boolean approved = promptUser("Allow bash: " + input + "?");
        if (approved) {
            return CompletableFuture.completedFuture(
                new PermissionResultAllow()
            );
        } else {
            return CompletableFuture.completedFuture(
                new PermissionResultDeny("User rejected")
            );
        }
    }
    
    return CompletableFuture.completedFuture(
        new PermissionResultAllow()
    );
})
```

## 関連項目
- [設定オプション](./feature-configuration-options.md#権限の設定)
- [権限コールバックのサンプル](../../examples/src/main/java/examples/PermissionCallbacks.java)
