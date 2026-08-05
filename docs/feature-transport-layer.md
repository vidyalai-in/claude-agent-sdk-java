# Transport Layer

Custom transport implementations for Claude Code communication.

## Overview

The transport layer handles I/O with Claude Code CLI. You can implement custom transports for remote connections or alternative communication methods.

## Transport Interface

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

## Default Implementation

### SubprocessCLITransport

The default transport spawns Claude Code CLI as a subprocess. It is constructed from the options alone — the prompt and streaming mode are carried on `ClaudeAgentOptions` and the message stream, not on the transport:

```java
Transport transport = new SubprocessCLITransport(options);
```

**Features**:
- Manages subprocess lifecycle
- Stdin/stdout communication
- Buffered reading
- Stderr callback support
- Automatic cleanup
- Graceful shutdown with grace period (waits for subprocess to flush session file after stdin EOF before sending SIGTERM)
- Sets `CLAUDE_CODE_ENTRYPOINT=sdk-java` by default (overridable via `ClaudeAgentOptions.env()`)

### CLI flag forwarding

`SubprocessCLITransport.buildCommand()` translates `ClaudeAgentOptions` into CLI flags. Notable flags relevant to recent options:

| Option | CLI flag(s) | Notes |
|---|---|---|
| `sessionStore(...)` | `--session-mirror` | Added when `sessionStore != null`. Tells the CLI to emit `transcript_mirror` frames on stdout, which the SDK peels off and forwards to the configured `SessionStore`. |
| `thinking(ThinkingConfigAdaptive(SUMMARIZED))` | `--thinking adaptive --thinking-display summarized` | `--thinking-display` is forwarded only for `Adaptive` and `Enabled` configs (and only when `display != null`); `Disabled` never emits it. |
| `thinking(ThinkingConfigEnabled(20000, OMITTED))` | `--max-thinking-tokens 20000 --thinking-display omitted` | Both flags emitted together when `display` is set. |

**Stderr piping**: stderr is piped only when `options.stderrCallback() != null`. The legacy `--debug-to-stderr` extra-arg detection was removed in 0.1.13 (prep for the CLI flag's deprecation). To capture verbose CLI debug output, pass `extraArgs(Map.of("debug-file", "/path/to/log"))` and read that file instead.

**Stderr callback isolation** (0.1.16): each `stderrCallback.accept(line)` call is wrapped in a per-line `try/catch(Throwable)`. A throwing callback is caught, logged at `FINE`, and the read loop continues with the next line. Previously a throw would exit the loop and silently drop every subsequent stderr line for the rest of the session. Outer-loop failures (stream closed unexpectedly, I/O errors) are also logged at `FINE` instead of being silently swallowed.

**Orphan-child cleanup** (0.1.18): every spawned CLI process is registered in a static `ACTIVE_CHILDREN` set, and a JVM shutdown hook best-effort terminates any that are still alive if the process exits before `close()` runs. `close()` escalates termination (grace period → `destroy()` / SIGTERM → `destroyForcibly()` / SIGKILL) and then removes the process from `ACTIVE_CHILDREN` **only after confirming it is no longer alive** (`!process.isAlive()`). A child that somehow survives the escalation (a raced kill, or a `waitFor` that timed out) therefore stays tracked, so the shutdown-hook reaper still gets a chance at it instead of leaking as an orphaned `claude` process.

**`resume` / `sessionId` argv flag-injection hardening** (0.1.19): `buildCommand()` emits `resume` and `sessionId` as single `--flag=value` argv tokens (`--resume=<value>`, `--session-id=<value>`) rather than as two separate tokens (`--resume`, `<value>`). The CLI declares `--resume` with an *optional* value, so in the two-token form a dash-leading value is not bound to the flag and is instead parsed as an independent CLI flag. An application that routes untrusted input into `resume`/`sessionId` (e.g. a "resume my session" endpoint that reads a session ID from a request) could therefore inject arbitrary CLI flags — `resume("--version")` silently ran `claude --version` and yielded zero messages. The equals form always binds the value to the flag, and the CLI then rejects a dash-leading value as an invalid session ID. This is argv-level (one argument per option, **no shell is involved**), so it is flag injection, not command execution, and only affects apps forwarding untrusted input into these options. Matches the `--setting-sources=` style already used elsewhere in `buildCommand()`.

### Windows: batch-script CLI refusal (0.1.21)

Windows has no shebang mechanism. When the CLI path names a `.bat` or `.cmd` file, the OS runs it by rewriting the spawn into a `cmd.exe /c` invocation, and **cmd.exe re-parses the whole command line** at execution time. Argument quoting follows the MSVCRT argv rules — which only add quotes around whitespace — not cmd.exe's, so cmd.exe metacharacters inside an argument value (a `--resume` session title, the `--mcp-config` JSON, a system prompt) reach cmd.exe unescaped and can execute injected commands before the CLI even starts.

The `--flag=value` form from 0.1.19 does not help on this path: once cmd.exe re-parses the string there is no argv boundary left to protect. Reliable escaping for cmd.exe does not exist (`%VAR%` expands even inside double quotes), so **refusing is the only robust remediation** — the same one Node.js shipped for this vulnerability class (CVE-2024-27980, "BatBadBut").

`connect()` validates the resolved path before anything is spawned with it, so the check covers every route to the executable: PATH discovery, an explicit `ClaudeAgentOptions.cliPath(...)`, and the version probe that runs before the main process.

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

**Remediation** (all avoid cmd.exe entirely): install Claude Code natively with `irm https://claude.ai/install.ps1 | iex`, or point `cliPath` at a `claude.exe`. If migrating off an npm-installed `claude.cmd` is genuinely blocked, see [the explicit opt-in](#windows-batch-cli-opt-in-0122) below.

Extension matching normalizes the way Win32 does and classifies **every** path component, not just the last one:

| Spelling | Refused | Why |
|---|---|---|
| `C:\npm\claude.cmd` | yes | the plain case |
| `C:\npm\claude.CMD` | yes | extension matching is case-insensitive |
| `C:\npm\claude.cmd ` / `claude.cmd.` | yes | Windows strips trailing dots and spaces at path resolution |
| `C:\npm\claude.cmd:stream` | yes | an NTFS stream spec still opens its base file |
| `C:\npm\claude:evil.cmd` | yes | Win32 finds the extension by a last-dot scan over the whole component, stream spec included |
| `C:claude.cmd` | yes | a drive prefix rides in the same component |
| `.cmd` | yes | `PathFindExtension` treats a bare `.cmd` as an extension |
| `C:\claude.cmd\..\claude.exe` | yes | any component counts — `.`/`..` normalization cannot launder it |
| `C:\bin\claude.exe` | no | native executable |
| `/opt/claude.cmd` on Linux/macOS | no | POSIX has no cmd.exe hop; `.cmd` is an ordinary filename |

The check is deliberately plain string logic rather than `java.nio.file.Path`: path parsing differs between POSIX and Windows, and only string logic behaves identically on both. Classifying every component closes the whole normalization-trick class outright, and costs nothing legitimate — no real `claude.exe` lives beneath a directory named like a batch file.

### Windows: CLI discovery order (0.1.21)

Discovery prefers a native executable, because an extensionless `claude` on Windows is a git-bash / WSL wrapper script the OS cannot run directly:

1. Sweep the **whole** `PATH` for `claude.exe` first. PATH is walked directory-major, so without this a wrapper script in an early directory would shadow a real `claude.exe` installed in a later one.
2. Otherwise fall back to `~/.local/bin/claude.exe`. The POSIX-shaped locations are deliberately **not** probed on Windows: an extensionless match there would preempt the explanatory refusal with an opaque spawn failure, and a rooted-but-driveless `/usr/local/bin/claude` resolves against the current drive — a location another local user can create, making it a binary-planting probe.
3. Otherwise return a `claude.cmd` / `claude.bat` shim if one is on `PATH`, specifically so `connect()` raises the batch-script refusal *with its remediation* rather than a bare not-found error.
4. Otherwise return a non-native `PATH` hit so the spawn error names what is actually installed.
5. Otherwise throw `CLINotFoundException` with a Windows-specific message pointing at the native installer. It does not recommend `npm install -g @anthropic-ai/claude-code`, because that produces the very shim this SDK refuses.

POSIX discovery is unchanged: `PATH`, then `~/.npm-global/bin`, `/usr/local/bin`, `~/.local/bin`, `~/node_modules/.bin`, `~/.yarn/bin`, `~/.claude/local`.

### Windows: cmd.exe metacharacter rejection (0.1.21)

Defense in depth. With batch spawning refused these characters are already harmless, but `resume` and `sessionId` are the values applications most often take from external input, so they are rejected anyway — keeping them inert even if a cmd.exe hop is ever reintroduced between the SDK and the CLI.

On Windows, a `resume` or `sessionId` containing `&`, `|`, `<`, `>`, `^`, `%`, `!`, `"`, CR or LF throws `IllegalArgumentException` from `buildCommand()`:

```java
// On Windows: IllegalArgumentException
ClaudeAgentOptions.builder().resume("R&D notes").build();

// Accepted — no format is imposed beyond the metacharacter check;
// resume values may be arbitrary session titles, not only UUIDs
ClaudeAgentOptions.builder().resume("Refactor the parser (part 2)").build();
```

**POSIX behavior is unchanged** — there is no cmd.exe to protect from, so `resume("R&D notes")` is passed through as-is.

### `extraArgs` value binding (0.1.21)

`buildCommand()` emits an `extraArgs` entry whose value starts with `-` as a single `--flag=value` token rather than two. This is the same injection class the `resume`/`sessionId` change closed, applied to the remaining two-token call site: in the two-token form a dash-leading value is not bound to its flag when the CLI declares that option with an optional value, and parses as a separate flag instead.

| `extraArgs` entry | Emitted argv |
|---|---|
| `Map.of("some-flag", "value")` | `--some-flag`, `value` |
| `Map.of("some-flag", "--evil")` | `--some-flag=--evil` |
| `Map.of("verbose-thing", "")` | `--verbose-thing` (bare boolean flag) |

### Windows: batch-CLI opt-in (0.1.22)

For deployments that cannot migrate off an npm-installed `claude.cmd` — centrally managed software distribution, for instance — the refusal above can be waived explicitly:

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cliPath(Path.of("C:\\Users\\Administrator\\AppData\\Roaming\\npm\\claude.cmd"))
    .allowUnsafeWindowsBatchCli(true)
    .build();
```

The default is `false`; nothing about the refusal changes for callers who don't set it.

#### Why this is not a plain bypass

The obvious implementation — skip the check when the flag is set — would hand back the entire cmd.exe re-parse hole. The relevant detail is in the JDK itself:

```java
// OpenJDK, src/java.base/windows/classes/java/lang/ProcessImpl.java
final String value = System.getProperty("jdk.lang.Process.allowAmbiguousCommands", "true");
final boolean allowAmbiguousCommands = !"false".equalsIgnoreCase(value);
if (allowAmbiguousCommands) {
    cmdstr = createCommandLine(VERIFICATION_LEGACY, executablePath, cmd);  // escape set: ""
```

The property defaults to `"true"`, selecting a legacy mode whose escape set is **empty** — nothing but whitespace is quoted and embedded quotes are accepted. **On a stock JVM, Java is exposed to this vulnerability class exactly as Node.js was**; the refusal is not merely inherited reasoning from another ecosystem.

Set the property to `false` and a non-`.exe` target instead selects `VERIFICATION_CMD_BAT`, whose escape set is `"<>&|^`: arguments containing those or whitespace get quoted, and an argument carrying an embedded quote throws outright.

So the opt-in requires that mode, and adds the piece the JDK omits:

1. **`-Djdk.lang.Process.allowAmbiguousCommands=false` is mandatory.** `connect()` throws `CLIConnectionException` if the property is anything other than `false` — matching the JDK's own `!"false".equalsIgnoreCase(value)` reading rather than inventing a separate notion of truthiness. The message names the flag to add.
2. **Every CLI argument is swept** for `& | < > ^ % ! "` and CR/LF, throwing `IllegalArgumentException` that names the offending option. `%` and `!` are absent from the JDK's escape set, and quoting does *not* stop `%VAR%` expansion — an unquoted `%FOO%` expanding to `x&calc` is re-parsed. This closes that argument-side vector. The executable path at argv[0] is not swept: it is not caller-supplied argument data and has already been classified.
3. **A `WARNING` is logged once per transport**, naming the path and the accepted risk.

The result is a *narrower* attack surface than simply not having the check.

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

#### Residual risk

cmd.exe expands `%VAR%` from the **environment**. The argv sweep closes the argument-side vector; it cannot help if an attacker controls the environment the JVM passes to the CLI. Use the opt-in only where the CLI path and every argument value are administrator-controlled, and treat it as a migration bridge rather than a destination.

POSIX is unaffected throughout — there is no cmd.exe hop, so a `.cmd` file is an ordinary filename and neither the refusal nor the opt-in's conditions apply.

See [`examples/WindowsBatchCliExample.java`](../examples/src/main/java/examples/WindowsBatchCliExample.java).

### Skill-name `--allowedTools` injection (0.1.22)

The three hardening entries above concern *argv* boundaries — one argument per option, no shell involved. This one is different: it is an injection **inside a single argument's value**.

`applySkillsDefaults()` formats each `ClaudeAgentOptions.skills(List)` entry into `Skill(<name>)` and joins the result into the one `--allowedTools` string. The CLI then splits that string back into permission rules on commas and spaces outside parentheses, and **that tokenizer honors no escape sequences** — escaping exists only in the per-rule grammar, which is applied after splitting. A name carrying a delimiter therefore cannot be passed through reliably; what it tokenizes into depends on what surrounds it.

```java
// Before 0.1.22:  --allowedTools "Skill(x),Bash(*)"
//                                          ^^^^^^^ an extra rule, never requested
ClaudeAgentOptions.builder().skills(List.of("x),Bash(*")).build();
```

`validateSkillName()` now runs on every entry before formatting, so the rejection surfaces from `buildCommand()` — at `connect()`, before the subprocess is spawned. It rejects parentheses, commas, control characters (C0, DEL, C1), byte-order marks, empty names, a literal `*`, and wildcard suffixes; and, so a dead rule cannot silently grant nothing, also surrounding whitespace, a leading `/`, consecutive backslashes, a trailing unpaired backslash, and lone surrogates. Ordinary names — plugin-qualified, interior spaces, single backslashes, non-ASCII — build exactly the argv they did before.

`rejectNonListSkills()` guards the value shape itself. The builder's `skills(List)` / `skillsAll()` pair already makes a bare string or non-list iterable unreachable — Java's type system does here what the Python SDK enforces at runtime — but the check is kept so a raw-typed or reflective caller fails closed rather than silently installing no skill filter at all.

Full rejection table, accepted-name list, and the two deliberate divergences from the Python SDK (lone-vs-any surrogates, non-breaking-space stripping): see [Skills → Name Validation](./feature-skills.md#name-validation-0122).

## Custom Transport

Implement `Transport` interface for custom communication:

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

## Using Custom Transport

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

## Best Practices

1. **Thread Safety**: Ensure write() is thread-safe
2. **Resource Management**: Implement close() properly
3. **Error Handling**: Throw CLIConnectionException for errors
4. **Blocking Reads**: readMessages() should block until data available
5. **JSON Format**: Messages must be JSON objects, one per line

## See Also
- [Architecture](./architecture.md#transport-layer) - Transport layer design
- Transport source code: `sdk/src/main/java/in/vidyalai/claude/sdk/transport/`
