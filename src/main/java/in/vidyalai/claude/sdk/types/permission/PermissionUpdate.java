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
     * This is the inverse of {@link #toMap()}.
     *
     * @param map the map representation from JSON
     * @return a PermissionUpdate instance
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
                PermissionMode mode = PermissionMode.fromValue((String) map.get(MODE));
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
     */
    public static PermissionUpdate addRules(
            List<PermissionRuleValue> rules,
            PermissionBehavior behavior,
            @Nullable PermissionUpdateDestination destination) {
        return new PermissionUpdate(PermissionUpdateType.ADD_RULES, rules, behavior, null, null, destination);
    }

    /**
     * Creates a replaceRules permission update.
     */
    public static PermissionUpdate replaceRules(
            List<PermissionRuleValue> rules,
            PermissionBehavior behavior,
            @Nullable PermissionUpdateDestination destination) {
        return new PermissionUpdate(PermissionUpdateType.REPLACE_RULES, rules, behavior, null, null, destination);
    }

    /**
     * Creates a removeRules permission update.
     */
    public static PermissionUpdate removeRules(
            List<PermissionRuleValue> rules,
            @Nullable PermissionUpdateDestination destination) {
        return new PermissionUpdate(PermissionUpdateType.REMOVE_RULES, rules, null, null, null, destination);
    }

    /**
     * Creates a setMode permission update.
     */
    public static PermissionUpdate setMode(
            PermissionMode mode,
            @Nullable PermissionUpdateDestination destination) {
        return new PermissionUpdate(PermissionUpdateType.SET_MODE, null, null, mode, null, destination);
    }

    /**
     * Creates an addDirectories permission update.
     */
    public static PermissionUpdate addDirectories(
            List<String> directories,
            @Nullable PermissionUpdateDestination destination) {
        return new PermissionUpdate(PermissionUpdateType.ADD_DIRS, null, null, null, directories, destination);
    }

    /**
     * Creates a removeDirectories permission update.
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
     * Converts this permission update to a Map for JSON serialization.
     *
     * <p>
     * The @JsonValue annotation tells Jackson to use this method's return
     * value when serializing PermissionUpdate objects.
     *
     * @return a map representation matching the CLI control protocol format
     */
    @JsonValue
    public Map<String, Object> toMap() {
        Map<String, Object> result = new HashMap<>();
        result.put(TYPE, type.getValue());

        if (destination != null) {
            result.put(DEST, destination.getValue());
        }

        switch (type) {
            case ADD_RULES, REPLACE_RULES, REMOVE_RULES -> {
                if (rules != null) {
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
                }
                if (behavior != null) {
                    result.put(BEHAVIOR, behavior.getValue());
                }
            }
            case SET_MODE -> {
                if (mode != null) {
                    result.put(MODE, mode.getValue());
                }
            }
            case ADD_DIRS, REMOVE_DIRS -> {
                if (directories != null) {
                    result.put(DIRS, directories);
                }
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
