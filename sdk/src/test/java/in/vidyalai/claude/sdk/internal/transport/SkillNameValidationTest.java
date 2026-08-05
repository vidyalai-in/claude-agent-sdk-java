package in.vidyalai.claude.sdk.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;

/**
 * Tests for skill-name validation in {@code ClaudeAgentOptions.skills}
 * (Python SDK #1145).
 *
 * <p>
 * Skill names are formatted into the {@code --allowedTools} value, which the
 * CLI splits into permission rules on commas and spaces outside parentheses.
 * That tokenizer honors no escape sequences, so a name carrying a delimiter
 * could close its own {@code Skill(...)} rule and append others — the
 * {@code "x),Bash(*"} case below would otherwise emit
 * {@code Skill(x),Bash(*)}. Names that tokenize cleanly but can never match the
 * listed skill are rejected too, so a dead rule fails loudly instead of
 * silently granting nothing.
 *
 * <p>
 * Validation runs in {@code applySkillsDefaults()} — reached from
 * {@code buildCommand()} — so every rejection surfaces at connect time, before
 * anything is spawned. Names carrying invisible characters are built from code
 * points rather than literals, so this source file stays readable.
 */
class SkillNameValidationTest {

    /**
     * Builds the CLI command with {@code skills} set to the given list.
     * {@code cliPath} is set so no PATH discovery runs.
     */
    private static List<String> buildCommandWithSkills(List<String> skills) {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .skills(skills)
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        try {
            return transport.buildCommand();
        } finally {
            transport.close();
        }
    }

    /** Wraps a raw list so non-String entries can reach the validator. */
    @SuppressWarnings("unchecked")
    private static List<String> rawSkills(Object... entries) {
        return (List<String>) (List<?>) Arrays.asList(entries);
    }

    private static String allowedToolsValue(List<String> cmd) {
        int idx = cmd.indexOf("--allowedTools");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        return cmd.get(idx + 1);
    }

    @SuppressWarnings("null")
    private static void assertRejected(String name, String expectedMessage) {
        assertThatThrownBy(() -> buildCommandWithSkills(List.of(name)))
                .as("skill name %s", quoteCodePoints(name))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessage);
    }

    /** Renders a name with non-printable characters escaped, for failure messages. */
    private static String quoteCodePoints(String name) {
        StringBuilder sb = new StringBuilder("\"");
        name.codePoints().forEach(cp -> {
            if ((cp >= 0x20) && (cp < 0x7F)) {
                sb.appendCodePoint(cp);
            } else {
                sb.append(String.format("\\u%04X", cp));
            }
        });
        return sb.append('"').toString();
    }

    // -------------------------------------------------------------------------
    // Rule-syntax delimiters — the injection vector
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "x),Bash(*",
            "safe),Bash,Skill(dummy",
            "name,with,commas",
            "unbalanced(",
            "unbalanced)",
            "()"
    })
    void testRejectsRuleSyntaxDelimiters(String hostileName) {
        assertRejected(hostileName, "Invalid skill name");
    }

    @Test
    void testRejectsControlCharacters() {
        // C0 range plus DEL.
        for (int cp : new int[] {'\t', '\n', '\r', 0x00, 0x01, 0x1F, 0x7F}) {
            assertRejected("mid" + (char) cp + "name", "Invalid skill name");
        }
    }

    @Test
    void testRejectsC1ControlCharacters() {
        // U+0085 NEL and U+009B CSI among them; the CLI never discovers a
        // skill directory whose name contains one.
        for (int cp : new int[] {0x80, 0x85, 0x9B, 0x9F}) {
            assertRejected("mid" + (char) cp + "name", "Invalid skill name");
        }
    }

    @Test
    void testRejectsByteOrderMarks() {
        // The CLI trims U+FEFF as whitespace while Character.isWhitespace does
        // not, so it is caught by the invalid-character check rather than the
        // surrounding-whitespace one.
        String bom = String.valueOf((char) 0xFEFF);
        for (String name : List.of(bom + "pdf", "pdf" + bom, "p" + bom + "df")) {
            assertRejected(name, "Invalid skill name");
        }
    }

    // -------------------------------------------------------------------------
    // Shapes that tokenize cleanly but can never match
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "  \t "})
    void testRejectsEmptyNames(String emptyName) {
        assertRejected(emptyName, "non-empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {" pdf", "pdf ", "\tpdf", " pdf ", "\npdf"})
    void testRejectsSurroundingWhitespace(String hostileName) {
        assertRejected(hostileName, "whitespace");
    }

    @Test
    void testRejectsSurroundingNonBreakingSpace() {
        // String.strip() follows Character.isWhitespace, which leaves U+00A0
        // in place; the union with Character.isSpaceChar is what makes these
        // match Python's str.strip().
        for (int cp : new int[] {0x00A0, 0x2007, 0x202F, 0x2003}) {
            assertRejected((char) cp + "pdf", "whitespace");
            assertRejected("pdf" + (char) cp, "whitespace");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/pdf", "/myplugin:pdf"})
    void testRejectsLeadingSlash(String hostileName) {
        assertRejected(hostileName, "may not start with");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pdf:*", "my skill *", ":*"})
    void testRejectsWildcardSuffixNames(String wildcardName) {
        assertRejected(wildcardName, "wildcard-suffix");
    }

    @Test
    void testRejectsBareWildcard() {
        assertRejected("*", "use skillsAll()");
    }

    @ParameterizedTest
    @ValueSource(strings = {"name\\\\", "name\\\\\\", "mid\\\\dle"})
    void testRejectsConsecutiveBackslashes(String hostileName) {
        assertRejected(hostileName, "consecutive backslashes");
    }

    @Test
    void testRejectsUnpairedTrailingBackslash() {
        assertRejected("name\\", "unpaired backslash");
    }

    // -------------------------------------------------------------------------
    // Surrogates — a deliberate divergence from the Python SDK
    // -------------------------------------------------------------------------
    //
    // Python rejects every surrogate code point, because a Python str holds
    // code points and any surrogate in one is unpaired by construction. Java
    // strings are UTF-16, where an astral character is legitimately a
    // high/low pair, so only lone surrogates are rejected here.

    @Test
    void testRejectsLoneSurrogates() {
        assertRejected("lone" + (char) 0xD800 + "surrogate", "surrogate");
        assertRejected((char) 0xDC00 + "leading", "surrogate");
        assertRejected("trailing" + (char) 0xD800, "surrogate");
        // A low surrogate before a high one is not a pair.
        assertRejected("mid" + (char) 0xDC00 + (char) 0xD800 + "name", "surrogate");
    }

    @Test
    void testAcceptsAstralCharactersAsWellFormedPairs() {
        // U+1D564 MATHEMATICAL DOUBLE-STRUCK SMALL S — one code point, two
        // Java chars. Python's blanket surrogate rule would reject this.
        String name = new String(Character.toChars(0x1D564)) + "kill";
        assertThat(allowedToolsValue(buildCommandWithSkills(List.of(name))))
                .isEqualTo("Skill(" + name + ")");
    }

    // -------------------------------------------------------------------------
    // Value-shape rejections
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("null")
    void testRejectsNonStringNames() {
        assertThatThrownBy(() -> buildCommandWithSkills(rawSkills(42)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be strings");
    }

    @Test
    @SuppressWarnings("null")
    void testRejectsNullNames() {
        assertThatThrownBy(() -> buildCommandWithSkills(rawSkills((Object) null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be strings");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pdf", "pdf-tools", "ALL"})
    @SuppressWarnings("null")
    void testRejectsABareString(String skills) {
        // Unreachable through the builder — skills(List) and skillsAll() are
        // the only entry points — so the guard is exercised directly.
        assertThatThrownBy(() -> SubprocessCLITransport.rejectNonListSkills(skills))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a list of skill names")
                // Python's "Did you mean [...]?" hint, rendered as the Java
                // call the caller should have written.
                .hasMessageContaining("Did you mean List.of(\"" + skills + "\")?");
    }

    @Test
    @SuppressWarnings("null")
    void testRejectsNonListCollections() {
        assertThatThrownBy(() -> SubprocessCLITransport.rejectNonListSkills(Set.of("pdf")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a list of skill names");
        assertThatThrownBy(() -> SubprocessCLITransport
                .rejectNonListSkills(new String[] {"pdf"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a list of skill names");
    }

    @Test
    void testAcceptsAllSentinelAndLists() {
        assertThatCode(() -> SubprocessCLITransport.rejectNonListSkills("all"))
                .doesNotThrowAnyException();
        assertThatCode(() -> SubprocessCLITransport.rejectNonListSkills(List.of("pdf")))
                .doesNotThrowAnyException();
        assertThatCode(() -> SubprocessCLITransport.rejectNonListSkills(List.of()))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Ordinary names still build the same argv
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "pdf-tools",
            "my_skill.v2",
            "myplugin:pdf",
            "skill with spaces",
            "dir\\sub",
            "日本語スキル"
    })
    void testAcceptsOrdinaryNames(String benignName) {
        assertThat(allowedToolsValue(buildCommandWithSkills(List.of(benignName))))
                .isEqualTo("Skill(" + benignName + ")");
    }

    @Test
    void testAcceptsMultipleNamesInOrder() {
        List<String> cmd = buildCommandWithSkills(List.of("commit", "review"));
        assertThat(allowedToolsValue(cmd)).isEqualTo("Skill(commit),Skill(review)");
    }

    @Test
    void testEmptyListRemainsAValidNoOp() {
        // An empty list means "suppress every skill", not "nothing to check".
        assertThatCode(() -> buildCommandWithSkills(List.of())).doesNotThrowAnyException();
    }

    @Test
    void testSkillsAllIsNotNameChecked() {
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .skillsAll()
                .cliPath(Path.of("/usr/bin/claude"))
                .build();
        SubprocessCLITransport transport = new SubprocessCLITransport(options);
        try {
            assertThat(allowedToolsValue(transport.buildCommand())).isEqualTo("Skill");
        } finally {
            transport.close();
        }
    }

    @Test
    @SuppressWarnings("null")
    void testRejectionHappensBeforeAnyRuleIsEmitted() {
        // A hostile name alongside benign ones must not produce a partially
        // built --allowedTools value; the whole command fails.
        assertThatThrownBy(() -> buildCommandWithSkills(List.of("pdf", "x),Bash(*", "review")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid skill name");
    }
}
