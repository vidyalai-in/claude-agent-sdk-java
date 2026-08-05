package in.vidyalai.claude.sdk.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;

/**
 * Tests for {@code allowUnsafeWindowsBatchCli}, the explicit opt-in that admits
 * an npm-installed {@code claude.cmd} on Windows (issue #2).
 *
 * <p>
 * The opt-in is deliberately not a plain bypass. Enabling it still requires
 * {@code jdk.lang.Process.allowAmbiguousCommands=false} — the JVM switch that
 * makes {@code ProcessImpl} apply its {@code VERIFICATION_CMD_BAT} rules to a
 * batch target, quoting {@code " < > & | ^} and rejecting arguments containing
 * a quote. That property defaults to {@code true}, under which the JDK quotes
 * nothing but whitespace, so without the switch the opt-in would hand back the
 * whole vulnerability. On top of it the SDK sweeps the argv for the {@code %}
 * and {@code !} expansions the JDK's rules omit.
 *
 * <p>
 * Windows code paths are exercised by overriding {@code os.name}, the same way
 * the sibling refusal suite does. Both that property and the JDK switch are
 * restored after every test.
 */
class WindowsBatchCliOptInTest {

    private static final String OS_NAME = "os.name";
    private static final String AMBIGUOUS = "jdk.lang.Process.allowAmbiguousCommands";
    private static final String BATCH_CLI = "C:\\Users\\Administrator\\AppData\\Roaming\\npm\\claude.cmd";

    private final String originalOsName = System.getProperty(OS_NAME);
    private final String originalAmbiguous = System.getProperty(AMBIGUOUS);

    @AfterEach
    void restoreProperties() {
        restore(OS_NAME, originalOsName);
        restore(AMBIGUOUS, originalAmbiguous);
    }

    private static void restore(String key, @org.jspecify.annotations.Nullable String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static void pretendWindows() {
        System.setProperty(OS_NAME, "Windows 11");
    }

    private static void pretendLinux() {
        System.setProperty(OS_NAME, "Linux");
    }

    /** Puts the JVM in the mode the opt-in requires. */
    private static void hardenedJvm() {
        System.setProperty(AMBIGUOUS, "false");
    }

    private static SubprocessCLITransport transportWith(boolean optIn, String cliPath) {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .allowUnsafeWindowsBatchCli(optIn)
                .cliPath(Path.of(cliPath))
                .build();
        return new SubprocessCLITransport(options);
    }

    // -------------------------------------------------------------------------
    // Default posture is unchanged
    // -------------------------------------------------------------------------

    @Nested
    class DefaultsUnchanged {

        @Test
        void optionDefaultsToFalse() {
            assertThat(ClaudeAgentOptions.builder().build().allowUnsafeWindowsBatchCli())
                    .isFalse();
        }

        @SuppressWarnings("null")
        @Test
        void batchCliIsStillRefusedWithoutOptIn() {
            pretendWindows();
            hardenedJvm(); // even here: the JVM switch alone grants nothing
            SubprocessCLITransport transport = transportWith(false, BATCH_CLI);
            try {
                assertThatThrownBy(() -> transport.enforceWindowsBatchCliPolicy(BATCH_CLI))
                        .isInstanceOf(CLIConnectionException.class)
                        .hasMessageContaining("Refusing to execute batch script");
            } finally {
                transport.close();
            }
        }

        @SuppressWarnings("null")
        @Test
        void refusalMessagePointsAtTheOptIn() {
            pretendWindows();
            // A user who hits the wall should learn the escape hatch exists,
            // and that it carries a JVM-flag requirement.
            assertThatThrownBy(() -> SubprocessCLITransport.rejectWindowsBatchCli(BATCH_CLI))
                    .isInstanceOf(CLIConnectionException.class)
                    .hasMessageContaining("allowUnsafeWindowsBatchCli(true)")
                    .hasMessageContaining("allowAmbiguousCommands=false")
                    .hasMessageContaining("install.ps1");
        }

        @Test
        void nativeExecutableIsUnaffectedByTheOptIn() {
            pretendWindows();
            SubprocessCLITransport transport = transportWith(true, "C:\\bin\\claude.exe");
            try {
                assertThatCode(
                        () -> transport.enforceWindowsBatchCliPolicy("C:\\bin\\claude.exe"))
                        .doesNotThrowAnyException();
            } finally {
                transport.close();
            }
        }

        @Test
        void posixIsUnaffected() {
            pretendLinux();
            // ".cmd" is an ordinary filename on POSIX — no cmd.exe hop exists,
            // so neither the refusal nor the opt-in's conditions apply.
            SubprocessCLITransport transport = transportWith(false, "/opt/claude.cmd");
            try {
                assertThatCode(() -> transport.enforceWindowsBatchCliPolicy("/opt/claude.cmd"))
                        .doesNotThrowAnyException();
            } finally {
                transport.close();
            }
        }
    }

    // -------------------------------------------------------------------------
    // The opt-in requires the hardened JVM mode
    // -------------------------------------------------------------------------

    @Nested
    class RequiresHardenedJvm {

        @SuppressWarnings("null")
        @Test
        void optInWithoutThePropertyIsRefused() {
            pretendWindows();
            System.clearProperty(AMBIGUOUS);
            SubprocessCLITransport transport = transportWith(true, BATCH_CLI);
            try {
                assertThatThrownBy(() -> transport.enforceWindowsBatchCliPolicy(BATCH_CLI))
                        .isInstanceOf(CLIConnectionException.class)
                        .hasMessageContaining("allowUnsafeWindowsBatchCli(true) was set")
                        .hasMessageContaining("unset (defaults to true)")
                        .hasMessageContaining("-Djdk.lang.Process.allowAmbiguousCommands=false");
            } finally {
                transport.close();
            }
        }

        @SuppressWarnings("null")
        @ParameterizedTest
        @ValueSource(strings = {"true", "TRUE", "yes", "0", ""})
        void optInWithANonFalsePropertyIsRefused(String value) {
            pretendWindows();
            System.setProperty(AMBIGUOUS, value);
            SubprocessCLITransport transport = transportWith(true, BATCH_CLI);
            try {
                // The JDK reads this as !"false".equalsIgnoreCase(value), so
                // anything that is not literally "false" leaves it in legacy
                // mode. The SDK matches that reading exactly rather than
                // inventing its own truthiness.
                assertThatThrownBy(() -> transport.enforceWindowsBatchCliPolicy(BATCH_CLI))
                        .isInstanceOf(CLIConnectionException.class)
                        .hasMessageContaining("is '" + value + "'");
            } finally {
                transport.close();
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"false", "FALSE", "False"})
        void optInWithThePropertyIsAdmitted(String value) {
            pretendWindows();
            System.setProperty(AMBIGUOUS, value);
            SubprocessCLITransport transport = transportWith(true, BATCH_CLI);
            try {
                assertThatCode(() -> transport.enforceWindowsBatchCliPolicy(BATCH_CLI))
                        .doesNotThrowAnyException();
            } finally {
                transport.close();
            }
        }

        @SuppressWarnings("null")
        @Test
        void admittingTheSpawnLogsAWarningOnceOnly() {
            pretendWindows();
            hardenedJvm();
            SubprocessCLITransport transport = transportWith(true, BATCH_CLI);
            Logger logger = Logger.getLogger(SubprocessCLITransport.class.getName());
            List<LogRecord> records = new ArrayList<>();
            Handler capture = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    records.add(record);
                }

                @Override
                public void flush() {
                    // Nothing buffered.
                }

                @Override
                public void close() {
                    // Nothing to release.
                }
            };
            logger.addHandler(capture);
            try {
                transport.enforceWindowsBatchCliPolicy(BATCH_CLI);
                transport.enforceWindowsBatchCliPolicy(BATCH_CLI);
                transport.enforceWindowsBatchCliPolicy(BATCH_CLI);

                List<LogRecord> warnings = records.stream()
                        .filter(r -> r.getLevel() == Level.WARNING)
                        .toList();
                assertThat(warnings).hasSize(1);
                assertThat(warnings.get(0).getMessage())
                        .contains("allowUnsafeWindowsBatchCli(true)")
                        .contains("%VAR%")
                        .contains(BATCH_CLI);
            } finally {
                logger.removeHandler(capture);
                transport.close();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Argv sweep: the half the JDK does not cover
    // -------------------------------------------------------------------------

    @Nested
    class ArgvMetacharacterSweep {

        private List<String> argv(String value) {
            return List.of(BATCH_CLI, "--output-format", "stream-json", "--resume", value);
        }

        @SuppressWarnings("null")
        @ParameterizedTest
        @ValueSource(strings = {
                // % and ! are the ones the JDK's VERIFICATION_CMD_BAT set
                // omits; quoting does not stop cmd.exe expanding them.
                "%FOO%",
                "pre%PATH%post",
                "!DELAYED!",
                // These the JDK would quote, but rejecting is cheaper than
                // trusting a second layer to hold.
                "a&calc",
                "a|calc",
                "a>out",
                "a<in",
                "a^b",
                "quote\"here",
        })
        void metacharactersInAnArgumentAreRejected(String hostile) {
            pretendWindows();
            hardenedJvm();
            SubprocessCLITransport transport = transportWith(true, BATCH_CLI);
            try {
                assertThatThrownBy(
                        () -> transport.rejectCmdMetacharactersInArgv(BATCH_CLI, argv(hostile)))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("cmd.exe metacharacters")
                        // The offending option is named, not just an index.
                        .hasMessageContaining("--resume");
            } finally {
                transport.close();
            }
        }

        @Test
        void newlinesInAnArgumentAreRejected() {
            pretendWindows();
            hardenedJvm();
            SubprocessCLITransport transport = transportWith(true, BATCH_CLI);
            try {
                assertThatThrownBy(() -> transport.rejectCmdMetacharactersInArgv(
                        BATCH_CLI, argv("line1\nline2")))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> transport.rejectCmdMetacharactersInArgv(
                        BATCH_CLI, argv("line1\rline2")))
                        .isInstanceOf(IllegalArgumentException.class);
            } finally {
                transport.close();
            }
        }

        @Test
        void ordinaryArgumentsPassThrough() {
            pretendWindows();
            hardenedJvm();
            SubprocessCLITransport transport = transportWith(true, BATCH_CLI);
            try {
                assertThatCode(() -> transport.rejectCmdMetacharactersInArgv(
                        BATCH_CLI, argv("Refactor the parser (part 2)")))
                        .doesNotThrowAnyException();
            } finally {
                transport.close();
            }
        }

        @Test
        void theExecutablePathItselfIsNotSwept() {
            pretendWindows();
            hardenedJvm();
            // A batch path may legitimately contain characters the sweep
            // rejects in argument values; it is not caller-supplied argument
            // data, and isWindowsBatchCli has already classified it.
            String oddPath = "C:\\npm (x86)\\claude.cmd";
            SubprocessCLITransport transport = transportWith(true, oddPath);
            try {
                assertThatCode(() -> transport.rejectCmdMetacharactersInArgv(
                        oddPath, List.of(oddPath, "--verbose")))
                        .doesNotThrowAnyException();
            } finally {
                transport.close();
            }
        }

        @Test
        void sweepDoesNotRunForANativeExecutable() {
            pretendWindows();
            hardenedJvm();
            // No cmd.exe hop, so metacharacters are inert — argument quoting is
            // correct for native executables.
            String exe = "C:\\bin\\claude.exe";
            SubprocessCLITransport transport = transportWith(true, exe);
            try {
                assertThatCode(() -> transport.rejectCmdMetacharactersInArgv(
                        exe, List.of(exe, "--resume", "R&D notes")))
                        .doesNotThrowAnyException();
            } finally {
                transport.close();
            }
        }

        @Test
        void sweepDoesNotRunWithoutTheOptIn() {
            pretendWindows();
            // Without the opt-in the spawn never happens at all — the refusal
            // in enforceWindowsBatchCliPolicy fires first — so the sweep has
            // nothing to add.
            SubprocessCLITransport transport = transportWith(false, BATCH_CLI);
            try {
                assertThatCode(() -> transport.rejectCmdMetacharactersInArgv(
                        BATCH_CLI, argv("a&calc")))
                        .doesNotThrowAnyException();
            } finally {
                transport.close();
            }
        }

        @Test
        void sweepDoesNotRunOnPosix() {
            pretendLinux();
            SubprocessCLITransport transport = transportWith(true, "/opt/claude.cmd");
            try {
                assertThatCode(() -> transport.rejectCmdMetacharactersInArgv(
                        "/opt/claude.cmd", List.of("/opt/claude.cmd", "--resume", "R&D notes")))
                        .doesNotThrowAnyException();
            } finally {
                transport.close();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Options plumbing
    // -------------------------------------------------------------------------

    @Nested
    class OptionsPlumbing {

        @Test
        void optInSurvivesToBuilder() {
            ClaudeAgentOptions original = ClaudeAgentOptions.builder()
                    .allowUnsafeWindowsBatchCli(true)
                    .build();
            assertThat(original.toBuilder().build().allowUnsafeWindowsBatchCli()).isTrue();
        }

        @Test
        void optInCanBeTurnedBackOff() {
            ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                    .allowUnsafeWindowsBatchCli(true)
                    .build()
                    .toBuilder()
                    .allowUnsafeWindowsBatchCli(false)
                    .build();
            assertThat(options.allowUnsafeWindowsBatchCli()).isFalse();
        }
    }
}
