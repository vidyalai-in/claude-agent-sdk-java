# MCP サーバー（Model Context Protocol）

MCP（Model Context Protocol）を使うと、会話中に Claude が利用できるカスタムツールを作れます。SDK はインプロセスの SDK サーバーと、外部の stdio/SSE/HTTP サーバーの両方に対応しています。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../feature-mcp-servers.md)より古い場合があります。内容が食い違う場合は英語版が優先されます。コードブロックは英語版と同一のまま、翻訳していません。

## 目次
- [概要](#概要)
- [SDK MCP サーバーと外部サーバー](#sdk-mcp-サーバーと外部サーバー)
- [SDK MCP サーバーの作成](#sdk-mcp-サーバーの作成)
- [@Tool アノテーションを使う](#tool-アノテーションを使う)
- [ツールのタイトルとアノテーション](#ツールのタイトルとアノテーション)
- [プログラムによるツール作成](#プログラムによるツール作成)
- [ツールのスキーマ](#ツールのスキーマ)
- [ツールの実行](#ツールの実行)
- [プロトコルの詳細](#プロトコルの詳細)
- [カスタム MCP ハンドラー](#カスタム-mcp-ハンドラー)
- [外部 MCP サーバー](#外部-mcp-サーバー)
- [MCP サーバーのステータス](#mcp-サーバーのステータス)
- [例](#例)
- [ベストプラクティス](#ベストプラクティス)

## 概要

MCP（Model Context Protocol）は、会話中に Claude が呼び出せるカスタムツールを定義するための標準的な方法を提供します。SDK は次に対応しています。

1. **SDK MCP サーバー**（インプロセス）— アプリケーション内で直接動作
2. **外部 MCP サーバー** — 別プロセスとして動作（stdio/SSE/HTTP）

**主な利点：**
- カスタム機能で Claude の能力を拡張できる
- アプリケーションの状態や API にアクセスできる
- 型安全なツール定義
- スキーマの自動生成
- CompletableFuture による非同期実行

## SDK MCP サーバーと外部サーバー

### SDK MCP サーバー（インプロセス）

**利点：**
- ✅ **性能が良い**：IPC のオーバーヘッドがない
- ✅ **デプロイが簡単**：単一プロセス
- ✅ **デバッグが容易**：同じプロセス、同じデバッガー
- ✅ **直接アクセス**：アプリケーションの状態に直接触れる
- ✅ **型安全**：Java の型システム
- ✅ **シリアライズ不要**：直接のメソッド呼び出し

**ユースケース：**
- アプリ固有のツール
- データベースアクセス
- ビジネスロジック
- 内部 API
- テストとプロトタイピング

### 外部 MCP サーバー

**利点：**
- ✅ **言語非依存**：どの言語でも書ける
- ✅ **隔離**：別のプロセス空間
- ✅ **再利用性**：複数のアプリで共有できる
- ✅ **セキュリティ**：プロセスのサンドボックス化

**ユースケース：**
- サードパーティのツール
- 特定言語のライブラリ（Node.js、Python）
- 共有のツールサーバー
- レガシーシステム

## SDK MCP サーバーの作成

SDK MCP サーバーの作り方は 3 通りあります。

1. `@Tool` アノテーションを使う（宣言的）
2. `SdkMcpTool.create()` を使う（プログラム的）
3. `SdkMcpServer.create()` を使う（手動）

### クイックスタート

```java
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.mcp.Tool;
import in.vidyalai.claude.sdk.mcp.ToolResult;
import in.vidyalai.claude.sdk.types.mcp.McpSdkServerConfig;
import java.util.concurrent.CompletableFuture;

public class MyTools {
    @Tool(name = "greet", description = "Greet a user")
    public CompletableFuture<ToolResult> greet(String name) {
        return CompletableFuture.completedFuture(
            ToolResult.text("Hello, " + name + "!")
        );
    }
}

// Create server from annotated class
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "my-tools",
    new MyTools()
);

// Use in options
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("tools", server))
    .allowedTools(List.of("mcp__tools__greet"))
    .build();
```

## @Tool アノテーションを使う

`@Tool` アノテーションは、ツールを宣言的に定義する方法を提供します。

### 基本のアノテーション

```java
import in.vidyalai.claude.sdk.mcp.Tool;
import in.vidyalai.claude.sdk.mcp.ToolResult;
import java.util.concurrent.CompletableFuture;
import java.util.Map;

public class Calculator {

    @Tool(name = "add", description = "Add two numbers")
    public CompletableFuture<ToolResult> add(double a, double b) {
        double result = a + b;
        return CompletableFuture.completedFuture(
            ToolResult.text("Result: " + result)
        );
    }

    @Tool(name = "multiply", description = "Multiply two numbers")
    public CompletableFuture<ToolResult> multiply(Map<String, Object> args) {
        double a = ((Number) args.get("a")).doubleValue();
        double b = ((Number) args.get("b")).doubleValue();
        return CompletableFuture.completedFuture(
            ToolResult.text("Result: " + (a * b))
        );
    }
}
```

## ツールのタイトルとアノテーション

### ツールのタイトル

技術的なツール名とは別の分かりやすい表示名を与えるには `title` 属性を使います。

```java
@Tool(
    name = "fetch_user_data",
    title = "User Data Fetcher",
    description = "Fetch user data from the database"
)
public CompletableFuture<ToolResult> fetchUserData(String userId) {
    // ...
}
```

タイトルは、MCP 2025-06-18 が置く場所であるツールのトップレベルと、それ以前の版が参照する `annotations` の内側の両方に送られます — トップレベルのフィールドより古いクライアントは知らないものを取り除くからです。ツールがアノテーションを宣言しているかどうかに関わらず、タイトルは `tools/list` に届きます。

### ツールのアノテーション（セマンティックなヒント）

ツールに振る舞いのヒントを付けるには `annotations` 属性を使います。`ToolAnnotations` インターフェースを実装してください。

```java
import in.vidyalai.claude.sdk.mcp.ToolAnnotations;

public class ReadOnlyHints implements ToolAnnotations {
    @Override
    public Boolean readOnlyHint() { return true; }
}

@Tool(
    name = "read_file",
    title = "File Reader",
    description = "Read the contents of a file",
    annotations = ReadOnlyHints.class
)
public CompletableFuture<ToolResult> readFile(String path) {
    // ...
}
```

利用できるアノテーションのヒント：

| ヒント | 説明 |
|------|-------------|
| `readOnlyHint` | ツールはデータを読むだけで、状態を変更しない |
| `destructiveHint` | ツールは元に戻せない操作を行う |
| `idempotentHint` | 同じ入力での繰り返し呼び出しは同じ結果になる |
| `openWorldHint` | ツールは外部システムに問い合わせ、結果に上限がない |
| `maxResultSizeChars` | CLI が一時ファイルへ退避する前の結果の最大文字数 |

### maxResultSizeChars（Anthropic 固有のヒント）

`maxResultSizeChars` アノテーションは、CLI のレイヤー 2 のツール結果退避しきい値を制御します。デフォルトでは、CLI は約 50K 文字を超えるツール結果を一時ファイルに退避します。このアノテーションを設定すると、特定のツールについてそのしきい値を上げ（または下げ）られます。

MCP SDK の Zod スキーマは未知のアノテーションフィールドを取り除くため、`maxResultSizeChars` は `tools/list` の JSONRPC レスポンス内で、名前空間付きキー `anthropic/maxResultSizeChars` として `_meta` 経由で転送されます。

```java
ToolAnnotations hints = ToolAnnotations.builder()
    .readOnlyHint(true)
    .maxResultSizeChars(200_000)  // Allow up to 200K chars
    .build();

SdkMcpTool<Map<String, Object>> bigResultTool = SdkMcpTool.builder("large_query", "Query returning large results")
    .inputSchema(Map.of("type", "object", "properties", Map.of(
        "query", Map.of("type", "string")
    ), "required", List.of("query")))
    .handler(args -> CompletableFuture.completedFuture(
        ToolResult.text(runLargeQuery((String) args.get("query")))
    ))
    .annotations(hints)
    .build();
```

### メソッドのシグネチャ

アノテーションを付けたメソッドは 2 つのシグネチャを取れます。

#### 1. 型付きパラメーター（推奨）

```java
@Tool(name = "greet", description = "Greet a user")
public CompletableFuture<ToolResult> greet(String firstName, String lastName) {
    return CompletableFuture.completedFuture(
        ToolResult.text("Hello, " + firstName + " " + lastName + "!")
    );
}
```

**要件：**
- パラメーター名を保持するため `-parameters` フラグ付きでコンパイルする。または、各パラメーターに
  [`@ToolParam(name = ...)`](#toolparam-でパラメーターを説明する) で名前を付ける
- パラメーターは自動的に JSON Schema にマッピングされ、各引数は同じ型ごとのルールでパラメーターの Java 型に
  変換される。そのため、スキーマが許すものはメソッドが受け取れるものと正確に一致する

**型のマッピング**（0.2.6 以降。それ以前は、`List`、配列、enum、`char`、`Optional` のパラメーターには、妥当な
引数をすべて拒否するスキーマが付いていました）：

| Java のパラメーター型 | 公開されるスキーマ | メソッドが受け取るもの |
|---|---|---|
| `String`、`CharSequence` | `{"type": "string"}` | その文字列 |
| `char`、`Character` | `{"type": "string", "minLength": 1, "maxLength": 1}` | その文字 |
| `int`、`long`、`short`、`byte`、それらのボックス型、`BigInteger` | `{"type": "integer"}` | その数値 |
| `double`、`float`、その他の `Number` 型 | `{"type": "number"}` | その数値 |
| `boolean`、`Boolean` | `{"type": "boolean"}` | その真偽値 |
| enum | `{"type": "string", "enum": [...]}`（定数の JSON 名） | その定数 |
| `List<T>`、`Set<T>`、その他のコレクション、`T[]` | `{"type": "array", "items": <T>}` | `T` のコレクションまたは配列 |
| `Optional<T>` | `T` のスキーマ。必須ではない | 省略された場合は `Optional.empty()` |
| `UUID` / `URI` | `{"type": "string", "format": "uuid"}` / `"uri"` | パースされた値 |
| `LocalDate` | `{"type": "string", "format": "date"}` | パースされた値 |
| `Instant`、`OffsetDateTime` | `{"type": "string", "format": "date-time"}` | パースされた値 |
| `Path`、`File`、`ZoneId`、その他の `java.time` 型 | `{"type": "string"}` | パースされた値 |
| `Map<String, V>` | `{"type": "object", "additionalProperties": <V>}`（`Map<String, Object>` では `true`） | 値が変換されたマップ |
| レコード | コンポーネントごとに 1 つのプロパティを持つ入れ子のオブジェクト | そのレコード |
| `Object` | `{"type": "object"}` | JSON の値そのまま |

レコードのコンポーネントは、`Optional` であるか `@ToolParam(required = false)` が付いていない限り必須です。
コンポーネントに付けた `@ToolParam` は、パラメーターの場合と同じようにそれを説明します。必須ではない
パラメーターが省略された場合は `null`（プリミティブの場合はゼロ値）を受け取ります。

#### `@ToolParam` でパラメーターを説明する

`@ToolParam(name, description, required)` は `@Tool` メソッドのパラメーターまたはレコードのコンポーネントに
付けるアノテーションです。Python SDK の `Annotated[str, "..."]` に相当する Java の仕組みです：

- `description` はスキーマ内のプロパティの `description` になります。これはモデルが何を渡すかを決めるために
  読むものです。
- `name` はモデルが使う引数名を設定します。既定は Java のパラメーター名なので、`-parameters` なしでコンパイル
  したコードや、Java の識別子として正しくない名前の場合に必要になります。
- `required = false` は引数を省略可能にします。`Optional` のパラメーターは、この指定にかかわらず決して必須に
  なりません。

```java
import in.vidyalai.claude.sdk.mcp.Tool;
import in.vidyalai.claude.sdk.mcp.ToolParam;
import in.vidyalai.claude.sdk.mcp.ToolResult;
import java.util.List;
import java.util.Optional;

public class PaintTools {

    public enum Color { WHITE, CREAM, SAGE, NAVY }

    @Tool(name = "paint_walls", description = "Order paint for some walls of a room")
    public ToolResult paintWalls(
            @ToolParam(description = "Names of the walls to paint") List<String> walls,
            @ToolParam(description = "Paint color") Color color,
            @ToolParam(description = "Coats of paint (default 2)") Optional<Integer> coats) {
        int n = coats.orElse(2);
        return ToolResult.text("Ordered " + (walls.size() * n) + " litres of " + color + ".");
    }
}
```

公開されるスキーマ：

```json
{
    "type": "object",
    "properties": {
        "walls": {"type": "array", "items": {"type": "string"},
                  "description": "Names of the walls to paint"},
        "color": {"type": "string", "enum": ["WHITE", "CREAM", "SAGE", "NAVY"],
                  "description": "Paint color"},
        "coats": {"type": "integer", "description": "Coats of paint (default 2)"}
    },
    "required": ["walls", "color"]
}
```

`AutoSchemaGeneration` はこのツールを CLI に対して実行し、公開するスキーマを出力します：
`mvn exec:java -Dexec.mainClass="examples.AutoSchemaGeneration" -Dexec.args="typed" -pl examples`。

#### 2. Map パラメーター

```java
@Tool(name = "greet", description = "Greet a user")
public CompletableFuture<ToolResult> greet(Map<String, Object> args) {
    String name = (String) args.get("name");
    return CompletableFuture.completedFuture(
        ToolResult.text("Hello, " + name + "!")
    );
}
```

**次の場合に使います：**
- パラメーターを手動で取り出したい
- スキーマが複雑（[明示的なスキーマ](#明示的なスキーマ)と組み合わせる）

引数パラメーターが `Map` 1 つだけのメソッドは、引数オブジェクト全体を受け取り、空のオブジェクトスキーマを
公開します。型付きパラメーターで省略可能な引数を扱うには、`Optional<T>` または `@ToolParam(required = false)`
を使ってください。

### スキーマの自動生成

型付きパラメーターを使うと、SDK が JSON Schema を自動生成します。

```java
@Tool(name = "search", description = "Search for items")
public CompletableFuture<ToolResult> search(String query, int limit) {
    // Implementation
}
```

生成されるスキーマ：
```json
{
    "type": "object",
    "properties": {
        "query": {
            "type": "string"
        },
        "limit": {
            "type": "integer"
        }
    },
    "required": ["query", "limit"]
}
```

すべてのパラメーターは、`Optional` であるか `@ToolParam(required = false)` が付いていない限り必須です。
サポートされるすべてのパラメーター型は[型のマッピング](#1-型付きパラメーター推奨)を参照してください。

### 明示的なスキーマ

複雑なスキーマには、明示的な JSON を渡してください。

```java
@Tool(
    name = "search",
    description = "Search for items",
    inputSchema = """
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The search query"
                },
                "limit": {
                    "type": "integer",
                    "description": "Max results",
                    "default": 10,
                    "minimum": 1,
                    "maximum": 100
                },
                "filters": {
                    "type": "object",
                    "properties": {
                        "category": {"type": "string"},
                        "minPrice": {"type": "number"}
                    }
                }
            },
            "required": ["query"]
        }
        """
)
public CompletableFuture<ToolResult> search(Map<String, Object> args) {
    // Implementation
}
```

### アノテーションからサーバーを作る

```java
// Create server from annotated instance
Calculator calculator = new Calculator();
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "calculator",
    calculator
);

// Or with version
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "calculator",
    "1.0.0",
    calculator
);
```

## プログラムによるツール作成

動的にツールを作るには `SdkMcpTool.create()` かビルダーを使います。

```java
import in.vidyalai.claude.sdk.mcp.SdkMcpTool;
import java.util.concurrent.CompletableFuture;

// Simple creation
SdkMcpTool<Map<String, Object>> uppercaseTool = SdkMcpTool.create(
    "uppercase",                           // Tool name
    "Convert text to uppercase",           // Description
    Map.of(                                // JSON Schema
        "type", "object",
        "properties", Map.of(
            "text", Map.of(
                "type", "string",
                "description", "The text to convert"
            )
        ),
        "required", List.of("text")
    ),
    args -> {                              // Handler function
        String text = (String) args.get("text");
        return CompletableFuture.completedFuture(
            ToolResult.text(text.toUpperCase())
        );
    }
);

// With title and annotations using builder
ToolAnnotations hints = ToolAnnotations.builder()
    .readOnlyHint(true)
    .idempotentHint(true)
    .build();

SdkMcpTool<Map<String, Object>> searchTool = SdkMcpTool.builder("search", "Search records")
    .title("Record Search")
    .inputSchema(Map.of("type", "object", "properties", Map.of(
        "query", Map.of("type", "string")
    ), "required", List.of("query")))
    .handler(args -> CompletableFuture.completedFuture(
        ToolResult.text("Results for: " + args.get("query"))
    ))
    .annotations(hints)
    .build();
```

#### 入力スキーマの省略記法

値がすべて Java の型（`Class`、その他の `java.lang.reflect.Type`、または Jackson の `TypeReference`）である
マップは、Python SDK の `@tool` が受け取る `{name: type}` の省略記法（`{"text": str}`）として読まれます：各キーは
必須の引数で、その値は引数の Java 型です。サーバーはこれを、`@Tool` のパラメーターと同じ型ごとのルールで
オブジェクトスキーマに展開します。

```java
SdkMcpTool<Map<String, Object>> shout = SdkMcpTool.create(
    "shout",
    "Convert text to uppercase",
    Map.of("text", String.class),          // -> {"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}
    args -> CompletableFuture.completedFuture(
        ToolResult.text(((String) args.get("text")).toUpperCase())));
```

ジェネリック型には Jackson の `TypeReference` を使ってください。たとえば
`Map.of("tags", new TypeReference<List<String>>() {})` です。それ以外のマップは JSON Schema であり、トップレベルの
`type` があるかどうかにかかわらず、書かれたとおりに公開されます：`Map.of("type", "object")` は開いたオブジェクト
スキーマのままで、`properties`/`required` だけのスキーマや `anyOf` は、MCP がすべての入力スキーマに求める
`"type": "object"` を加えたうえで書かれたとおりに送られます。Python 風の `Map.of("text", "string")` のように
JSON Schema のキーワードをまったく持たないマップは、警告として記録されます：これはどんな引数も受け付けてしまう
うえ、省略記法では値に Java の型が必要です（`Map.of("text", String.class)`）。

### ツールからサーバーを作る

```java
import in.vidyalai.claude.sdk.mcp.SdkMcpServer;

// Create multiple tools
List<SdkMcpTool<?>> tools = List.of(
    uppercaseTool,
    lowercaseTool,
    reverseTool
);

// Create server
SdkMcpServer server = SdkMcpServer.create(
    "text-tools",  // Server name
    "1.0.0",       // Version
    tools          // Tool list
);

// Get config for options
McpSdkServerConfig config = server.toConfig();
```

## ツールのスキーマ

### JSON Schema の形式

ツールの `inputSchema` は JSON Schema です。引数はハンドラーが動く前にこれに照らして検証されます（[引数の検証](#引数の検証)を参照）。方言はスキーマの `$schema` キーワードがあればそこから取られ、無い場合は Draft 2020-12 —— MCP 仕様が準拠している方言 —— と見なされます。Draft 4、6、7、2019-09、2020-12 のいずれも解釈できます。

```java
Map<String, Object> schema = Map.of(
    "type", "object",
    "properties", Map.of(
        "name", Map.of(
            "type", "string",
            "description", "User's name",
            "minLength", 1
        ),
        "age", Map.of(
            "type", "integer",
            "description", "User's age",
            "minimum", 0,
            "maximum", 150
        ),
        "email", Map.of(
            "type", "string",
            "format", "email"
        )
    ),
    "required", List.of("name", "email")
);
```

### 対応する型

- `string` — テキスト値
- `integer` — 整数
- `number` — 浮動小数点数
- `boolean` — true/false
- `object` — ネストしたオブジェクト
- `array` — 値のリスト
- `null` — null 値

### 制約

```java
Map.of(
    // String constraints
    "minLength", 1,
    "maxLength", 100,
    "pattern", "^[A-Z][a-z]+$",
    "format", "email",  // email, uri, date-time, etc.

    // Number constraints
    "minimum", 0,
    "maximum", 100,
    "exclusiveMinimum", true,
    "multipleOf", 5,

    // Array constraints
    "minItems", 1,
    "maxItems", 10,
    "uniqueItems", true,

    // Enum values
    "enum", List.of("red", "green", "blue")
);
```

## ツールの実行

### ToolResult

`SdkMcpTool` のハンドラーは `CompletableFuture<ToolResult>` を返します。`@Tool` メソッドは `ToolResult` または
`CompletableFuture<ToolResult>` のどちらかを返せます。それ以外の値は `String.valueOf` のテキストとして送られます。
`null` を返す（または future が `null` で完了する）と、`isError` の「結果なし」の応答になります。`void`、`Void`、
`CompletableFuture<Void>` と宣言されたメソッドは、テキストが `"null"` の成功で応答します
（[失敗のセマンティクス](#失敗のセマンティクス)を参照）：

```java
import in.vidyalai.claude.sdk.mcp.ToolResult;

// Text result
ToolResult.text("Hello, world!");

// JSON result — serialized into a single text block
ToolResult.json(Map.of("status", "success", "data", data));

// Image result (Base64)
ToolResult.image(base64Data, "image/png");

// Several content blocks
ToolResult.builder()
    .addText("Result:")
    .addJson(data)
    .addResourceLink("Full report", "file:///tmp/report.md", "Every row")
    .build();

// From raw MCP content blocks, normalized (see below)
ToolResult.ofContent(List.of(
    Map.of("type", "text", "text", "Result:"),
    Map.of("type", "resource_link", "name", "Docs", "uri", "https://example.com")
));

// Error result
ToolResult.error("Failed to process request");
```

#### コンテンツブロック

MCP は CLI が描画できる以上のコンテンツ型を定義しているため、表示できないものはテキストに畳み込まれます — Python SDK が行う変換と同じです。

| ブロック | 変換後 |
|---|---|
| `text` | そのまま |
| `image` | そのまま |
| `resource_link` | テキスト：名前、URI、説明をそれぞれ 1 行に。空のものは省略（すべて無い場合は `Resource link`） |
| `text` を持つ `resource` | そのテキスト |
| バイナリデータを持つ `resource` | 破棄し、`WARNING` で記録 |
| それ以外 | 破棄し、`WARNING` で記録 |

`addResourceLink(...)` と `addResource(...)` にも同じ規則が適用されるため、ハンドラーはそれを知らなくてもブロック単位で結果を組み立てられます。

### 非同期実行

ツールは CompletableFuture を使って非同期に実行されます。

```java
@Tool(name = "fetch_data", description = "Fetch data from API")
public CompletableFuture<ToolResult> fetchData(String url) {
    // Async HTTP request
    return httpClient.sendAsync(request, BodyHandlers.ofString())
        .thenApply(response -> ToolResult.text(response.body()))
        .exceptionally(e -> ToolResult.error(e.getMessage()));
}
```

### エラー処理

モデルに見せるべき失敗は `ToolResult.error(...)` を返して報告します。これは `isError: true` を伴う結果を生成します。

```java
@Tool(name = "divide", description = "Divide two numbers")
public CompletableFuture<ToolResult> divide(double a, double b) {
    if (b == 0) {
        return CompletableFuture.completedFuture(
            ToolResult.error("Cannot divide by zero")
        );
    }

    return CompletableFuture.completedFuture(
        ToolResult.text("Result: " + (a / b))
    );
}
```

すべてを自分で捕捉する必要はありません。例外をスローするハンドラーや、例外的に完了する `CompletableFuture` も同じように報告されます（下記の[失敗のセマンティクス](#失敗のセマンティクス)を参照）。

### 引数の検証

ハンドラーが動く前に、`tools/call` の引数がそのツールの宣言した `inputSchema` に照らして検証されます。これは MCP サーバーに求められていること —— *「サーバーはすべてのツール入力を検証**しなければならない**」* —— であり、ハンドラーは自分が公開した契約に合致する引数しか見ないということでもあります。

合致しない呼び出しは `isError: true` のツール結果として返り、テキストは `Input validation error:` で始まります。そして**ハンドラーは呼ばれません**。

```
{"count": 21}            -> handler runs, returns its result
{}                       -> Input validation error: required property 'count' not found
{"count": "twenty-one"}  -> Input validation error: /count string found, integer expected
```

頼りにしてよい帰結が 2 つあります。

- 副作用のあるツールが、受け入れると合意していない引数で失敗する前に、処理を中途半端に適用してしまうことがない。
- モデルは問題のあるプロパティ名を示す一文を受け取り、それに基づいて動ける。ハンドラーが欠落や型違いの値を読もうとしてたまたま投げた例外ではない。

スキーマが無い、あるいは空のツールは検証されません — 照らし合わせる対象が無いからです。

#### 妥当な JSON Schema ではないスキーマ

検証は Python SDK と同様に**クローズドに失敗**します。サーバーの構築時に各 `inputSchema` がその方言のメタスキーマに照らして検査され、通らなかったツールは `WARNING` で記録され、以降そのツールへの呼び出しはハンドラーを動かさずに `isError` を返します。

```
{"type": "object", "properties": "not-an-object"}
    -> Tool 'x' has an inputSchema this server cannot use, so it cannot be
       called: /properties string found, object expected
```

これは見た目より重要です。バリデーターは壊れたスキーマを平然と受け入れ、その後それに照らして誤った検証を行います。`{"type": "bogus"}` はコンパイルできても何にもマッチしないため、あらゆる呼び出しが本当の欠陥ではなく呼び出し側の引数を挙げて失敗します。一方 `"properties": "a string"` は完全に無視され、すべての呼び出しを無検査で通してしまいます。どちらもハンドラーを動かしてよい状態ではありません。

このテキストは意図的に `Input validation error:` で始めて**いません**。その接頭辞はモデルに「引数が間違っていた」と伝えるものですが、壊れたスキーマはモデルが回避できないサーバー側の欠陥であり、それを誤ってラベル付けすると果てしない再試行を招きます。未知のキーワードは合法のままです — `x-vendor` 拡張を持つスキーマは問題なく通ります。

### 失敗のセマンティクス

各種の失敗が呼び出し側にどう届くか：

| 状況 | レスポンス | モデルが見るもの |
|---|---|---|
| ハンドラーが `ToolResult.error(msg)` を返す | 結果、`isError: true` | `msg` |
| ハンドラーがスローする、または future が失敗する | 結果、`isError: true` | 例外メッセージ。null/空白のときはそのクラス名 |
| `SdkMcpTool` のハンドラーまたは `@Tool` メソッドが `null` を返す、または future が `null` で完了する | 結果、`isError: true` | `Tool '<name>' returned no result` / `… completed with no result`（0.2.6 より前は呼び出しに応答が返されないか、`@Tool` メソッドの `null` がテキスト `"null"` として送られていました）。例外：`void`、`Void`、または `CompletableFuture<Void>`/`CompletionStage<Void>` と宣言された `@Tool` メソッドには返す結果がないため、引き続きテキストが `"null"` の成功で応答します |
| 結果が不正なコンテンツブロック（`text` のない text など）を含む | 結果、`isError: true` | そのブロックが不正である理由 |
| 引数が `inputSchema` に合致しない | 結果、`isError: true` | `Input validation error: …`（ハンドラーは動かない） |
| `inputSchema` が妥当な JSON Schema でない | 結果、`isError: true` | `… inputSchema this server cannot use …`（ハンドラーは動かない） |
| ツール名が登録されていない | 結果、`isError: true` | `Tool '<name>' not found` |
| 呼び出しがキャンセルされた | JSON-RPC エラー `-32800` | 何も見ない。CLI はすでに諦めている |
| このサーバーが実装していないメソッド | JSON-RPC エラー `-32601` | 何も見ない。モデルはこれらを送らない |
| `params` が欠落または不正 | JSON-RPC エラー `-32602` | 何も見ない。同上 |

*ツール呼び出し*が遭遇しうるものはすべて**ツール実行エラー**です。呼び出しは処理され、たまたま失敗を記述する結果を生んだのであり、そのテキストはモデルが読んで適応できる出力として届きます。JSON-RPC エラーは、リクエストがそもそも処理できなかったことを意味し、モデルはそれを目にしません — 未知のツールも結果として報告されるのはそのためで、Python SDK と揃っています。これらのセマンティクスは SDK 自身のものなので、ツールはどちらでも同じように振る舞います。

### 実行中のツールをキャンセルする

CLI は MCP ツール呼び出しに独自のタイムアウト（`MCP_TOOL_TIMEOUT`）を課します。それが発火すると CLI は待つのをやめ、MCP の `notifications/cancelled` を送ります。SDK は保留中の呼び出しに `-32800` で答え、ハンドラーが最終的に返すものは破棄します。

ハンドラー自身は、見に行かない限り動き続けます。`CompletableFuture` は外部から中断できず — `cancel(true)` は future を完了させるだけで処理はそのまま — なので、長くかかる処理や副作用のある処理を行うツールは、引数と並んで `ToolCallContext` を受け取るべきです。

```java
SdkMcpTool<Map<String, Object>> crawl = SdkMcpTool.create(
        "crawl", "Fetch every page under a URL", schema,
        (args, context) -> CompletableFuture.supplyAsync(() -> {
            List<String> pages = new ArrayList<>();
            for (String url : urlsFrom(args)) {
                if (context.isCancelled()) {
                    break;              // nobody is waiting for this any more
                }
                pages.add(fetch(url));
            }
            return ToolResult.text(String.join("\n", pages));
        }));
```

`context.onCancel(runnable)` は、ポーリングできない処理 — ブロッキング読み取りや他サービスへの呼び出し — に対して、リソースを閉じる場所を与えます。呼び出しがすでにキャンセルされている場合は即座に実行されます。`context.throwIfCancelled()` は、巻き戻したいハンドラー向けのチェックポイント版です。

引数だけを受け取るハンドラーは従来どおり動きます。単にキャンセルを観測できないだけです。`@Tool` を付けたメソッドはシグネチャのどこにでも `ToolCallContext` パラメーターを宣言できます — それは注入され、ツールの公開スキーマには現れません。

切断も同じ効果を持ちます。クライアントを閉じると進行中の呼び出しは放棄されるので、中断できないツールのせいでシャットダウンが止まることはありません。

その呼び出しを運んだ制御リクエストに対する `control_cancel_request` も同様です：コンテキストはキャンセルされ、応答は送られません。[アーキテクチャ → キャンセルされた制御リクエスト](./architecture.md#キャンセルされた制御リクエスト)を参照してください。

ハンドラーの失敗はスタックトレース付きでローカルにも `WARNING` で記録されるため、クラッシュしたツールはモデルのトランスクリプトだけが記録という状態にならずデバッグできます。

### 長時間かかる処理

```java
@Tool(name = "process_large_file", description = "Process a large file")
public CompletableFuture<ToolResult> processFile(String path) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            // Long-running operation
            byte[] data = Files.readAllBytes(Path.of(path));
            String result = processData(data);
            return ToolResult.text("Processed: " + result);
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    });
}
```

## プロトコルの詳細

### プロトコルのバージョン

サーバーは `2025-11-25`、`2025-06-18`、`2024-11-05` を、新しい順で公表します（`2025-11-25` は 0.2.6 以降）。`initialize` では、話せるバージョンをクライアントが要求していればそれをそのまま返し、そうでなければ自分が話せる最新のものを返します — 仕様が定める握手のとおりです。

`2025-11-25` を名乗るのは、この版が追加するもののうち、ツールだけのサーバーにとって必須のものが何もないからです：アイコン、タスク、URL によるエリシテーション、ツール付きのサンプリングは省略可能か能力によって制御されるものであり、入力検証の失敗はすでにツール実行エラーとして報告されており、いまや既定の方言となった JSON Schema 2020-12 は、すでに引数の検証に使われている方言です。

`2025-03-26` は意図的に名乗りません。この版は JSON-RPC のバッチを*受信*側で必須としましたが、バッチはトップレベルの配列であり、これらのメッセージを運ぶコントロールリクエストはそれをマップとして型付けしているため表現できません。SDK が唯一の必須変更を守れないバージョンを名乗ることは、クライアントがそれに基づいて動く約束をすることになります。

### メソッド

`initialize`、`ping`、`tools/list`、`tools/call` を実装しています。`tools/list` はツールを登録順に返します。`arguments` が存在するもののオブジェクトではない `tools/call` には `-32602` で答えます。`arguments` が存在しないか `null` の場合は、引数なしを意味します。それ以外はすべて `-32601` で答えますが、これは欠落ではなく正しい動作です。サーバーは `tools` 能力のみを公表するので、準拠したクライアントが resources、prompts、completions を求めることはありません。（CLI に対して検証済み：`tools` のみを宣言するサーバーに `resources/list` や `prompts/list` が送られることはありません。）

### 通知

JSON-RPC の通知 — `method` があり `id` が無いメッセージ — には JSON-RPC の要求どおり決してレスポンスを返しません。`notifications/initialized` と `notifications/cancelled` は処理し、それ以外は `FINE` で記録して捨てます。その通知を運んだ*コントロールリクエスト*には `{"jsonrpc": "2.0", "result": {}}` で応答します。そうしないと CLI が永遠に待ち続けます。

`method` がまったく無いメッセージは JSON-RPC のレスポンスか、ただのゴミです。SDK は CLI にリクエストを送らないので、そうして届いたものは対応づける相手がありません。答えるのではなく無視します。

## カスタム MCP ハンドラー

`McpSdkServerConfig` が保持するのは `McpMessageHandler` であって、`SdkMcpServer` に限りません。`SdkMcpServer` が扱わない MCP の部分 — resources、prompts、completions — を提供したり、サードパーティの MCP ライブラリを適合させたりするには、このインターフェースを直接実装します。

```java
public class MyMcpServer implements McpMessageHandler {

    @Override
    public CompletableFuture<Map<String, Object>> handleMessage(Map<String, Object> message) {
        // Return the JSON-RPC response for a request, or null for a
        // notification, which must never be answered.
        ...
    }

    @Override
    public void close() {
        // Optional: the connection using this handler is going away.
    }
}

var options = ClaudeAgentOptions.builder()
        .mcpServers(Map.of("mine", new McpSdkServerConfig("mine", new MyMcpServer())))
        .build();
```

CLI が何を送るかは `initialize` から返す `capabilities` で決まるため、resources を公表したハンドラーには resources が求められます。

`close()` は「あなたを使っている接続が消えます」という意味であり、「停止せよ」ではありません。1 つのハンドラーが複数のクライアントに登録されうるので、冪等で、その後も使える状態でなければなりません。

### クライアントごとに 1 つのセッション：`openSession()`

各 `ClaudeSDKClient`（または単発の `query`）は、起動時に設定されたサーバーごとに 1 回 `openSession()` を呼びます。そのサーバー宛てのすべてのメッセージを返されたハンドラーに送り、シャットダウン時には登録されたハンドラーではなく、そのハンドラーの `close()` を呼びます。

`SdkMcpServer.openSession()` は新しいセッションを返します。このセッションはツールとスキーマを共有し、接続ごとのものをすべて所有します：進行中の呼び出し、それらを決着させる `notifications/cancelled`、そしてそのセッションの呼び出しだけを放棄する `close()` です。そのため、1 つの `SdkMcpServer` を任意の数の並行クライアントで共有できます。0.2.6 より前は、それらのクライアントが 1 つの呼び出しテーブルを共有していました：JSON-RPC id が衝突し、あるクライアントのキャンセルが別のクライアントの呼び出しに届くことがあり、あるクライアントのクローズが別のクライアントの呼び出しを失敗させていました。Python SDK も、クエリごとに 1 つのブリッジを持つことで同じ分離を保っています。

既定の `McpMessageHandler.openSession()` は `this` を返すため、すべての接続が独自のハンドラーを共有します。これは、このメソッドが存在する前に書かれたハンドラーの挙動と同じです。接続ごとの状態（進行中のリクエスト、キャンセル）を持つ独自のハンドラーは、新しいオブジェクトを返すべきです：

```java
public class MyMcpServer implements McpMessageHandler {

    @Override
    public CompletableFuture<Map<String, Object>> handleMessage(Map<String, Object> message) {
        ...
    }

    @Override
    public McpMessageHandler openSession() {
        return new MyMcpServer();   // per-connection state lives here
    }
}
```

1 つのセッション内では、まだ進行中の JSON-RPC id は、レスポンスが誤った呼び出し側に届く危険を冒す代わりに `-32603` で拒否されます。

## 外部 MCP サーバー

### Stdio サーバー

```java
import in.vidyalai.claude.sdk.types.mcp.McpStdioServerConfig;

McpStdioServerConfig server = new McpStdioServerConfig(
    "node",                              // Command
    List.of("path/to/server.js"),        // Arguments
    Map.of("NODE_ENV", "production")     // Environment variables
);

var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("external", server))
    .build();
```

### SSE サーバー

```java
import in.vidyalai.claude.sdk.types.mcp.McpSseServerConfig;

McpSseServerConfig server = new McpSseServerConfig(
    "http://localhost:8080/sse"  // SSE endpoint URL
);
```

### HTTP サーバー

```java
import in.vidyalai.claude.sdk.types.mcp.McpHttpServerConfig;

McpHttpServerConfig server = new McpHttpServerConfig(
    "http://localhost:8080"  // Base URL
);
```

### 混在させる

SDK サーバーと外部サーバーは一緒に使えます。

```java
// SDK server (in-process)
McpSdkServerConfig sdkServer = ClaudeSDK.createSdkMcpServer(
    "app-tools",
    new MyTools()
);

// External stdio server
McpStdioServerConfig externalServer = new McpStdioServerConfig(
    "node",
    List.of("external-server.js"),
    Map.of()
);

// Configure both
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of(
        "app", sdkServer,
        "external", externalServer
    ))
    .allowedTools(List.of(
        "mcp__app__my_tool",
        "mcp__external__their_tool"
    ))
    .build();
```

### 厳格な MCP 設定

デフォルトでは、CLI は `mcpServers(...)` で渡したものに加えて、プロジェクトの `.mcp.json`、ユーザー／グローバル設定、各種プラグインから MCP サーバーを読み込みます。`strictMcpConfig(true)` を設定すると、渡したサーバー以外はすべて無視されます。

```java
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("app", sdkServer))
    .strictMcpConfig(true)   // ignore project / user / plugin MCP configs
    .build();
```

CLI の `--strict-mcp-config` フラグに対応します。どの MCP サーバーに到達できるかを厳密に制御したい、再現性のあるデプロイやテストの隔離に役立ちます。

## 例

### 例 1：電卓

```java
public class Calculator {

    @Tool(name = "calculate", description = "Perform calculations")
    public CompletableFuture<ToolResult> calculate(
            double a, double b, String operation) {

        double result = switch (operation) {
            case "add" -> a + b;
            case "subtract" -> a - b;
            case "multiply" -> a * b;
            case "divide" -> {
                if (b == 0) {
                    return CompletableFuture.completedFuture(
                        ToolResult.error("Cannot divide by zero")
                    );
                }
                yield a / b;
            }
            default -> throw new IllegalArgumentException(
                "Unknown operation: " + operation
            );
        };

        return CompletableFuture.completedFuture(
            ToolResult.text(a + " " + operation + " " + b + " = " + result)
        );
    }
}

// Usage
McpSdkServerConfig server = ClaudeSDK.createSdkMcpServer(
    "calculator",
    new Calculator()
);

var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("calc", server))
    .allowedTools(List.of("mcp__calc__calculate"))
    .build();

try (var client = ClaudeSDK.createClient(options)) {
    client.connect();
    client.sendMessage("Calculate 15 * 7, then 100 / 4");

    for (Message msg : client.receiveResponse()) {
        if (msg instanceof AssistantMessage assistant) {
            System.out.println(assistant.getTextContent());
        }
    }
}
```

### 例 2：データベースアクセス

```java
public class DatabaseTools {
    private final DataSource dataSource;

    public DatabaseTools(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Tool(name = "query_users", description = "Query users from database")
    public CompletableFuture<ToolResult> queryUsers(String filter) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM users WHERE name LIKE ?")) {

                stmt.setString(1, "%" + filter + "%");
                ResultSet rs = stmt.executeQuery();

                List<Map<String, Object>> users = new ArrayList<>();
                while (rs.next()) {
                    users.add(Map.of(
                        "id", rs.getInt("id"),
                        "name", rs.getString("name"),
                        "email", rs.getString("email")
                    ));
                }

                return ToolResult.json(Map.of(
                    "count", users.size(),
                    "users", users
                ));

            } catch (SQLException e) {
                return ToolResult.error("Database error: " + e.getMessage());
            }
        });
    }
}
```

### 例 3：API 連携

```java
public class WeatherTools {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String apiKey;

    public WeatherTools(String apiKey) {
        this.apiKey = apiKey;
    }

    @Tool(name = "get_weather", description = "Get current weather")
    public CompletableFuture<ToolResult> getWeather(String city) {
        String url = "https://api.weather.com/weather?city=" + city +
                     "&key=" + apiKey;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .build();

        return httpClient.sendAsync(request, BodyHandlers.ofString())
            .thenApply(response -> {
                // Parse JSON response
                Map<String, Object> data = parseJson(response.body());
                return ToolResult.json(data);
            })
            .exceptionally(e -> ToolResult.error(
                "Failed to fetch weather: " + e.getMessage()
            ));
    }
}
```

## ベストプラクティス

### 1. 適切な戻り値の型を使う

```java
// ✅ Good: Specific result types
ToolResult.text("Simple text response");
ToolResult.json(Map.of("key", "value"));
ToolResult.error("Error message");

// ❌ Bad: Always using text for structured data
ToolResult.text("{\"key\":\"value\"}");  // Should use json()
```

### 2. エラーを丁寧に扱う

```java
// ✅ Good: Proper error handling
@Tool(name = "read_file", description = "Read a file")
public CompletableFuture<ToolResult> readFile(String path) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            String content = Files.readString(Path.of(path));
            return ToolResult.text(content);
        } catch (IOException e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }
    });
}

// ❌ Bad: Throwing exceptions
public CompletableFuture<ToolResult> readFile(String path) {
    String content = Files.readString(Path.of(path));  // Throws!
    return CompletableFuture.completedFuture(ToolResult.text(content));
}
```

### 3. 良い説明を書く

```java
// ✅ Good: Descriptive and clear
@Tool(
    name = "search_products",
    description = "Search for products by name, category, or price range. " +
                  "Returns a list of matching products with details."
)

// ❌ Bad: Vague description
@Tool(name = "search", description = "Search")
```

### 4. 複雑な入力には明示的なスキーマを使う

SDK が引数を検証する基準は宣言されたスキーマなので、入力を正確に記述するほどハンドラーが前提にできることが増え、モデルがツールを誤って呼んだときに返るメッセージも有用になります。スキーマの無いツールはまったく検証されません。

```java
// ✅ Good: Explicit schema with validation
@Tool(
    name = "create_user",
    description = "Create a new user",
    inputSchema = """
        {
            "type": "object",
            "properties": {
                "email": {"type": "string", "format": "email"},
                "age": {"type": "integer", "minimum": 18}
            },
            "required": ["email"]
        }
        """
)

// ❌ Bad: No validation
@Tool(name = "create_user", description = "Create user")
public CompletableFuture<ToolResult> createUser(Map<String, Object> args)
```

### 5. ツールの責務を絞る

```java
// ✅ Good: Single responsibility
@Tool(name = "add_numbers", description = "Add two numbers")
@Tool(name = "multiply_numbers", description = "Multiply two numbers")

// ❌ Bad: Too much in one tool
@Tool(name = "math", description = "Do any math operation")
```

### 6. I/O には非同期を使う

```java
// ✅ Good: Async I/O
@Tool(name = "fetch", description = "Fetch URL")
public CompletableFuture<ToolResult> fetch(String url) {
    return httpClient.sendAsync(request, BodyHandlers.ofString())
        .thenApply(response -> ToolResult.text(response.body()));
}

// ❌ Bad: Blocking I/O
public CompletableFuture<ToolResult> fetch(String url) {
    String result = blockingHttpCall(url);  // Blocks!
    return CompletableFuture.completedFuture(ToolResult.text(result));
}
```

### 7. ツールの権限を設定する

```java
// ✅ Good: Explicitly allow tools
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("calc", calcServer))
    .allowedTools(List.of(
        "mcp__calc__add",
        "mcp__calc__subtract"
    ))
    .build();

// ❌ Bad: Allowing all tools (security risk)
var options = ClaudeAgentOptions.builder()
    .mcpServers(Map.of("calc", calcServer))
    .build();  // All tools allowed!
```

## MCP サーバーのステータス

`ClaudeSDKClient.getMcpStatus()` は、設定されたすべての MCP サーバーの現在の接続状態を含む `McpStatusResponse` を返します。

### McpStatusResponse

```java
record McpStatusResponse(
    List<McpServerStatus> mcpServers   // list of server status entries
)
```

### McpServerStatus

```java
record McpServerStatus(
    String name,                               // server name as configured
    McpServerConnectionStatus status,          // connection state
    @Nullable McpServerInfo serverInfo,        // info from MCP handshake (when connected)
    @Nullable String error,                    // error message (when status = FAILED)
    @Nullable McpServerStatusConfig config,    // server configuration
    @Nullable String scope,                    // config scope (project, user, local)
    @Nullable List<McpToolInfo> tools          // available tools (when connected)
)
```

### McpServerConnectionStatus

```java
enum McpServerConnectionStatus {
    CONNECTED,    // server is connected and ready
    FAILED,       // connection attempt failed
    NEEDS_AUTH,   // server requires authentication
    PENDING,      // connection in progress
    DISABLED      // server is disabled
}
```

### McpServerInfo

```java
record McpServerInfo(
    String name,      // server name from MCP handshake
    String version    // server version from MCP handshake
)
```

### McpToolInfo

```java
record McpToolInfo(
    String name,                               // tool name
    @Nullable String description,              // tool description
    @Nullable McpToolAnnotations annotations   // behavioral hints
)
```

### McpServerStatusConfig（封印インターフェース）

ステータスレスポンス内のサーバー設定を表します。多態なので、パターンマッチングを使ってください。

```java
switch (server.config()) {
    case McpStdioServerConfig c -> System.out.println("stdio: " + c.command());
    case McpSseServerConfig c -> System.out.println("sse: " + c.url());
    case McpHttpServerConfig c -> System.out.println("http: " + c.url());
    case McpSdkServerConfigStatus c -> System.out.println("sdk: " + c.name());
    case McpClaudeAIProxyServerConfig c -> System.out.println("proxy: " + c.url());
    case null -> {}
}
```

### 例：MCP のステータスを確認する

```java
try (var client = ClaudeSDK.createClient(options)) {
    client.connect();

    McpStatusResponse status = client.getMcpStatus();
    for (McpServerStatus server : status.mcpServers()) {
        System.out.printf("[%s] %s%n", server.status(), server.name());
        if (server.status() == McpServerConnectionStatus.CONNECTED) {
            if (server.tools() != null) {
                server.tools().forEach(t -> System.out.println("  - " + t.name()));
            }
        } else if (server.status() == McpServerConnectionStatus.FAILED) {
            System.err.println("  Error: " + server.error());
        }
    }
}
```

## 関連ドキュメント

- [設定オプション](./feature-configuration-options.md) — mcpServers と tools のオプション
- [ツール利用の例](../../examples/src/main/java/examples/McpServer.java) — 完全な例
- [スキーマ自動生成の例](../../examples/src/main/java/examples/AutoSchemaGeneration.java)
- [MCP 仕様](https://spec.modelcontextprotocol.io/) — 公式の MCP プロトコル文書
