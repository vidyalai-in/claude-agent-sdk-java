package in.vidyalai.claude.sdk.types.config;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Controls how much effort Claude puts into its response.
 *
 * <p>Works with adaptive thinking to guide thinking depth.
 *
 * <p>Mirrors the Python SDK {@code EffortLevel} type alias and is exported as
 * part of the public API so downstream SDK wrappers and type annotations can
 * reference it directly.
 *
 * <p>Supported values:
 * <ul>
 *   <li>{@link #LOW} — Minimal thinking, fastest responses.</li>
 *   <li>{@link #MEDIUM} — Moderate thinking.</li>
 *   <li>{@link #HIGH} — Deep reasoning (default).</li>
 *   <li>{@link #XHIGH} — Extended reasoning depth (Opus 4.7 only; falls back to
 *       {@link #HIGH} on other models).</li>
 *   <li>{@link #MAX} — Maximum effort.</li>
 * </ul>
 *
 * @see <a href="https://docs.anthropic.com/en/docs/build-with-claude/adaptive-thinking">Adaptive thinking</a>
 */
public enum EffortLevel {

    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max");

    private final String value;

    EffortLevel(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static EffortLevel fromValue(String value) {
        for (EffortLevel level : values()) {
            if (level.value.equals(value)) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unknown effort level: " + value);
    }

}
