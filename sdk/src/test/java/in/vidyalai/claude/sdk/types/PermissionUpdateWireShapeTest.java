package in.vidyalai.claude.sdk.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import in.vidyalai.claude.sdk.types.permission.PermissionBehavior;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;
import in.vidyalai.claude.sdk.types.permission.PermissionRuleValue;
import in.vidyalai.claude.sdk.types.permission.PermissionUpdate;
import in.vidyalai.claude.sdk.types.permission.PermissionUpdateDestination;

/**
 * {@link PermissionUpdate#toMap()} sends updates in the shape Claude Code
 * accepts (Python SDK #1502, {@code TestPermissionUpdate} in
 * {@code tests/test_types.py}). Claude Code ignores every update in a response
 * when one of them does not fit its schema.
 */
@SuppressWarnings("null")
class PermissionUpdateWireShapeTest {

    private static final List<PermissionRuleValue> READ = List.of(new PermissionRuleValue("Read"));

    private static Map<String, Object> wire(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    void wholeToolRuleHasNoRuleContentKey() {
        PermissionUpdate update = PermissionUpdate.addRules(
                List.of(new PermissionRuleValue("WebSearch")),
                PermissionBehavior.ALLOW,
                PermissionUpdateDestination.LOCAL_SETTINGS);
        assertThat(update.toMap()).isEqualTo(Map.of(
                "type", "addRules",
                "destination", "localSettings",
                "behavior", "allow",
                "rules", List.of(Map.of("toolName", "WebSearch"))));
    }

    @Test
    void emptyRuleContentIsSentAsIs() {
        PermissionUpdate update = PermissionUpdate.addRules(
                List.of(new PermissionRuleValue("Bash", "")),
                PermissionBehavior.DENY,
                PermissionUpdateDestination.SESSION);
        assertThat(update.toMap().get("rules"))
                .isEqualTo(List.of(Map.of("toolName", "Bash", "ruleContent", "")));
    }

    @Test
    void fromMapReadsAMissingRuleContentAsNullAndOmitsItAgain() {
        Map<String, Object> wire = wire(
                "type", "addRules",
                "destination", "session",
                "behavior", "allow",
                "rules", List.of(Map.of("toolName", "Bash", "ruleContent", "npm *"), Map.of("toolName", "Read")));
        PermissionUpdate update = PermissionUpdate.fromMap(wire);
        assertThat(update.rules()).containsExactly(
                new PermissionRuleValue("Bash", "npm *"),
                new PermissionRuleValue("Read", null));
        assertThat(update.toMap()).isEqualTo(wire);
    }

    static Stream<PermissionUpdate> updatesWithoutDestination() {
        return Stream.of(
                PermissionUpdate.addRules(READ, PermissionBehavior.ALLOW, null),
                PermissionUpdate.replaceRules(READ, PermissionBehavior.DENY, null),
                PermissionUpdate.removeRules(READ, PermissionBehavior.ALLOW, null),
                PermissionUpdate.setMode(PermissionMode.ACCEPT_EDITS, null),
                PermissionUpdate.addDirectories(List.of("/tmp/a"), null),
                PermissionUpdate.removeDirectories(List.of("/tmp/a"), null));
    }

    @ParameterizedTest
    @MethodSource("updatesWithoutDestination")
    void missingDestinationIsSentAsSession(PermissionUpdate update) {
        assertThat(update.toMap()).containsEntry("destination", "session");
    }

    @SuppressWarnings("removal")
    static Stream<Arguments> updatesMissingARequiredField() {
        return Stream.of(
                Arguments.of(PermissionUpdate.addRules(null, PermissionBehavior.ALLOW, null), "rules"),
                Arguments.of(PermissionUpdate.replaceRules(null, PermissionBehavior.DENY, null), "rules"),
                Arguments.of(PermissionUpdate.removeRules(null, PermissionBehavior.ASK, null), "rules"),
                Arguments.of(PermissionUpdate.addRules(READ, null, null), "behavior"),
                Arguments.of(PermissionUpdate.replaceRules(READ, null, null), "behavior"),
                Arguments.of(PermissionUpdate.removeRules(READ, null, null), "behavior"),
                // The deprecated factory has no behavior parameter at all.
                Arguments.of(PermissionUpdate.removeRules(READ, (PermissionUpdateDestination) null), "behavior"),
                Arguments.of(PermissionUpdate.setMode(null, null), "mode"),
                Arguments.of(PermissionUpdate.addDirectories(null, null), "directories"),
                Arguments.of(PermissionUpdate.removeDirectories(null, null), "directories"),
                // A CLI suggestion that reads with a field unset is kept, but cannot be sent.
                Arguments.of(PermissionUpdate.fromMap(Map.of("type", "setMode", "destination", "session")), "mode"),
                Arguments.of(PermissionUpdate.fromMap(Map.of("type", "addRules", "behavior", "allow")), "rules"));
    }

    @ParameterizedTest
    @MethodSource("updatesMissingARequiredField")
    void missingRequiredFieldThrows(PermissionUpdate update, String missing) {
        assertThatThrownBy(update::toMap)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no " + missing);
    }

    // Python's test_unknown_type_raises is N/A: PermissionUpdateType is an
    // enum, so an update with an unknown type cannot be built, and fromMap
    // already refuses one.

    static Stream<Map<String, Object>> cliSuggestions() {
        return Stream.of(
                wire("type", "addRules", "destination", "localSettings", "behavior", "allow",
                        "rules", List.of(Map.of("toolName", "WebSearch"))),
                wire("type", "replaceRules", "destination", "projectSettings", "behavior", "deny",
                        "rules", List.of(Map.of("toolName", "Bash", "ruleContent", "rm -rf *"))),
                wire("type", "removeRules", "destination", "userSettings", "behavior", "ask",
                        "rules", List.of(Map.of("toolName", "mcp__server__tool"))),
                wire("type", "setMode", "mode", "plan", "destination", "session"),
                wire("type", "removeDirectories", "directories", List.of("/tmp/a"), "destination", "localSettings"));
    }

    @ParameterizedTest
    @MethodSource("cliSuggestions")
    void cliSuggestionRoundTripsUnchanged(Map<String, Object> wire) {
        // Echoing a suggestion from Claude Code sends back exactly what it sent.
        assertThat(PermissionUpdate.fromMap(wire).toMap()).isEqualTo(wire);
    }

}
