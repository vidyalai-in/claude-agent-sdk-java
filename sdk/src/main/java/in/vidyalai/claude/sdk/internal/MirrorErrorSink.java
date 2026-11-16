package in.vidyalai.claude.sdk.internal;

import org.jspecify.annotations.Nullable;

import in.vidyalai.claude.sdk.types.session.SessionKey;

/**
 * Where a {@link TranscriptMirrorBatcher} reports a batch it dropped (after
 * its retries, or after a single timed-out append).
 *
 * <p>
 * Each report carries the number of the drain that dropped the batch. Drains
 * are numbered from 1 in the order they are scheduled, and a flush covers
 * every drain up to the number its {@code Barrier} names, so the receiver can
 * tell which {@code result} a report belongs to: {@code QueryHandler} uses it
 * to deliver a {@code mirror_error} ahead of that result and behind the ones
 * before it.
 */
@FunctionalInterface
public interface MirrorErrorSink {

    /**
     * Reports one dropped batch. Must not block for long: reports are
     * serialized, and a flush waits for the reports of the drains it covers.
     *
     * @param key   the session the batch was for, or {@code null} if unknown
     * @param error the failure's message
     * @param drain the number of the drain that dropped the batch (from 1)
     */
    void report(@Nullable SessionKey key, String error, long drain);

}
