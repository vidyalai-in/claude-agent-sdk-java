package in.vidyalai.claude.sdk.types.session;

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
 * fields verbatim. Every entry the CLI writes carries a string {@code type},
 * but none is required here: like the Python SDK's {@code SessionStoreEntry},
 * which is an unvalidated dict, an entry without one (or with a non-string
 * one) is carried through mirroring, import and listing unchanged.
 */
public final class SessionStoreEntry {

    private final Map<String, Object> data;

    /**
     * Wraps a copy of {@code data}.
     *
     * <p>Up to 0.2.5 this threw {@link IllegalArgumentException} when
     * {@code data} had no non-empty string {@code type}; such entries are now
     * accepted, and {@link #type()} returns {@code null} for them.
     *
     * @throws IllegalArgumentException if {@code data} is null
     */
    public SessionStoreEntry(Map<String, Object> data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        this.data = new LinkedHashMap<>(data);
    }

    /**
     * Transcript entry type discriminator, or {@code null} when the entry has
     * no string {@code type} field.
     */
    @Nullable
    public String type() {
        Object v = data.get("type");
        return v instanceof String s ? s : null;
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
     * Build a {@code SessionStoreEntry} from a copy of {@code map}, keeping
     * its key order.
     */
    public static SessionStoreEntry of(Map<String, Object> map) {
        return new SessionStoreEntry(map);
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
