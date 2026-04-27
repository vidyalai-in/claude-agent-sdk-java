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
    @Nullable Long fileSize,       // session file size in bytes (null for remote storage backends)
    @Nullable String customTitle,  // user-set custom title or AI-generated title (may be null)
    @Nullable String firstPrompt,  // first meaningful user prompt (may be null)
    @Nullable String gitBranch,    // git branch at end of session (may be null)
    @Nullable String cwd,          // working directory for the session (may be null)
    @Nullable String tag,          // user-set session tag (may be null)
    @Nullable Long createdAt       // creation time in ms since epoch from first entry's ISO timestamp (may be null)
)
```

The `summary` field is resolved in priority order: custom title > AI title > lastPrompt > auto-generated summary > first prompt.

A backwards-compatible constructor without `tag` and `createdAt` is also available.

## Session Messages

`SessionMessage` holds a single message from a session transcript:

```java
record SessionMessage(
    String type,                         // "user" or "assistant"
    String uuid,                         // unique message UUID
    String sessionId,                    // session ID this message belongs to
    Object message,                      // raw Anthropic API message (Map with role/content)
    @Nullable String parentToolUseId     // null for top-level messages
)
```

Only top-level conversation messages are returned — tool-use sidechain messages, meta messages, and subagent messages are filtered out.

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

Filters to sessions whose working directory matches `projectDir`.

### With limit and worktrees

```java
List<SDKSessionInfo> recent = ClaudeSDK.listSessions(
    Path.of("/my/project"),   // null for all projects
    10,                        // max 10 results
    true                       // include git worktrees
);
```

`includeWorktrees = true` runs `git worktree list` and includes sessions from all worktrees of the repository.

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
- `sessionId` must be a valid UUID (lowercase hex with hyphens).
- `title` must be non-empty after stripping leading/trailing whitespace.
- Throws `FileNotFoundException` if the session JSONL file is not found.
- Throws `IOException` if the file write fails.

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
- `sessionId` and optional `upToMessageId` must be valid UUIDs.
- Throws `FileNotFoundException` if the source session is not found.
- Throws `IllegalArgumentException` if the session has no messages or `upToMessageId` is not found.

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

By default, sessions are stored under `~/.claude/projects/`. If the environment variable `CLAUDE_CONFIG_DIR` is set, it overrides the location. The SDK respects this variable automatically.

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
