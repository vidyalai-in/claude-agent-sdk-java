---
name: doc-gen
description: Generates comprehensive technical documentation for git repositories by analyzing code structure, features, and tests, then brings the docs/<lang>/ translations up to date with the English docs; supports incremental updates and auto-commits with "docgen:" prefix
---

# Documentation Generator Skill

Generate comprehensive technical documentation for the current git repository.

## Skill Purpose
Automatically scan the repository and create/update technical documentation based on actual code, tests, and examples. All documentation must be verified against the actual implementation to ensure accuracy. After the English documentation is committed, every translation under `docs/<lang>/` is synced to it (Phase 6), so a run never leaves the translated docs behind.

## Process

### Phase 1: Repository Analysis

1. **Verify Git Repository**
   - Confirm the current directory is a git repository
   - If not, inform the user and exit

2. **Check for Incremental Updates**
   - Search git history for the most recent commit with prefix "docgen: "
   - If found, get the commit hash and use it as the baseline
   - Only document changes made after this commit
   - If no "docgen: " commit exists, this is a full documentation generation

3. **Identify Changed Files** (for incremental updates)
   - Run `git diff --name-only <last-docgen-commit>..HEAD`
   - Focus documentation updates on these changed files and their dependencies

4. **Scan Repository Structure**
   - Identify programming language(s) used
   - Locate source code directories
   - Find test directories and files
   - Identify example/demo code
   - Find configuration files (package.json, requirements.txt, Cargo.toml, go.mod, etc.)
   - Identify entry points (main files, CLI definitions, API routes)

### Phase 2: Deep Code Analysis

5. **Understand Architecture**
   - Identify architectural patterns (MVC, microservices, monolith, library, etc.)
   - Map out major components/modules and their relationships
   - Identify core abstractions and design patterns
   - Document data flow and control flow
   - Note external dependencies and integrations

6. **Identify Features and Functionality**
   - Extract features from code organization (modules, packages, classes)
   - Analyze public APIs and exported functions
   - Review CLI commands or HTTP endpoints
   - Study tests to understand intended behavior
   - Group related functionality into logical features

7. **Extract Implementation Details**
   - For each feature:
     - What it does (purpose)
     - How it works (implementation approach)
     - Key functions/classes/modules involved
     - Dependencies and prerequisites
     - Usage examples from tests or example code
     - Configuration options
     - Edge cases and error handling

### Phase 3: Documentation Generation

8. **Create/Update docs Directory**
   - Create `docs/` in the repository root if it doesn't exist
   - Preserve existing documentation files
   - Author and update documentation in English only (files directly under
     `docs/`, and the repository `README.md`). The `docs/<lang>/` directories
     are translations of those files and are brought up to date in Phase 6; never
     use them as a source when generating English docs

9. **Generate Architecture Documentation**
   - Create/update `docs/architecture.md` with:
     - Overview of the system architecture
     - Component diagram (in markdown/mermaid if complex)
     - Technology stack
     - Key design decisions and patterns
     - Data models and schemas
     - External dependencies and integrations
     - File/directory structure explanation

10. **Generate Feature Documentation**
    - For each major feature/functionality, create/update `docs/feature-<name>.md`:
      - Feature name and purpose
      - How to use the feature (with code examples from tests/examples)
      - Key components and their roles
      - Configuration options
      - API reference (functions, parameters, return values)
      - Error handling and edge cases
      - Related features or dependencies

11. **Generate README**
    - Create/update `docs/README.md` with:
      - Project overview
      - Link to architecture documentation
      - List of all features with brief descriptions and links
      - Quick start guide
      - Common use cases

### Phase 4: Verification and Quality Assurance

12. **Verify Documentation Accuracy**
    - For each documented feature:
      - Re-read the actual implementation
      - Verify code examples are correct and will work
      - Confirm function signatures match actual code
      - Check that behavior descriptions match implementation
      - Validate configuration options exist
    - CRITICAL: Flag any discrepancies and correct them
    - NEVER include speculative or assumed information
    - If uncertain about something, read the code again or mark as [TODO: Verify]

13. **Check Documentation Completeness**
    - Ensure all major features are documented
    - Verify all public APIs are covered
    - Confirm examples are practical and useful
    - Ensure architecture matches actual code structure
    - Ensure NOTHING is marked as to be documented, in progress, planned etc. Complete ALL documentation

### Phase 5: Commit the English Documentation

14. **Stage English Documentation Files**
    - Stage the English documentation you changed: files directly under `docs/`
      and, if you touched it, the repository root `README.md`
    - Do NOT stage translations (`docs/<lang>/`) in this commit; they get their own
      commit in Phase 6
    - Do not stage other unrelated changes

15. **Create Commit**
    - Commit with message format: `docgen: <brief description of what was documented>`
    - Examples:
      - `docgen: Add architecture and feature documentation`
      - `docgen: Update authentication and API documentation`
      - `docgen: Document database layer and caching features`
    - End the message with the co-author line configured for the session (the
      `Co-Authored-By:` attribution the environment provides for the current
      model); never hard-code a model name that is not the one doing the work

### Phase 6: Sync Translations

This repository keeps translations of the documentation in `docs/<lang>/`
(`zh`, `ja`, `ko`, `pt`, `es` at the time of writing). English is authoritative,
but every docgen run must leave the translations up to date too. Read
`docs/TRANSLATIONS.md` before starting: it defines the file mapping, what is
and is not translated, and which documents are English-only.

16. **Find What Is Stale**
    - Run `./scripts/check-translations.sh` after the English commit. It is the
      source of truth: it lists every `STALE` and `MISSING` translation, for every
      language in its `LANGS` array (use that array, not a hard-coded list)
    - File mapping: repository `README.md` -> `docs/<lang>/README.md`;
      `docs/README.md` -> `docs/<lang>/index.md`; `docs/<name>.md` ->
      `docs/<lang>/<name>.md`
    - English-only documents (`NOT_TRANSLATED` in the script, plus
      `docs/TRANSLATIONS.md`) are never translated; the script reports a `STRAY`
      finding if one appears in a language directory
    - If the script reports nothing, skip to Phase 7

17. **Update Each Stale Translation**
    - For each stale translation, find the commit it was last synced at
      (`git log -1 --format=%H -- <translation>`) and diff its English source
      from there: `git diff <that-commit>..HEAD -- <english-source>`. Apply the
      equivalent change to the translation: translate added or changed prose,
      remove deleted content, and rewrite wholesale any section whose English was
      rewritten or renamed. Leave untouched the parts whose English did not
      change, so earlier human corrections to a translation survive
    - For a `MISSING` translation, translate the whole current English file
    - Follow `docs/TRANSLATIONS.md` exactly. Code blocks and their comments,
      identifiers, Maven coordinates, CLI flags, environment variable names, file
      paths and URLs are NOT translated, and code blocks must be byte-identical to
      the English source
    - Match the terminology, tone and (for `pt`) language variant of the existing
      files in that language directory, and follow the anchor-link convention those
      files already use so in-page and cross-file links resolve
    - The languages are independent, so work them in parallel: one subagent per
      language, each told to edit only `docs/<lang>/` and not to run any git
      write command. Give each the exact list of stale files, the baseline commit
      per file, and these rules

18. **Verify the Translations**
    - Every file the script listed must have been edited (check
      `git status docs/<lang>/`)
    - For every edited translation, check that each fenced code block also appears
      byte-identically in its English source, and that the heading count and order
      match the English file
    - Check that relative links and heading anchors in the edited files resolve
    - Spot-read at least one rewritten section per language against the English
      for meaning; machine output nobody looked at is not a translation

19. **Commit the Translations**
    - Stage only `docs/<lang>/` changes, in a separate commit after the English
      one: `docgen: Sync <languages> translations with <what changed>`, with the
      session's co-author line
    - Run `./scripts/check-translations.sh` again. It must report
      `All translations are up to date with their English sources.` If it does
      not, go back to step 17 for what it still lists

### Phase 7: Summary Report

20. **Summary Report**
    - List all documentation files created/updated, English and translated
    - Summarize what was documented
    - Report the translation status: which languages and files were synced, and
      the final `check-translations.sh` result
    - Note any areas that need manual review or additional information, including
      terminology you were unsure of in a translation
    - If incremental, show what changed since last docgen commit

## Guidelines

### Documentation Style
- Use clear, concise language
- Include practical code examples
- Use markdown formatting for readability
- Add mermaid diagrams for complex flows where helpful
- Link between related documentation files
- Keep a consistent structure across feature docs

### Accuracy Requirements
- NEVER hallucinate or make up information
- All code examples must be verified against actual code
- All function signatures must match implementation
- All behavior descriptions must match what the code actually does
- When in doubt, read the code again
- Mark uncertain areas with [TODO: Verify] rather than guessing

### Translations
- Every run ends with the translations in sync; English-only runs are
  incomplete. Phase 6 is not optional
- Translation staleness is tracked by git ancestry, not by content: a
  translation is stale when its last commit is older than its English source's.
  Only a commit that touches the translation clears it, so never "refresh" a
  translation without actually syncing its content

### Incremental Updates
- When a "docgen: " commit exists:
  - Focus on changed files and affected features
  - Update existing docs rather than recreating from scratch
  - Add new feature docs for new functionality
  - Remove docs for deleted features
  - Update architecture docs if structure changed

### What NOT to Document
- Internal implementation details that are likely to change frequently
- Obvious or trivial code
- Generated code (unless it's part of the public API)
- Third-party library documentation (just link to their docs)

## Notes
- First run will create full documentation
- Subsequent runs will be incremental based on changes
- Documentation should evolve with the codebase
- Review generated docs before pushing to ensure accuracy
