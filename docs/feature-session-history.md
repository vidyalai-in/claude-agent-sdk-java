# Session History

Read and browse historical Claude Code conversation sessions without running the CLI.

## Table of Contents
- [Overview](#overview)
- [Session Metadata](#session-metadata)
- [Session Messages](#session-messages)
- [Listing Sessions](#listing-sessions)
- [Looking Up a Single Session](#looking-up-a-single-session)
- [Reading Session Messages](#reading-session-messages)
- [Renaming Sessions](#renaming-sessions)
- [Tagging Sessions](#tagging-sessions)
- [Deleting Sessions](#deleting-sessions)
- [Forking Sessions](#forking-sessions)
- [Examples](#examples)
- [Best Practices](#best-practices)

## Overview

Claude Code stores every conversation as a JSONL file under `~/.claude/projects/`. The session history API lets you:

- **List sessions** across all projects or filtered by a specific working directory
- **Read messages** from any past session — the full conversation transcript

All reading is done directly from disk, independent of the CLI. No process is spawned.

**Performance:** For listing, only the first and last 64 KB of each session file are read (no full JSONL parse). Full parsing is done only when retrieving messages with `getSessionMessages`.

> **Looking for a remote/multi-host backend?** See [Session Store](./feature-session-store.md). Every method on this page has a `*FromStore` (read) or `*ViaStore` (mutation) counterpart on `ClaudeSDK` that operates against a `SessionStore` adapter (S3, Postgres, Redis, custom). The local-disk APIs documented here remain the canonical path; SessionStore APIs are additive and target the same on-disk layout for portability.

## Session Metadata

`SDKSessionInfo` holds metadata for a single session:

```java
record SDKSessionInfo(
    String sessionId,              // UUID identifying the session
    String summary,                // display title (custom title, AI title, lastPrompt, summary, or first prompt)
    long lastModified,             // last-modified time in milliseconds since epoch
    @Nullable Long fileSize,       // transcript size in bytes (null when read from a store's summary sidecar)
    @Nullable String customTitle,  // user-set custom title or AI-generated title (may be null)
    @Nullable String firstPrompt,  // first meaningful user prompt (may be null)
    @Nullable String gitBranch,    // git branch at end of session (may be null)
    @Nullable String cwd,          // working directory for the session (may be null)
    @Nullable String tag,          // user-set session tag (may be null)
    @Nullable Long createdAt       // creation time in ms since epoch from first entry's ISO timestamp (may be null)
)
```

The `summary` field is resolved in priority order: custom title > AI title > lastPrompt > auto-generated summary > first prompt. An empty `customTitle`, `gitBranch` or `cwd` counts as absent and falls through to the next source, as in the Python SDK.

### How the first prompt is found

`firstPrompt`, the summary fallback, the default fork title and the
`SessionSummary` fold all use one extraction, which follows the Python SDK's
rules since 0.2.6. It reads the user entries from the start of the transcript
and skips:

- tool results, `isMeta` entries and compact summaries (`isCompactSummary`);
- auto-generated text: `<local-command-stdout>`, `<session-start-hook>`,
  `<tick>`, `<goal>`, `[Request interrupted by user…]`, and text that is only an
  `<ide_opened_file>` or `<ide_selection>` tag.

Every text block of a message is tried, in order, with newlines collapsed to
spaces. A text that carries a `<command-name>` tag is skipped too, but the
first command name seen becomes the fallback when no other prompt is found.
The result is cut at 200 characters plus an ellipsis.

A backwards-compatible constructor without `tag` and `createdAt` is also available.

## Session Messages

`SessionMessage` holds a single message from a session transcript:

```java
record SessionMessage(
    String type,                         // "user" or "assistant"
    String uuid,                         // unique message UUID
    String sessionId,                    // session ID this message belongs to
    Object message,                      // raw Anthropic API message (Map with role/content)
    @Nullable String parentToolUseId,    // spawning Agent tool_use id (subagent reads only)
    @Nullable String parentAgentId       // spawning subagent id (nested subagents only)
)
```

Only top-level conversation messages are returned — tool-use sidechain messages, meta messages, and subagent messages are filtered out.

### How the conversation is rebuilt

A transcript is a tree: each entry names its parent in `parentUuid`, and a
resumed or edited conversation leaves branches. `getSessionMessages()` and
`getSessionMessagesFromStore()` rebuild the conversation the same way, matching
the Python SDK:

1. **Pick the leaf.** Each entry nothing points back to is walked back to its
   nearest user or assistant entry, which becomes a candidate leaf. The latest
   main-chain candidate (not a sidechain, meta or team entry) wins. Only when
   there is none does the latest candidate of any kind win. The `isSidechain`,
   `isMeta` and `teamName` flags are read the way Python reads truthiness, so an
   empty `teamName` is no team.
2. **Walk back** from the leaf through `parentUuid` to the root. An entry whose
   `uuid` is not a string is skipped, and a `parentUuid` that is not a non-empty
   string counts as no parent.
3. **Put parallel tool calls back.** The CLI writes a response with several
   parallel tool calls as one assistant entry per call, sharing `message.id`,
   and writes each result under its own call's entry. The transcript forks
   there, and the walk keeps only the branch the next turn continued from. The
   calls and results it skipped are put back right after the response's last
   entry on the chain: calls in timestamp order, then results in the order they
   completed. A result is matched to its call through its parent,
   `sourceToolAssistantUUID` or `tool_use_id`, never across agents, and never a
   second time for a call the chain already answers.

So a turn with parallel tool calls reads back whole: every call, and every
result after its call. `ParallelToolSessionReadsExample` runs such a turn
against the live CLI and checks both readers. Subagent transcripts are linear
and need none of this.

`parentToolUseId` and `parentAgentId` are always null for `getSessionMessages()` / `getSessionMessagesFromStore()` results. They are populated for `getSubagentMessages()` / `getSubagentMessagesFromStore()`: `parentToolUseId` is the id of the Agent `tool_use` block in the parent session that spawned the subagent, and `parentAgentId` names the spawning subagent when one subagent spawned another. Both come from the subagent's `agent-<agentId>.meta.json` sidecar (or, for store reads, the `agent_metadata` entry that stands in for it), so both are null when that metadata is missing or unusable. Every message in a given subagent transcript carries the same pair.

### Accessing Message Content

The `message` field is a raw `Map<String, Object>` matching the Anthropic API wire format:

```java
SessionMessage msg = ...;
if (msg.message() instanceof Map<?, ?> m) {
    Object content = m.get("content");
    if (content instanceof String text) {
        System.out.println(text);
    } else if (content instanceof List<?> blocks) {
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> b && b.get("text") instanceof String t) {
                System.out.println(t);
            }
        }
    }
}
```

## Listing Sessions

### All sessions

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions();
```

Returns all sessions across all projects, sorted by most-recently-modified first.

### Sessions for a project

```java
Path projectDir = Path.of("/my/project");
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(projectDir);
```

Filters to sessions whose working directory matches `projectDir`. The directory is canonicalized first (relative paths made absolute, symlinks resolved, Unicode NFC-normalized), so a symlinked, relative or NFD-spelled path finds the same sessions as the real path.

When `projectDir` is inside a git repository with more than one worktree, sessions from every worktree are included, the same as `listSessions(projectDir, null, true)`. Before 0.2.6 this overload read only `projectDir` itself. To keep that, pass `false` for `includeWorktrees`:

```java
List<SDKSessionInfo> thisDirOnly = ClaudeSDK.listSessions(projectDir, null, false);
```

### With limit and worktrees

```java
List<SDKSessionInfo> recent = ClaudeSDK.listSessions(
    Path.of("/my/project"),   // null for all projects
    10,                        // max 10 results
    true                       // include git worktrees
);
```

`includeWorktrees = true` runs `git worktree list` and includes sessions from all worktrees of the repository. A project directory is matched to a worktree by its exact name, so `/root/project` does not pick up the sessions of `/root/project-foo`. Prefix matching is used only for paths long enough that the CLI truncates the directory name and appends a hash.

### With offset pagination

```java
// Page 1: first 50 sessions
List<SDKSessionInfo> page1 = ClaudeSDK.listSessions(
    Path.of("/my/project"), 50, 0, true);

// Page 2: next 50 sessions
List<SDKSessionInfo> page2 = ClaudeSDK.listSessions(
    Path.of("/my/project"), 50, 50, true);
```

## Looking Up a Single Session

Use `getSessionInfo` to look up a single session by ID without scanning all session files. This is more efficient than `listSessions` when you already know the session UUID.

### By session ID (searches all projects)

```java
SDKSessionInfo info = ClaudeSDK.getSessionInfo("550e8400-e29b-41d4-a716-446655440000");
if (info != null) {
    System.out.println("Session: " + info.summary());
    if (info.tag() != null) {
        System.out.println("Tag: " + info.tag());
    }
    if (info.createdAt() != null) {
        String created = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(info.createdAt()));
        System.out.println("Created: " + created);
    }
}
```

### Scoped to a project directory

```java
SDKSessionInfo info = ClaudeSDK.getSessionInfo(
    "550e8400-e29b-41d4-a716-446655440000",
    Path.of("/my/project")
);
```

Returns `null` if the session is not found, is a sidechain session, or has no extractable summary.

With a directory, the lookup tries that directory's project first and then each of its git worktrees. A missing transcript or a 0-byte stub counts as "not here", so a stub left in one worktree does not hide the real session in another. `getSessionMessages`, `renameSession` and `tagSession` search the same way.

## Reading Session Messages

### Full transcript

```java
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(sessionId);
```

Searches all project directories for the session UUID.

### Scoped to a project

```java
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(
    sessionId,
    Path.of("/my/project")
);
```

### With pagination

```java
// Skip first 20 messages, return next 10
List<SessionMessage> page = ClaudeSDK.getSessionMessages(
    sessionId,
    null,    // all projects
    10,      // limit
    20       // offset
);
```

Paging is applied to the rebuilt conversation, offset first: the result is
messages `offset` to `offset + limit - 1`. A `limit` that is `null`, `0` or
negative means no limit. Every session and subagent reader, on disk and from a
`SessionStore`, pages this way, and messages put back from a parallel tool turn
count toward the limit.

## Renaming Sessions

Rename a session by appending a custom-title entry to its JSONL file. The most recent rename always wins — safe to call multiple times.

```java
// Rename by session ID (searches all projects)
ClaudeSDK.renameSession("550e8400-e29b-41d4-a716-446655440000", "My Feature Branch Session");

// Rename scoped to a specific project directory
ClaudeSDK.renameSession(
    "550e8400-e29b-41d4-a716-446655440000",
    "My Feature Branch Session",
    Path.of("/my/project")
);
```

**Constraints:**
- `sessionId` must be a valid UUID (hex with hyphens, either letter case).
- `title` must be non-empty after stripping leading/trailing whitespace.
- Throws `FileNotFoundException` if the session JSONL file is not found.
- Throws `IOException` if the file write fails.

**Where the entry is written.** With a directory, the SDK tries that
directory's project first, then the project of each of its git worktrees, as
`listSessions` and `getSessionMessages` do. A missing file or a 0-byte stub
means "not here, keep looking". The file is opened in append mode and is never
created, so the entry always lands at the current end of the file, even while a
running CLI is appending to the same transcript. Any other I/O error (permission
denied, disk full) is thrown rather than treated as "not found".

After renaming, `listSessions()` returns the new title in the `summary` and `customTitle` fields of `SDKSessionInfo`.

## Tagging Sessions

Tag a session for organizational filtering. Pass `null` to clear an existing tag. Tags are Unicode-sanitized before storage for CLI filter compatibility.

```java
// Tag a session (searches all projects)
ClaudeSDK.tagSession("550e8400-e29b-41d4-a716-446655440000", "production");

// Clear a tag
ClaudeSDK.tagSession("550e8400-e29b-41d4-a716-446655440000", null);

// Tag scoped to a specific project directory
ClaudeSDK.tagSession(
    "550e8400-e29b-41d4-a716-446655440000",
    "staging",
    Path.of("/my/project")
);
```

**Constraints:**
- `sessionId` must be a valid UUID.
- `tag` must be non-empty after Unicode sanitization and whitespace stripping (or `null` to clear).
- Tags with dangerous Unicode characters (zero-width chars, directional marks, private-use chars) are automatically sanitized.
- Throws `FileNotFoundException` if the session JSONL file is not found.
- Throws `IOException` if the file write fails.
- The entry is written the same way as a rename: worktree fallback, then an atomic append that never creates the file.

**Concurrent safety:** If the session is currently open in the CLI process, the CLI absorbs SDK-written entries into its cache on next metadata re-append. The most recent write wins.

## Deleting Sessions

Delete a session permanently by removing its JSONL file. The sibling `<sessionId>/` directory holding subagent transcripts is also recursively removed (best-effort; absent dir is fine).

```java
// Delete by session ID (searches all projects)
ClaudeSDK.deleteSession("550e8400-e29b-41d4-a716-446655440000");

// Delete scoped to a specific project directory
ClaudeSDK.deleteSession("550e8400-e29b-41d4-a716-446655440000", Path.of("/my/project"));
```

**Constraints:**
- `sessionId` must be a valid UUID.
- Throws `FileNotFoundException` if the session file is not found.
- For soft-delete semantics, use `tagSession(id, "__hidden")` and filter on listing instead.

## Reading Subagent Transcripts

When a session spawns subagents (via the `Task` tool or programmatic agent definitions), each subagent writes its own transcript to `~/.claude/projects/<project>/<sessionId>/subagents/agent-<agentId>.jsonl`. Subagent transcripts may also live in nested directories such as `subagents/workflows/<runId>/`.

```java
// Enumerate subagent IDs for a session
List<String> agentIds = ClaudeSDK.listSubagents(
    "550e8400-e29b-41d4-a716-446655440000");

// Or scoped to a specific project
List<String> agentIds = ClaudeSDK.listSubagents(
    "550e8400-e29b-41d4-a716-446655440000",
    Path.of("/my/project"));

// Read a subagent's full conversation
List<SessionMessage> messages = ClaudeSDK.getSubagentMessages(
    "550e8400-e29b-41d4-a716-446655440000",
    "abc123");

// With limit and offset
List<SessionMessage> page = ClaudeSDK.getSubagentMessages(
    "550e8400-e29b-41d4-a716-446655440000",
    "abc123",
    Path.of("/my/project"),
    50,    // limit (null or 0 = no limit)
    0);    // offset
```

**Behavior:**
- `listSubagents` recursively scans the `subagents/` tree for files matching `agent-<id>.jsonl` and returns the IDs in directory iteration order.
- `getSubagentMessages` walks `parentUuid` links from the leaf to reconstruct the chain. Subagent transcripts are linear (no compaction, no sidechains), so the returned list is the full conversation in chronological order.
- Corrupt JSONL lines are skipped silently.
- Invalid UUIDs, missing sessions, missing agents, and empty agent IDs all return an empty list (never throw).

## Forking Sessions

Fork a session into a new branch with fresh UUIDs. Copies transcript messages from the source session, remapping every message UUID and preserving the `parentUuid` chain. Forked sessions start without undo history.

```java
// Fork a session (searches all projects)
ForkSessionResult result = ClaudeSDK.forkSession("550e8400-e29b-41d4-a716-446655440000");
System.out.println("New session: " + result.sessionId());

// Fork scoped to a project directory
ForkSessionResult result = ClaudeSDK.forkSession(
    "550e8400-e29b-41d4-a716-446655440000",
    Path.of("/my/project")
);

// Fork from a specific message (truncate transcript)
ForkSessionResult result = ClaudeSDK.forkSession(
    "550e8400-e29b-41d4-a716-446655440000",
    null,                                       // search all projects
    "660e8400-e29b-41d4-a716-446655440001",    // slice transcript at this message
    "My Fork Title"                            // custom title (null = original + " (fork)")
);
```

**`ForkSessionResult`** contains:
- `sessionId` — UUID of the newly created forked session

**Constraints:**
- `sessionId` and optional `upToMessageId` must be valid UUIDs. An empty `upToMessageId` is the same as `null`: the whole transcript is copied.
- Throws `FileNotFoundException` if the source session is not found.
- Throws `IllegalArgumentException` if the session has no messages or `upToMessageId` is not found.

Without a `title`, the fork is named after the source's custom title, then its
AI title, then its [first prompt](#how-the-first-prompt-is-found), with
`" (fork)"` appended (`"Forked session (fork)"` when none exists). The new
transcript is created exclusively, and with mode `0600` (owner read/write only)
on file systems with POSIX permissions.

`forkSession()` produces an offline copy without running the CLI. To *resume*
from an earlier point and keep talking, use truncating resume instead.

## Truncating Resume

`resumeSessionAt` loads a resumed conversation only up to and including a given
transcript-entry UUID, so everything after it is dropped. Paired with
`forkSession(true)` it branches into a new session and leaves the original
untouched.

`resumeDropsTurn` makes that truncation **safe**. Give it the UUID of the user
prompt whose turn you intend to discard, and the CLI validates at load time
that every entry past the fork point belongs to that turn — refusing otherwise.
Without it, a queued user message or a background-task notification the session
absorbed mid-turn, which you never observed, would be silently discarded.

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
    .cwd(projectDir)
    .resume(sessionId)
    .forkSession(true)               // branch; leave the source session intact
    .resumeSessionAt(keepAtUuid)     // last transcript entry of the turn to keep
    .resumeDropsTurn(nextPromptUuid) // prompt UUID of the turn being discarded
    .build();

for (Message msg : ClaudeSDK.query("Reply with exactly: three", options)) {
    if (msg instanceof ResultMessage r) {
        System.out.println("Forked session: " + r.sessionId());
    }
}
```

**Choosing the two UUIDs.** Set `resumeSessionAt` to the *last* transcript entry
of the turn you are keeping — whatever its type — and `resumeDropsTurn` to the
prompt UUID of the turn immediately after it. Both are readable from
`ClaudeSDK.getSessionMessages(sessionId, cwd)`: find the entry you want to keep,
then take the `uuid()` of the next `SessionMessage` whose `type()` is `"user"`.
An `AssistantMessage.uuid()` observed live works as the fork point too.

Note that with structured output (`outputFormat`) or end-turn MCP tools, a kept
turn ends on entries *after* its last assistant message — so forking at the
assistant UUID is refused by design in those cases.

**Handling a refusal.** The refusal arrives as an exception whose message
contains `Resume rejected by --resume-drops-turn:`:

```java
try {
    ClaudeSDK.query(prompt, options);
} catch (ClaudeSDKException e) {
    if (String.valueOf(e.getMessage()).contains("Resume rejected by --resume-drops-turn:")) {
        // Deterministic — the transcript is not what we assumed.
        // Clear the fork target and resume plainly; do not retry as-is.
    }
}
```

Treat it as deterministic: retrying the identical request will fail identically.
Leave `resumeDropsTurn` unset to keep the older, unvalidated truncation
behavior.

Both options are also forwarded when resuming from a `SessionStore`. See
[`TruncatingResumeExample`](../examples/src/main/java/examples/TruncatingResumeExample.java)
for a runnable end-to-end demonstration.

## Examples

### Example 1: List recent sessions

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 5, true);

DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault());

for (SDKSessionInfo session : sessions) {
    String time = fmt.format(Instant.ofEpochMilli(session.lastModified()));
    System.out.printf("[%s] %s%n", time, session.summary());
    System.out.printf("  id:  %s%n", session.sessionId());
    if (session.cwd() != null) {
        System.out.printf("  cwd: %s%n", session.cwd());
    }
    if (session.gitBranch() != null) {
        System.out.printf("  git: %s%n", session.gitBranch());
    }
}
```

### Example 2: Sessions for current project

```java
Path cwd = Path.of(System.getProperty("user.dir"));
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(cwd);

System.out.printf("Found %d session(s) for: %s%n", sessions.size(), cwd);
for (SDKSessionInfo session : sessions) {
    String sizeStr = (session.fileSize() != null)
            ? String.format("%.1f KB", session.fileSize() / 1024.0) : "N/A";
    System.out.printf("  %s (%s)%n", session.summary(), sizeStr);
}
```

### Example 3: Read messages from most recent session

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 1, false);
if (sessions.isEmpty()) {
    System.out.println("No sessions found.");
    return;
}

SDKSessionInfo recent = sessions.get(0);
List<SessionMessage> messages = ClaudeSDK.getSessionMessages(recent.sessionId());

System.out.printf("Session: %s (%d messages)%n",
    recent.summary(), messages.size());

for (SessionMessage msg : messages) {
    System.out.printf("%n[%s]%n", msg.type().toUpperCase());
    if (msg.message() instanceof Map<?, ?> m) {
        Object content = m.get("content");
        if (content instanceof String text) {
            System.out.println(text);
        } else if (content instanceof List<?> blocks && !blocks.isEmpty()) {
            Object first = ((List<?>) blocks).get(0);
            if (first instanceof Map<?, ?> b && b.get("text") instanceof String t) {
                System.out.println(t);
            }
        }
    }
}
```

### Example 4: Find session by prompt keyword

```java
List<SDKSessionInfo> all = ClaudeSDK.listSessions();

List<SDKSessionInfo> matching = all.stream()
    .filter(s -> s.summary().toLowerCase().contains("refactor"))
    .toList();

System.out.println("Found " + matching.size() + " sessions about refactoring");
```

### Example 5: Rename the most recent session

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(null, 1, false);
if (!sessions.isEmpty()) {
    String sessionId = sessions.get(0).sessionId();
    ClaudeSDK.renameSession(sessionId, "Important: Production Bug Fix");
    System.out.println("Renamed session " + sessionId);
}
```

### Example 6: Tag sessions by project phase

```java
// Tag a session after a query completes, using the result's session ID
List<Message> messages = ClaudeSDK.query(prompt, options);
for (Message msg : messages) {
    if (msg instanceof ResultMessage result) {
        ClaudeSDK.tagSession(result.sessionId(), "sprint-42");
        break;
    }
}
```

### Example 7: Clear a tag

```java
// Retrieve a session and clear its tag
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions();
for (SDKSessionInfo session : sessions) {
    if ("old-tag".equals(session.customTitle())) {
        ClaudeSDK.tagSession(session.sessionId(), null);
    }
}
```

## Best Practices

### Use directory filtering when possible

```java
// Efficient: scoped to one project
ClaudeSDK.listSessions(Path.of("/my/project"));

// Less efficient: scans all projects
ClaudeSDK.listSessions();
```

### Check for empty results

```java
List<SDKSessionInfo> sessions = ClaudeSDK.listSessions(dir);
if (sessions.isEmpty()) {
    // No sessions yet — run Claude Code in this directory first
}
```

### Handle missing CLAUDE_CONFIG_DIR

By default, sessions are stored under `~/.claude/projects/`. If the environment variable `CLAUDE_CONFIG_DIR` is set, it overrides the location. The SDK respects this variable automatically, and NFC-normalizes it as it does project directories.

### Use limit to avoid large result sets

```java
// Return only the 20 most recent
List<SDKSessionInfo> recent = ClaudeSDK.listSessions(null, 20, false);
```

## See Also

- [API Reference: ClaudeSDK](./api-claude-sdk.md#session-history-methods) - Method signatures
- [Session Store](./feature-session-store.md) - External-store backed equivalents (`*FromStore`/`*ViaStore`) plus mirror-on-write integration
- [Session Listing Example](../examples/src/main/java/examples/SessionListingExample.java) - Complete runnable example
- [Interactive Conversations](./feature-interactive-conversations.md) - Managing live sessions
