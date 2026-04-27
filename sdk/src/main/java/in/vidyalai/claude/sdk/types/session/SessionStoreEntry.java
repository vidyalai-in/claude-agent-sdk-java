package in.vidyalai.claude.sdk.types.session;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * One JSONL transcript line as observed by a {@link SessionStore} adapter.
 *
 * <p>The concrete shape is the CLI's on-disk transcript format (a large
 * discriminated union). That union is internal, so this is a minimal
 * structural supertype — adapters should treat entries as pass-through
 * blobs; round-tripping {@code Jackson serialize}/{@code Jackson deserialize}
 * is the only required invariant.
 *
 * <p>Backed by a {@link Map} so adapters can persist arbitrary JSON-safe
 * fields verbatim. The {@code type} field is required.
 */
public final class SessionStoreEntry {

    private final Map<String, Object> data;

    public SessionStoreEntry(Map<String, Object> data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        Object type = data.get("type");
        if (!(type instanceof String s) || s.isEmpty()) {
            throw new IllegalArgumentException("SessionStoreEntry requires a non-empty 'type' field");
        }
        this.data = new LinkedHashMap<>(data);
    }

    /**
     * Required transcript entry type discriminator.
     */
    public String type() {
        return (String) data.get("type");
    }

    /**
     * Stable entry UUID for idempotent upserts in adapters.
     * May be {@code null} for non-message entries (titles, tags, mode markers).
     */
    @Nullable
    public String uuid() {
        Object v = data.get("uuid");
        return v instanceof String s ? s : null;
    }

    /**
     * ISO-8601 timestamp (or {@code null} if absent).
     */
    @Nullable
    public String timestamp() {
        Object v = data.get("timestamp");
        return v instanceof String s ? s : null;
    }

    /**
     * Returns the underlying entry data as an unmodifiable view. The map is
     * preserved verbatim — adapters should pass it through to storage.
     */
    public Map<String, Object> asMap() {
        return java.util.Collections.unmodifiableMap(data);
    }

    /**
     * Convenience: get a typed value from the entry's underlying map.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public <T> T get(String key) {
        return (T) data.get(key);
    }

    /**
     * Build a {@code SessionStoreEntry} from a copy of {@code map}.
     */
    public static SessionStoreEntry of(Map<String, Object> map) {
        return new SessionStoreEntry(new HashMap<>(map));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionStoreEntry other)) {
            return false;
        }
        return data.equals(other.data);
    }

    @Override
    public int hashCode() {
        return data.hashCode();
    }

    @Override
    public String toString() {
        return "SessionStoreEntry" + data;
    }

}
