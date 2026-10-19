package in.vidyalai.claude.sdk.types.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Sandbox violations to leave unreported.
 *
 * <p>
 * Maps a command pattern to a list of strings. A violation is left unreported
 * when the command's text contains the pattern and the violation's text
 * contains one of the strings. The pattern {@value #ALL_COMMANDS} matches every
 * command. Both checks are plain substring matches, not globs.
 *
 * <p>
 * Example: {@code SandboxIgnoreViolations.forAllCommands(List.of("/etc/hosts"))}
 * serializes as {@code {"*": ["/etc/hosts"]}} and leaves violations that
 * mention {@code /etc/hosts} unreported for every command.
 *
 * <p>
 * This only affects which violations are reported, not what the sandbox
 * blocks.
 *
 * <p>
 * Serializes as the bare map, matching the CLI's {@code ignoreViolations}
 * settings schema.
 *
 * @param patterns command pattern to violation-text substrings
 */
public record SandboxIgnoreViolations(Map<String, List<String>> patterns) {

    /** The command pattern that matches every command. */
    public static final String ALL_COMMANDS = "*";

    /**
     * Canonical constructor; copies {@code patterns} into an unmodifiable,
     * insertion-ordered map.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public SandboxIgnoreViolations {
        Objects.requireNonNull(patterns, "patterns");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        patterns.forEach((pattern, substrings) -> copy.put(
                Objects.requireNonNull(pattern, "pattern"),
                List.copyOf(Objects.requireNonNull(substrings, "substrings"))));
        patterns = Collections.unmodifiableMap(copy);
    }

    /**
     * Pre-0.2.4 constructor, from the old {@code file}/{@code network} shape.
     *
     * <p>
     * That shape never matched the CLI's schema: it was sent as
     * {@code {"file": [...], "network": [...]}}, so the CLI read
     * {@code "file"} and {@code "network"} as command patterns. This
     * constructor now does what its parameters described, leaving violations
     * that mention any of the given paths or hosts unreported for every
     * command ({@value #ALL_COMMANDS}).
     *
     * @param file    substrings, typically file paths, to leave unreported
     * @param network substrings, typically hosts, to leave unreported
     * @deprecated use {@link #forAllCommands(List)} or {@link #of(Map)}
     */
    @Deprecated(since = "0.2.4", forRemoval = true)
    public SandboxIgnoreViolations(@Nullable List<String> file, @Nullable List<String> network) {
        this(Map.of(ALL_COMMANDS, concat(file, network)));
    }

    /**
     * Creates ignore rules from a command-pattern map.
     *
     * @param patterns command pattern ({@value #ALL_COMMANDS} for every
     *                 command) to violation-text substrings
     * @return the ignore rules
     */
    public static SandboxIgnoreViolations of(Map<String, List<String>> patterns) {
        return new SandboxIgnoreViolations(patterns);
    }

    /**
     * Creates ignore rules that apply to every command.
     *
     * @param substrings violation-text substrings to leave unreported
     * @return the ignore rules, as {@code {"*": substrings}}
     */
    public static SandboxIgnoreViolations forAllCommands(List<String> substrings) {
        return new SandboxIgnoreViolations(Map.of(ALL_COMMANDS, substrings));
    }

    /**
     * The command-pattern map, which is also this type's JSON form.
     *
     * @return command pattern to violation-text substrings
     */
    @JsonValue
    @Override
    public Map<String, List<String>> patterns() {
        return patterns;
    }

    private static List<String> concat(@Nullable List<String> a, @Nullable List<String> b) {
        List<String> all = new ArrayList<>();
        if (a != null) {
            all.addAll(a);
        }
        if (b != null) {
            all.addAll(b);
        }
        return all;
    }

}
