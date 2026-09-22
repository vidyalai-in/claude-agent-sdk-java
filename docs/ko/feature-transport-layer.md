# 전송 계층

Claude Code와의 통신을 위한 사용자 정의 전송 구현입니다.

> **번역 안내**: 공식 문서는 영어판뿐입니다. 이 번역은 [영어 원문](../feature-transport-layer.md)보다 뒤처져 있을 수 있으며, 내용이 어긋날 경우 영어판이 기준입니다. 코드 블록은 영어 원문과 완전히 동일하게 유지되며 번역하지 않습니다.

## 개요

전송 계층은 Claude Code CLI와의 I/O를 담당합니다. 원격 연결이나 다른 통신 방식을 위해 직접 전송을
구현할 수 있습니다.

## Transport 인터페이스

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

## 기본 구현

### SubprocessCLITransport

기본 전송은 Claude Code CLI를 서브프로세스로 생성합니다. 이는 options만으로 구성됩니다 — 프롬프트와
스트리밍 모드는 전송이 아니라 `ClaudeAgentOptions`와 메시지 스트림이 실어 나릅니다:

```java
Transport transport = new SubprocessCLITransport(options);
```

**기능**:
- 서브프로세스 수명 주기 관리
- stdin/stdout 통신
- 버퍼링된 읽기
- stderr 콜백 지원
- 자동 정리
- 유예 기간을 둔 정상 종료(stdin EOF 이후 SIGTERM을 보내기 전에, 서브프로세스가 세션 파일을 비울 때까지 기다림)
- 기본적으로 `CLAUDE_CODE_ENTRYPOINT=sdk-java` 설정(`ClaudeAgentOptions.env()`로 덮어쓸 수 있음)

### CLI 플래그 전달

`SubprocessCLITransport.buildCommand()`는 `ClaudeAgentOptions`를 CLI 플래그로 옮깁니다. 최근 옵션과
관련된 주요 플래그는 다음과 같습니다:

| 옵션 | CLI 플래그 | 참고 |
|---|---|---|
| `sessionStore(...)` | `--session-mirror` | `sessionStore != null`일 때 추가됩니다. CLI에게 stdout으로 `transcript_mirror` 프레임을 내보내라고 알리고, SDK가 그것을 걷어내어 설정된 `SessionStore`로 전달합니다. |
| `thinking(ThinkingConfigAdaptive(SUMMARIZED))` | `--thinking adaptive --thinking-display summarized` | `--thinking-display`는 `Adaptive`와 `Enabled` 구성에서만(그리고 `display != null`일 때만) 전달됩니다. `Disabled`는 결코 내보내지 않습니다. |
| `thinking(ThinkingConfigEnabled(20000, OMITTED))` | `--max-thinking-tokens 20000 --thinking-display omitted` | `display`가 설정되면 두 플래그가 함께 나갑니다. |

**stderr 파이프**: stderr는 `options.stderrCallback() != null`일 때만 파이프됩니다. 예전의
`--debug-to-stderr` 추가 인자 감지는 0.1.13에서 제거되었습니다(해당 CLI 플래그의 폐기에 대비).
CLI의 자세한 디버그 출력을 얻으려면 `extraArgs(Map.of("debug-file", "/path/to/log"))`를 넘기고 그
파일을 읽으세요.

**stderr 콜백 격리**(0.1.16): `stderrCallback.accept(line)` 호출은 한 줄마다
`try/catch(Throwable)`로 감싸집니다. 예외를 던지는 콜백은 잡혀서 `FINE` 수준으로 기록되고, 읽기
루프는 다음 줄로 이어집니다. 예전에는 한 번 예외가 나면 루프를 빠져나가, 그 세션이 끝날 때까지 이후의
모든 stderr 줄이 조용히 버려졌습니다. 바깥 루프의 실패(스트림이 예기치 않게 닫힘, I/O 오류)도 조용히
삼켜지는 대신 `FINE`으로 기록됩니다.

**고아 자식 프로세스 정리**(0.1.18): 생성된 모든 CLI 프로세스는 정적 `ACTIVE_CHILDREN` 집합에
등록되고, `close()`가 실행되기 전에 프로세스가 종료되면 JVM 종료 훅이 아직 살아 있는 것들을 최선을
다해 종료시킵니다. `close()`는 종료 수단을 단계적으로 강화하고(유예 기간 → `destroy()` / SIGTERM →
`destroyForcibly()` / SIGKILL), **더 이상 살아 있지 않음을 확인한 뒤에야**(`!process.isAlive()`)
그 프로세스를 `ACTIVE_CHILDREN`에서 제거합니다. 그래서 어떤 이유로든 이 단계적 종료를 살아남은 자식
프로세스(경쟁 상태의 kill이나 시간 초과된 `waitFor`)는 계속 추적되어, 종료 훅의 회수 로직이 다시 한
번 손볼 기회를 얻고 고아 `claude` 프로세스로 새어 나가지 않습니다.

**`resume` / `sessionId`의 argv 플래그 주입 방어**(0.1.19): `buildCommand()`는 `resume`와
`sessionId`를 두 개의 토큰(`--resume`, `<value>`)이 아니라 하나의 `--flag=value` argv
토큰(`--resume=<value>`, `--session-id=<value>`)으로 내보냅니다. CLI는 `--resume`을 값이 *선택적인*
것으로 선언하므로, 두 토큰 형태에서는 대시로 시작하는 값이 플래그에 묶이지 않고 독립적인 CLI 플래그로
해석됩니다. 따라서 신뢰할 수 없는 입력을 `resume`/`sessionId`로 흘려보내는 애플리케이션(요청에서 세션
ID를 읽는 "내 세션 재개" 엔드포인트 등)은 임의의 CLI 플래그를 주입당할 수 있었습니다 —
`resume("--version")`은 조용히 `claude --version`을 실행하고 메시지를 0건 반환했습니다. 등호 형태는
값을 항상 플래그에 묶고, 그러면 CLI가 대시로 시작하는 값을 유효하지 않은 세션 ID로 거부합니다. 이는
argv 수준의 문제이고(옵션당 인자 하나, **셸은 전혀 개입하지 않음**) 명령 실행이 아니라 플래그
주입이며, 신뢰할 수 없는 입력을 이 옵션들로 전달하는 앱에만 영향을 줍니다. `buildCommand()`의 다른
곳에서 이미 쓰고 있는 `--setting-sources=` 방식과 일치합니다.

### Windows: 배치 스크립트 CLI 거부 (0.1.21)

Windows에는 shebang 메커니즘이 없습니다. CLI 경로가 `.bat`이나 `.cmd` 파일을 가리키면, OS는 생성을
`cmd.exe /c` 호출로 바꿔 실행하고, **cmd.exe는 실행 시점에 명령줄 전체를 다시 파싱합니다**. 인자
따옴표 처리는 cmd.exe가 아니라 MSVCRT의 argv 규칙을 따르는데, 이 규칙은 공백 주위에만 따옴표를
붙입니다. 그래서 인자 값 안의 cmd.exe 메타문자(`--resume`의 세션 제목, `--mcp-config`의 JSON, 시스템
프롬프트)가 이스케이프되지 않은 채 cmd.exe에 도달해, CLI가 시작되기도 전에 주입된 명령을 실행할 수
있습니다.

0.1.19의 `--flag=value` 형태는 이 경로에서는 도움이 되지 않습니다. cmd.exe가 문자열을 다시 파싱하고
나면 보호할 argv 경계가 남아 있지 않기 때문입니다. cmd.exe에 대한 신뢰할 만한 이스케이프는 존재하지
않으므로(`%VAR%`는 큰따옴표 안에서도 확장됩니다) **거부가 유일하게 견고한 해법**입니다 — Node.js가
이 취약점 부류(CVE-2024-27980, "BatBadBut")에 대해 내놓은 것과 같은 해법입니다.

`connect()`는 그 경로로 무언가를 생성하기 전에 해석된 경로를 검증하므로, 이 검사는 실행 파일에 이르는
모든 경로를 포괄합니다: PATH 탐색, 명시적인 `ClaudeAgentOptions.cliPath(...)`, 그리고 주 프로세스
이전에 실행되는 버전 확인입니다.

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

**해결책**(모두 cmd.exe를 완전히 피합니다): `irm https://claude.ai/install.ps1 | iex`로 Claude
Code를 네이티브로 설치하거나, `cliPath`를 `claude.exe`로 지정하세요. npm으로 설치한 `claude.cmd`에서
벗어나는 것이 정말로 불가능하다면, 아래의 [명시적 옵트인](#windows-배치-cli-옵트인-0122)을 참고하세요.

확장자 대조는 Win32와 같은 방식으로 정규화하며, 마지막 것만이 아니라 **모든** 경로 구성 요소를
분류합니다:

| 표기 | 거부 | 이유 |
|---|---|---|
| `C:\npm\claude.cmd` | 예 | 평범한 경우 |
| `C:\npm\claude.CMD` | 예 | 확장자 대조는 대소문자를 구분하지 않음 |
| `C:\npm\claude.cmd ` / `claude.cmd.` | 예 | Windows는 경로 해석 시 끝의 점과 공백을 떼어 냄 |
| `C:\npm\claude.cmd:stream` | 예 | NTFS 스트림 지정자도 기반 파일을 엶 |
| `C:\npm\claude:evil.cmd` | 예 | Win32는 스트림 지정자를 포함한 구성 요소 전체에 대해 마지막 점을 찾아 확장자를 정함 |
| `C:claude.cmd` | 예 | 드라이브 접두사는 같은 구성 요소 안에 들어 있음 |
| `.cmd` | 예 | `PathFindExtension`은 맨 `.cmd`도 확장자로 취급함 |
| `C:\claude.cmd\..\claude.exe` | 예 | 어떤 구성 요소든 해당됨 — `.`/`..` 정규화로는 세탁할 수 없음 |
| `C:\bin\claude.exe` | 아니오 | 네이티브 실행 파일 |
| Linux/macOS의 `/opt/claude.cmd` | 아니오 | POSIX에는 cmd.exe 경유가 없고 `.cmd`는 평범한 파일 이름임 |

이 검사는 `java.nio.file.Path`가 아니라 일부러 단순한 문자열 로직으로 되어 있습니다. 경로 파싱은
POSIX와 Windows가 다르고, 양쪽에서 똑같이 동작하는 것은 문자열 로직뿐이기 때문입니다. 모든 구성 요소를
분류하면 정규화 트릭 부류 전체를 한 번에 막을 수 있고, 정당한 사용에는 아무 비용도 들지 않습니다 —
배치 파일처럼 이름 붙은 디렉터리 밑에 진짜 `claude.exe`가 있을 리 없습니다.

### Windows: CLI 탐색 순서 (0.1.21)

탐색은 네이티브 실행 파일을 우선합니다. Windows에서 확장자 없는 `claude`는 OS가 직접 실행할 수 없는
git-bash / WSL 래퍼 스크립트이기 때문입니다:

1. 먼저 **`PATH` 전체**를 훑어 `claude.exe`를 찾습니다. PATH는 디렉터리 우선으로 순회되므로, 이 단계가
   없으면 앞쪽 디렉터리의 래퍼 스크립트가 뒤쪽에 설치된 진짜 `claude.exe`를 가려 버립니다.
2. 그렇지 않으면 `~/.local/bin/claude.exe`로 물러납니다. POSIX 형태의 위치들은 Windows에서 일부러
   **탐색하지 않습니다**. 거기서 확장자 없는 것이 잡히면 설명이 담긴 거부 대신 알 수 없는 생성 실패가
   먼저 일어나고, 루트로 시작하지만 드라이브가 없는 `/usr/local/bin/claude`는 현재 드라이브를 기준으로
   해석되는데, 그곳은 다른 로컬 사용자가 만들 수 있는 위치라 바이너리 심기의 탐침이 됩니다.
3. 그렇지 않으면 `PATH`에 `claude.cmd` / `claude.bat` 심이 있을 때 그것을 반환합니다. 이는 `connect()`가
   맹숭맹숭한 "찾을 수 없음" 대신 배치 스크립트 거부를 *해결책과 함께* 던지게 하기 위함입니다.
4. 그렇지 않으면 네이티브가 아닌 `PATH` 결과를 반환해, 생성 오류가 실제로 설치된 것이 무엇인지 알려
   주도록 합니다.
5. 그렇지 않으면 네이티브 설치 프로그램을 안내하는 Windows 전용 메시지와 함께 `CLINotFoundException`을
   던집니다. `npm install -g @anthropic-ai/claude-code`는 권하지 않습니다. 그것이야말로 이 SDK가
   거부하는 바로 그 심을 만들어 내기 때문입니다.

POSIX 탐색은 그대로입니다: `PATH`, 그다음 `~/.npm-global/bin`, `/usr/local/bin`, `~/.local/bin`,
`~/node_modules/.bin`, `~/.yarn/bin`, `~/.claude/local`.

### Windows: cmd.exe 메타문자 거부 (0.1.21)

심층 방어입니다. 배치 생성이 거부된 상태에서는 이 문자들이 이미 무해하지만, `resume`와 `sessionId`는
애플리케이션이 외부 입력에서 값을 받아 오는 일이 가장 잦은 곳이므로 그래도 거부합니다 — 혹시라도
나중에 SDK와 CLI 사이에 cmd.exe 경유가 다시 들어오더라도 무해하게 유지하기 위해서입니다.

Windows에서 `resume`나 `sessionId`에 `&`, `|`, `<`, `>`, `^`, `%`, `!`, `"`, CR, LF가 들어 있으면
`buildCommand()`가 `IllegalArgumentException`을 던집니다:

```java
// On Windows: IllegalArgumentException
ClaudeAgentOptions.builder().resume("R&D notes").build();

// Accepted — no format is imposed beyond the metacharacter check;
// resume values may be arbitrary session titles, not only UUIDs
ClaudeAgentOptions.builder().resume("Refactor the parser (part 2)").build();
```

**POSIX 동작은 그대로입니다** — 막아야 할 cmd.exe가 없으므로 `resume("R&D notes")`는 있는 그대로
전달됩니다.

### `extraArgs` 값 묶기 (0.1.21)

`buildCommand()`는 값이 `-`로 시작하는 `extraArgs` 항목을 두 개가 아니라 하나의 `--flag=value`
토큰으로 내보냅니다. 이는 `resume`/`sessionId` 변경이 막은 것과 같은 주입 부류를, 남아 있던 두 토큰
호출 지점에 적용한 것입니다. 두 토큰 형태에서는 CLI가 그 옵션을 값이 선택적인 것으로 선언한 경우 대시로
시작하는 값이 플래그에 묶이지 않고 별도의 플래그로 해석됩니다.

| `extraArgs` 항목 | 생성되는 argv |
|---|---|
| `Map.of("some-flag", "value")` | `--some-flag`, `value` |
| `Map.of("some-flag", "--evil")` | `--some-flag=--evil` |
| `Map.of("verbose-thing", "")` | `--verbose-thing`(맨 불리언 플래그) |

### Windows: 배치 CLI 옵트인 (0.1.22)

npm으로 설치한 `claude.cmd`에서 벗어날 수 없는 배포 환경 — 예를 들어 중앙에서 관리되는 소프트웨어
배포 — 을 위해, 위의 거부를 명시적으로 면제할 수 있습니다:

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cliPath(Path.of("C:\\Users\\Administrator\\AppData\\Roaming\\npm\\claude.cmd"))
    .allowUnsafeWindowsBatchCli(true)
    .build();
```

기본값은 `false`이며, 이를 설정하지 않는 호출자에게 거부 동작은 아무것도 달라지지 않습니다.

#### 왜 이것이 단순한 우회가 아닌가

가장 뻔한 구현 — 플래그가 설정되면 검사를 건너뛰기 — 은 cmd.exe 재파싱 구멍을 통째로 되돌려줍니다.
핵심은 JDK 자체에 있습니다:

```java
// OpenJDK, src/java.base/windows/classes/java/lang/ProcessImpl.java
final String value = System.getProperty("jdk.lang.Process.allowAmbiguousCommands", "true");
final boolean allowAmbiguousCommands = !"false".equalsIgnoreCase(value);
if (allowAmbiguousCommands) {
    cmdstr = createCommandLine(VERIFICATION_LEGACY, executablePath, cmd);  // escape set: ""
```

이 속성의 기본값은 `"true"`이며, 이스케이프 집합이 **비어 있는** 레거시 모드를 선택합니다 — 공백
말고는 아무것도 따옴표로 감싸지 않고, 내장된 따옴표도 그대로 받아들입니다. **기본 설정의 JVM에서
Java는 이 취약점 부류에 당시의 Node.js와 정확히 같은 정도로 노출되어 있습니다**. 이 거부는 단지 다른
생태계에서 빌려온 논리가 아닙니다.

이 속성을 `false`로 두면, `.exe`가 아닌 대상에는 대신 `VERIFICATION_CMD_BAT`가 선택됩니다. 그
이스케이프 집합은 `"<>&|^`이며, 이런 문자나 공백을 담은 인자는 따옴표로 감싸이고, 내장된 따옴표를
가진 인자는 곧바로 예외를 일으킵니다.

그래서 이 옵트인은 그 모드를 요구하고, JDK가 빠뜨린 부분을 채웁니다:

1. **`-Djdk.lang.Process.allowAmbiguousCommands=false`가 필수입니다.** 이 속성이 `false`가 아니면
   `connect()`가 `CLIConnectionException`을 던집니다 — 참/거짓 판정을 따로 만들어 내는 대신 JDK
   자신의 `!"false".equalsIgnoreCase(value)` 해석에 맞춘 것입니다. 메시지에 추가해야 할 플래그가
   명시됩니다.
2. **모든 CLI 인자를 훑어** `& | < > ^ % ! "`와 CR/LF를 찾고, 문제가 된 옵션을 지목하는
   `IllegalArgumentException`을 던집니다. `%`와 `!`는 JDK의 이스케이프 집합에 없고, 따옴표로 감싸도
   `%VAR%` 확장은 *멈추지 않습니다* — 따옴표 없는 `%FOO%`가 `x&calc`로 확장되면 다시 파싱됩니다. 이로써
   인자 쪽 경로가 막힙니다. argv[0]의 실행 파일 경로는 훑지 않습니다. 호출자가 준 인자 데이터가 아니며
   이미 분류를 마쳤기 때문입니다.
3. **전송마다 한 번 `WARNING`을 기록**하여 경로와 감수한 위험을 알립니다.

그 결과는 검사가 아예 없는 것보다 *더 좁은* 공격면입니다.

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

#### 남아 있는 위험

cmd.exe는 `%VAR%`를 **환경 변수**에서 확장합니다. argv 훑기는 인자 쪽 경로를 막지만, 공격자가 JVM이
CLI에 넘기는 환경을 통제할 수 있다면 소용이 없습니다. 이 옵트인은 CLI 경로와 모든 인자 값이 관리자
통제 아래 있는 곳에서만 사용하고, 목적지가 아니라 이행을 위한 다리로 여기세요.

POSIX는 전 구간에서 영향을 받지 않습니다 — cmd.exe 경유가 없으므로 `.cmd` 파일은 평범한 파일 이름이고,
거부도 옵트인의 조건도 적용되지 않습니다.

[`examples/WindowsBatchCliExample.java`](../../examples/src/main/java/examples/WindowsBatchCliExample.java)를
참고하세요.

### skill 이름을 통한 `--allowedTools` 주입 (0.1.22)

위의 세 가지 강화는 *argv* 경계에 관한 것이었습니다 — 옵션당 인자 하나, 셸은 개입하지 않습니다. 이것은
다릅니다: **단일 인자의 값 안쪽**에서 일어나는 주입입니다.

`applySkillsDefaults()`는 각 `ClaudeAgentOptions.skills(List)` 항목을 `Skill(<name>)`로 만들고, 그
결과를 하나의 `--allowedTools` 문자열로 잇습니다. 그러면 CLI는 그 문자열을 괄호 밖의 쉼표와 공백을
기준으로 다시 권한 규칙으로 나누는데, **그 토크나이저는 어떤 이스케이프 시퀀스도 인정하지 않습니다** —
이스케이프는 규칙 단위 문법에만 존재하고, 그것은 분할 이후에 적용됩니다. 따라서 구분자를 품은 이름은
안정적으로 전달될 수 없고, 어떻게 토큰화되는지는 주변에 무엇이 있느냐에 달려 있습니다.

```java
// Before 0.1.22:  --allowedTools "Skill(x),Bash(*)"
//                                          ^^^^^^^ an extra rule, never requested
ClaudeAgentOptions.builder().skills(List.of("x),Bash(*")).build();
```

이제 `validateSkillName()`이 형식을 만들기 전에 모든 항목에 대해 실행되므로, 거부는
`buildCommand()`에서 — `connect()` 시점, 서브프로세스가 생성되기 전에 — 드러납니다. 괄호, 쉼표, 제어
문자(C0, DEL, C1), 바이트 순서 표식, 빈 이름, 문자 그대로의 `*`, 와일드카드 접미사를 거부합니다.
그리고 죽은 규칙이 조용히 아무것도 허용하지 않는 일이 없도록, 앞뒤 공백, 앞의 `/`, 연속된 백슬래시,
끝의 짝 없는 백슬래시, 단독 서로게이트도 거부합니다. 평범한 이름 — 플러그인 한정, 내부 공백, 단일
백슬래시, 비 ASCII — 은 예전과 똑같은 argv를 만듭니다.

`rejectNonListSkills()`는 값의 형태 자체를 지킵니다. 빌더의 `skills(List)` / `skillsAll()` 짝이 이미
맨 문자열이나 리스트가 아닌 이터러블을 닿을 수 없게 만들지만 — Java의 타입 시스템이 Python SDK가
런타임에 강제하는 일을 여기서 해 줍니다 — 원시 타입이나 리플렉션을 쓰는 호출자가 skill 필터를 아예
설치하지 않은 채 조용히 지나가는 대신 확실히 실패하도록 이 검사는 남겨 두었습니다.

전체 거부 표, 허용되는 이름 목록, 그리고 Python SDK와의 의도적인 두 가지 차이(단독 서로게이트 대 모든
서로게이트, 줄바꿈 없는 공백 제거)는
[Skills → 이름 검증](./feature-skills.md#이름-검증-0122)을 참고하세요.

## 사용자 정의 전송

직접 만든 통신 방식을 위해 `Transport` 인터페이스를 구현합니다:

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

## 사용자 정의 전송 사용하기

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

## 모범 사례

1. **스레드 안전성**: write()를 스레드 안전하게 만드세요
2. **자원 관리**: close()를 제대로 구현하세요
3. **오류 처리**: 오류에는 CLIConnectionException을 던지세요
4. **블로킹 읽기**: readMessages()는 데이터가 올 때까지 블록해야 합니다
5. **JSON 형식**: 메시지는 한 줄에 하나씩인 JSON 객체여야 합니다

## 관련 항목
- [아키텍처](./architecture.md#4-전송-계층) — 전송 계층 설계
- 전송 계층 소스 코드: `sdk/src/main/java/in/vidyalai/claude/sdk/transport/`
