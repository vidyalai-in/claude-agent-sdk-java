# トランスポート層

Claude Code との通信のための独自トランスポート実装です。

> **この翻訳について**：正式なドキュメントは英語版のみです。この翻訳は[英語の原文](../feature-transport-layer.md)より古い場合があります。内容が食い違う場合は英語版が正となります。コードブロックは英語の原文と完全に同一で、翻訳していません。

## 概要

トランスポート層は Claude Code CLI との I/O を担います。リモート接続や別の通信手段のために、独自の
トランスポートを実装できます。

## Transport インターフェース

```java
public interface Transport extends AutoCloseable {
    void connect() throws CLIConnectionException;
    void write(String data) throws CLIConnectionException;
    Iterator<Map<String, Object>> readMessages();
    void endInput();
    boolean isReady();
    void close();
}
```

## 既定の実装

### SubprocessCLITransport

既定のトランスポートは Claude Code CLI をサブプロセスとして生成します。これは options だけから
構築されます —— プロンプトとストリーミングモードは、トランスポートではなく
`ClaudeAgentOptions` とメッセージストリームが運びます：

```java
Transport transport = new SubprocessCLITransport(options);
```

**機能**：
- サブプロセスのライフサイクル管理
- stdin/stdout による通信
- バッファ付きの読み取り
- stderr コールバックのサポート
- 自動的な後片づけ
- 猶予期間を設けた穏当なシャットダウン（stdin の EOF 後、SIGTERM を送る前に、サブプロセスが
  セッションファイルをフラッシュするのを待ちます）
- 既定で `CLAUDE_CODE_ENTRYPOINT=sdk-java` を設定（`ClaudeAgentOptions.env()` で上書き可能）

### CLI フラグの転送

`SubprocessCLITransport.buildCommand()` は `ClaudeAgentOptions` を CLI フラグへ変換します。最近の
オプションに関係する主なフラグは次のとおりです：

| オプション | CLI フラグ | 備考 |
|---|---|---|
| `sessionStore(...)` | `--session-mirror` | `sessionStore != null` のときに追加されます。CLI に stdout へ `transcript_mirror` フレームを出すよう指示し、SDK がそれを取り出して設定済みの `SessionStore` へ転送します。 |
| `thinking(ThinkingConfigAdaptive(SUMMARIZED))` | `--thinking adaptive --thinking-display summarized` | `--thinking-display` が転送されるのは `Adaptive` と `Enabled` の設定のときだけです（しかも `display != null` のときのみ）。`Disabled` は決して出しません。 |
| `thinking(ThinkingConfigEnabled(20000, OMITTED))` | `--max-thinking-tokens 20000 --thinking-display omitted` | `display` が設定されている場合、両方のフラグが一緒に出ます。 |

**stderr のパイプ**：stderr がパイプされるのは `options.stderrCallback() != null` のときだけです。
従来の `--debug-to-stderr` 追加引数の検出は 0.1.13 で削除されました（当該 CLI フラグの非推奨化への
備え）。CLI の詳細なデバッグ出力を取得したい場合は、`extraArgs(Map.of("debug-file", "/path/to/log"))`
を渡し、そのファイルを読んでください。

**stderr コールバックの隔離**（0.1.16）：`stderrCallback.accept(line)` の呼び出しは 1 行ごとに
`try/catch(Throwable)` で包まれます。例外を投げるコールバックは捕捉されて `FINE` レベルで記録され、
読み取りループは次の行へ進みます。以前は 1 度の送出でループが抜けてしまい、そのセッションの残りの
間、後続の stderr 行がすべて黙って捨てられていました。外側ループの失敗（ストリームの予期せぬ閉鎖、
I/O エラー）も、黙って握りつぶされる代わりに `FINE` で記録されます。

**孤児となった子プロセスの後始末**（0.1.18）：生成された CLI プロセスはすべて静的な
`ACTIVE_CHILDREN` 集合に登録され、`close()` が走る前にプロセスが終了した場合は、JVM のシャット
ダウンフックがまだ生きているものをベストエフォートで終了させます。`close()` は終了手段を段階的に
強めていき（猶予期間 → `destroy()` / SIGTERM → `destroyForcibly()` / SIGKILL）、**もはや生きて
いないことを確認してから初めて**（`!process.isAlive()`）そのプロセスを `ACTIVE_CHILDREN` から
取り除きます。何らかの理由でこの段階的な終了を生き延びた子プロセス（競合した kill や、タイムアウト
した `waitFor` など）は追跡され続けるので、シャットダウンフックの回収処理がもう一度手を出す機会を
得られ、孤児の `claude` プロセスとして漏れ出すことがありません。

**`resume` / `sessionId` の argv フラグ注入への対策**（0.1.19）：`buildCommand()` は `resume` と
`sessionId` を、2 つに分かれたトークン（`--resume`、`<value>`）ではなく、単一の `--flag=value`
argv トークン（`--resume=<value>`、`--session-id=<value>`）として出します。CLI は `--resume` を
値が*省略可能*なものとして宣言しているため、2 トークン形式ではダッシュで始まる値がフラグに束縛
されず、独立した CLI フラグとして解釈されてしまいます。したがって、信頼できない入力を
`resume`/`sessionId` に流し込むアプリケーション（リクエストからセッション ID を読む「セッションを
再開する」エンドポイントなど）は、任意の CLI フラグを注入され得ました —— `resume("--version")` は
黙って `claude --version` を実行し、メッセージが 0 件になっていました。等号形式なら値は常にフラグに
束縛され、CLI はダッシュで始まる値を無効なセッション ID として拒否します。これは argv レベルの話で
（オプションごとに 1 引数、**シェルは一切介在しません**）、コマンド実行ではなくフラグ注入であり、
信頼できない入力をこれらのオプションへ転送しているアプリだけに影響します。`buildCommand()` の他所で
すでに使われている `--setting-sources=` のスタイルと揃っています。

### Windows：バッチスクリプト CLI の拒否（0.1.21）

Windows には shebang の仕組みがありません。CLI パスが `.bat` や `.cmd` ファイルを指している場合、
OS は生成を `cmd.exe /c` の呼び出しに書き換えて実行し、**cmd.exe は実行時にコマンドライン全体を
再解析します**。引数のクォートは cmd.exe の規則ではなく MSVCRT の argv 規則に従い、空白の周りにしか
クォートを付けません。そのため引数値の中にある cmd.exe のメタ文字（`--resume` のセッションタイトル、
`--mcp-config` の JSON、システムプロンプト）はエスケープされないまま cmd.exe に届き、CLI が起動する
前に注入されたコマンドを実行し得ます。

0.1.19 の `--flag=value` 形式はこの経路では役に立ちません。cmd.exe が文字列を再解析してしまえば、
守るべき argv の境界はもう残っていないからです。cmd.exe に対する確実なエスケープは存在しない
（`%VAR%` はダブルクォートの中でも展開されます）ため、**拒否することが唯一の堅牢な対処**です ——
Node.js がこの脆弱性クラス（CVE-2024-27980、「BatBadBut」）に対して出したのと同じ対処です。

`connect()` は、そのパスで何かを生成する前に解決済みパスを検証するので、この検査は実行ファイルへの
すべての経路をカバーします：PATH による発見、明示的な `ClaudeAgentOptions.cliPath(...)`、そして
メインプロセスの前に走るバージョン探査です。

```java
// On Windows, with cliPath pointing at npm's shim:
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cliPath(Path.of("C:\\Users\\me\\AppData\\Roaming\\npm\\claude.cmd"))
    .build();

// throws CLIConnectionException: "Refusing to execute batch script ..."
try (var client = ClaudeSDK.createClient(options)) {
    client.connect("hello");
}
```

**対処**（いずれも cmd.exe を完全に避けます）：`irm https://claude.ai/install.ps1 | iex` で
Claude Code をネイティブにインストールするか、`cliPath` を `claude.exe` に向けてください。npm で
インストールした `claude.cmd` からの移行が本当に困難な場合は、下の[明示的なオプトイン](#windowsバッチ-cli-のオプトイン0122)を参照してください。

拡張子の照合は Win32 と同じ正規化を行い、最後の 1 つだけでなく**すべての**パス構成要素を分類します：

| 書き方 | 拒否 | 理由 |
|---|---|---|
| `C:\npm\claude.cmd` | はい | 素直なケース |
| `C:\npm\claude.CMD` | はい | 拡張子の照合は大文字小文字を区別しません |
| `C:\npm\claude.cmd ` / `claude.cmd.` | はい | Windows はパス解決時に末尾のドットと空白を落とします |
| `C:\npm\claude.cmd:stream` | はい | NTFS のストリーム指定でも基底ファイルは開かれます |
| `C:\npm\claude:evil.cmd` | はい | Win32 は構成要素全体（ストリーム指定を含む）に対する「最後のドット」走査で拡張子を見つけます |
| `C:claude.cmd` | はい | ドライブ接頭辞は同じ構成要素に含まれます |
| `.cmd` | はい | `PathFindExtension` は裸の `.cmd` を拡張子とみなします |
| `C:\claude.cmd\..\claude.exe` | はい | どの構成要素も対象です —— `.`/`..` の正規化ではごまかせません |
| `C:\bin\claude.exe` | いいえ | ネイティブの実行ファイル |
| Linux/macOS 上の `/opt/claude.cmd` | いいえ | POSIX に cmd.exe の経由はなく、`.cmd` は普通のファイル名です |

この検査は `java.nio.file.Path` ではなく、あえて素朴な文字列ロジックです。パスの解析は POSIX と
Windows で異なり、両方で同一に振る舞うのは文字列ロジックだけだからです。すべての構成要素を分類する
ことで正規化トリックのクラス全体をまとめて塞げますし、正当な用途を何も損ないません —— バッチ
ファイルのような名前のディレクトリの下に本物の `claude.exe` が置かれることはありません。

### Windows：CLI の探索順序（0.1.21）

探索はネイティブの実行ファイルを優先します。Windows における拡張子なしの `claude` は
git-bash / WSL のラッパースクリプトであり、OS が直接実行できないからです：

1. まず **`PATH` 全体**を走査して `claude.exe` を探します。PATH はディレクトリを主軸に走査されるため、
   これがないと先の方のディレクトリにあるラッパースクリプトが、後ろのディレクトリにインストール
   された本物の `claude.exe` を覆い隠してしまいます。
2. そうでなければ `~/.local/bin/claude.exe` にフォールバックします。POSIX 風の場所は Windows では
   あえて探査**しません**。そこで拡張子なしのものに一致すると、説明的な拒否ではなく不可解な生成失敗
   が先に起きてしまいますし、ルート付きでドライブなしの `/usr/local/bin/claude` は現在のドライブに
   対して解決されます —— そこは別のローカルユーザーが作成できる場所であり、バイナリ植え込みの探査点
   になります。
3. そうでなければ、`PATH` 上に `claude.cmd` / `claude.bat` のシムがあればそれを返します。これは、
   素っ気ない「見つかりません」ではなく、`connect()` にバッチスクリプトの拒否を*その対処方法とともに*
   送出させるためです。
4. そうでなければ、非ネイティブの `PATH` ヒットを返し、生成エラーが実際に入っているものを示せる
   ようにします。
5. そうでなければ、ネイティブのインストーラを案内する Windows 専用のメッセージとともに
   `CLINotFoundException` を投げます。`npm install -g @anthropic-ai/claude-code` は勧めません。
   それこそが、この SDK が拒否するシムを作り出すからです。

POSIX の探索は変わりません：`PATH`、次に `~/.npm-global/bin`、`/usr/local/bin`、`~/.local/bin`、
`~/node_modules/.bin`、`~/.yarn/bin`、`~/.claude/local`。

### Windows：cmd.exe メタ文字の拒否（0.1.21）

多層防御です。バッチ生成が拒否されている以上これらの文字はすでに無害ですが、`resume` と `sessionId`
はアプリケーションが外部入力から値を取ることの最も多い箇所なので、それでも拒否します —— 将来 SDK と
CLI の間に cmd.exe の経由が再導入されたとしても、無害なままに保つためです。

Windows では、`resume` や `sessionId` に `&`、`|`、`<`、`>`、`^`、`%`、`!`、`"`、CR、LF が含まれて
いると、`buildCommand()` が `IllegalArgumentException` を投げます：

```java
// On Windows: IllegalArgumentException
ClaudeAgentOptions.builder().resume("R&D notes").build();

// Accepted — no format is imposed beyond the metacharacter check;
// resume values may be arbitrary session titles, not only UUIDs
ClaudeAgentOptions.builder().resume("Refactor the parser (part 2)").build();
```

**POSIX の挙動は変わりません** —— 守るべき cmd.exe がないので、`resume("R&D notes")` はそのまま
渡されます。

### `extraArgs` の値の束縛（0.1.21）

`buildCommand()` は、値が `-` で始まる `extraArgs` エントリを 2 つではなく単一の `--flag=value`
トークンとして出します。これは `resume`/`sessionId` の変更が塞いだのと同じ注入クラスを、残っていた
2 トークンの呼び出し箇所に適用したものです。2 トークン形式では、CLI がそのオプションを値が省略可能な
ものとして宣言している場合、ダッシュで始まる値はフラグに束縛されず、別のフラグとして解釈されます。

| `extraArgs` エントリ | 出力される argv |
|---|---|
| `Map.of("some-flag", "value")` | `--some-flag`、`value` |
| `Map.of("some-flag", "--evil")` | `--some-flag=--evil` |
| `Map.of("verbose-thing", "")` | `--verbose-thing`（裸の真偽フラグ） |

### Windows：バッチ CLI のオプトイン（0.1.22）

npm でインストールした `claude.cmd` から移行できない配備 —— たとえば集中管理されたソフトウェア配布
—— のために、上記の拒否を明示的に免除できます：

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cliPath(Path.of("C:\\Users\\Administrator\\AppData\\Roaming\\npm\\claude.cmd"))
    .allowUnsafeWindowsBatchCli(true)
    .build();
```

既定は `false` で、設定しない呼び出し側にとって拒否の挙動は何も変わりません。

#### なぜこれが単なるバイパスではないのか

もっとも素朴な実装 —— フラグが立っていたら検査を飛ばす —— は、cmd.exe の再解析の穴をそっくりそのまま
返してしまいます。肝心な部分は JDK 自身にあります：

```java
// OpenJDK, src/java.base/windows/classes/java/lang/ProcessImpl.java
final String value = System.getProperty("jdk.lang.Process.allowAmbiguousCommands", "true");
final boolean allowAmbiguousCommands = !"false".equalsIgnoreCase(value);
if (allowAmbiguousCommands) {
    cmdstr = createCommandLine(VERIFICATION_LEGACY, executablePath, cmd);  // escape set: ""
```

このプロパティの既定値は `"true"` で、エスケープ集合が**空**のレガシーモードが選ばれます —— 空白
以外は何もクォートされず、埋め込まれたクォートも受け入れられます。**素の JVM では、Java はこの脆弱性
クラスに、当時の Node.js とまったく同じだけさらされています**。この拒否は、別の生態系から借りてきた
推論にすぎないわけではありません。

このプロパティを `false` にすると、`.exe` でないターゲットでは代わりに `VERIFICATION_CMD_BAT` が
選ばれます。そのエスケープ集合は `"<>&|^` で、これらや空白を含む引数はクォートされ、埋め込まれた
クォートを持つ引数はその場で例外になります。

そこでこのオプトインはそのモードを要求し、JDK が省いている部分を補います：

1. **`-Djdk.lang.Process.allowAmbiguousCommands=false` は必須です。** プロパティが `false` 以外の
   場合、`connect()` は `CLIConnectionException` を投げます —— 真偽の判定を独自に定義するのではなく、
   JDK 自身の `!"false".equalsIgnoreCase(value)` という読み方に合わせています。メッセージには追加
   すべきフラグが示されます。
2. **すべての CLI 引数を走査**し、`& | < > ^ % ! "` と CR/LF を探して、問題のオプションを名指しする
   `IllegalArgumentException` を投げます。`%` と `!` は JDK のエスケープ集合に含まれておらず、
   クォートしても `%VAR%` の展開は*止まりません* —— クォートされていない `%FOO%` が `x&calc` に展開
   されれば再解析されます。これが引数側のベクタを塞ぎます。argv[0] の実行ファイルパスは走査しません。
   呼び出し側が与えた引数データではなく、すでに分類済みだからです。
3. **トランスポートごとに 1 度 `WARNING` を記録**し、パスと受け入れたリスクを明示します。

結果として、検査がまったくない場合よりも*狭い*攻撃面になります。

```java
// JVM started without the flag:
// CLIConnectionException: allowUnsafeWindowsBatchCli(true) was set for '...claude.cmd',
//   but jdk.lang.Process.allowAmbiguousCommands is unset (defaults to true). ...

// With the flag, but a hostile argument value:
ClaudeAgentOptions.builder()
    .cliPath(Path.of("C:\\...\\claude.cmd"))
    .allowUnsafeWindowsBatchCli(true)
    .resume("%USERPROFILE%")
    .build();
// IllegalArgumentException: Refusing to pass the value of --resume to a Windows
//   batch CLI: it contains cmd.exe metacharacters [%], which cmd.exe re-parses.
```

#### 残存リスク

cmd.exe は `%VAR%` を**環境**から展開します。argv の走査は引数側のベクタを塞ぎますが、攻撃者が JVM
から CLI へ渡される環境を制御できる場合には無力です。このオプトインは、CLI のパスとすべての引数値が
管理者の管理下にある場面でのみ使い、行き先ではなく移行の橋渡しとして扱ってください。

POSIX は全体を通して影響を受けません —— cmd.exe の経由がないため `.cmd` ファイルは普通のファイル名で
あり、拒否もオプトインの条件も当てはまりません。

[`examples/WindowsBatchCliExample.java`](../../examples/src/main/java/examples/WindowsBatchCliExample.java)
を参照してください。

### Skill 名による `--allowedTools` への注入（0.1.22）

上の 3 つの強化は *argv* の境界に関するものでした —— オプションごとに 1 引数、シェルは介在しません。
これは違います。**単一の引数の値の内側**での注入です。

`applySkillsDefaults()` は各 `ClaudeAgentOptions.skills(List)` エントリを `Skill(<name>)` に整形し、
その結果を 1 本の `--allowedTools` 文字列に連結します。CLI はその文字列を、括弧の外にあるカンマと
スペースで権限ルールへ分割し直しますが、**そのトークナイザはエスケープシーケンスを一切解釈しません**
—— エスケープはルール単位の文法にしか存在せず、分割の後に適用されるからです。したがって区切り文字を
含む名前を確実に通すことはできず、どうトークン化されるかは周囲に何があるか次第です。

```java
// Before 0.1.22:  --allowedTools "Skill(x),Bash(*)"
//                                          ^^^^^^^ an extra rule, never requested
ClaudeAgentOptions.builder().skills(List.of("x),Bash(*")).build();
```

いまは整形の前に `validateSkillName()` がすべてのエントリに対して走るため、拒否は `buildCommand()`
から —— `connect()` の時点、サブプロセスが生成される前に —— 現れます。括弧、カンマ、制御文字
（C0、DEL、C1）、バイトオーダーマーク、空の名前、リテラルの `*`、ワイルドカードの接尾辞を拒否します。
さらに、死んだルールが黙って何も許可しない事態を避けるため、前後の空白、先頭の `/`、連続する
バックスラッシュ、末尾の対にならないバックスラッシュ、単独のサロゲートも拒否します。通常の名前
—— プラグイン修飾、内部の空白、単一のバックスラッシュ、非 ASCII —— は以前とまったく同じ argv を
生成します。

`rejectNonListSkills()` は値の形そのものを守ります。ビルダーの `skills(List)` / `skillsAll()` の
組み合わせによって、裸の文字列や非リストの反復可能オブジェクトはすでに到達し得ません —— Java の型
システムが、Python SDK が実行時に強制していることをここで担っています —— が、生型やリフレクション
経由の呼び出し側が、skill フィルタをまったく設定しないまま黙って進むのではなく、確実に失敗するよう
この検査は残されています。

拒否の完全な表、受理される名前の一覧、そして Python SDK との 2 つの意図的な差異（単独サロゲートか
任意のサロゲートか、ノーブレークスペースの除去）については、
[Skills → 名前の検証](./feature-skills.md#名前の検証01122)を参照してください。

## 独自のトランスポート

独自の通信手段のために `Transport` インターフェースを実装します：

```java
public class RemoteTransport implements Transport {
    private Socket socket;
    private BufferedWriter writer;
    private BufferedReader reader;
    
    @Override
    public void connect() throws CLIConnectionException {
        try {
            socket = new Socket("remote-host", 8080);
            writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream())
            );
            reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream())
            );
        } catch (IOException e) {
            throw new CLIConnectionException("Connection failed", e);
        }
    }
    
    @Override
    public void write(String data) throws CLIConnectionException {
        try {
            writer.write(data);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new CLIConnectionException("Write failed", e);
        }
    }
    
    @Override
    public Iterator<Map<String, Object>> readMessages() {
        return new Iterator<>() {
            private final ObjectMapper mapper = new ObjectMapper();
            
            @Override
            public boolean hasNext() {
                return true;  // Or check connection
            }
            
            @Override
            public Map<String, Object> next() {
                try {
                    String line = reader.readLine();
                    if (line == null) {
                        throw new NoSuchElementException();
                    }
                    return mapper.readValue(line, Map.class);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }
    
    @Override
    public void endInput() {
        try {
            writer.close();
        } catch (IOException e) {
            // Log error
        }
    }
    
    @Override
    public boolean isReady() {
        return socket != null && socket.isConnected();
    }
    
    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            // Log error
        }
    }
}
```

## 独自トランスポートの使用

```java
// Create custom transport
Transport transport = new RemoteTransport();

// Use with ClaudeSDK.query
List<Message> messages = ClaudeSDK.query(
    prompt,
    options,
    transport  // Custom transport
);

// Or with streaming query
List<Message> messages = ClaudeSDK.query(
    messageStream,
    options,
    transport
);
```

## ベストプラクティス

1. **スレッド安全性**：write() をスレッドセーフにしてください
2. **リソース管理**：close() を適切に実装してください
3. **エラー処理**：エラーでは CLIConnectionException を投げてください
4. **ブロッキング読み取り**：readMessages() はデータが届くまでブロックすべきです
5. **JSON 形式**：メッセージは 1 行 1 つの JSON オブジェクトでなければなりません

## 関連項目
- [アーキテクチャ](./architecture.md#4-トランスポート層) —— トランスポート層の設計
- トランスポートのソースコード：`sdk/src/main/java/in/vidyalai/claude/sdk/transport/`
