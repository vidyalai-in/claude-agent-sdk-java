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
