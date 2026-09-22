# ClaudeSDK API リファレンス

シンプルなクエリとクライアント生成のための静的ファサードです。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../api-claude-sdk.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

## クラスの概要

```java
public final class ClaudeSDK
```

SDK の一般的な操作のための静的メソッドを提供するユーティリティクラスです。

## クエリのメソッド

### query(String prompt)

```java
public static List<Message> query(String prompt)
```

既定のオプションでクエリを実行します。

**戻り値**：`List<Message>`

### query(String prompt, ClaudeAgentOptions options)

```java
public static List<Message> query(
    String prompt,
    ClaudeAgentOptions options
)
```

独自のオプションでクエリを実行します。

**引数**：
- `prompt` —— プロンプト
- `options` —— 設定オプション

**戻り値**：`List<Message>`

**送出**：
- `IllegalArgumentException` —— canUseTool と permissionPromptToolName の両方が設定されている場合
- `CLIConnectionException` —— 接続に失敗した場合
- `ProcessException` —— CLI プロセスが失敗した場合
- `QueryFailedException` —— 実行がエラー結果で終わった場合（`error_max_turns`、`error_max_budget_usd`、`resumeDropsTurn` に拒否されたレジューム）。その前に収集されたメッセージを保持します —— 下記参照。

### query(Iterator<Map<String, Object>> messageStream, ClaudeAgentOptions options)

```java
public static List<Message> query(
    Iterator<Map<String, Object>> messageStream,
    ClaudeAgentOptions options
)
```

複数のメッセージでストリーミングのクエリを実行します。

**引数**：
- `messageStream` —— メッセージ辞書のイテレータ
- `options` —— 設定オプション

**戻り値**：`List<Message>`

**送出**：上と同じで、`QueryFailedException` を含みます。

### エラー結果と部分メッセージ

CLI は `error_max_turns` と `error_max_budget_usd` を、*完全な*ターン —— アシスタントメッセージと、
サブタイプ・コスト・使用量を持つ最終的な `ResultMessage` —— を発行し、そのあとで意図的に非ゼロ終了
することによって報告します（シェルの利用者のためです）。

これら収集を行うメソッドはリストを返すか例外を投げるかしかないので、その場合は
`QueryFailedException` を投げ、収集済みのメッセージをその例外に載せて返します。何も失われません：

```java
try {
    List<Message> messages = ClaudeSDK.query(prompt, options);
} catch (QueryFailedException e) {
    ResultMessage result = e.resultMessage();       // the final result, or null
    List<Message> partial = e.partialMessages();    // everything received first
    if (result != null && "error_max_budget_usd".equals(result.subtype())) {
        System.out.printf("Stopped after $%.4f%n", result.totalCostUsd());
    }
}
```

`maxTurns` や `maxBudgetUsd` を設定したときは必ず捕捉してください —— 自分で設定した上限に達することは
クラッシュではなく、想定内の結果です。収集済みメッセージではなく結果のペイロード（`subtype()`、
`terminalReason()`、`apiErrorStatus()` など）が欲しい場合は、cause から読み取ってください。それは
[`ResultException`](./api-exceptions.md#resultexception) です。

[`ClaudeSDKClient`](./api-claude-sdk-client.md) のストリーミング API には、そもそもこの例外は不要
でした。メッセージは届いた端から渡され、`receiveResponse()` は `ResultMessage` で止まります ——
その `isError()` と `subtype()` を直接確認してください。
[例外](./api-exceptions.md#queryfailedexception)を参照してください。

## 便利メソッド

### queryForText(String prompt, ClaudeAgentOptions options)

```java
public static String queryForText(
    String prompt,
    ClaudeAgentOptions options
)
```

アシスタントメッセージのテキスト内容だけを取得します。

**戻り値**：`String` —— 結合されたテキスト

### queryForResult(String prompt, ClaudeAgentOptions options)

```java
public static ResultMessage queryForResult(
    String prompt,
    ClaudeAgentOptions options
)
```

結果メッセージだけを取得します。

**戻り値**：`ResultMessage` または null

## クライアントのファクトリメソッド

### createClient()

```java
public static ClaudeSDKClient createClient()
```

既定のオプションでクライアントを生成します。

**戻り値**：`ClaudeSDKClient`

### createClient(ClaudeAgentOptions options)

```java
public static ClaudeSDKClient createClient(
    ClaudeAgentOptions options
)
```

独自のオプションでクライアントを生成します。

**戻り値**：`ClaudeSDKClient`

## MCP サーバーのファクトリメソッド

### createSdkMcpServer(String name, List<SdkMcpTool<?>> tools)

```java
public static McpSdkServerConfig createSdkMcpServer(
    String name,
    List<SdkMcpTool<?>> tools
)
```

ツールのリストから SDK MCP サーバーを生成します。

**引数**：
- `name` —— サーバー名
- `tools` —— ツールのリスト

**戻り値**：`McpSdkServerConfig`

### createSdkMcpServer(String name, String version, List<SdkMcpTool<?>> tools)

```java
public static McpSdkServerConfig createSdkMcpServer(
    String name,
    String version,
    List<SdkMcpTool<?>> tools
)
```

バージョン付きで SDK MCP サーバーを生成します。

### createSdkMcpServer(String name, Object instance)

```java
public static McpSdkMcpServer createSdkMcpServer(
    String name,
    Object instance
)
```

@Tool 注釈の付いたメソッドから SDK MCP サーバーを生成します。

**引数**：
- `name` —— サーバー名
- `instance` —— @Tool メソッドを持つオブジェクト

**戻り値**：`McpSdkServerConfig`

## セッション履歴のメソッド

### listSessions()

```java
public static List<SDKSessionInfo> listSessions()
```

全プロジェクトのすべてのセッションを、更新の新しい順に一覧します。`~/.claude/projects/` から読み取り、
JSONL ファイルを完全には解析しません —— ファイルごとに先頭と末尾の 64 KB だけです。

**戻り値**：更新時刻の降順に並んだ `List<SDKSessionInfo>`

### listSessions(Path directory)

```java
public static List<SDKSessionInfo> listSessions(Path directory)
```

特定のプロジェクトディレクトリのセッションを一覧します。

**引数**：
- `directory` —— 絞り込みに使うプロジェクトの作業ディレクトリ

**戻り値**：`List<SDKSessionInfo>`

### listSessions(Path directory, Integer limit, boolean includeWorktrees)

```java
public static List<SDKSessionInfo> listSessions(
    Path directory,
    Integer limit,
    boolean includeWorktrees
)
```

完全に制御しながらセッションを一覧します。

**引数**：
- `directory` —— 絞り込みに使うプロジェクトディレクトリ（null = 全プロジェクト）
- `limit` —— 返すセッションの上限（null = 無制限）
- `includeWorktrees` —— git の worktree ディレクトリを含めるかどうか

**戻り値**：`List<SDKSessionInfo>`

### getSessionInfo(String sessionId)

```java
@Nullable
public static SDKSessionInfo getSessionInfo(String sessionId)
```

ID で単一のセッションを参照します。`~/.claude/projects/` 以下のすべてのプロジェクトディレクトリを
検索します。O(n) のディレクトリ走査は行わず、対象のセッションファイルだけを読みます。

**引数**：
- `sessionId` —— 参照したいセッションの UUID

**戻り値**：そのセッションの `SDKSessionInfo`。見つからない場合、サイドチェーンのセッションである場合、
要約を取り出せない場合は `null`

### getSessionInfo(String sessionId, Path directory)

```java
@Nullable
public static SDKSessionInfo getSessionInfo(
    String sessionId,
    Path directory
)
```

特定のプロジェクトディレクトリ内で、ID により単一のセッションを参照します。

**引数**：
- `sessionId` —— 参照したいセッションの UUID
- `directory` —— 検索するプロジェクトの作業ディレクトリ

**戻り値**：`SDKSessionInfo` または `null`

### getSessionMessages(String sessionId)

```java
public static List<SessionMessage> getSessionMessages(String sessionId)
```

あるセッションの会話メッセージ全体を返します。すべてのプロジェクトディレクトリを検索します。

**引数**：
- `sessionId` —— セッションの UUID

**戻り値**：会話順に並んだ `List<SessionMessage>`

### getSessionMessages(String sessionId, Path directory)

```java
public static List<SessionMessage> getSessionMessages(
    String sessionId,
    Path directory
)
```

特定のプロジェクト内のセッションのメッセージを返します。

**引数**：
- `sessionId` —— セッションの UUID
- `directory` —— 検索するプロジェクトの作業ディレクトリ

**戻り値**：`List<SessionMessage>`

### getSessionMessages(String sessionId, Path directory, Integer limit, int offset)

```java
public static List<SessionMessage> getSessionMessages(
    String sessionId,
    Path directory,
    Integer limit,
    int offset
)
```

絞り込みを完全に制御してメッセージを返します。

**引数**：
- `sessionId` —— セッションの UUID
- `directory` —— 検索するプロジェクトディレクトリ（null = 全プロジェクト）
- `limit` —— 返すメッセージの上限（null = 無制限）
- `offset` —— 先頭から読み飛ばすメッセージ数

**戻り値**：`List<SessionMessage>`

## サブエージェントのトランスクリプト用メソッド

セッションが（`Task` ツールやプログラムによるエージェント定義で）サブエージェントを生成すると、各
サブエージェントのトランスクリプトは
`~/.claude/projects/<project>/<sessionId>/subagents/agent-<agentId>.jsonl` に書き出されます。これらの
ファイルは `subagents/workflows/<runId>/` のような入れ子のディレクトリに置かれることもあります。

### listSubagents(String sessionId)

```java
public static List<String> listSubagents(String sessionId)
```

すべてのプロジェクトディレクトリにわたり、そのセッションの `subagents/` ディレクトリを走査して
サブエージェントの ID を一覧します。

**引数**：
- `sessionId` —— 親セッションの UUID

**戻り値**：サブエージェント ID の `List<String>`。セッションが見つからない、`sessionId` が妥当な
UUID でない、あるいはそのセッションにサブエージェントがない場合は空になります。

### listSubagents(String sessionId, Path directory)

```java
public static List<String> listSubagents(String sessionId, Path directory)
```

特定のプロジェクトディレクトリに限定してサブエージェント ID を一覧します。

**引数**：
- `sessionId` —— 親セッションの UUID
- `directory` —— そのセッションを探すプロジェクトの作業ディレクトリ

### getSubagentMessages(String sessionId, String agentId)

```java
public static List<SessionMessage> getSubagentMessages(
    String sessionId,
    String agentId
)
```

サブエージェントの JSONL トランスクリプトから user/assistant のメッセージを読み取ります。
`parentUuid` のリンクをたどって連鎖を再構成します。各メッセージの `parentToolUseId` は、そのサブ
エージェントを生成した親セッション内の Agent `tool_use` であり、入れ子のサブエージェントでは
`parentAgentId` が生成元のサブエージェントを指します。どちらもトランスクリプトの隣にある
`agent-<agentId>.meta.json` サイドカーに由来し（トランスクリプトの行自体はそれらを記録しないため）、
そのサイドカーが欠けているか使えない場合は両方 null です。

**引数**：
- `sessionId` —— 親セッションの UUID
- `agentId` —— サブエージェント ID（`listSubagents` が返すもの）

**戻り値**：時系列順の `List<SessionMessage>`。セッションやサブエージェントが見つからない、
`sessionId` が妥当な UUID でない、あるいはトランスクリプトに user/assistant のメッセージがない場合は
空になります。

### getSubagentMessages(String sessionId, String agentId, Path directory)

```java
public static List<SessionMessage> getSubagentMessages(
    String sessionId,
    String agentId,
    Path directory
)
```

特定のプロジェクトディレクトリに限定してサブエージェントのメッセージを読み取ります。

### getSubagentMessages(String sessionId, String agentId, Path directory, Integer limit, int offset)

```java
public static List<SessionMessage> getSubagentMessages(
    String sessionId,
    String agentId,
    @Nullable Path directory,
    @Nullable Integer limit,
    int offset
)
```

絞り込みとページネーションを完全に制御してサブエージェントのメッセージを読み取ります。

**引数**：
- `sessionId` —— 親セッションの UUID
- `agentId` —— サブエージェント ID
- `directory` —— 検索するプロジェクトディレクトリ（null = 全プロジェクト）
- `limit` —— 返すメッセージの上限（null または `0` = 無制限）
- `offset` —— 先頭から読み飛ばすメッセージ数

## セッション変更のメソッド

### renameSession(String sessionId, String title)

```java
public static void renameSession(
    String sessionId,
    String title
) throws IOException
```

カスタムタイトルのエントリを追記してセッションをリネームします。最後のリネームが勝ちます。すべての
プロジェクトディレクトリを検索します。

**引数**：
- `sessionId` —— リネームするセッションの UUID
- `title` —— 新しいセッションタイトル（前後の空白は取り除かれます）

**送出**：
- `IllegalArgumentException` —— `sessionId` が妥当な UUID でない、または `title` が空の場合
- `FileNotFoundException` —— セッションファイルが見つからない場合
- `IOException` —— 書き込みに失敗した場合

### renameSession(String sessionId, String title, Path directory)

```java
public static void renameSession(
    String sessionId,
    String title,
    Path directory
) throws IOException
```

特定のプロジェクトディレクトリに限定してセッションをリネームします。

**引数**：
- `sessionId` —— リネームするセッションの UUID
- `title` —— 新しいセッションタイトル
- `directory` —— 検索するプロジェクトの作業ディレクトリ

### tagSession(String sessionId, String tag)

```java
public static void tagSession(
    String sessionId,
    @Nullable String tag
) throws IOException
```

セッションにタグを付けます。`null` を渡すと既存のタグを消します。タグは保存前に Unicode の
サニタイズが行われます。すべてのプロジェクトディレクトリを検索します。

**引数**：
- `sessionId` —— タグを付けるセッションの UUID
- `tag` —— タグ文字列、または消す場合は `null`。（`null` でない限り）サニタイズ後に空であっては
  いけません。

**送出**：
- `IllegalArgumentException` —— `sessionId` が不正、またはサニタイズ後に `tag` が空の場合
- `FileNotFoundException` —— セッションファイルが見つからない場合
- `IOException` —— 書き込みに失敗した場合

### tagSession(String sessionId, String tag, Path directory)

```java
public static void tagSession(
    String sessionId,
    @Nullable String tag,
    Path directory
) throws IOException
```

特定のプロジェクトディレクトリに限定してセッションにタグを付けます。

**引数**：
- `sessionId` —— タグを付けるセッションの UUID
- `tag` —— タグ文字列、または消す場合は `null`
- `directory` —— 検索するプロジェクトの作業ディレクトリ

### deleteSession(String sessionId)

```java
public static void deleteSession(String sessionId) throws IOException
```

JSONL ファイルを削除してセッションを完全に消します。サブエージェントのトランスクリプトを保持する
兄弟ディレクトリ `<sessionId>/` も（存在すれば）再帰的に削除します。論理削除が必要な場合は、代わりに
`tagSession(id, "__hidden")` を使い、一覧時に絞り込んでください。

**引数**：
- `sessionId` —— 削除するセッションの UUID

**送出**：
- `IllegalArgumentException` —— `sessionId` が妥当な UUID でない場合
- `FileNotFoundException` —— セッションファイルが見つからない場合
- `IOException` —— 削除に失敗した場合（サブエージェントのディレクトリの後始末はベストエフォートで、
  呼び出しを失敗させることはありません）

### deleteSession(String sessionId, Path directory)

```java
public static void deleteSession(
    String sessionId,
    Path directory
) throws IOException
```

特定のプロジェクトディレクトリに限定してセッションを削除します。

### forkSession(String sessionId)

```java
public static ForkSessionResult forkSession(String sessionId) throws IOException
```

セッションを新しい UUID とともに新しいブランチへフォークします。

**戻り値**：新しいセッションの UUID を含む `ForkSessionResult`

**送出**：
- `IllegalArgumentException` —— `sessionId` が妥当な UUID でない場合
- `FileNotFoundException` —— セッションファイルが見つからない場合
- `IOException` —— フォークに失敗した場合

### forkSession(String sessionId, Path directory)

```java
public static ForkSessionResult forkSession(
    String sessionId,
    Path directory
) throws IOException
```

特定のプロジェクトディレクトリに限定してセッションをフォークします。

### forkSession(String sessionId, Path directory, String upToMessageId, String title)

```java
public static ForkSessionResult forkSession(
    String sessionId,
    @Nullable Path directory,
    @Nullable String upToMessageId,
    @Nullable String title
) throws IOException
```

省略可能な切り詰め地点とカスタムタイトルを指定してセッションをフォークします。

**引数**：
- `sessionId` —— 元となるセッションの UUID
- `directory` —— プロジェクトディレクトリ（null は全プロジェクトを検索）
- `upToMessageId` —— このメッセージ UUID でトランスクリプトを切ります（この項目を含む）。null なら
  すべてコピー
- `title` —— フォークのカスタムタイトル。null なら元のタイトル + " (fork)" から導出

### listSessions(Path directory, Integer limit, int offset, boolean includeWorktrees)

```java
public static List<SDKSessionInfo> listSessions(
    Path directory,
    Integer limit,
    int offset,
    boolean includeWorktrees
)
```

オフセットによるページネーションに対応してセッションを一覧します。

**引数**：
- `directory` —— プロジェクトディレクトリ（null は全プロジェクト）
- `limit` —— 返すセッションの最大数
- `offset` —— 読み飛ばすセッション数（ページネーション用）
- `includeWorktrees` —— git の worktree のセッションを含める

## SessionStore を使うメソッド

これらのメソッドは、ローカルの `~/.claude/projects/` ファイルシステムではなく `SessionStore`
アダプタを通じてセッションを読み書きします。機能の完全なドキュメントは
[Session Store ガイド](./feature-session-store.md) を参照してください。

### projectKeyForDirectory(Path directory)

```java
public static String projectKeyForDirectory(@Nullable Path directory)
```

CLI と同じ realpath + NFC 正規化 + djb2 ハッシュによるサニタイズを使って、あるディレクトリの
`SessionStore` の `project_key` を求めます。`directory == null` のときは現在の作業ディレクトリが
既定になります。

**戻り値**：`SessionKey.projectKey()` に使えるサニタイズ済みのプロジェクトキー文字列。

### listSessionsFromStore(SessionStore, Path, Integer, int)

```java
public static List<SDKSessionInfo> listSessionsFromStore(
    SessionStore sessionStore,
    @Nullable Path directory,
    @Nullable Integer limit,
    int offset)
```

`SessionStore` からセッションを一覧します。`store.implementsListSessionSummaries()` が `true` を
返すときは高速経路を使い、そうでなければ同時実行数を 16 に制限したセッションごとの読み込みへ
フォールバックします。

**送出**：ストアが `listSessionSummaries()` も `listSessions()` も実装していない場合は
`IllegalStateException`。

### getSessionInfoFromStore(SessionStore, String, Path)

```java
public static @Nullable SDKSessionInfo getSessionInfoFromStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory)
```

ストアから単一セッションのメタデータを読み取ります。不正な UUID、存在しないセッション、サイドチェーンの
セッション、要約を取り出せないセッションでは `null` を返します。

### getSessionMessagesFromStore(SessionStore, String, Path, Integer, int)

```java
public static List<SessionMessage> getSessionMessagesFromStore(
    SessionStore sessionStore, String sessionId,
    @Nullable Path directory, @Nullable Integer limit, int offset)
```

ストアからセッションの会話トランスクリプト全体を読み取ります。不正な UUID や存在しないセッションでは
空のリストを返します。

### listSubagentsFromStore(SessionStore, String, Path)

```java
public static List<String> listSubagentsFromStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory)
```

`subagents/agent-<id>` 以下のストアのサブキーを列挙して、セッションのサブエージェント ID を一覧します。

**送出**：ストアが `listSubkeys()` を実装していない場合は `IllegalStateException`。

### getSubagentMessagesFromStore(SessionStore, String, String, Path, Integer, int)

```java
public static List<SessionMessage> getSubagentMessagesFromStore(
    SessionStore sessionStore, String sessionId, String agentId,
    @Nullable Path directory, @Nullable Integer limit, int offset)
```

ストアからサブエージェントのトランスクリプトを読み取ります。合成された `agent_metadata` エントリは
メッセージとしては返らず、各メッセージの `parentToolUseId`（そのサブエージェントを生成した Agent の
`tool_use`）と `parentAgentId`（入れ子のサブエージェントにおける生成元）を埋めるために読まれます。
そのエントリが存在しないか、id が文字列でない場合は両方 null です。

### renameSessionViaStore(SessionStore, String, String, Path)

```java
public static void renameSessionViaStore(
    SessionStore sessionStore, String sessionId, String title,
    @Nullable Path directory)
```

ストア内のそのセッションに `custom-title` エントリを追記します。

**送出**：`sessionId` が妥当な UUID でない、または `title` が空／空白のみの場合は
`IllegalArgumentException`。

### tagSessionViaStore(SessionStore, String, String, Path)

```java
public static void tagSessionViaStore(
    SessionStore sessionStore, String sessionId, @Nullable String tag,
    @Nullable Path directory)
```

`tag` エントリを追記します。`tag` に `null` を渡すと消去します。タグは保存前に Unicode の
サニタイズが行われます。

**送出**：不正な UUID や、サニタイズ後に空となるタグの場合は `IllegalArgumentException`。

### deleteSessionViaStore(SessionStore, String, Path)

```java
public static void deleteSessionViaStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory)
```

ストアからセッションを削除します。ストアが `delete()` を実装していない場合は no-op です
（WORM／追記専用のバックエンドに適切です）。

### forkSessionViaStore(SessionStore, String, Path, String, String)

```java
public static ForkSessionResult forkSessionViaStore(
    SessionStore sessionStore, String sessionId, @Nullable Path directory,
    @Nullable String upToMessageId, @Nullable String title) throws java.io.IOException
```

ストアを通じて、セッションを新しい UUID とともに新しいブランチへフォークします。ディスク上のフォークと
同じ UUID 振り直しの変換を行います —— ストレージ層のコピーだけでは**不十分**です。

**送出**：不正な UUID には `IllegalArgumentException`。元のセッションがストアに見つからない場合は
`FileNotFoundException`。

### importSessionToStore(String, SessionStore, Path)

```java
public static void importSessionToStore(
    String sessionId, SessionStore sessionStore, @Nullable Path directory)
    throws java.io.IOException
```

ローカルのディスク上にあるセッショントランスクリプトを `SessionStore` へ再生します。
`includeSubagents=true` と既定のバッチサイズ
（`TranscriptMirrorBatcher.MAX_PENDING_ENTRIES = 500`）を使う便利なオーバーロードです。

### importSessionToStore(String, SessionStore, Path, boolean, int)

```java
public static void importSessionToStore(
    String sessionId, SessionStore sessionStore, @Nullable Path directory,
    boolean includeSubagents, int batchSize) throws java.io.IOException
```

オプションを明示した完全版です。

**引数**：
- `includeSubagents` —— `<sessionDir>/subagents/**/*.jsonl` と `.meta.json` サイドカーを再帰的に
  取り込む
- `batchSize` —— `store.append()` 呼び出しあたりのエントリ数。`≤ 0` の値は既定を使います

**送出**：不正な UUID には `IllegalArgumentException`。セッションファイルが見つからない場合は
`NoSuchFileException`。

## バージョンのメソッド

### getVersion()

```java
public static String getVersion()
```

SDK のバージョン文字列を取得します。

**戻り値**：バージョン（例："0.1.3-SNAPSHOT"）

## 関連項目
- [シンプルなクエリのガイド](./feature-simple-queries.md)
- [MCP サーバーのガイド](./feature-mcp-servers.md)
- [セッション履歴のガイド](./feature-session-history.md)
- [Session Store のガイド](./feature-session-store.md)
