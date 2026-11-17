package in.vidyalai.claude.sdk.types.permission;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Permission update configuration.
 *
 * <p>
 * <b>JSON Naming Convention:</b> This type uses {@code camelCase} for JSON
 * field names in its {@link #toMap()} method because it represents data <b>sent
 * to the CLI</b> in control protocol responses. See
 * {@link in.vidyalai.claude.sdk.types} package documentation for details.
 */
public final class PermissionUpdate {

    private static final String TYPE = "type";
    private static final String DEST = "destination";
    private static final String RULES = "rules";
    private static final String TOOL_NAME = "toolName";
    private static final String RULE_CONTENT = "ruleContent";
    private static final String BEHAVIOR = "behavior";
    private static final String MODE = "mode";
    private static final String DIRS = "directories";

    private final PermissionUpdateType type;
    @Nullable
    private final List<PermissionRuleValue> rules;
    @Nullable
    private final PermissionBehavior behavior;
    @Nullable
    private final PermissionMode mode;
    @Nullable
    private final List<String> directories;
    @Nullable
    private final PermissionUpdateDestination destination;

    private PermissionUpdate(
            PermissionUpdateType type,
            @Nullable List<PermissionRuleValue> rules,
            @Nullable PermissionBehavior behavior,
            @Nullable PermissionMode mode,
            @Nullable List<String> directories,
            @Nullable PermissionUpdateDestination destination) {
        this.type = type;
        this.rules = rules;
        this.behavior = behavior;
        this.mode = mode;
        this.directories = directories;
        this.destination = destination;
    }

    /**
     * Creates a PermissionUpdate from a Map (for Jackson deserialization).
     * This is the inverse of {@link #toMap()}, except that an update built
     * with a {@code null} destination reads back as
     * {@link PermissionUpdateDestination#SESSION}, which is what it was sent as.
     *
     * <p>
     * Absent optional fields (including the {@code mode} of a
     * {@code setMode} update) read as null. A value this SDK cannot represent
     * (an unknown {@code type}, {@code destination}, {@code behavior} or
     * {@code mode}) throws; when the CLI offers such a suggestion on a
     * permission request, the SDK skips that one suggestion and still runs
     * the {@code canUseTool} callback with the others.
     *
     * @param map the map representation from JSON
     * @return a PermissionUpdate instance
     * @throws IllegalArgumentException if a value cannot be represented
     */
    @JsonCreator
    @SuppressWarnings("unchecked")
    public static PermissionUpdate fromMap(Map<String, Object> map) {
        String typeStr = (String) map.get(TYPE);
        PermissionUpdateType type = PermissionUpdateType.fromValue(typeStr);

        String destStr = (String) map.get(DEST);
        PermissionUpdateDestination destination = ((destStr != null)
                ? PermissionUpdateDestination.fromValue(destStr)
                : null);

        return switch (type) {
            case ADD_RULES, REPLACE_RULES, REMOVE_RULES -> {
                List<PermissionRuleValue> rules = parseRules((List<Map<String, Object>>) map.get(RULES));
                String behaviorStr = (String) map.get(BEHAVIOR);
                PermissionBehavior behavior = ((behaviorStr != null)
                        ? PermissionBehavior.fromValue(behaviorStr)
                        : null);
                yield new PermissionUpdate(type, rules, behavior, null, null, destination);
            }
            case SET_MODE -> {
                // A missing mode is kept as null, as the Python SDK keeps it,
                // rather than failing the suggestion; toMap() then throws.
                String modeStr = (String) map.get(MODE);
                PermissionMode mode = ((modeStr != null) ? PermissionMode.fromValue(modeStr) : null);
                yield new PermissionUpdate(type, null, null, mode, null, destination);
            }
            case ADD_DIRS, REMOVE_DIRS -> {
                List<String> directories = (List<String>) map.get(DIRS);
                yield new PermissionUpdate(type, null, null, null, directories, destination);
            }
        };
    }

    @SuppressWarnings("null")
    private static List<PermissionRuleValue> parseRules(@Nullable List<Map<String, Object>> rulesData) {
        if (rulesData == null) {
            return null;
        }
        return rulesData.stream()
                .map(ruleMap -> {
                    String toolName = (String) ruleMap.get(TOOL_NAME);
                    String ruleContent = (String) ruleMap.get(RULE_CONTENT);
                    return new PermissionRuleValue(toolName, ruleContent);
                })
                .toList();
    }

    // Factory methods for different update types

    /**
     * Creates an addRules permission update.
     *
     * @param rules       the rules to add
     * @param behavior    the behavior the rules grant
     * @param destination where the update applies and is kept; {@code null}
     *                    is sent as {@code "session"}
     * @return the update
     */
    public static PermissionUpdate addRules(
            List<PermissionRuleValue> rules,
            PermissionBehavior behavior,
            @Nullable PermissionUpdateDestination destination) {
        return new PermissionUpdate(PermissionUpdateType.ADD_RULES, rules, behavior, null, null, destination);
    }

    /**
     * Creates a replaceRules permission update.
     *
     * @param rules       the rules that replace those with this behavior
     * @param behavior    the behavior whose rules are replaced
     * @param destination where the update applies and is kept; {@code null}
     *                    is sent as {@code "session"}
     * @return the update
     */
    public static PermissionUpdate replaceRules(
            List<PermissionRuleValue> rules,
            PermissionBehavior behavior,
            @Nullable PermissionUpdateDestination destination) {
        return new PermissionUpdate(PermissionUpdateType.REPLACE_RULES, rules, behavior, null, null, destination);
    }

    /**
     * Creates a removeRules permission update.
     *
     * @param rules       the rules to remove
     * @param behavior    the behavior the rules were added with
     * @param destination where the rules are kept; {@code null} is sent as
     *                    {@code "session"}
     * @return the update
     */
    public static PermissionUpdate removeRules(
            List<PermissionRuleValue> rules,
            PermissionBehavior behavior,
            @Nullable PermissionUpdateDestination destination) {
        return new PermissionUpdate(PermissionUpdateType.REMOVE_RULES, rules, behavior, null, null, destination);
    }

    /**
     * Creates a removeRules permission update with no behavior.
     *
     * @param rules       the rules to remove
     * @param destination where the rules are kept
     * @return the update
     * @deprecated Claude Code needs a {@code behavior} on a removeRules
     *             update. Without one it rejected the update and ignored
     *             every other update in the same response. {@link #toMap()}
     *             now throws for such an update. Use
     *             {@link #removeRules(List, PermissionBehavior, PermissionUpdateDestination)}.
     */
    @Deprecated(since = "0.2.8", forRemoval = true)
    public static PermissionUpdate removeRules(
            List<PermissionRuleValue> rules,
            @Nullable PermissionUpdateDestination destination) {
        return new PermissionUpdate(PermissionUpdateType.REMOVE_RULES, rules, null, null, null, destination);
    }

    /**
     * Creates a setMode permission update.
     *
     * @param mode        the permission mode to set
     * @param destination where the update applies and is kept; {@code null}
     *                    is sent as {@code "session"}
     * @return the update
     */
    public static PermissionUpdate setMode(
            PermissionMode mode,
            @Nullable PermissionUpdateDestination destination) {
        return new PermissionUpdate(PermissionUpdateType.SET_MODE, null, null, mode, null, destination);
    }

    /**
     * Creates an addDirectories permission update.
     *
     * @param directories the directories to allow
     * @param destination where the update applies and is kept; {@code null}
     *                    is sent as {@code "session"}
     * @return the update
     */
    public static PermissionUpdate addDirectories(
            List<String> directories,
            @Nullable PermissionUpdateDestination destination) {
        return new PermissionUpdate(PermissionUpdateType.ADD_DIRS, null, null, null, directories, destination);
    }

    /**
     * Creates a removeDirectories permission update.
     *
     * @param directories the directories to remove
     * @param destination where the update applies and is kept; {@code null}
     *                    is sent as {@code "session"}
     * @return the update
     */
    public static PermissionUpdate removeDirectories(
            List<String> directories,
            @Nullable PermissionUpdateDestination destination) {
        return new PermissionUpdate(PermissionUpdateType.REMOVE_DIRS, null, null, null, directories, destination);
    }

    // Getters

    public PermissionUpdateType type() {
        return type;
    }

    @Nullable
    public List<PermissionRuleValue> rules() {
        return rules;
    }

    @Nullable
    public PermissionBehavior behavior() {
        return behavior;
    }

    @Nullable
    public PermissionMode mode() {
        return mode;
    }

    @Nullable
    public List<String> directories() {
        return directories;
    }

    @Nullable
    public PermissionUpdateDestination destination() {
        return destination;
    }

    /**
     * Converts this permission update to a Map for JSON serialization, in the
     * control protocol format.
     *
     * <p>
     * The @JsonValue annotation tells Jackson to use this method's return
     * value when serializing PermissionUpdate objects.
     *
     * <p>
     * Claude Code rejects an update that lacks a field its type needs, or
     * whose {@code ruleContent} is {@code null}, and then ignores every update
     * in the same response. So this method:
     * <ul>
     * <li>leaves {@code ruleContent} out when a rule's
     * {@link PermissionRuleValue#ruleContent()} is {@code null}, which is how
     * Claude Code expects a rule for the whole tool;</li>
     * <li>sends {@code "session"} when {@code destination} is {@code null}, so
     * the update changes only the current session;</li>
     * <li>throws {@link IllegalArgumentException} when a field the type needs
     * is {@code null}: {@code rules} and {@code behavior} for
     * {@code addRules}, {@code replaceRules} and {@code removeRules},
     * {@code mode} for {@code setMode}, and {@code directories} for
     * {@code addDirectories} and {@code removeDirectories}.</li>
     * </ul>
     *
     * <p>
     * When Jackson serializes the update (this is its {@code @JsonValue}), the
     * same failure surfaces as a {@code JsonMappingException} whose cause is
     * the {@code IllegalArgumentException}.
     *
     * @return a map representation matching the CLI control protocol format
     * @throws IllegalArgumentException if a field the update's type needs is
     *                                  {@code null}
     */
    @JsonValue
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put(TYPE, type.getValue());
        result.put(DEST, ((destination != null)
                ? destination.getValue()
                : PermissionUpdateDestination.SESSION.getValue()));

        switch (type) {
            case ADD_RULES, REPLACE_RULES, REMOVE_RULES -> {
                if (rules == null) {
                    throw new IllegalArgumentException("PermissionUpdate(type=" + type.getValue()
                            + ") has no rules. Set rules to a list of PermissionRuleValue.");
                }
                if (behavior == null) {
                    throw new IllegalArgumentException("PermissionUpdate(type=" + type.getValue()
                            + ") has no behavior. Set behavior to ALLOW, DENY or ASK.");
                }
                result.put(RULES, rules.stream()
                        .map(rule -> {
                            Map<String, Object> ruleMap = new HashMap<>();
                            ruleMap.put(TOOL_NAME, rule.toolName());
                            if (rule.ruleContent() != null) {
                                ruleMap.put(RULE_CONTENT, rule.ruleContent());
                            }
                            return ruleMap;
                        })
                        .toList());
                result.put(BEHAVIOR, behavior.getValue());
            }
            case SET_MODE -> {
                if (mode == null) {
                    throw new IllegalArgumentException("PermissionUpdate(type=setMode) has no mode. "
                            + "Set mode to a permission mode such as ACCEPT_EDITS.");
                }
                result.put(MODE, mode.getValue());
            }
            case ADD_DIRS, REMOVE_DIRS -> {
                if (directories == null) {
                    throw new IllegalArgumentException("PermissionUpdate(type=" + type.getValue()
                            + ") has no directories. Set directories to a list of paths.");
                }
                result.put(DIRS, directories);
            }
        }

        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("PermissionUpdate [");
        sb.append("type=").append(type);
        sb.append(", rules=").append(rules);
        sb.append(", behavior=").append(behavior);
        sb.append(", mode=").append(mode);
        sb.append(", directories=").append(directories);
        sb.append(", destination=").append(destination);
        sb.append("]");

        return sb.toString();
    }

}
