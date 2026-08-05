package examples;

import java.nio.file.Path;
import java.util.List;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.exceptions.CLIConnectionException;
import in.vidyalai.claude.sdk.types.message.AssistantMessage;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.TextBlock;

/**
 * Running against an npm-installed {@code claude.cmd} on Windows.
 *
 * <p>
 * The SDK refuses a {@code .bat}/{@code .cmd} CLI by default. Windows has no
 * shebang mechanism, so the OS runs a batch script by rewriting the spawn into
 * {@code cmd.exe /c}, and cmd.exe re-parses the whole command line —
 * metacharacters inside an argument value can execute injected commands before
 * the CLI starts (CVE-2024-27980, "BatBadBut").
 *
 * <p>
 * <b>Prefer a native executable.</b> Install with
 * {@code irm https://claude.ai/install.ps1 | iex}, or point
 * {@code cliPath} at a {@code claude.exe}. Everything below is for the case
 * where that migration is genuinely blocked — centrally managed software
 * distribution, for instance.
 *
 * <p>
 * The opt-in is not a plain bypass. It requires the JVM to be started with:
 *
 * <pre>{@code
 * java -Djdk.lang.Process.allowAmbiguousCommands=false -jar your-app.jar
 * }</pre>
 *
 * Without that switch {@code connect()} throws, because the JDK's default mode
 * quotes nothing but whitespace when spawning a batch script. With it, the JDK
 * quotes {@code " < > & | ^} and rejects arguments containing a quote, and the
 * SDK additionally rejects {@code %} and {@code !} — the expansions the JDK's
 * rules omit — across every CLI argument.
 *
 * <p>
 * Run it:
 *
 * <pre>{@code
 * mvn exec:java -Dexec.mainClass="examples.WindowsBatchCliExample" \
 *     -Dexec.args="C:\\Users\\Administrator\\AppData\\Roaming\\npm\\claude.cmd" \
 *     -pl examples
 * }</pre>
 */
public class WindowsBatchCliExample {

    public static void main(String[] args) {
        System.out.println("=== Windows Batch CLI Opt-In Example ===\n");

        if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
            System.out.println("This example is Windows-only. On POSIX there is no cmd.exe");
            System.out.println("hop, so a '.cmd' file is just an ordinary filename and the");
            System.out.println("refusal never applies.");
            return;
        }

        Path cliPath = Path.of(args.length > 0
                ? args[0]
                : "C:\\Users\\Administrator\\AppData\\Roaming\\npm\\claude.cmd");

        showDefaultRefusal(cliPath);
        showOptIn(cliPath);
        showArgumentSweep(cliPath);
    }

    /** Default posture: the batch CLI is refused before anything is spawned. */
    static void showDefaultRefusal(Path cliPath) {
        System.out.println("--- Default: refused ---");

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .cliPath(cliPath)
                .maxTurns(1)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect("hello");
            System.out.println("Connected — cliPath was not a batch script.");
        } catch (CLIConnectionException e) {
            System.out.println("Refused, as expected:");
            System.out.println("  " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Unexpected failure: " + e);
        }
        System.out.println();
    }

    /**
     * Explicit opt-in. Fails with an actionable message if the JVM was started
     * without {@code -Djdk.lang.Process.allowAmbiguousCommands=false}.
     */
    static void showOptIn(Path cliPath) {
        System.out.println("--- With allowUnsafeWindowsBatchCli(true) ---");

        String ambiguous = System.getProperty("jdk.lang.Process.allowAmbiguousCommands");
        System.out.println("jdk.lang.Process.allowAmbiguousCommands = "
                + ((ambiguous == null) ? "<unset, defaults to true>" : ambiguous));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .cliPath(cliPath)
                .allowUnsafeWindowsBatchCli(true)
                .maxTurns(1)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect("Say hello in five words.");
            for (Message message : client.receiveResponse()) {
                if (message instanceof AssistantMessage assistant) {
                    for (var block : assistant.content()) {
                        if (block instanceof TextBlock text) {
                            System.out.println("Claude: " + text.text());
                        }
                    }
                }
            }
            System.out.println("(A WARNING was logged naming the accepted risk.)");
        } catch (CLIConnectionException e) {
            System.out.println("Connect refused:");
            System.out.println("  " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Unexpected failure: " + e);
        }
        System.out.println();
    }

    /**
     * The argv sweep that accompanies the opt-in: no CLI argument may carry a
     * cmd.exe metacharacter, because cmd.exe would re-parse it.
     */
    static void showArgumentSweep(Path cliPath) {
        System.out.println("--- Argument sweep ---");

        // %VAR% is the interesting case: the JDK's batch-mode quoting does not
        // cover '%', and quoting does not stop cmd.exe expanding it.
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .cliPath(cliPath)
                .allowUnsafeWindowsBatchCli(true)
                .resume("%USERPROFILE%")
                .maxTurns(1)
                .build();

        try (var client = ClaudeSDK.createClient(options)) {
            client.connect("hello");
            System.out.println("Connected.");
        } catch (IllegalArgumentException e) {
            System.out.println("Argument rejected, as expected:");
            System.out.println("  " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Failed: " + e.getMessage());
        }

        System.out.println();
        System.out.println("Ordinary values are unaffected:");
        ClaudeAgentOptions ok = ClaudeAgentOptions.builder()
                .cliPath(cliPath)
                .allowUnsafeWindowsBatchCli(true)
                .allowedTools(List.of("Read"))
                .resume("Refactor the parser (part 2)")
                .maxTurns(1)
                .build();
        System.out.println("  resume=" + ok.resume() + " -> accepted");
    }
}
