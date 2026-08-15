# Session Store (External Transcript Mirroring)

Mirror Claude Code session transcripts to an external store (S3, Postgres, Redis, your own backend) so sessions are durable beyond local disk and resumable from anywhere.

## Table of Contents
- [Overview](#overview)
- [When to Use a SessionStore](#when-to-use-a-sessionstore)
- [Quick Start](#quick-start)
- [The SessionStore Interface](#the-sessionstore-interface)
- [Sync vs Async API](#sync-vs-async-api)
- [Configuring the Async Executor](#configuring-the-async-executor)
- [Bundled Reference Adapters](#bundled-reference-adapters)
- [SessionStore-Backed Read APIs](#sessionstore-backed-read-apis)
- [SessionStore-Backed Mutations](#sessionstore-backed-mutations)
- [Resume from a Store](#resume-from-a-store)
- [Mirror Errors](#mirror-errors)
- [Flush Mode (Batched vs Eager)](#flush-mode-batched-vs-eager)
- [Importing Local Sessions Into a Store](#importing-local-sessions-into-a-store)
- [Conformance Test Harness](#conformance-test-harness)
- [Internal Runtime Pieces](#internal-runtime-pieces)
- [Best Practices](#best-practices)
- [API Reference](#api-reference)

## Overview

By default the Claude Code CLI writes every session as a JSONL file under `~/.claude/projects/`. The SDK can additionally mirror every transcript line to an external store of your choice — useful for:

- **Durable long-running sessions** — local disk is ephemeral on serverless / autoscaling platforms.
- **Multi-host resume** — start a session on host A, resume on host B.
- **Audit / compliance retention** — apply your own TTL policies (S3 lifecycle, Postgres partitions, Redis TTL).
- **Multi-tenant deployments** — scope transcripts by `project_key` to isolate tenants.

The SDK ships:

- `SessionStore` interface (sync + async variants)
- `InMemorySessionStore` reference adapter
- Runtime mirror integration (transparent — set `sessionStore` on options and the SDK handles the rest)
- Read + mutation helpers that work directly against a store (`*FromStore`, `*ViaStore`)
- A `SessionStoreConformance` test harness for validating your own adapter
- `importSessionToStore()` for migrating existing on-disk sessions

The local-disk transcript is always written first; mirroring is a secondary durability path. Mirror failures never block a session — they surface as a non-fatal `MirrorErrorMessage`.

## When to Use a SessionStore

| Scenario | Recommendation |
|---|---|
| Single-user CLI on a workstation | Don't bother — local JSONL is fine |
| Long-running server, sessions span requests | Use a `SessionStore` |
| Compliance / regulated retention | Use a `SessionStore` with native lifecycle policies |
| Multi-host fleet / cloud autoscaling | Use a `SessionStore` so any host can resume |
| Audit / replay across many sessions | Use a `SessionStore` for centralized querying |

## Quick Start

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;

InMemorySessionStore store = new InMemorySessionStore();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)            // mirror every transcript line here
    .build();

ClaudeSDK.query("Hello!", options);
// All transcript entries from this turn are now in `store`
```

The SDK adds `--session-mirror` to the CLI invocation, peels `transcript_mirror` frames off the CLI's stdout, and forwards them to `store.append(...)` in batches.

To resume from the store on another host:

```java
ClaudeAgentOptions resumeOptions = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .resume("previous-session-uuid")
    .build();

ClaudeSDK.query("Continue where we left off", resumeOptions);
```

The SDK loads the stored transcript into a temp `CLAUDE_CONFIG_DIR` so the CLI subprocess can pick up the conversation.

## The SessionStore Interface

`in.vidyalai.claude.sdk.types.session.SessionStore` is a Java interface. Two methods are required; the rest are optional with `implements*()` probe flags so callers detect what's supported without `instanceof`.

### Required methods

```java
void append(SessionKey key, List<SessionStoreEntry> entries);

@Nullable
List<SessionStoreEntry> load(SessionKey key);
```

- `append` — mirror a batch of transcript entries. Called AFTER the local disk write succeeds, so durability is already guaranteed locally. Adapters should treat `entry.uuid()` as an idempotency key (entries without `uuid` like custom-title or tag should be appended without dedup).
- `load` — return all entries for a key (deep-equal to what was appended; byte-equal not required). Returns `null` for a key that was never written.

### Optional methods (default to throwing `UnsupportedOperationException`)

```java
default List<SessionStoreListEntry> listSessions(String projectKey);
default List<SessionSummaryEntry> listSessionSummaries(String projectKey);
default void delete(SessionKey key);
default List<String> listSubkeys(SessionListSubkeysKey key);
```

### Capability probes

```java
default boolean implementsListSessions() { return false; }
default boolean implementsListSessionSummaries() { return false; }
default boolean implementsDelete() { return false; }
default boolean implementsListSubkeys() { return false; }
```

Override these to return `true` when you implement the corresponding optional method. The SDK uses these probes (not `try/catch`) to decide whether to call the optional method.

### Key types

```java
public record SessionKey(
    String projectKey,             // caller-defined scope (default: sanitized cwd)
    String sessionId,              // session UUID
    @Nullable String subpath        // null for main; "subagents/agent-x" for subagent
);

public record SessionListSubkeysKey(String projectKey, String sessionId);

public record SessionStoreListEntry(String sessionId, long mtime);

public record SessionSummaryEntry(String sessionId, long mtime, Map<String, Object> data);
```

`SessionStoreEntry` is a thin wrapper around `Map<String, Object>` requiring a `type` field; everything else is opaque pass-through:

```java
SessionStoreEntry entry = SessionStoreEntry.of(Map.of(
    "type", "user",
    "uuid", "u1",
    "message", Map.of(
        "content", List.of(Map.of("type", "text", "text", "Hello"))),
    "timestamp", "2026-04-27T00:00:00Z"
));

entry.type();      // "user"
entry.uuid();      // "u1"
entry.timestamp(); // "2026-04-27T00:00:00Z"
entry.<String>get("custom_field"); // typed convenience accessor
entry.asMap();     // unmodifiable map view
```

## Sync vs Async API

Every method on `SessionStore` has both sync and `*Async` (returning `CompletableFuture`) variants:

```java
// Sync (required to implement; or default to *Async().join() if you only override async)
void append(SessionKey key, List<SessionStoreEntry> entries);

// Async with default executor
default CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries);

// Async with explicit executor
default CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries, Executor executor);
```

The internal mirror batcher and resume materializer call the `*Async` variants — so adapters with native non-blocking clients (AWS SDK v2 async, R2DBC, Lettuce reactive) can override the `*Async` methods and preserve parallelism end-to-end.

### Default delegation

- If you override only the **sync** methods (the typical case for JDBC, Jedis, blocking S3 SDK v1), the `*Async` defaults wrap your sync calls in `CompletableFuture.supplyAsync(...)` on the configured executor (per-task virtual thread by default).
- If you override only the **async** methods (recommended for AWS SDK v2 async / Lettuce reactive / R2DBC), implement the sync method as `appendAsync(key, entries).join()` so both call sites work.

```java
public class S3AsyncStore implements SessionStore {
    private final S3AsyncClient s3;

    @Override
    public void append(SessionKey key, List<SessionStoreEntry> entries) {
        appendAsync(key, entries).join();
    }

    @Override
    public CompletableFuture<Void> appendAsync(SessionKey key, List<SessionStoreEntry> entries) {
        // Native async — no thread hop
        return s3.putObject(/* ... */).thenApply(r -> null);
    }

    @Override
    public List<SessionStoreEntry> load(SessionKey key) { /* ... */ }
}
```

## Configuring the Async Executor

By default, async wrappers run on a **per-task virtual thread** via:

```java
Executors.newThreadPerTaskExecutor(
    Thread.ofVirtual().name("session-store-", 0).factory());
```

You can override this once at startup via `SessionStoreExecutor`:

```java
import in.vidyalai.claude.sdk.types.session.SessionStoreExecutor;

// Bounded virtual-thread pool with a custom name
ExecutorService bounded = Executors.newThreadPerTaskExecutor(
    Thread.ofVirtual().name("my-store-", 0).factory());
SessionStoreExecutor.setDefault(bounded);

// Or a platform-thread pool when virtual threads aren't desired
SessionStoreExecutor.setDefault(Executors.newFixedThreadPool(8));

// Reset to the built-in default
SessionStoreExecutor.reset();
```

You can also pass a per-call executor:

```java
store.appendAsync(key, entries, customExecutor)
```

The configured executor is used by every `*Async` default that doesn't take an explicit executor. Adapters that override `*Async` directly bypass this entirely — the executor only applies to the sync→async wrapping path.

## Bundled Reference Adapters

### `InMemorySessionStore`

A thread-safe in-memory implementation suitable for tests and prototyping:

```java
import in.vidyalai.claude.sdk.types.session.InMemorySessionStore;

InMemorySessionStore store = new InMemorySessionStore();

// All optional methods implemented
store.implementsListSessions();         // true
store.implementsListSessionSummaries(); // true
store.implementsDelete();               // true
store.implementsListSubkeys();          // true

// Test helpers
store.size();             // count of main-transcript sessions
store.snapshotSummaries();// LinkedHashMap snapshot of summary sidecars
store.clear();            // wipe everything
```

Maintains an incremental `SessionSummaryEntry` sidecar inside `append()` so `listSessionSummaries()` runs in O(1) — never re-reads transcripts.

### Path → Key Helper

`InMemorySessionStore.filePathToSessionKey(filePath, projectsDir)` is a static helper that maps an on-disk transcript path back to a `SessionKey`:

```java
SessionKey k = InMemorySessionStore.filePathToSessionKey(
    "/home/u/.claude/projects/myproj/abc-123.jsonl",
    "/home/u/.claude/projects");
// k = SessionKey("myproj", "abc-123", null)

SessionKey sub = InMemorySessionStore.filePathToSessionKey(
    "/home/u/.claude/projects/myproj/abc-123/subagents/agent-x.jsonl",
    "/home/u/.claude/projects");
// sub = SessionKey("myproj", "abc-123", "subagents/agent-x")
```

Returns `null` for paths outside `projectsDir` or unrecognized layouts. Used internally by the mirror batcher; exposed for adapter implementations that need the same mapping.

### Production Adapters (S3, Redis, Postgres, …)

The SDK does not ship production adapters — those depend on heavyweight client libraries (AWS SDK, Lettuce, JDBC, R2DBC) we don't want as transitive dependencies. Implement your own and validate with `SessionStoreConformance` (see below). The protocol is small and stable.

## SessionStore-Backed Read APIs

Read sessions directly from a store without involving the CLI:

```java
import in.vidyalai.claude.sdk.ClaudeSDK;

// List all sessions in the store for the current cwd
List<SDKSessionInfo> sessions =
    ClaudeSDK.listSessionsFromStore(store, /* directory */ null, /* limit */ 50, /* offset */ 0);

// Single-session metadata
SDKSessionInfo info = ClaudeSDK.getSessionInfoFromStore(store, sessionId, null);

// Full transcript
List<SessionMessage> messages =
    ClaudeSDK.getSessionMessagesFromStore(store, sessionId, null, null, 0);

// Subagent transcript discovery + reading
List<String> agentIds = ClaudeSDK.listSubagentsFromStore(store, sessionId, null);
List<SessionMessage> subAgent =
    ClaudeSDK.getSubagentMessagesFromStore(store, sessionId, agentIds.get(0), null, null, 0);
```

`listSessionsFromStore` has a fast path when the store implements `listSessionSummaries`: one batch summary call plus a cheap `listSessions` enumeration to gap-fill any sessions missing or stale sidecars. When `listSessionSummaries` isn't implemented, it falls back to one `loadAsync()` per session, **bounded at 16 concurrent calls** (matching the Python SDK), so large project listings don't exhaust adapter connection pools.

If `listSessions` and `listSessionSummaries` are both unimplemented, the call throws `IllegalStateException`. Adapter `loadAsync` failures degrade individual rows to empty-summary entries instead of failing the whole list.

## SessionStore-Backed Mutations

Same shape as the on-disk mutation APIs, but they write to the store:

```java
ClaudeSDK.renameSessionViaStore(store, sessionId, "My New Title", null);
ClaudeSDK.tagSessionViaStore(store, sessionId, "important", null);
ClaudeSDK.tagSessionViaStore(store, sessionId, null, null);   // clear tag
ClaudeSDK.deleteSessionViaStore(store, sessionId, null);

ForkSessionResult fork = ClaudeSDK.forkSessionViaStore(
    store, sessionId, /* directory */ null,
    /* upToMessageId */ null,                  // null copies full transcript
    /* title */ "My Fork");
```

Internals:

- `renameSessionViaStore` appends a `custom-title` entry.
- `tagSessionViaStore` appends a `tag` entry; `null` clears via empty string.
- `deleteSessionViaStore` is a no-op if the store doesn't implement `delete()` (appropriate for WORM/append-only backends like raw S3).
- `forkSessionViaStore` runs the same UUID-remap transform as the on-disk fork (shared `SessionMutations.buildForkLines`) — a storage-layer copy is NOT sufficient.

`listSubagentsFromStore` requires `listSubkeys()` and throws `IllegalStateException` otherwise.

## Resume from a Store

When `options.sessionStore` is set together with `options.resume` (or `options.continueConversation`), the SDK:

1. Calls `store.load()` for the requested session ID (or, for `continueConversation`, picks the most-recently-modified non-sidechain session via `store.listSessions()`).
2. Writes the entries to a temporary directory laid out exactly like `~/.claude/`.
3. Seeds the temp dir from your real config dir so the subprocess can authenticate and behave as it normally would — `.credentials.json` (with `refreshToken` redacted), `.claude.json`, and your user `settings.json` / `cowork_settings.json`. See [What gets seeded](#what-gets-seeded).
4. Materializes any subagent transcripts and `.meta.json` sidecars from the store (when `listSubkeys` is implemented).
5. Spawns the CLI with `CLAUDE_CONFIG_DIR=<temp dir>` so it resumes from local disk as usual.
6. Cleans up the temp dir on disconnect (with retry on transient Windows AV/indexer locks).

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .resume(previousSessionId)
    .loadTimeoutMs(60_000)        // per-call timeout for store.load() / listSubkeys()
    .build();
```

The `loadTimeoutMs` option (default 60 000) bounds each individual store call during materialization; if an adapter doesn't settle within this window the query fails fast with a clear error rather than hanging the iterator.

### What gets seeded

Because the subprocess runs under a redirected `CLAUDE_CONFIG_DIR`, it would
otherwise see none of your configuration. The SDK copies four files from the
caller's config dir — resolved as `options.env["CLAUDE_CONFIG_DIR"]` → the
process environment → `~/.claude` (`.claude.json` lives at
`$CLAUDE_CONFIG_DIR/.claude.json` when set, else `~/.claude.json`, *not*
`~/.claude/.claude.json`):

| File | Why it matters |
|------|----------------|
| `.credentials.json` | OAuth credentials, with `claudeAiOauth.refreshToken` removed |
| `.claude.json` | User-level CLI state |
| `settings.json` | `apiKeyHelper`, plus your `env`, `hooks` and `permissions` |
| `cowork_settings.json` | The alternate settings filename read in cowork-plugins mode |

Seeding `settings.json` matters more than it looks: `apiKeyHelper` is a fourth
authentication mechanism alongside the credentials file, the macOS Keychain and
environment variables. Before v0.1.23 it was not copied, so a host that
authenticated solely through `apiKeyHelper` failed with **"Not logged in"** the
moment it resumed from a store.

Both settings files pass through a transform that drops only the keys that
misbehave under a redirected config dir:

- `enabledPlugins` and `extraKnownMarketplaces` — these reconcile against the
  always-empty temp plugin cache and would network-install every declared
  marketplace on each resume.
- `env.CLAUDE_CONFIG_DIR` — would point the subprocess's config reads back out
  of the temp dir.

Everything else is preserved. A UTF-8 BOM (PowerShell writes one) is tolerated,
and content that is not valid UTF-8, or does not parse as a JSON object, is
copied byte-for-byte so the subprocess sees exactly what the CLI would have
read. Files are written owner-only (`0600`) inside the owner-only (`0700`) temp
dir.

Seeding is best-effort: a file that cannot be read for any reason other than
"missing" — a permissions error, or a directory or FIFO where a file was
expected — is logged and skipped rather than aborting a resume that would
otherwise succeed. A copy that fails midway removes the partial destination so
the subprocess cannot misparse a truncated file.

### Validation guards

Before any subprocess work, the SDK rejects invalid combinations:

- `continueConversation + sessionStore` requires `store.implementsListSessions()`.
- `sessionStore + enableFileCheckpointing` is rejected — checkpoints are local-disk only and would diverge from the mirrored transcript.

These throw `IllegalArgumentException` immediately.

## Mirror Errors

Mirror append failures are non-fatal — the local-disk transcript is already durable, so the session continues unaffected. The SDK retries each batch up to 3 times with `[200ms, 800ms]` backoff, then drops it and surfaces a `MirrorErrorMessage` to your message stream:

```java
import in.vidyalai.claude.sdk.types.message.MirrorErrorMessage;

for (Message msg : ClaudeSDK.query("Hello", options)) {
    switch (msg) {
        case MirrorErrorMessage err -> {
            // Non-fatal — log and consider importing the local file later
            System.err.println("Mirror error for " + (err.key() != null
                    ? err.key().sessionId() : "<unknown>")
                    + ": " + err.error());
        }
        case AssistantMessage a -> System.out.println(a.getTextContent());
        // ... other cases
        default -> { /* ignore */ }
    }
}
```

`MirrorErrorMessage` is a member of the `Message` sealed interface (alongside `AssistantMessage`, `SystemMessage`, etc.) — `subtype` is always `"mirror_error"`, `error` is the failure message, and `key` (nullable) is the `SessionKey` that the failed batch was targeting.

Timeouts are NOT retried (the in-flight call may still land — a retry would launch a concurrent duplicate). Adapters should dedupe on `entry.uuid()` so a partial-succeed retry is duplicate-safe.

## Flush Mode (Batched vs Eager)

By default the `TranscriptMirrorBatcher` buffers every `transcript_mirror` frame and flushes once per turn (on the `result` message) or when the pending buffer exceeds `MAX_PENDING_ENTRIES=500` entries / `MAX_PENDING_BYTES=1 MiB`. This keeps adapter latency off the streaming hot path and is the right choice for almost every deployment.

The `sessionStoreFlush` option lets you switch to eager mirroring when you need entries to land in the store with sub-second latency:

```java
import in.vidyalai.claude.sdk.types.session.SessionStoreFlushMode;

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .sessionStore(store)
    .sessionStoreFlush(SessionStoreFlushMode.EAGER)
    .build();
```

| Mode | When entries are flushed | Use when |
|---|---|---|
| `BATCHED` (default) | Once per `result` message OR when pending exceeds 500 entries / 1 MiB | Almost all production workloads — keeps adapter latency off the streaming hot path |
| `EAGER` | Background drain scheduled after every enqueued frame | Live transcript streaming to clients, real-time audit pipelines, very large turns where you can't wait until `result` |

`EAGER` zeroes the batcher's pending thresholds — every enqueued frame schedules a background flush via the configured `SessionStoreExecutor` (named virtual thread per task by default). Appends remain serialized in enqueue order; a slow adapter will not stall the read loop but will see frames coalesced while it's busy. The option is ignored when `sessionStore` is unset.

## Importing Local Sessions Into a Store

Migrate existing on-disk sessions into a store, or catch the store up after a `MirrorErrorMessage` exposed a gap:

```java
ClaudeSDK.importSessionToStore(sessionId, store, /* directory */ null);
// or with explicit options:
ClaudeSDK.importSessionToStore(
    sessionId, store, /* directory */ null,
    /* includeSubagents */ true,
    /* batchSize */ 500);
```

The helper:

- Streams the local JSONL line-by-line (skips blank lines).
- Calls `store.append(key, batch)` every `batchSize` entries (default 500) or 1 MiB of line bytes, whichever comes first.
- When `includeSubagents=true`, recursively imports `<sessionDir>/subagents/**/*.jsonl` and `.meta.json` sidecars (the `.meta.json` becomes an `agent_metadata` entry).
- Throws `IllegalArgumentException` on invalid UUID, `NoSuchFileException` if the session file isn't found.

The destination `project_key` is the on-disk project directory name — same key `filePathToSessionKey` produces — so an imported session is indistinguishable from a live-mirrored one and resumable from the original `cwd`.

Adapters should treat `entry.uuid()` as an idempotency key so re-importing is duplicate-safe.

## Conformance Test Harness

`in.vidyalai.claude.sdk.testing.SessionStoreConformance` is a public, framework-agnostic test harness that exercises the 14 behavioral contracts every adapter must satisfy. Use it to validate your own implementations:

```java
import in.vidyalai.claude.sdk.testing.SessionStoreConformance;
import org.junit.jupiter.api.Test;

class MyRedisStoreConformanceTest {
    @Test
    void satisfiesContract() {
        SessionStoreConformance.run(MyRedisStore::new);
    }
}
```

To skip optional methods you don't implement:

```java
SessionStoreConformance.run(WormStore::new,
    EnumSet.of(SessionStoreConformance.OptionalMethod.DELETE));
```

The harness uses plain `AssertionError` (no test-framework dependency), so it works under JUnit, TestNG, Spock, or even a plain `main()` smoke test.

The 14 contracts cover:

| # | Contract |
|---|---|
| 1 | `append` then `load` returns same entries in same order |
| 2 | `load` for an unknown key returns `null` |
| 3 | Multiple `append` calls preserve order |
| 4 | `append([])` is a no-op |
| 5 | Subpath keys are stored independently of main |
| 6 | `project_key` isolation |
| 7 | `listSessions` returns session IDs for project, mtime in epoch-ms |
| 8 | `listSessions` excludes subagent subpaths |
| 9 | `delete` then `load` returns `null` |
| 10 | `delete` of main key cascades to subkeys |
| 11 | `delete` with subpath removes only that subkey |
| 12 | `listSubkeys` returns subpaths |
| 13 | `listSubkeys` excludes main transcript |
| 14 | `listSessionSummaries` round-trips through `foldSessionSummary` |

## Internal Runtime Pieces

These live in `in.vidyalai.claude.sdk.internal` and are not part of the public API, but understanding them helps debug mirror behavior.

### `TranscriptMirrorBatcher`

Buffers `transcript_mirror` frames the CLI emits on stdout and flushes them to `store.appendAsync(...)`:

- Eager-flush thresholds: `MAX_PENDING_ENTRIES=500`, `MAX_PENDING_BYTES=1 MiB`. With `sessionStoreFlush(EAGER)` both thresholds are zeroed so every enqueued frame schedules a background drain (see [Flush Mode](#flush-mode-batched-vs-eager)).
- Explicit flush before each `result` message and again at end-of-stream / close.
- Coalesces frames per `filePath` so each unique file gets one `append` call per flush.
- Frames whose path falls outside `projectsDir` are dropped with a warning (would happen if `CLAUDE_CONFIG_DIR` differs between parent and subprocess).
- `MIRROR_APPEND_MAX_ATTEMPTS=3` retries with `[200ms, 800ms]` backoff; timeouts are not retried.
- `maxPendingEntries()` / `maxPendingBytes()` test accessors expose the configured thresholds (mirrors Python's public attributes).

### `SessionResume`

Materializes a stored session into a temp `CLAUDE_CONFIG_DIR` so the CLI can resume:

- `materializeResumeSession(options)` — main entry point.
- `applyMaterializedOptions(options, materialized)` — copies options with `CLAUDE_CONFIG_DIR` injected, `resume` set, `continueConversation` cleared.
- `buildMirrorBatcher(store, materialized, env, onError)` — constructs the batcher with the right `projectsDir` (defaults to `BATCHED` flush mode). The 5-arg overload `buildMirrorBatcher(store, materialized, env, onError, flushMode)` zeroes the batcher's thresholds when `flushMode == EAGER`.
- `MaterializedResume.cleanup()` — best-effort recursive removal with retry on transient Windows AV/indexer locks.

### `SessionStoreValidation`

Pre-flight option checks called before subprocess spawn (rejects misconfiguration with `IllegalArgumentException`).

### `SessionSummary`

Pure helpers that adapters can use inside `append()` to maintain incremental summary sidecars without re-reading the transcript:

```java
SessionSummaryEntry folded = SessionSummary.foldSessionSummary(
    /* prev */ existing, key, entries);
// stamp folded.mtime() with the adapter's storage write time, then persist.
```

`SessionSummary.summaryEntryToSdkInfo(entry, projectPath)` converts a sidecar back to an `SDKSessionInfo` for listing.

## Best Practices

### Adapter implementation

- **Always implement `append` + `load`.** They're required.
- **Maintain a summary sidecar** via `SessionSummary.foldSessionSummary` inside `append()` if your backend supports list operations; this makes `listSessionsFromStore` O(1) instead of O(N) loads. Skip the fold for keys with a `subpath` — subagent transcripts must not contribute to the main session's summary.
- **Treat `entry.uuid()` as the idempotency key.** Use upsert semantics or skip-if-exists. The SDK retries failed batches and may partial-succeed.
- **Stamp summary `mtime` with your storage write time**, not entry timestamps. The fast-path freshness check compares summary mtime against `listSessions().mtime` for the same session — using entry timestamps would make every sidecar look stale.
- **Cascade deletes** from a main-transcript key to all subkeys (subagent transcripts).
- **Run the conformance harness** in CI.

### When to override `*Async` methods

- Your client is natively async (AWS SDK v2 async, R2DBC, Lettuce reactive) — override `*Async` to avoid a thread hop.
- Your client is sync (JDBC, Jedis, AWS SDK v1) — implement only the sync methods; the default `*Async` wrappers are fine on virtual threads.

### When to use `importSessionToStore`

- One-time migration of pre-existing local sessions to a store.
- Catching up after a `MirrorErrorMessage` (re-import the local file; idempotency on `uuid` makes this safe).

### Avoid

- Combining `sessionStore` with `enableFileCheckpointing` (rejected at validation time anyway — checkpoints are local-only).
- Storing secrets or PII without retention controls. The SDK does not auto-delete; configure your store's lifecycle.
- Relying on byte-equal serialization in `load()`. The contract is deep-equal; Postgres `jsonb` reorders keys, for example.

## API Reference

### `SessionStore` interface

`in.vidyalai.claude.sdk.types.session.SessionStore`

| Method | Required | Default | Notes |
|---|---|---|---|
| `void append(SessionKey, List<SessionStoreEntry>)` | ✅ | — | Mirror batch; called after local write |
| `List<SessionStoreEntry> load(SessionKey)` | ✅ | — | Return entries or `null` |
| `List<SessionStoreListEntry> listSessions(String)` | optional | throws | Excludes subpath entries |
| `List<SessionSummaryEntry> listSessionSummaries(String)` | optional | throws | Fast-path for `listSessionsFromStore` |
| `void delete(SessionKey)` | optional | throws | Main key cascades to subkeys |
| `List<String> listSubkeys(SessionListSubkeysKey)` | optional | throws | Used by resume materialization |
| `boolean implementsListSessions()` | — | `false` | Override to declare support |
| `boolean implementsListSessionSummaries()` | — | `false` | Override to declare support |
| `boolean implementsDelete()` | — | `false` | Override to declare support |
| `boolean implementsListSubkeys()` | — | `false` | Override to declare support |
| `CompletableFuture<Void> appendAsync(...)` | optional | wraps sync | Override for native async clients |
| `CompletableFuture<List<SessionStoreEntry>> loadAsync(...)` | optional | wraps sync | Override for native async clients |
| `CompletableFuture<List<SessionStoreListEntry>> listSessionsAsync(...)` | optional | wraps sync | — |
| `CompletableFuture<List<SessionSummaryEntry>> listSessionSummariesAsync(...)` | optional | wraps sync | — |
| `CompletableFuture<Void> deleteAsync(...)` | optional | wraps sync | — |
| `CompletableFuture<List<String>> listSubkeysAsync(...)` | optional | wraps sync | — |

Each `*Async` method has both a no-arg overload (uses the configured default executor) and an `Executor`-taking overload (per-call control).

### `ClaudeSDK` static methods

| Method | Description |
|---|---|
| `String projectKeyForDirectory(@Nullable Path)` | Sanitize a directory into a `project_key` |
| `List<SDKSessionInfo> listSessionsFromStore(SessionStore, @Nullable Path, @Nullable Integer, int)` | List sessions in a store |
| `SDKSessionInfo getSessionInfoFromStore(SessionStore, String, @Nullable Path)` | Read single-session metadata |
| `List<SessionMessage> getSessionMessagesFromStore(SessionStore, String, @Nullable Path, @Nullable Integer, int)` | Read full transcript |
| `List<String> listSubagentsFromStore(SessionStore, String, @Nullable Path)` | Discover subagent IDs |
| `List<SessionMessage> getSubagentMessagesFromStore(SessionStore, String, String, @Nullable Path, @Nullable Integer, int)` | Read subagent transcript |
| `void renameSessionViaStore(SessionStore, String, String, @Nullable Path)` | Append `custom-title` entry |
| `void tagSessionViaStore(SessionStore, String, @Nullable String, @Nullable Path)` | Append `tag` entry; `null` clears |
| `void deleteSessionViaStore(SessionStore, String, @Nullable Path)` | Delete (no-op if `delete` unimplemented) |
| `ForkSessionResult forkSessionViaStore(SessionStore, String, @Nullable Path, @Nullable String, @Nullable String)` | UUID-remap fork |
| `void importSessionToStore(String, SessionStore, @Nullable Path)` | Replay local→store (default options) |
| `void importSessionToStore(String, SessionStore, @Nullable Path, boolean, int)` | Replay with explicit `includeSubagents` and `batchSize` |

### `ClaudeAgentOptions` builder methods

| Method | Default | Description |
|---|---|---|
| `Builder sessionStore(@Nullable SessionStore)` | `null` | Mirror transcripts to this store |
| `Builder loadTimeoutMs(long)` | `60_000` | Per-call timeout during resume materialization |

### `SessionStoreExecutor`

`in.vidyalai.claude.sdk.types.session.SessionStoreExecutor`

| Method | Description |
|---|---|
| `Executor getDefault()` | Current default executor |
| `void setDefault(Executor)` | Override; `null` resets to built-in |
| `void reset()` | Reset to built-in `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("session-store-", 0).factory())` |

### `SessionStoreConformance`

`in.vidyalai.claude.sdk.testing.SessionStoreConformance`

| Method | Description |
|---|---|
| `void run(Supplier<SessionStore>)` | Run all 14 contracts |
| `void run(Supplier<SessionStore>, Set<OptionalMethod>)` | Skip listed optional methods |

`OptionalMethod` enum: `LIST_SESSIONS`, `LIST_SESSION_SUMMARIES`, `DELETE`, `LIST_SUBKEYS`.

## See Also

- [Session History](./feature-session-history.md) — Local-disk equivalents (`listSessions`, `getSessionMessages`, etc.)
- [Message Types](./feature-message-types.md) — `MirrorErrorMessage` integration
- [ClaudeAgentOptions](./api-claude-agent-options.md) — `sessionStore` and `loadTimeoutMs`
- [ClaudeSDK](./api-claude-sdk.md) — Public API entry points
- `SessionStoreExample.java` in the `examples/` module
