package in.vidyalai.claude.sdk.types.hook.input;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Normalizes the {@code raw} component every {@link HookInput} record carries.
 */
final class HookInputRaw {

    private HookInputRaw() {
    }

    /**
     * An unmodifiable copy of {@code raw}, which may hold null values (a JSON
     * {@code null}); {@code null} reads as an empty map.
     */
    static Map<String, Object> copy(@Nullable Map<String, Object> raw) {
        if ((raw == null) || raw.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

}
