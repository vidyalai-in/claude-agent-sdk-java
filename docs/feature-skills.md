# Skills

The `skills` option on `ClaudeAgentOptions` is the single place to enable Claude Code skills for the main session. The SDK auto-wires `allowedTools` and `settingSources` so callers don't have to configure both manually.

> **What is a skill?** A skill is a reusable capability bundle installed at `.claude/skills/<name>/SKILL.md` (project scope) or `~/.claude/skills/<name>/SKILL.md` (user scope). Skills are invoked via the built-in `Skill` tool. See the Claude Code docs for skill authoring.

## Quick Start

```java
import in.vidyalai.claude.sdk.ClaudeAgentOptions;

// Mode 1 — enable every discovered skill
var options = ClaudeAgentOptions.builder()
    .skillsAll()
    .build();

// Mode 2 — enable only specific skills
var options = ClaudeAgentOptions.builder()
    .skills(List.of("commit", "review"))
    .build();

// Mode 3 — suppress every skill from the listing
var options = ClaudeAgentOptions.builder()
    .skills(List.of())
    .build();

// Mode 4 (default) — no SDK auto-configuration; CLI defaults apply
var options = ClaudeAgentOptions.builder().build();
```

## Modes

| Builder call | `allowedTools` injection | `settingSources` default | Initialize wire field |
|---|---|---|---|
| _omitted_ | none | none | omitted |
| `.skillsAll()` | adds bare `Skill` | `[user, project]` | omitted |
| `.skills(List.of("a", "b"))` | adds `Skill(a)`, `Skill(b)` | `[user, project]` | `["a", "b"]` |
| `.skills(List.of())` | none | `[user, project]` | `[]` |

Notes:

- **`null` ≠ skills off.** Omitting the option leaves CLI defaults intact. To suppress every skill from the model's listing, pass an empty list.
- **`"all"` and omitted are equivalent at the wire level.** Both omit the `skills` field on the initialize control request. The CLI treats omission as "no filter."
- **Empty list is sent on the wire.** `List.of()` becomes `"skills": []` in the initialize request, telling supporting CLIs to load no skills into the system prompt.

## How the Auto-Wiring Works

`SubprocessCLITransport.applySkillsDefaults()` builds the effective CLI flags before the subprocess is spawned. The original `ClaudeAgentOptions` is never mutated.

For `skillsAll()`:

- If `allowedTools` does not already contain `"Skill"`, the bare tool is appended.
- If `settingSources` is null, it defaults to `[USER, PROJECT]` so the CLI discovers installed skills.
- An explicit `settingSources(...)` always wins over the default.

For `skills(List.of(...))`:

- For each name `n`, append `Skill(n)` to `allowedTools` (deduplicated against existing entries).
- Same `settingSources` default behavior.

For `skills(List.of())`:

- `allowedTools` is unchanged.
- Same `settingSources` default behavior.

## Name Validation (0.1.22)

Names passed to `skills(List.of(...))` are validated before they are formatted into the CLI's `--allowedTools` value. Every rejection throws `IllegalArgumentException` at **connect time** — from `buildCommand()`, before the CLI subprocess is spawned.

Validation exists because `--allowedTools` is a single string that the CLI splits into permission rules on commas and spaces outside parentheses, and that tokenizer honors no escape sequences. Escaping exists only in the per-rule grammar, applied *after* splitting, so a name carrying a delimiter cannot be passed through reliably — what it tokenizes into depends on what surrounds it:

```java
// Before 0.1.22 this emitted --allowedTools "Skill(x),Bash(*)",
// silently granting the session unrestricted Bash.
ClaudeAgentOptions.builder()
    .skills(List.of("x),Bash(*"))
    .build();

// 0.1.22: IllegalArgumentException at connect()
// "Invalid skill name 'x),Bash(*': parentheses, commas, control characters,
//  and byte-order marks are not allowed. ..."
```

### Rejected shapes

| Shape | Example | Why |
|---|---|---|
| Parentheses or commas | `"x),Bash(*"`, `"a,b"`, `"()"` | rule delimiters — the injection vector above |
| Control characters | C0 (`\n`, `\t`, `\u0000`), DEL (`\u007F`), C1 (`\u0080`–`\u009F`) | never appear in a skill directory name |
| Byte-order mark | `\uFEFF` anywhere in the name | the CLI trims U+FEFF as whitespace; the rule would name a different skill |
| Empty or whitespace-only | `""`, `" "`, `"  \t "` | names nothing |
| Bare wildcard | `"*"` | use `skillsAll()` instead |
| Wildcard suffix | `"pdf:*"`, `"my skill *"` | list each skill by its exact name |
| Surrounding whitespace | `" pdf"`, `"pdf "` | can never match — the `Skill` tool trims the invoked name |
| Leading `/` | `"/commit"` | the option takes the canonical name, not the slash-command form |
| Consecutive backslashes | `"mid\\\\dle"` | the per-rule parser collapses them, so the rule would name a different skill |
| Trailing unpaired backslash | `"name\\"` | dangling escape |
| Unpaired surrogate | a lone `\ud800` | can never match a name the CLI discovered |

Only the first three rows are injection vectors. The rest tokenize cleanly but would build a rule that can never match the skill you named — they are rejected so that failure is loud at `connect()` rather than a skill silently missing from the session. String examples above are shown as Java source literals, so `"mid\\\\dle"` is a name containing two backslashes and `"dir\\sub"` (accepted) is one.

### Accepted names

Ordinary names are unaffected. All of these still build exactly the argv they did before:

```java
.skills(List.of(
    "pdf-tools",          // hyphens
    "my_skill.v2",        // underscores, dots
    "myplugin:pdf",       // plugin-qualified
    "skill with spaces",  // interior spaces are fine; only surrounding ones are not
    "dir\\sub",           // a single backslash
    "日本語スキル"          // non-ASCII
))
```

### Breaking changes

Two previously-accepted shapes now throw:

| Was | Old behavior | Now |
|---|---|---|
| `skills(List.of("*"))`, `skills(List.of("plugin:*"))` | built a wildcard rule | throws — use `skillsAll()`, or add a `Skill(...)` entry to `allowedTools` directly for prefix matching |
| `skills(List.of(" name"))`, `skills(List.of("/name"))` | built a rule that matched nothing, so the skill was **silently unavailable** | throws, naming the problem |

### Java-specific behavior

Two checks deliberately differ from the Python SDK, because the languages model strings differently:

- **Surrogates.** Python rejects every surrogate code point — sound there, since a Python `str` holds code points and an astral character is a single non-surrogate item, so any surrogate present is unpaired by construction. Java strings are UTF-16, where an astral character legitimately *is* a high/low pair. Java therefore rejects only **lone** surrogates; a name like `"𝕤kill"` is accepted.
- **Whitespace.** `String.strip()` follows `Character.isWhitespace`, which leaves the non-breaking spaces (U+00A0, U+2007, U+202F) that Python's `str.strip()` removes. The padding check unions it with `Character.isSpaceChar` so those are caught too. U+FEFF stays out of both and is rejected as an invalid character, exactly as in Python.

`skillsAll()` is not name-checked — there is no name to check — and `skills(List.of())` remains a valid no-op.

## Initialize Wire Protocol

Skills also flow over the SDK control protocol via `SDKControlInitializeRequest.skills`. Only an explicit list is sent; `"all"` and `null` both omit the field.

Older CLIs that don't recognize the `skills` initialize field ignore it — the auto-injected `allowedTools` entries are still respected.

> **Deprecation:** Passing the bare `"Skill"` token in `allowedTools(...)` (or in `AgentDefinition.tools`) is **deprecated**. Use `skillsAll()` / `skills(List.of(...))` instead — they configure everything needed (including allowing the `Skill` tool) and avoid drift between `allowedTools` and the wire-level `skills` field.

## Examples

### Mixing with explicit `allowedTools`

Skills augment, never replace, an existing allowlist:

```java
var options = ClaudeAgentOptions.builder()
    .allowedTools(List.of("Read", "Write"))
    .skills(List.of("commit"))
    .build();
// effective allowedTools: [Read, Write, Skill(commit)]
```

### Idempotent injection

If you've already added `Skill` or `Skill(name)` to your allowlist, the SDK does not duplicate it:

```java
var options = ClaudeAgentOptions.builder()
    .allowedTools(List.of("Skill(pdf)"))
    .skills(List.of("pdf"))
    .build();
// effective allowedTools: [Skill(pdf)]   (not [Skill(pdf), Skill(pdf)])
```

### Preserving explicit `settingSources`

```java
var options = ClaudeAgentOptions.builder()
    .skillsAll()
    .settingSources(List.of(SettingSource.LOCAL))
    .build();
// effective settingSources: [LOCAL]   (your value wins over the [USER, PROJECT] default)
```

## Security Note

The `skills` option is a **context filter, not a sandbox.** Unlisted skills are hidden from the model's skill listing and cannot be invoked via the `Skill` tool, but their files remain on disk — a session with `Read` or `Bash` can still access `.claude/skills/**` directly.

For hard isolation:

- Point `cwd` at a directory whose `.claude/skills/` contains only the desired subset, **or**
- Add permission deny rules for `Read`/`Bash` on skill paths.

Bundled skills and installed-plugin skills are discovered regardless of `settingSources`. The `skills` allowlist is the single mechanism that hides them from the model's listing.

**Do not store secrets in skill files.**

## Complete Example

See [`examples/SkillsExample.java`](../examples/src/main/java/examples/SkillsExample.java) for a runnable demonstration of all three modes.

```java
package examples;

import java.util.List;
import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;

public class SkillsExample {
    public static void main(String[] args) throws Exception {
        var options = ClaudeAgentOptions.builder()
            .skillsAll()
            .maxTurns(1)
            .build();
        ClaudeSDK.query("List the skills you have available.", options);
    }
}
```

## See Also

- [Configuration Options](./feature-configuration-options.md) — full builder API
- [Agent Definitions](./feature-agents.md) — `skills` field on `AgentDefinition` (per-subagent allowlist)
- [Session History](./feature-session-history.md) — read transcripts from disk
