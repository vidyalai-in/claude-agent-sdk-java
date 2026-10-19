package in.vidyalai.claude.sdk.types.message;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Why fast mode can't serve right now, reported in
 * {@link SystemInitData#fastModeDisabledReason()}.
 *
 * <p>
 * Newer CLIs may send values not listed here; {@link SystemInitData} reads
 * those as {@code null}, and the raw string stays in
 * {@link SystemMessage#data()}.
 *
 * @see SystemInitData
 */
public enum FastModeDisabledReason {

    /** Wire value {@code "free"}. */
    FREE("free"),

    /** Wire value {@code "preference"}. */
    PREFERENCE("preference"),

    /** Wire value {@code "extra_usage_disabled"}. */
    EXTRA_USAGE_DISABLED("extra_usage_disabled"),

    /** Wire value {@code "network_error"}. */
    NETWORK_ERROR("network_error"),

    /** Wire value {@code "unknown"}. */
    UNKNOWN("unknown"),

    /** Wire value {@code "not_first_party"}. */
    NOT_FIRST_PARTY("not_first_party"),

    /** Wire value {@code "disabled_by_env"}. */
    DISABLED_BY_ENV("disabled_by_env"),

    /** Wire value {@code "model_not_allowed"}. */
    MODEL_NOT_ALLOWED("model_not_allowed"),

    /** Wire value {@code "sdk_opt_in_required"}. */
    SDK_OPT_IN_REQUIRED("sdk_opt_in_required"),

    /** Wire value {@code "pending"}. */
    PENDING("pending");

    private final String value;

    FastModeDisabledReason(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    public static FastModeDisabledReason fromValue(String value) {
        for (FastModeDisabledReason v : values()) {
            if (v.value.equals(value)) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown FastModeDisabledReason: " + value);
    }

}
