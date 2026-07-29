package in.vidyalai.claude.sdk.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.exceptions.CLINotFoundException;

/**
 * Tests for the Windows batch-script refusal and its defense-in-depth argument
 * checks (Python SDK #1127, follow-up to the argv-injection fix in #1123).
 *
 * <p>
 * On Windows the OS runs {@code .bat}/{@code .cmd} files by rewriting the spawn
 * into a {@code cmd.exe /c} invocation, and cmd.exe re-parses the whole command
 * line. Argument quoting follows the MSVCRT argv rules, not cmd.exe's, so
 * metacharacters inside an argument value reach cmd.exe unescaped. This is the
 * "BatBadBut" class (CVE-2024-27980); refusing is the only robust remediation.
 *
 * <p>
 * The Windows code paths are exercised by overriding the {@code os.name} system
 * property, the same way the Python suite patches {@code platform.system()}.
 * The actual cmd.exe re-parse is Windows-only behavior reasoned from the OS
 * spawn semantics rather than executed here.
 */
class SubprocessCLIWindowsRefusalTest {

    private static final String OS_NAME = "os.name";
    private final String originalOsName = System.getProperty(OS_NAME);

    @AfterEach
    void restoreOsName() {
        if (originalOsName == null) {
            System.clearProperty(OS_NAME);
        } else {
            System.setProperty(OS_NAME, originalOsName);
        }
    }

    private static void pretendWindows() {
        System.setProperty(OS_NAME, "Windows 11");
    }

    private static void pretendLinux() {
        System.setProperty(OS_NAME, "Linux");
    }

    @Nested
    class BatchExtensionDetection {

        @ParameterizedTest
        @ValueSource(strings = {
                "C:\\npm\\claude.cmd",
                "C:\\npm\\claude.bat",
                // Case is irrelevant to Win32 extension matching.
                "C:\\npm\\claude.CMD",
                // Windows strips trailing dots and spaces at path resolution.
                "C:\\npm\\claude.cmd ",
                "C:\\npm\\claude.cmd.",
                "C:\\npm\\claude.cmd...   ",
                // An NTFS stream spec still opens its base file.
                "C:\\npm\\claude.cmd:stream",
                // Win32 finds the extension by a last-dot scan over the whole
                // component, stream spec included.
                "C:\\npm\\claude:evil.cmd",
                // Drive-relative paths ride in the same component.
                "C:claude.cmd",
                // PathFindExtension treats a bare ".cmd" as an extension.
                ".cmd",
                // Any component counts: normalization tricks still have to
                // spell the batch component somewhere in the string.
                "C:\\claude.cmd\\..\\claude.exe",
                "C:\\a\\\\claude.bat",
                // Forward slashes are valid separators on Windows.
                "C:/npm/claude.cmd",
        })
        void batchPathsAreDetectedOnWindows(String cliPath) {
            pretendWindows();
            assertThat(SubprocessCLITransport.isWindowsBatchCli(cliPath)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "C:\\Program Files\\claude.exe",
                "C:\\bin\\claude.com",
                "claude",
                "C:\\cmd\\claude.exe",
                "C:\\batch\\claude.exe",
                "/usr/local/bin/claude",
        })
        void nonBatchPathsAreAllowedOnWindows(String cliPath) {
            pretendWindows();
            assertThat(SubprocessCLITransport.isWindowsBatchCli(cliPath)).isFalse();
        }

        @Test
        void batchPathsAreNotRefusedOffWindows() {
            pretendLinux();
            // POSIX has no cmd.exe hop, and ".cmd" is an ordinary filename.
            assertThat(SubprocessCLITransport.isWindowsBatchCli("/opt/claude.cmd")).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"claude.exe", "claude.EXE", "claude.exe.", "claude.com",
                "C:\\bin\\claude.exe"})
        void nativeExecutablesAreRecognized(String cliPath) {
            assertThat(SubprocessCLITransport.isWindowsNativeExe(cliPath)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"claude.cmd", "claude", "C:\\bin\\claude", "claude.exe.cmd"})
        void nonNativeExecutablesAreRejected(String cliPath) {
            assertThat(SubprocessCLITransport.isWindowsNativeExe(cliPath)).isFalse();
        }
    }

    @Nested
    class Refusal {

        @Test
        void batchCliIsRefusedOnWindows() {
            pretendWindows();
            assertThatThrownBy(
                    () -> SubprocessCLITransport.rejectWindowsBatchCli("C:\\npm\\claude.cmd"))
                    .isInstanceOf(CLIConnectionException.class)
                    .hasMessageContaining("Refusing to execute batch script")
                    // The message must point at the alternatives that avoid
                    // cmd.exe entirely.
                    .hasMessageContaining("install.ps1")
                    .hasMessageContaining("cliPath");
        }

        @Test
        void nativeCliIsNotRefusedOnWindows() {
            pretendWindows();
            assertThatCode(
                    () -> SubprocessCLITransport.rejectWindowsBatchCli("C:\\bin\\claude.exe"))
                    .doesNotThrowAnyException();
        }

        @Test
        void batchCliIsNotRefusedOffWindows() {
            pretendLinux();
            assertThatCode(() -> SubprocessCLITransport.rejectWindowsBatchCli("/opt/claude.cmd"))
                    .doesNotThrowAnyException();
        }

        @Test
        void connectRefusesBatchCliBeforeSpawning() {
            pretendWindows();
            // The refusal must land before anything is spawned, including the
            // version probe — so a nonexistent path still fails as a refusal
            // rather than as a spawn or not-found error.
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .cliPath(Path.of("C:\\npm\\claude.cmd"))
                    .build();
            try (SubprocessCLITransport transport = new SubprocessCLITransport(options)) {
                assertThatThrownBy(transport::connect)
                        .isInstanceOf(CLIConnectionException.class)
                        .hasMessageContaining("Refusing to execute batch script");
            }
        }
    }

    /**
     * Discovery order. {@code PATH} is injected and {@code user.home} is
     * redirected at a temp directory, so these run identically on POSIX CI and
     * on Windows.
     */
    @Nested
    class Discovery {

        @TempDir
        Path tmp;

        private final String originalUserHome = System.getProperty("user.home");

        @AfterEach
        void restoreUserHome() {
            System.setProperty("user.home", originalUserHome);
        }

        private Path touchExecutable(Path dir, String name) throws IOException {
            Files.createDirectories(dir);
            Path file = Files.createFile(dir.resolve(name));
            file.toFile().setExecutable(true);
            return file;
        }

        private String pathOf(Path... dirs) {
            String sep = SubprocessCLITransport.isWindows() ? ";" : ":";
            StringBuilder sb = new StringBuilder();
            for (Path dir : dirs) {
                if (sb.length() > 0) {
                    sb.append(sep);
                }
                sb.append(dir);
            }
            return sb.toString();
        }

        @Test
        void nativeExeIsPreferredOverShadowingShimInSameDirectory() throws IOException {
            pretendWindows();
            Path bin = tmp.resolve("bin");
            // npm drops an extensionless bash wrapper next to its shim; without
            // the .exe preference it would shadow the real executable.
            touchExecutable(bin, "claude");
            touchExecutable(bin, "claude.exe");

            assertThat(SubprocessCLITransport.findCli(pathOf(bin)))
                    .endsWith("claude.exe");
        }

        @Test
        void nativeExeInLaterDirectoryBeatsExtensionlessWrapperInEarlierOne()
                throws IOException {
            pretendWindows();
            Path early = tmp.resolve("early");
            Path late = tmp.resolve("late");
            touchExecutable(early, "claude");
            touchExecutable(late, "claude.exe");

            // PATH is walked directory-major, so an extensionless entry in an
            // early directory would otherwise win outright.
            assertThat(SubprocessCLITransport.findCli(pathOf(early, late)))
                    .endsWith("claude.exe");
        }

        @Test
        void npmShimIsReturnedSoConnectCanExplainTheRefusal() throws IOException {
            pretendWindows();
            Path bin = tmp.resolve("bin");
            touchExecutable(bin, "claude.cmd");
            System.setProperty("user.home", tmp.resolve("home").toString());

            // With no native executable anywhere, the shim is returned rather
            // than a not-found error, so connect() raises the batch-script
            // refusal with its remediation.
            String resolved = SubprocessCLITransport.findCli(pathOf(bin));
            assertThat(resolved).endsWith("claude.cmd");
            assertThatThrownBy(() -> SubprocessCLITransport.rejectWindowsBatchCli(resolved))
                    .isInstanceOf(CLIConnectionException.class);
        }

        @Test
        void shimIsPreferredOverExtensionlessWrapper() throws IOException {
            pretendWindows();
            Path bin = tmp.resolve("bin");
            touchExecutable(bin, "claude");
            touchExecutable(bin, "claude.cmd");
            System.setProperty("user.home", tmp.resolve("home").toString());

            // Windows resolves .CMD ahead of an extensionless name via PATHEXT,
            // and the explanatory refusal beats an opaque spawn failure.
            assertThat(SubprocessCLITransport.findCli(pathOf(bin))).endsWith("claude.cmd");
        }

        @Test
        void windowsFallbackFindsNativeExeUnderLocalBin() throws IOException {
            pretendWindows();
            Path home = tmp.resolve("home");
            touchExecutable(home.resolve(".local").resolve("bin"), "claude.exe");
            System.setProperty("user.home", home.toString());

            assertThat(SubprocessCLITransport.findCli(pathOf(tmp.resolve("empty"))))
                    .endsWith("claude.exe");
        }

        @Test
        void windowsFallbackSkipsPosixShapedProbes() throws IOException {
            pretendWindows();
            Path home = tmp.resolve("home");
            // An extensionless artifact from a WSL / git-bash setup must not be
            // picked up: it would preempt the explanatory refusal with an
            // opaque spawn failure.
            touchExecutable(home.resolve(".local").resolve("bin"), "claude");
            touchExecutable(home.resolve(".npm-global").resolve("bin"), "claude");
            System.setProperty("user.home", home.toString());

            assertThatThrownBy(
                    () -> SubprocessCLITransport.findCli(pathOf(tmp.resolve("empty"))))
                    .isInstanceOf(CLINotFoundException.class);
        }

        @Test
        void posixFallbackLocationsStillWork() throws IOException {
            pretendLinux();
            Path home = tmp.resolve("home");
            touchExecutable(home.resolve(".npm-global").resolve("bin"), "claude");
            System.setProperty("user.home", home.toString());

            assertThat(SubprocessCLITransport.findCli(pathOf(tmp.resolve("empty"))))
                    .endsWith("claude");
        }

        @Test
        void notFoundMessageOnWindowsRecommendsNativeExe() {
            pretendWindows();
            System.setProperty("user.home", tmp.resolve("home").toString());

            assertThatThrownBy(() -> SubprocessCLITransport.findCli(pathOf(tmp.resolve("empty"))))
                    .isInstanceOf(CLINotFoundException.class)
                    .hasMessageContaining("install.ps1")
                    .hasMessageContaining("claude.exe")
                    // npm's Windows install is the refused shim, so it must not
                    // be recommended.
                    .hasMessageContaining("refuses to run");
        }

        @Test
        void notFoundMessageOffWindowsIsUnchanged() {
            pretendLinux();
            System.setProperty("user.home", tmp.resolve("home").toString());

            // Java's POSIX message predates this change and must stay as-is;
            // only the Windows branch is new.
            assertThatThrownBy(() -> SubprocessCLITransport.findCli(pathOf(tmp.resolve("empty"))))
                    .isInstanceOf(CLINotFoundException.class)
                    .hasMessageContaining("https://code.claude.com/docs/en/setup")
                    .hasMessageNotContaining("install.ps1");
        }
    }

    @Nested
    class CmdMetacharacterRejection {

        private List<String> buildWith(ClaudeAgentOptions options) {
            try (SubprocessCLITransport transport = new SubprocessCLITransport(options)) {
                return transport.buildCommand();
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"R&D notes", "a|b", "a<b", "a>b", "a^b", "%PATH%", "a!b", "a\"b",
                "line1\nline2", "line1\rline2"})
        void resumeWithCmdMetacharactersIsRejectedOnWindows(String resume) {
            pretendWindows();
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .resume(resume)
                    .cliPath(Path.of("C:\\bin\\claude.exe"))
                    .build();
            assertThatThrownBy(() -> buildWith(options))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("resume")
                    .hasMessageContaining("unsafe to pass on a Windows command line");
        }

        @Test
        void sessionIdWithCmdMetacharactersIsRejectedOnWindows() {
            pretendWindows();
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .sessionId("abc&whoami")
                    .cliPath(Path.of("C:\\bin\\claude.exe"))
                    .build();
            assertThatThrownBy(() -> buildWith(options))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sessionId");
        }

        @Test
        void ordinarySessionTitlesArePermittedOnWindows() {
            pretendWindows();
            // No format is imposed beyond the metacharacter check: resume values
            // may be arbitrary session titles, not only UUIDs.
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .resume("Refactor the parser (part 2)")
                    .cliPath(Path.of("C:\\bin\\claude.exe"))
                    .build();
            assertThat(buildWith(options)).contains("--resume=Refactor the parser (part 2)");
        }

        @Test
        void cmdMetacharactersArePermittedOffWindows() {
            pretendLinux();
            // POSIX behavior is unchanged: there is no cmd.exe to protect from.
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .resume("R&D notes")
                    .cliPath(Path.of("/usr/bin/claude"))
                    .build();
            assertThat(buildWith(options)).contains("--resume=R&D notes");
        }
    }

    @Nested
    class ExtraArgsValueBinding {

        private List<String> buildWith(Map<String, String> extraArgs) {
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .extraArgs(extraArgs)
                    .cliPath(Path.of("/usr/bin/claude"))
                    .build();
            try (SubprocessCLITransport transport = new SubprocessCLITransport(options)) {
                return transport.buildCommand();
            }
        }

        @Test
        void dashLeadingValueUsesEqualsForm() {
            pretendLinux();
            // In the two-token form a dash-leading value detaches from its flag
            // and parses as a separate CLI flag — the same injection the
            // --resume equals form closes.
            List<String> cmd = buildWith(Map.of("some-flag", "--evil"));

            assertThat(cmd).contains("--some-flag=--evil");
            assertThat(cmd).doesNotContain("--evil");
            assertThat(cmd).doesNotContain("--some-flag");
        }

        @Test
        void ordinaryValueUsesTwoTokenForm() {
            pretendLinux();
            List<String> cmd = buildWith(Map.of("some-flag", "value"));

            assertThat(cmd).containsSequence("--some-flag", "value");
        }

        @Test
        void blankValueEmitsBareFlag() {
            pretendLinux();
            List<String> cmd = buildWith(Map.of("verbose-thing", ""));

            assertThat(cmd).contains("--verbose-thing");
            assertThat(cmd).doesNotContain("--verbose-thing=");
        }
    }
}
