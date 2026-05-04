package in.vidyalai.claude.sdk.types.session;

/**
 * Controls when transcript-mirror entries are flushed to a
 * {@link SessionStore}.
 *
 * <p>
 * Mirrors Python SDK's {@code SessionStoreFlushMode} literal.
 *
 * <ul>
 * <li>{@link #BATCHED} (default) — buffer entries and flush once per turn (on
 * the {@code result} message) or when the pending buffer exceeds 500 entries
 * or 1 MiB. Keeps adapter latency off the streaming hot path.
 * <li>{@link #EAGER} — trigger a background flush after every
 * {@code transcript_mirror} frame so {@link SessionStore#append} sees entries
 * in near real time. Appends are still serialized in enqueue order; a slow
 * adapter will not stall the read loop but will see frames coalesced while it
 * is busy.
 * </ul>
 */
public enum SessionStoreFlushMode {

    /** Default — coalesce entries and flush once per turn or buffer overflow. */
    BATCHED("batched"),

    /** Background-flush after every frame for near real-time delivery. */
    EAGER("eager");

    private final String wireValue;

    SessionStoreFlushMode(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Wire value used in Python ({@code "batched"} / {@code "eager"}). */
    public String wireValue() {
        return wireValue;
    }

}
