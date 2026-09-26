# 传输层

用于与 Claude Code 通信的自定义传输实现。

> **关于本译文**：英文文档是唯一权威版本。本译文可能滞后于[英文原文](../feature-transport-layer.md)；若两者不一致，以英文为准。代码块保持与英文原文完全一致，未作翻译。

## 概览

传输层负责与 Claude Code CLI 之间的 I/O。你可以为远程连接或其他通信方式实现自定义传输。

## Transport 接口

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

## 默认实现

### SubprocessCLITransport

默认的传输会把 Claude Code CLI 作为子进程派生。它仅由 options 构造 —— 提示词与流式模式是由
`ClaudeAgentOptions` 和消息流携带的，而不是由传输携带：

```java
Transport transport = new SubprocessCLITransport(options);
```

**特性**：
- 管理子进程生命周期
- stdin/stdout 通信
- 带缓冲的读取
- 支持 stderr 回调
- 自动清理
- 带宽限期的优雅关闭（在 stdin EOF 之后、发送 SIGTERM 之前，等待子进程把会话文件刷盘）
- 默认设置 `CLAUDE_CODE_ENTRYPOINT=sdk-java`（可通过 `ClaudeAgentOptions.env()` 覆盖）
- 设置 `CLAUDE_CODE_SDK_READS_SESSION_STATE=1`（除非调用方的 `env()` 或继承的环境中已经出现该变量，不区分大小写），使 CLI 报告标记为 `sdk_host_only` 的 `session_state_changed` 帧；`QueryHandler` 读取这些帧来决定 stdin 何时可以关闭，并在它们到达消费者之前将其丢弃。参见[架构 → stdin 的生命周期](./architecture.md#stdin-的生命周期与一次运行的结束)。传输层设置的完整变量列表见[配置选项 → env()](./feature-configuration-options.md#env)。
- 当 CLI 版本低于 2.0.0 时，以及开启了 `verbatimPrompts` 但 CLI 版本低于 2.1.248（它会忽略 `client_composed`）时，在连接时发出警告（记录 `WARNING` 日志）。版本检查会运行 `<cli> -v`，设置了 `CLAUDE_AGENT_SDK_SKIP_VERSION_CHECK` 时跳过。

### CLI 标志转发

`SubprocessCLITransport.buildCommand()` 会把 `ClaudeAgentOptions` 翻译成 CLI 标志。与近期新增选项
相关的重要标志如下：

| 选项 | CLI 标志 | 说明 |
|---|---|---|
| `sessionStore(...)` | `--session-mirror` | 当 `sessionStore != null` 时添加。它告诉 CLI 在 stdout 上发出 `transcript_mirror` 帧，SDK 会把这些帧剥离出来并转发给所配置的 `SessionStore`。 |
| `thinking(ThinkingConfigAdaptive(SUMMARIZED))` | `--thinking adaptive --thinking-display summarized` | `--thinking-display` 只在 `Adaptive` 与 `Enabled` 配置下转发（且仅当 `display != null` 时）；`Disabled` 从不发出它。 |
| `thinking(ThinkingConfigEnabled(20000, OMITTED))` | `--max-thinking-tokens 20000 --thinking-display omitted` | 设置了 `display` 时两个标志会一起发出。 |
| `systemPrompt(SystemPromptCustom.of(p, …))` | `--system-prompt <p>` | 与普通字符串相同；`snapshot` 则改为随 `initialize` 请求发送。 |
| `plugins(List.of(SdkPluginConfig.local(dir)))` | `--plugin-dir <dir>` | 每个 `local` 插件一个；其他类型会被跳过。 |

**stderr 管道**：只有当 `options.stderrCallback() != null` 时才会为 stderr 建立管道。旧有的
`--debug-to-stderr` 额外参数检测已在 0.1.13 中移除（为该 CLI 标志的废弃做准备）。若要捕获 CLI 的
详细调试输出，请传入 `extraArgs(Map.of("debug-file", "/path/to/log"))` 并改为读取那个文件。

**stderr 回调隔离**（0.1.16）：每一次 `stderrCallback.accept(line)` 调用都被逐行包在
`try/catch(Throwable)` 中。抛出异常的回调会被捕获、以 `FINE` 级别记录，读取循环则继续处理下一行。
此前一次抛出会退出循环，并在会话剩余时间内悄无声息地丢弃之后的每一行 stderr。外层循环的失败
（流意外关闭、I/O 错误）现在也会以 `FINE` 级别记录，而不再被默默吞掉。

**孤儿子进程清理**（0.1.18）：每个被派生的 CLI 进程都会登记到静态的 `ACTIVE_CHILDREN` 集合中，
并由一个 JVM 关闭钩子在进程先于 `close()` 退出时尽力终止仍然存活的进程。`close()` 会逐级升级终止
手段（宽限期 → `destroy()` / SIGTERM → `destroyForcibly()` / SIGKILL），然后**只有在确认进程已不再
存活之后**（`!process.isAlive()`）才把它从 `ACTIVE_CHILDREN` 中移除。因此，某个以某种方式熬过了
升级过程的子进程（一次竞态的 kill，或者超时的 `waitFor`）仍会被继续跟踪，这样关闭钩子的回收器还有
机会处理它，而不至于泄漏成一个孤立的 `claude` 进程。

**`resume` / `sessionId` 的 argv 标志注入加固**（0.1.19）：`buildCommand()` 会把 `resume` 与
`sessionId` 作为单个 `--flag=value` argv 记号发出（`--resume=<value>`、`--session-id=<value>`），
而不是拆成两个独立记号（`--resume`、`<value>`）。CLI 声明的 `--resume` 带有*可选*取值，因此在
双记号形式下，以短横线开头的取值不会绑定到该标志，反而会被解析成一个独立的 CLI 标志。于是，把不可信
输入路由进 `resume`/`sessionId` 的应用（例如某个从请求中读取会话 ID 的"恢复我的会话"端点）就可能被
注入任意 CLI 标志 —— `resume("--version")` 会悄悄执行 `claude --version` 并返回零条消息。等号形式
总是把取值绑定到标志上，CLI 随后会把以短横线开头的取值判为无效会话 ID 而拒绝。这发生在 argv 层面
（每个选项一个参数，**完全不涉及 shell**），因此属于标志注入而非命令执行，并且只影响那些把不可信
输入转发进这些选项的应用。这与 `buildCommand()` 中别处已经采用的 `--setting-sources=` 风格一致。

### Windows：拒绝批处理脚本 CLI（0.1.21）

Windows 没有 shebang 机制。当 CLI 路径指向 `.bat` 或 `.cmd` 文件时，操作系统会把派生改写成
`cmd.exe /c` 调用来运行它，而 **cmd.exe 会在执行时重新解析整条命令行**。参数加引号遵循的是 MSVCRT
的 argv 规则 —— 它只会在含空白时加引号 —— 而不是 cmd.exe 的规则，于是参数值内部的 cmd.exe 元字符
（`--resume` 的会话标题、`--mcp-config` 的 JSON、系统提示词）会未经转义地抵达 cmd.exe，并可能在 CLI
尚未启动之前就执行被注入的命令。

0.1.19 引入的 `--flag=value` 形式在这条路径上无济于事：一旦 cmd.exe 重新解析该字符串，就不再有可供
保护的 argv 边界。针对 cmd.exe 的可靠转义并不存在（`%VAR%` 即便在双引号内也会展开），因此**拒绝是
唯一稳健的补救措施** —— Node.js 针对这一漏洞类别（CVE-2024-27980，"BatBadBut"）也采用了同样的做法。

`connect()` 会在用任何路径派生进程之前先校验解析出来的路径，因此该检查覆盖了通往可执行文件的每一条
途径：PATH 发现、显式的 `ClaudeAgentOptions.cliPath(...)`，以及在主进程之前运行的版本探测。

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

**补救方式**（都完全避开 cmd.exe）：用 `irm https://claude.ai/install.ps1 | iex` 原生安装
Claude Code，或者把 `cliPath` 指向某个 `claude.exe`。如果确实无法从 npm 安装的 `claude.cmd` 迁移，
请参见下文的[显式选择开关](#windows批处理-cli-选择开关0122)。

扩展名匹配采用与 Win32 相同的归一化方式，并会对**每一个**路径组件分类，而不仅仅是最后一个：

| 写法 | 是否拒绝 | 原因 |
|---|---|---|
| `C:\npm\claude.cmd` | 是 | 普通情形 |
| `C:\npm\claude.CMD` | 是 | 扩展名匹配不区分大小写 |
| `C:\npm\claude.cmd ` / `claude.cmd.` | 是 | Windows 在路径解析时会去掉结尾的点和空格 |
| `C:\npm\claude.cmd:stream` | 是 | NTFS 流说明符仍然会打开其基础文件 |
| `C:\npm\claude:evil.cmd` | 是 | Win32 通过对整个组件（含流说明符）做"最后一个点"扫描来确定扩展名 |
| `C:claude.cmd` | 是 | 盘符前缀与其处在同一个组件中 |
| `.cmd` | 是 | `PathFindExtension` 把裸的 `.cmd` 当作扩展名 |
| `C:\claude.cmd\..\claude.exe` | 是 | 任何组件都算数 —— `.`/`..` 归一化无法把它洗白 |
| `C:\bin\claude.exe` | 否 | 原生可执行文件 |
| Linux/macOS 上的 `/opt/claude.cmd` | 否 | POSIX 没有 cmd.exe 这一跳；`.cmd` 只是普通文件名 |

该检查刻意使用朴素的字符串逻辑而不是 `java.nio.file.Path`：路径解析在 POSIX 与 Windows 之间存在
差异，只有字符串逻辑才能在两者上表现一致。对每个组件分类可以一举堵死整个归一化花招类别，而且不会
误伤任何正当用法 —— 真实的 `claude.exe` 不会位于一个以批处理文件命名的目录之下。

### Windows：CLI 发现顺序（0.1.21）

发现过程优先选择原生可执行文件，因为 Windows 上无扩展名的 `claude` 是一个 git-bash / WSL 包装脚本，
操作系统无法直接运行它：

1. 先在**整个** `PATH` 中搜寻 `claude.exe`。PATH 是按目录为主序遍历的，若无此步，早出现目录中的包装
   脚本会遮蔽掉安装在后面目录里的真正 `claude.exe`。
2. 否则回退到 `~/.local/bin/claude.exe`。在 Windows 上刻意**不**探测那些 POSIX 风格的位置：在那里
   匹配到无扩展名的文件会用一个晦涩的派生失败抢在解释性拒绝之前；而且带根但无盘符的
   `/usr/local/bin/claude` 会相对当前盘符解析 —— 那是另一个本地用户可以创建的位置，构成了植入二进制
   的探测点。
3. 否则，如果 `PATH` 上有 `claude.cmd` / `claude.bat` 垫片，就返回它 —— 这样做正是为了让
   `connect()` 抛出批处理脚本拒绝*及其补救建议*，而不是一个干巴巴的"未找到"错误。
4. 否则返回一个非原生的 `PATH` 命中，以便派生错误能指出实际安装的是什么。
5. 否则抛出 `CLINotFoundException`，并附上指向原生安装器的 Windows 专用提示信息。它不会推荐
   `npm install -g @anthropic-ai/claude-code`，因为那正好会产生本 SDK 所拒绝的那种垫片。

POSIX 的发现顺序保持不变：`PATH`，然后是 `~/.npm-global/bin`、`/usr/local/bin`、`~/.local/bin`、
`~/node_modules/.bin`、`~/.yarn/bin`、`~/.claude/local`。

### Windows：拒绝 cmd.exe 元字符（0.1.21）

纵深防御。在批处理派生已被拒绝的前提下，这些字符本已无害，但 `resume` 与 `sessionId` 是应用最常
从外部输入取值的地方，因此仍然会被拒绝 —— 即便将来某天 SDK 与 CLI 之间又重新引入 cmd.exe 这一跳，
它们也保持无害。

在 Windows 上，若 `resume` 或 `sessionId` 含有 `&`、`|`、`<`、`>`、`^`、`%`、`!`、`"`、CR 或 LF，
`buildCommand()` 会抛出 `IllegalArgumentException`：

```java
// On Windows: IllegalArgumentException
ClaudeAgentOptions.builder().resume("R&D notes").build();

// Accepted — no format is imposed beyond the metacharacter check;
// resume values may be arbitrary session titles, not only UUIDs
ClaudeAgentOptions.builder().resume("Refactor the parser (part 2)").build();
```

**POSIX 行为未变** —— 那里没有需要防范的 cmd.exe，因此 `resume("R&D notes")` 会原样传递。

### `extraArgs` 取值绑定（0.1.21）

对于取值以 `-` 开头的 `extraArgs` 条目，`buildCommand()` 会把它作为单个 `--flag=value` 记号发出，
而不是两个。这与 `resume`/`sessionId` 的改动所堵住的是同一类注入，只是应用到了剩下那个双记号的调用
位置：在双记号形式下，当 CLI 把该选项声明为取值可选时，以短横线开头的取值不会绑定到其标志，反而会
被解析成一个独立的标志。

| `extraArgs` 条目 | 发出的 argv |
|---|---|
| `Map.of("some-flag", "value")` | `--some-flag`、`value` |
| `Map.of("some-flag", "--evil")` | `--some-flag=--evil` |
| `Map.of("verbose-thing", "")` | `--verbose-thing`（裸的布尔标志） |

### Windows：批处理 CLI 选择开关（0.1.22）

对于确实无法从 npm 安装的 `claude.cmd` 迁移的部署环境 —— 例如集中管理的软件分发 —— 可以显式放弃上述
拒绝策略：

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cliPath(Path.of("C:\\Users\\Administrator\\AppData\\Roaming\\npm\\claude.cmd"))
    .allowUnsafeWindowsBatchCli(true)
    .build();
```

默认值是 `false`；对于不设置它的调用方，拒绝策略没有任何变化。

#### 为什么这不是简单的绕过

最直白的实现 —— 设置该标志时跳过检查 —— 会把整个 cmd.exe 重解析漏洞原封不动地交回去。关键细节其实在
JDK 自身：

```java
// OpenJDK, src/java.base/windows/classes/java/lang/ProcessImpl.java
final String value = System.getProperty("jdk.lang.Process.allowAmbiguousCommands", "true");
final boolean allowAmbiguousCommands = !"false".equalsIgnoreCase(value);
if (allowAmbiguousCommands) {
    cmdstr = createCommandLine(VERIFICATION_LEGACY, executablePath, cmd);  // escape set: ""
```

该属性默认为 `"true"`，从而选中一种遗留模式，其转义字符集是**空的** —— 除空白之外什么都不加引号，
而且内嵌的引号会被接受。**在原装 JVM 上，Java 面对这一漏洞类别的暴露程度与当年的 Node.js 完全相同**；
这项拒绝策略并不只是从另一个生态借来的推理。

把该属性设为 `false` 之后，非 `.exe` 的目标会转而选中 `VERIFICATION_CMD_BAT`，其转义字符集是
`"<>&|^`：含有这些字符或空白的参数会被加引号，而携带内嵌引号的参数会直接抛出异常。

因此这个选择开关要求启用该模式，并补上了 JDK 遗漏的那一块：

1. **必须设置 `-Djdk.lang.Process.allowAmbiguousCommands=false`。** 如果该属性不是 `false`，
   `connect()` 会抛出 `CLIConnectionException` —— 这与 JDK 自身的 `!"false".equalsIgnoreCase(value)`
   读法保持一致，而不是另行发明一套真假判定。错误信息会点明需要添加的标志。
2. **会扫描每一个 CLI 参数**中的 `& | < > ^ % ! "` 以及 CR/LF，并抛出指明违规选项的
   `IllegalArgumentException`。`%` 与 `!` 不在 JDK 的转义字符集中，而且加引号*并不能*阻止 `%VAR%`
   展开 —— 一个未加引号、展开为 `x&calc` 的 `%FOO%` 会被重新解析。这堵住了参数侧的那条途径。位于
   argv[0] 的可执行文件路径不参与扫描：它不是调用方提供的参数数据，而且已经过分类。
3. **每个传输会记录一次 `WARNING`**，点明路径以及所接受的风险。

其结果是一个比"干脆没有这项检查"*更窄*的攻击面。

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

#### 残余风险

cmd.exe 会从**环境变量**展开 `%VAR%`。argv 扫描堵住的是参数侧的途径；如果攻击者能控制 JVM 传给 CLI
的环境，它就无能为力。仅在 CLI 路径与每一个参数值都由管理员掌控的场合使用这个选择开关，并把它当作
迁移期的过渡桥梁，而不是终点。

POSIX 自始至终不受影响 —— 那里没有 cmd.exe 这一跳，因此 `.cmd` 文件只是普通文件名，拒绝策略与选择
开关的各项条件都不适用。

参见 [`examples/WindowsBatchCliExample.java`](../../examples/src/main/java/examples/WindowsBatchCliExample.java)。

### Skill 名称对 `--allowedTools` 的注入（0.1.22）

上面三条加固都关乎 *argv* 边界 —— 每个选项一个参数，不涉及 shell。这一条不同：它是**单个参数取值
内部**的注入。

`applySkillsDefaults()` 会把每个 `ClaudeAgentOptions.skills(List)` 条目格式化成 `Skill(<name>)`，
并把结果拼接进那唯一的 `--allowedTools` 字符串。随后 CLI 会按括号外的逗号和空格把该字符串拆回成
权限规则，而**那个分词器不识别任何转义序列** —— 转义只存在于单条规则的语法中，且在拆分之后才应用。
因此携带分隔符的名称无法被可靠地传递；它会被分成什么，取决于它周围是什么。

```java
// Before 0.1.22:  --allowedTools "Skill(x),Bash(*)"
//                                          ^^^^^^^ an extra rule, never requested
ClaudeAgentOptions.builder().skills(List.of("x),Bash(*")).build();
```

现在 `validateSkillName()` 会在格式化之前对每个条目运行，因此拒绝会从 `buildCommand()` 抛出 ——
在 `connect()` 时、子进程被派生之前。它会拒绝括号、逗号、控制字符（C0、DEL、C1）、字节序标记、空
名称、字面量 `*` 以及通配符后缀；并且为了避免一条"死规则"悄无声息地什么都不授予，还会拒绝首尾空白、
开头的 `/`、连续的反斜杠、结尾孤立的反斜杠以及孤立的代理项。普通名称 —— 插件限定的、含内部空格的、
单个反斜杠的、非 ASCII 的 —— 生成的 argv 与以前完全一致。

`rejectNonListSkills()` 守护取值形状本身。builder 的 `skills(List)` / `skillsAll()` 这对方法已经让
裸字符串或非列表的可迭代对象无法抵达 —— Java 的类型系统在这里做到了 Python SDK 需要在运行时强制的
事情 —— 但该检查仍被保留，以便使用原始类型或反射的调用方会以失败关闭告终，而不是悄悄地根本没装上
任何 skill 过滤器。

完整的拒绝表、被接受的名称列表，以及与 Python SDK 之间两处刻意的差异（孤立代理项 vs 任意代理项、
不换行空格的去除）：参见 [Skills → 名称校验](./feature-skills.md#名称校验0122)。

## 自定义传输

实现 `Transport` 接口以采用自定义通信方式：

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

## 使用自定义传输

使用自定义传输时，SDK 会调用它的 `connect()`，通过 `write()` 投递提示词，并经由控制协议发送钩子、agent 以及其他 `initialize` 请求中的设置（包括 `systemPromptSnapshot`）。`SubprocessCLITransport` 会转换成 CLI 标志或环境变量的一切 —— model、cwd、权限模式、tools、`env` 等 —— 都**不会**被应用，基于存储的会话恢复也会被跳过。`verbatimPrompts` 仍然生效，因为 SDK 会在调用 `write()` 之前给提示词加上标记。

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

## 最佳实践

1. **线程安全**：确保 write() 是线程安全的
2. **资源管理**：正确实现 close()
3. **错误处理**：出错时抛出 CLIConnectionException
4. **阻塞读取**：readMessages() 应当阻塞直到有数据可用
5. **JSON 格式**：消息必须是 JSON 对象，每行一个

## 另见
- [架构](./architecture.md#4-传输层) —— 传输层设计
- 传输层源代码：`sdk/src/main/java/in/vidyalai/claude/sdk/transport/`
