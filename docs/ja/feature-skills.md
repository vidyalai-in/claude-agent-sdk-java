# Skills

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../feature-skills.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

`ClaudeAgentOptions` の `skills` オプションは、メインセッションで Claude Code の skill を有効化する
唯一の場所です。SDK が `allowedTools` と `settingSources` を自動的に配線するため、呼び出し側が
両方を手作業で設定する必要はありません。

> **skill とは？** skill は、`.claude/skills/<name>/SKILL.md`（プロジェクトスコープ）または
> `~/.claude/skills/<name>/SKILL.md`（ユーザースコープ）にインストールされる再利用可能な機能の
> まとまりです。skill は組み込みの `Skill` ツールから呼び出します。skill の作成方法については
> Claude Code のドキュメントを参照してください。

## クイックスタート

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

// Mode 1 — enable every discovered skill
var options = ClaudeAgentOptions.builder()
    .skillsAll()
    .build();

// Mode 2 — enable only specific skills
var options = ClaudeAgentOptions.builder()
    .skills(List.of("commit", "review"))
    .build();

// Mode 3 — suppress every skill from the listing
var options = ClaudeAgentOptions.builder()
    .skills(List.of())
    .build();

// Mode 4 (default) — no SDK auto-configuration; CLI defaults apply
var options = ClaudeAgentOptions.builder().build();
```

## モード

| ビルダー呼び出し | `allowedTools` への注入 | `settingSources` の既定 | initialize のワイヤーフィールド |
|---|---|---|---|
| _省略_ | なし | なし | 省略 |
| `.skillsAll()` | 裸の `Skill` を追加 | `[user, project]` | 省略 |
| `.skills(List.of("a", "b"))` | `Skill(a)`、`Skill(b)` を追加 | `[user, project]` | `["a", "b"]` |
| `.skills(List.of())` | なし | `[user, project]` | `[]` |

補足：

- **`null` は「skill オフ」ではありません。** オプションを省略すると CLI の既定がそのまま残ります。
  モデルの一覧からすべての skill を消したい場合は、空のリストを渡してください。
- **`"all"` と省略はワイヤーレベルで等価です。** どちらも initialize 制御リクエストの `skills`
  フィールドを省略します。CLI は省略を「フィルタなし」として扱います。
- **空のリストはワイヤー上に送られます。** `List.of()` は initialize リクエストで `"skills": []` と
  なり、対応する CLI に「システムプロンプトへ skill を一切読み込むな」と伝えます。

## 自動配線の仕組み

`SubprocessCLITransport.applySkillsDefaults()` が、サブプロセスを生成する前に実効的な CLI フラグを
組み立てます。元の `ClaudeAgentOptions` が書き換えられることはありません。

`skillsAll()` の場合：

- `allowedTools` にまだ `"Skill"` が含まれていなければ、裸のツールが追加されます。
- `settingSources` が null なら、CLI がインストール済みの skill を発見できるよう
  `[USER, PROJECT]` が既定になります。
- 明示的な `settingSources(...)` は常に既定より優先されます。

`skills(List.of(...))` の場合：

- 各名前 `n` について、`allowedTools` に `Skill(n)` を追加します（既存エントリと重複排除）。
- `settingSources` の既定の挙動は同じです。

`skills(List.of())` の場合：

- `allowedTools` は変更されません。
- `settingSources` の既定の挙動は同じです。

## 名前の検証（0.1.22）

`skills(List.of(...))` に渡した名前は、CLI の `--allowedTools` の値に整形される前に検証されます。
拒否はすべて**接続時**に `IllegalArgumentException` を投げます —— CLI サブプロセスが生成される前の
`buildCommand()` からです。

検証が必要なのは、`--allowedTools` が 1 本の文字列であり、CLI が括弧の外にあるカンマとスペースで
それを権限ルールに分割するうえ、そのトークナイザがエスケープシーケンスを一切解釈しないからです。
エスケープはルール単位の文法にしか存在せず、分割の*後*に適用されるため、区切り文字を含む名前を
確実に通すことはできません —— どうトークン化されるかは、その前後にあるものに依存します：

```java
// Before 0.1.22 this emitted --allowedTools "Skill(x),Bash(*)",
// silently granting the session unrestricted Bash.
ClaudeAgentOptions.builder()
    .skills(List.of("x),Bash(*"))
    .build();

// 0.1.22: IllegalArgumentException at connect()
// "Invalid skill name 'x),Bash(*': parentheses, commas, control characters,
//  and byte-order marks are not allowed. ..."
```

### 拒否される形

| 形 | 例 | 理由 |
|---|---|---|
| 括弧またはカンマ | `"x),Bash(*"`、`"a,b"`、`"()"` | ルールの区切り文字 —— 上記のインジェクション経路 |
| 制御文字 | C0（`\n`、`\t`、`\u0000`）、DEL（`\u007F`）、C1（`\u0080`–`\u009F`） | skill のディレクトリ名に現れることはない |
| バイトオーダーマーク | 名前中のどこかにある `﻿` | CLI は U+FEFF を空白として削るため、ルールが別の skill を指してしまう |
| 空または空白のみ | `""`、`" "`、`"  \t "` | 何も指していない |
| 裸のワイルドカード | `"*"` | 代わりに `skillsAll()` を使ってください |
| ワイルドカードの接尾辞 | `"pdf:*"`、`"my skill *"` | skill は正確な名前で 1 つずつ列挙してください |
| 前後の空白 | `" pdf"`、`"pdf "` | 決して一致しません —— `Skill` ツールは呼び出された名前をトリムします |
| 先頭の `/` | `"/commit"` | このオプションが取るのは正規の名前であって、スラッシュコマンド形式ではありません |
| 連続するバックスラッシュ | `"mid\\\\dle"` | ルール単位のパーサがそれらを畳み込むため、ルールが別の skill を指してしまう |
| 末尾の対にならないバックスラッシュ | `"name\\"` | 宙に浮いたエスケープ |
| 対にならないサロゲート | 単独の `\ud800` | CLI が発見したどの名前とも一致し得ない |

インジェクション経路なのは最初の 3 行だけです。残りはきれいにトークン化されますが、あなたが指定した
skill と決して一致しないルールを作ってしまいます —— skill が黙ってセッションから欠落するのではなく、
`connect()` で失敗が大きく表に出るように拒否しています。上の文字列例は Java のソースリテラル表記な
ので、`"mid\\\\dle"` はバックスラッシュ 2 個を含む名前、`"dir\\sub"`（受理される）は 1 個です。

### 受理される名前

通常の名前は影響を受けません。以下はどれも従来どおりの argv を生成します：

```java
.skills(List.of(
    "pdf-tools",          // hyphens
    "my_skill.v2",        // underscores, dots
    "myplugin:pdf",       // plugin-qualified
    "skill with spaces",  // interior spaces are fine; only surrounding ones are not
    "dir\\sub",           // a single backslash
    "日本語スキル"          // non-ASCII
))
```

### 破壊的変更

これまで受理されていた 2 つの形が、いまは例外を投げます：

| 従来 | 以前の挙動 | 現在 |
|---|---|---|
| `skills(List.of("*"))`、`skills(List.of("plugin:*"))` | ワイルドカードのルールを作っていた | 例外を投げます —— `skillsAll()` を使うか、前方一致のために `allowedTools` へ直接 `Skill(...)` エントリを足してください |
| `skills(List.of(" name"))`、`skills(List.of("/name"))` | 何にも一致しないルールを作り、その skill は**黙って使えなくなっていた** | 例外を投げ、問題を指摘します |

### Java 固有の挙動

2 つのチェックは Python SDK と意図的に異なります。言語ごとに文字列のモデル化が違うためです：

- **サロゲート。** Python はすべてのサロゲートコードポイントを拒否します —— Python の `str` は
  コードポイントを保持し、追加面の文字は非サロゲートの 1 要素なので、サロゲートが存在すれば構造上
  必ず対になっていない、という理屈で妥当です。Java の文字列は UTF-16 で、追加面の文字は正当に
  高／低サロゲートの*対*です。したがって Java は**単独の**サロゲートだけを拒否し、`"𝕤kill"` の
  ような名前は受理されます。
- **空白。** `String.strip()` は `Character.isWhitespace` に従うため、Python の `str.strip()` が
  取り除くノーブレークスペース（U+00A0、U+2007、U+202F）が残ります。パディングのチェックでは
  `Character.isSpaceChar` と和を取っているので、それらも捕捉されます。U+FEFF はどちらにも含まれず、
  Python と同じく不正な文字として拒否されます。

`skillsAll()` は名前のチェックを行いません —— チェックすべき名前がないからです。`skills(List.of())`
は引き続き有効な no-op です。

## Initialize ワイヤープロトコル

skills は `SDKControlInitializeRequest.skills` を通じて SDK の制御プロトコルにも流れます。送られる
のは明示的なリストだけで、`"all"` と `null` はどちらもフィールドを省略します。

`skills` という initialize フィールドを知らない古い CLI はそれを無視します —— 自動注入された
`allowedTools` エントリは引き続き尊重されます。

> **非推奨：** `allowedTools(...)`（や `AgentDefinition.tools`）に裸の `"Skill"` トークンを渡すのは
> **非推奨**です。代わりに `skillsAll()` / `skills(List.of(...))` を使ってください —— 必要なもの
> （`Skill` ツールの許可を含む）をすべて設定し、`allowedTools` とワイヤーレベルの `skills`
> フィールドがずれるのを防ぎます。

## サンプル

### 明示的な `allowedTools` との併用

skills は既存の許可リストを補うだけで、置き換えることはありません：

```java
var options = ClaudeAgentOptions.builder()
    .allowedTools(List.of("Read", "Write"))
    .skills(List.of("commit"))
    .build();
// effective allowedTools: [Read, Write, Skill(commit)]
```

### 冪等な注入

すでに `Skill` や `Skill(name)` を許可リストに入れている場合、SDK はそれを重複させません：

```java
var options = ClaudeAgentOptions.builder()
    .allowedTools(List.of("Skill(pdf)"))
    .skills(List.of("pdf"))
    .build();
// effective allowedTools: [Skill(pdf)]   (not [Skill(pdf), Skill(pdf)])
```

### 明示的な `settingSources` の保持

```java
var options = ClaudeAgentOptions.builder()
    .skillsAll()
    .settingSources(List.of(SettingSource.LOCAL))
    .build();
// effective settingSources: [LOCAL]   (your value wins over the [USER, PROJECT] default)
```

## セキュリティ上の注意

`skills` オプションは**コンテキストのフィルタであって、サンドボックスではありません。** リストに
ない skill はモデルの skill 一覧から隠され、`Skill` ツールから呼び出すこともできませんが、ファイル
自体はディスク上に残ります —— `Read` や `Bash` を持つセッションは `.claude/skills/**` に直接
アクセスできます。

厳密な隔離が必要な場合は：

- `.claude/skills/` に目的のサブセットしか含まないディレクトリを `cwd` に指定する、**または**
- skill のパスに対して `Read`/`Bash` の拒否ルールを追加してください。

同梱の skill とインストール済みプラグインの skill は、`settingSources` に関係なく発見されます。
`skills` の許可リストは、それらをモデルの一覧から隠す唯一の仕組みです。

**skill ファイルに秘密情報を保存しないでください。**

## 完全なサンプル

3 つのモードすべてを実行して確かめられるデモは
[`examples/SkillsExample.java`](../../examples/src/main/java/examples/SkillsExample.java) を
参照してください。

```java
package examples;

import java.util.List;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;

public class SkillsExample {
    public static void main(String[] args) throws Exception {
        var options = ClaudeAgentOptions.builder()
            .skillsAll()
            .maxTurns(1)
            .build();
        ClaudeSDK.query("List the skills you have available.", options);
    }
}
```

## 関連項目

- [設定オプション](./feature-configuration-options.md) —— ビルダー API の全体像
- [エージェント定義](./feature-agents.md) —— `AgentDefinition` の `skills` フィールド（サブエージェントごとの許可リスト）
- [セッション履歴](./feature-session-history.md) —— ディスクからトランスクリプトを読む
