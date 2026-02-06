---
name: doc-gen
description: Generates comprehensive technical documentation for git repositories by analyzing code structure, features, and tests; supports incremental updates and auto-commits with "docgen:" prefix
---

# Documentation Generator Skill

Generate comprehensive technical documentation for the current git repository.

## Skill Purpose
Automatically scan the repository and create/update technical documentation based on actual code, tests, and examples. All documentation must be verified against the actual implementation to ensure accuracy.

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

### Phase 5: Commit Documentation

14. **Stage Documentation Files**
    - Stage all files in the `docs/` directory
    - Do not stage other unrelated changes

15. **Create Commit**
    - Commit with message format: `docgen: <brief description of what was documented>`
    - Examples:
      - `docgen: Add architecture and feature documentation`
      - `docgen: Update authentication and API documentation`
      - `docgen: Document database layer and caching features`
    - Include co-author tag: `Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>`

16. **Summary Report**
    - List all documentation files created/updated
    - Summarize what was documented
    - Note any areas that need manual review or additional information
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
