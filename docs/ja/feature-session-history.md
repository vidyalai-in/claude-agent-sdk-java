# セッション履歴

CLI を実行せずに、過去の Claude Code の会話セッションを読み取り、閲覧します。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../feature-session-history.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

## 目次
- [概要](#概要)
- [セッションのメタデータ](#セッションのメタデータ)
- [セッションのメッセージ](#セッションのメッセージ)
- [セッションの一覧](#セッションの一覧)
- [単一セッションの参照](#単一セッションの参照)
- [セッションのメッセージを読む](#セッションのメッセージを読む)
- [セッションのリネーム](#セッションのリネーム)
- [セッションへのタグ付け](#セッションへのタグ付け)
- [セッションの削除](#セッションの削除)
- [セッションのフォーク](#セッションのフォーク)
- [サンプル](#サンプル)
- [ベストプラクティス](#ベストプラクティス)

## 概要

Claude Code はすべての会話を `~/.claude/projects/` 以下の JSONL ファイルとして保存します。セッション
履歴 API では次のことができます：

- **セッションの一覧**：全プロジェクト横断、または特定の作業ディレクトリで絞り込み
- **メッセージの読み取り**：過去のどのセッションからでも、会話トランスクリプト全体を

読み取りはすべてディスクから直接行われ、CLI とは無関係です。プロセスは生成されません。

**性能：** 一覧では各セッションファイルの先頭と末尾の 64 KB だけを読み取ります（JSONL 全体の解析は
行いません）。完全な解析は `getSessionMessages` でメッセージを取得するときだけ行われます。

> **リモート／マルチホストのバックエンドをお探しですか？**
> [Session Store](./feature-session-store.md) を参照してください。このページのすべてのメソッドには、
> `SessionStore` アダプタ（S3、Postgres、Redis、独自実装）に対して動作する `*FromStore`（読み取り）
> または `*ViaStore`（変更）の対応物が `ClaudeSDK` にあります。ここで説明するローカルディスクの API
> が引き続き正典の経路であり、SessionStore の API は追加的なもので、可搬性のために同じディスク上の
> レイアウトを対象としています。

## セッションのメタデータ

`SDKSessionInfo` は 1 つのセッションのメタデータを保持します：

```java
record SDKSessionInfo(
    String sessionId,              // UUID identifying the session
    String summary,                // display title (custom title, AI title, lastPrompt, summary, or first prompt)
    long lastModified,             // last-modified time in milliseconds since epoch
    @Nullable Long fileSize,       // session file size in bytes (null for remote storage backends)
    @Nullable String customTitle,  // user-set custom title or AI-generated title (may be null)
    @Nullable String firstPrompt,  // first meaningful user prompt (may be null)
    @Nullable String gitBranch,    // git branch at end of session (may be null)
    @Nullable String cwd,          // working directory for the session (may be null)
    @Nullable String tag,          // user-set session tag (may be null)
    @Nullable Long createdAt       // creation time in ms since epoch from first entry's ISO timestamp (may be null)
)
```

`summary` フィールドは優先順に解決されます：カスタムタイトル > AI タイトル > lastPrompt >
自動生成の要約 > 最初のプロンプト。

`tag` と `createdAt` を持たない後方互換のコンストラクタも用意されています。

## セッションのメッセージ

`SessionMessage` はセッショントランスクリプト中の 1 件のメッセージを保持します：

```java
record SessionMessage(
    String type,                         // "user" or "assistant"
    String uuid,                         // unique message UUID
    String sessionId,                    // session ID this message belongs to
    Object message,                      // raw Anthropic API message (Map with role/content)
    @Nullable String parentToolUseId,    // spawning Agent tool_use id (subagent reads only)
    @Nullable String parentAgentId       // spawning subagent id (nested subagents only)
)
```

返されるのはトップレベルの会話メッセージだけです —— ツール利用のサイドチェーンメッセージ、メタ
メッセージ、サブエージェントのメッセージは除外されます。

`getSessionMessages()` / `getSessionMessagesFromStore()` の結果では、`parentToolUseId` と
`parentAgentId` は常に null です。これらが埋まるのは `getSubagentMessages()` /
`getSubagentMessagesFromStore()` の場合で、`parentToolUseId` はそのサブエージェントを生成した親
セッション内の Agent `tool_use` ブロックの id、`parentAgentId` はあるサブエージェントが別のサブ
エージェントを生成したときの生成元を指します。どちらもサブエージェントの
`agent-<agentId>.meta.json` サイドカー（ストア読み取りでは、その代わりとなる `agent_metadata`
エントリ）に由来するため、そのメタデータが欠けていたり使えなかったりすると両方 null になります。
あるサブエージェントのトランスクリプト内のすべてのメッセージが、同じこの 2 つの値を持ちます。

### メッセージ内容へのアクセス

`message` フィールドは Anthropic API のワイヤー形式に対応した生の `Map<String, Object>` です：

```java
SessionMessage msg = ...;
if (msg.message() instanceof Map<?, ?> m) {
    Object content = m.get("content");
    if (content instanceof String text) {
        System.out.println(text);
    } else if (content instanceof List<?> blocks) {
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> b && b.get("text") instanceof String t) {
                System.out.println(t);
            }
        }
    }
}
```

## セッションの一覧

### すべてのセッション

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions();
```

全プロジェクトのすべてのセッションを、更新の新しい順に返します。

### あるプロジェクトのセッション

```java
Path projectDir = Path.of("/my/project");
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(projectDir);
```

作業ディレクトリが `projectDir` と一致するセッションに絞り込みます。

### 上限と worktree を指定する

```java
List<SDKSessionInfo> recent = ClaudeSDK.listSessions(
    Path.of("/my/project"),   // null for all projects
    10,                        // max 10 results
    true                       // include git worktrees
);
```

`includeWorktrees = true` にすると `git worktree list` を実行し、リポジトリのすべての worktree の
セッションを含めます。

### オフセットによるページネーション

```java
// Page 1: first 50 sessions
List<SDKSessionInfo> page1 = ClaudeSDK.listSessions(
    Path.of("/my/project"), 50, 0, true);

// Page 2: next 50 sessions
List<SDKSessionInfo> page2 = ClaudeSDK.listSessions(
    Path.of("/my/project"), 50, 50, true);
```

## 単一セッションの参照

`getSessionInfo` を使うと、すべてのセッションファイルを走査せずに ID で 1 つのセッションを参照できます。
セッションの UUID がすでに分かっている場合は `listSessions` より効率的です。

### セッション ID で（全プロジェクトを検索）

```java
SDKSessionInfo info = ClaudeSDK.getSessionInfo("550e8400-e29b-41d4-a716-446655440000");
if (info != null) {
    System.out.println("Session: " + info.summary());
    if (info.tag() != null) {
        System.out.println("Tag: " + info.tag());
    }
    if (info.createdAt() != null) {
        String created = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(info.createdAt()));
        System.out.println("Created: " + created);
    }
}
```

### プロジェクトディレクトリに限定する

```java
SDKSessionInfo info = ClaudeSDK.getSessionInfo(
    "550e8400-e29b-41d4-a716-446655440000",
    Path.of("/my/project")
);
```

セッションが見つからない場合、サイドチェーンのセッションである場合、要約を取り出せない場合は `null`
を返します。

## セッションのメッセージを読む

### トランスクリプト全体

```java
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(sessionId);
```

すべてのプロジェクトディレクトリからそのセッション UUID を探します。

### プロジェクトに限定する

```java
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(
    sessionId,
    Path.of("/my/project")
);
```

### ページネーション付き

```java
// Skip first 20 messages, return next 10
List<SessionMessage> page = ClaudeSDK.getSessionMessages(
    sessionId,
    null,    // all projects
    10,      // limit
    20       // offset
);
```

## セッションのリネーム

セッションの JSONL ファイルにカスタムタイトルのエントリを追記してリネームします。最後のリネームが
常に勝つので、何度呼んでも安全です。

```java
// Rename by session ID (searches all projects)
ClaudeSDK.renameSession("550e8400-e29b-41d4-a716-446655440000", "My Feature Branch Session");

// Rename scoped to a specific project directory
ClaudeSDK.renameSession(
    "550e8400-e29b-41d4-a716-446655440000",
    "My Feature Branch Session",
    Path.of("/my/project")
);
```

**制約：**
- `sessionId` は妥当な UUID（小文字の 16 進数とハイフン）でなければなりません。
- `title` は前後の空白を除いたうえで空であってはいけません。
- セッションの JSONL ファイルが見つからない場合は `FileNotFoundException` を送出します。
- ファイルの書き込みに失敗した場合は `IOException` を送出します。

リネーム後、`listSessions()` は `SDKSessionInfo` の `summary` と `customTitle` フィールドに新しい
タイトルを返します。

## セッションへのタグ付け

整理して絞り込むためにセッションにタグを付けます。既存のタグを消すには `null` を渡します。タグは CLI
のフィルタと互換にするため、保存前に Unicode の正規化（サニタイズ）が行われます。

```java
// Tag a session (searches all projects)
ClaudeSDK.tagSession("550e8400-e29b-41d4-a716-446655440000", "production");

// Clear a tag
ClaudeSDK.tagSession("550e8400-e29b-41d4-a716-446655440000", null);

// Tag scoped to a specific project directory
ClaudeSDK.tagSession(
    "550e8400-e29b-41d4-a716-446655440000",
    "staging",
    Path.of("/my/project")
);
```

**制約：**
- `sessionId` は妥当な UUID でなければなりません。
- `tag` は Unicode のサニタイズと空白除去のあと空であってはいけません（消す場合は `null`）。
- 危険な Unicode 文字（ゼロ幅文字、方向制御記号、私用領域の文字）を含むタグは自動的にサニタイズ
  されます。
- セッションの JSONL ファイルが見つからない場合は `FileNotFoundException` を送出します。
- ファイルの書き込みに失敗した場合は `IOException` を送出します。

**同時実行時の安全性：** そのセッションが CLI プロセスで開かれている場合、CLI は次にメタデータを
再追記する際に SDK が書いたエントリをキャッシュへ取り込みます。最後の書き込みが勝ちます。

## セッションの削除

JSONL ファイルを削除してセッションを完全に消します。サブエージェントのトランスクリプトを保持する
兄弟ディレクトリ `<sessionId>/` も再帰的に削除されます（ベストエフォート。存在しなくても問題ありません）。

```java
// Delete by session ID (searches all projects)
ClaudeSDK.deleteSession("550e8400-e29b-41d4-a716-446655440000");

// Delete scoped to a specific project directory
ClaudeSDK.deleteSession("550e8400-e29b-41d4-a716-446655440000", Path.of("/my/project"));
```

**制約：**
- `sessionId` は妥当な UUID でなければなりません。
- セッションファイルが見つからない場合は `FileNotFoundException` を送出します。
- 論理削除の挙動が欲しい場合は、代わりに `tagSession(id, "__hidden")` を使い、一覧時に絞り込んで
  ください。

## サブエージェントのトランスクリプトを読む

セッションが（`Task` ツールやプログラムによるエージェント定義で）サブエージェントを生成すると、各
サブエージェントは自身のトランスクリプトを
`~/.claude/projects/<project>/<sessionId>/subagents/agent-<agentId>.jsonl` に書き出します。
サブエージェントのトランスクリプトは `subagents/workflows/<runId>/` のような入れ子のディレクトリに
置かれることもあります。

```java
// Enumerate subagent IDs for a session
List<String> agentIds = ClaudeSDK.listSubagents(
    "550e8400-e29b-41d4-a716-446655440000");

// Or scoped to a specific project
List<String> agentIds = ClaudeSDK.listSubagents(
    "550e8400-e29b-41d4-a716-446655440000",
    Path.of("/my/project"));

// Read a subagent's full conversation
List<SessionMessage> messages = ClaudeSDK.getSubagentMessages(
    "550e8400-e29b-41d4-a716-446655440000",
    "abc123");

// With limit and offset
List<SessionMessage> page = ClaudeSDK.getSubagentMessages(
    "550e8400-e29b-41d4-a716-446655440000",
    "abc123",
    Path.of("/my/project"),
    50,    // limit (null or 0 = no limit)
    0);    // offset
```

**挙動：**
- `listSubagents` は `subagents/` ツリーを再帰的に走査して `agent-<id>.jsonl` に一致するファイルを
  探し、ディレクトリの走査順で ID を返します。
- `getSubagentMessages` は葉から `parentUuid` のリンクをたどって連鎖を再構成します。サブエージェント
  のトランスクリプトは線形（圧縮もサイドチェーンもなし）なので、返るリストは時系列順の完全な会話です。
- 壊れた JSONL の行は黙って読み飛ばされます。
- 不正な UUID、存在しないセッション、存在しないエージェント、空のエージェント ID は、いずれも空の
  リストを返します（例外は投げません）。

## セッションのフォーク

セッションを新しい UUID とともに新しいブランチへフォークします。元のセッションのトランスクリプト
メッセージをコピーし、各メッセージの UUID を振り直しつつ `parentUuid` の連鎖を保ちます。フォーク
されたセッションに取り消し履歴はありません。

```java
// Fork a session (searches all projects)
ForkSessionResult result = ClaudeSDK.forkSession("550e8400-e29b-41d4-a716-446655440000");
System.out.println("New session: " + result.sessionId());

// Fork scoped to a project directory
ForkSessionResult result = ClaudeSDK.forkSession(
    "550e8400-e29b-41d4-a716-446655440000",
    Path.of("/my/project")
);

// Fork from a specific message (truncate transcript)
ForkSessionResult result = ClaudeSDK.forkSession(
    "550e8400-e29b-41d4-a716-446655440000",
    null,                                       // search all projects
    "660e8400-e29b-41d4-a716-446655440001",    // slice transcript at this message
    "My Fork Title"                            // custom title (null = original + " (fork)")
);
```

**`ForkSessionResult`** が含むもの：
- `sessionId` —— 新しく作られたフォーク先セッションの UUID

**制約：**
- `sessionId` と、省略可能な `upToMessageId` は妥当な UUID でなければなりません。
- 元のセッションが見つからない場合は `FileNotFoundException` を送出します。
- セッションにメッセージがない場合や `upToMessageId` が見つからない場合は
  `IllegalArgumentException` を送出します。

`forkSession()` は CLI を実行せずにオフラインのコピーを作ります。過去の地点から*再開*して会話を
続けたい場合は、代わりに切り詰めレジュームを使ってください。

## 切り詰めレジューム

`resumeSessionAt` は、再開する会話を指定したトランスクリプトエントリ UUID までを含む範囲だけ読み込み、
それより後をすべて捨てます。`forkSession(true)` と組み合わせれば新しいセッションへ分岐し、元の
セッションはそのまま残ります。

`resumeDropsTurn` はその切り詰めを**安全**にします。捨てるつもりのターンのユーザープロンプトの UUID を
渡すと、CLI は読み込み時に、分岐点より後のすべてのエントリがそのターンに属することを検証し、
そうでなければ拒否します。これがないと、セッションがターンの途中で取り込んだ、あなたが観測していない
キュー中のユーザーメッセージやバックグラウンドタスクの通知が、黙って捨てられてしまいます。

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cwd(projectDir)
    .resume(sessionId)
    .forkSession(true)               // branch; leave the source session intact
    .resumeSessionAt(keepAtUuid)     // last transcript entry of the turn to keep
    .resumeDropsTurn(nextPromptUuid) // prompt UUID of the turn being discarded
    .build();

for (Message msg : ClaudeSDK.query("Reply with exactly: three", options)) {
    if (msg instanceof ResultMessage r) {
        System.out.println("Forked session: " + r.sessionId());
    }
}
```

**2 つの UUID の選び方。** `resumeSessionAt` には残したいターンの*最後の*トランスクリプトエントリを
（型は問いません）、`resumeDropsTurn` にはその直後のターンのプロンプト UUID を設定します。どちらも
`ClaudeSDK.getSessionMessages(sessionId, cwd)` から読み取れます。残したいエントリを見つけ、次に
`type()` が `"user"` である `SessionMessage` の `uuid()` を取ってください。ライブで観測した
`AssistantMessage.uuid()` も分岐点として使えます。

なお、構造化出力（`outputFormat`）やターンを終わらせる MCP ツールを使っている場合、残すターンは
最後のアシスタントメッセージ*より後*のエントリで終わります —— そのためアシスタントの UUID での分岐は
設計上そのケースでは拒否されます。

**拒否されたときの扱い。** 拒否は、メッセージに `Resume rejected by --resume-drops-turn:` を含む
例外として届きます：

```java
try {
    ClaudeSDK.query(prompt, options);
} catch (ClaudeSDKException e) {
    if (String.valueOf(e.getMessage()).contains("Resume rejected by --resume-drops-turn:")) {
        // Deterministic — the transcript is not what we assumed.
        // Clear the fork target and resume plainly; do not retry as-is.
    }
}
```

これは決定論的なものとして扱ってください。同じリクエストを再試行しても同じように失敗します。
古い、検証のない切り詰めの挙動を保ちたい場合は `resumeDropsTurn` を設定しないでください。

どちらのオプションも `SessionStore` からのレジューム時にも転送されます。実行できるエンドツーエンドの
デモは
[`TruncatingResumeExample`](../../examples/src/main/java/examples/TruncatingResumeExample.java)
を参照してください。

## サンプル

### サンプル 1：最近のセッションを一覧する

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 5, true);

DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault());

for (SDKSessionInfo session : sessions) {
    String time = fmt.format(Instant.ofEpochMilli(session.lastModified()));
    System.out.printf("[%s] %s%n", time, session.summary());
    System.out.printf("  id:  %s%n", session.sessionId());
    if (session.cwd() != null) {
        System.out.printf("  cwd: %s%n", session.cwd());
    }
    if (session.gitBranch() != null) {
        System.out.printf("  git: %s%n", session.gitBranch());
    }
}
```

### サンプル 2：現在のプロジェクトのセッション

```java
Path cwd = Path.of(System.getProperty("user.dir"));
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(cwd);

System.out.printf("Found %d session(s) for: %s%n", sessions.size(), cwd);
for (SDKSessionInfo session : sessions) {
    String sizeStr = (session.fileSize() != null)
            ? String.format("%.1f KB", session.fileSize() / 1024.0) : "N/A";
    System.out.printf("  %s (%s)%n", session.summary(), sizeStr);
}
```

### サンプル 3：直近のセッションのメッセージを読む

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 1, false);
if (sessions.isEmpty()) {
    System.out.println("No sessions found.");
    return;
}

SDKSessionInfo recent = sessions.get(0);
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(recent.sessionId());

System.out.printf("Session: %s (%d messages)%n",
    recent.summary(), messages.size());

for (SessionMessage msg : messages) {
    System.out.printf("%n[%s]%n", msg.type().toUpperCase());
    if (msg.message() instanceof Map<?, ?> m) {
        Object content = m.get("content");
        if (content instanceof String text) {
            System.out.println(text);
        } else if (content instanceof List<?> blocks && !blocks.isEmpty()) {
            Object first = ((List<?>) blocks).get(0);
            if (first instanceof Map<?, ?> b && b.get("text") instanceof String t) {
                System.out.println(t);
            }
        }
    }
}
```

### サンプル 4：プロンプトのキーワードでセッションを探す

```java
List<SDKSessionInfo> all = ClaudeSDK.listSessions();

List<SDKSessionInfo> matching = all.stream()
    .filter(s -> s.summary().toLowerCase().contains("refactor"))
    .toList();

System.out.println("Found " + matching.size() + " sessions about refactoring");
```

### サンプル 5：直近のセッションをリネームする

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 1, false);
if (!sessions.isEmpty()) {
    String sessionId = sessions.get(0).sessionId();
    ClaudeSDK.renameSession(sessionId, "Important: Production Bug Fix");
    System.out.println("Renamed session " + sessionId);
}
```

### サンプル 6：プロジェクトのフェーズでタグ付けする

```java
// Tag a session after a query completes, using the result's session ID
List<Message> messages = ClaudeSDK.query(prompt, options);
for (Message msg : messages) {
    if (msg instanceof ResultMessage result) {
        ClaudeSDK.tagSession(result.sessionId(), "sprint-42");
        break;
    }
}
```

### サンプル 7：タグを消す

```java
// Retrieve a session and clear its tag
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions();
for (SDKSessionInfo session : sessions) {
    if ("old-tag".equals(session.customTitle())) {
        ClaudeSDK.tagSession(session.sessionId(), null);
    }
}
```

## ベストプラクティス

### 可能なときはディレクトリで絞り込む

```java
// Efficient: scoped to one project
ClaudeSDK.listSessions(Path.of("/my/project"));

// Less efficient: scans all projects
ClaudeSDK.listSessions();
```

### 空の結果を確認する

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(dir);
if (sessions.isEmpty()) {
    // No sessions yet — run Claude Code in this directory first
}
```

### CLAUDE_CONFIG_DIR が未設定の場合の扱い

既定ではセッションは `~/.claude/projects/` 以下に保存されます。環境変数 `CLAUDE_CONFIG_DIR` が設定
されていれば、その場所が優先されます。SDK はこの変数を自動的に尊重します。

### limit を使って巨大な結果を避ける

```java
// Return only the 20 most recent
List<SDKSessionInfo> recent = ClaudeSDK.listSessions(null, 20, false);
```

## 関連項目

- [API リファレンス：ClaudeSDK](./api-claude-sdk.md#セッション履歴のメソッド) —— メソッドのシグネチャ
- [Session Store](./feature-session-store.md) —— 外部ストアを使った等価物（`*FromStore`/`*ViaStore`）と、書き込み時ミラーの統合
- [セッション一覧のサンプル](../../examples/src/main/java/examples/SessionListingExample.java) —— 実行できる完全なサンプル
- [対話的な会話](./feature-interactive-conversations.md) —— 進行中のセッションを管理する
