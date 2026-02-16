---
name: pysdk-sync
description: Syncs Java SDK with Python SDK by pulling latest changes and porting all new functionality, tests, and examples since the last sync commit
---

# Claude Agent Python SDK Sync Skill

Synchronize the Java SDK with the latest changes from the Python SDK repository.

## Skill Purpose
Automatically detect and port new features, bug fixes, types, tests, and examples from the Python SDK to the Java SDK. This ensures the Java SDK maintains 100% feature parity with the Python SDK.

## Prerequisites
- Python SDK must be cloned at `../claude-agent-sdk-python`
- Git must be available
- Java SDK must have `docs/PYTHON_SDK_PARITY.md` file

## Process

### Phase 1: Repository Analysis and Preparation

1. **Verify Repository Structure**
   - Confirm current directory is the Java SDK repository
   - Verify `docs/PYTHON_SDK_PARITY.md` exists
   - Verify Python SDK exists at `../claude-agent-sdk-python`
   - If any verification fails, inform the user and exit

2. **Read Current Sync State**
   - Read `docs/PYTHON_SDK_PARITY.md`
   - Extract the current Python SDK version and commit hash from the "Python SDK Version:" line
   - Example: `**Python SDK Version:** [0.1.33](https://github.com/anthropics/claude-agent-sdk-python/commit/ea81d412923c3e4d6b94ba770ea452cdfba3f51a)`
   - Parse to get: version `0.1.33` and commit `ea81d412923c3e4d6b94ba770ea452cdfba3f51a`

3. **Update Python SDK Repository**
   - Navigate to `../claude-agent-sdk-python`
   - Run `git fetch origin`
   - Run `git pull origin main` to get latest changes
   - Get the latest commit hash: `git rev-parse HEAD`
   - Get the latest version from `pyproject.toml`
   - Return to Java SDK directory

4. **Compare Versions**
   - If the Python SDK commit hash matches the one in PYTHON_SDK_PARITY.md:
     - Inform user: "Already in sync with Python SDK version X.Y.Z (commit abc1234)"
     - Ask if they want to force a re-sync or exit
   - If different, continue with sync process

### Phase 2: Change Detection

5. **Identify Changed Files in Python SDK**
   - In Python SDK directory, run:
     ```bash
     git diff --name-status <last-sync-commit>..HEAD
     ```
   - Categorize changes:
     - **Source code changes**: Files in `claude_agent_sdk/` directory
     - **Test changes**: Files in `tests/` directory
     - **Example changes**: Files in `examples/` and `e2e-tests/` directories
     - **Documentation changes**: `README.md`, `CHANGELOG.md`, etc.
     - **Configuration changes**: `pyproject.toml`, etc.

6. **Extract Commit Messages**
   - Get all commit messages since last sync:
     ```bash
     git log --oneline <last-sync-commit>..HEAD
     ```
   - Identify patterns:
     - New features
     - Bug fixes
     - API changes
     - Breaking changes
     - CLI version updates

7. **Analyze Python SDK Changes**
   - For each changed source file in `claude_agent_sdk/`:
     - Read the file content
     - Identify what changed (new classes, methods, fields, types)
     - Note any new dependencies or imports
   - For each changed test file:
     - Identify new test cases or modified tests
   - For each changed example/e2e test:
     - Determine if it's a new example or modification

### Phase 3: Java SDK Porting

8. **Port Source Code Changes**
   - For each Python source code change:
     - Identify the equivalent Java file (follow package/class mapping)
     - If new class/type: Create new Java file with:
       - Appropriate package declaration
       - Idiomatic Java patterns (sealed interfaces, records, builders)
       - Proper nullability annotations (@Nullable, @NotNull)
       - Javadoc comments
     - If modified class: Update existing Java file
     - Follow Java naming conventions:
       - `snake_case` → `camelCase` for methods/fields
       - `TitleCase` for classes
       - Type mappings: `dict` → `Map`, `list` → `List`, `str` → `String`, etc.

9. **Port Type Definitions**
   - For new types in Python SDK:
     - Union types → sealed interfaces
     - dataclass → record
     - TypedDict → class or record
     - Literal → enum
     - Optional → @Nullable
     - Ensure all type fields are present

10. **Port Tests**
    - For each new or modified test in Python SDK:
      - Identify the equivalent Java test class
      - Port test cases to JUnit 5 format
      - Use AssertJ for assertions
      - Ensure async tests use virtual threads or CompletableFuture
      - Verify test names follow Java conventions (testMethodName)
      - Add tests to appropriate test class:
        - `IntegrationTest.java` for integration tests
        - `StreamingClientTest.java` for client tests
        - Type-specific test classes for type tests

11. **Port Examples**
    - For each new or modified example in Python SDK:
      - Determine if it's an e2e test or standalone example
      - Create new Java example class in `examples/` module
      - Follow naming convention: `ExampleNameExample.java`
      - Implement main method demonstrating the feature
      - Add proper error handling
      - Include comments explaining the feature
      - Update `examples/pom.xml` if new dependencies needed

12. **Update MCP Server Support**
    - If changes involve MCP servers or tools:
      - Update `sdk/src/main/java/in/vidyalai/claude/sdk/mcp/` classes
      - Ensure `@Tool` annotation support is current
      - Verify `SdkMcpServer` functionality

### Phase 4: Verification and Testing

13. **Build Java SDK**
    - Run from root directory:
      ```bash
      mvn clean compile -DskipTests
      ```
    - If compilation fails:
      - Fix compilation errors
      - Re-run compile
      - Continue until successful

14. **Run All Tests**
    - Run tests:
      ```bash
      mvn test -pl sdk
      ```
    - If tests fail:
      - Analyze failures
      - Fix issues in ported code
      - Re-run tests
      - Continue until all tests pass

15. **Verify Examples Compile**
    - Build examples module:
      ```bash
      mvn clean compile -pl examples -DskipTests
      ```
    - If compilation fails, fix issues and retry

16. **Cross-Reference Feature Parity**
    - Review `docs/PYTHON_SDK_PARITY.md` sections:
      - Core API Parity
      - Type System Parity
      - MCP Parity
      - Configuration Options Parity
      - Examples Parity
      - Test Coverage Parity
    - Verify all new features are accounted for
    - Check if any new categories need to be added

### Phase 5: Documentation Updates

17. **Update PYTHON_SDK_PARITY.md**
    - Update "Python SDK Version:" with new version and commit
    - Update "Analysis Date:" to current date
    - Update "Java SDK Version:" if needed
    - Add section documenting new changes:
      - List new features/changes from Python SDK
      - Mark them as ✅ implemented in Java SDK
      - Update parity sections as needed
    - Update "Recent Python SDK Updates" section with new version notes
    - Verify all parity percentages remain at 100%

18. **Update CHANGELOG.md**
    - Add new entry for the sync in `docs/CHANGELOG.md`
    - Format: `## [Unreleased]` or version section
    - Include:
      - Date of sync
      - Python SDK version synced to
      - List of new features/changes ported
      - Any breaking changes (if applicable)

19. **Update README.md** (only if needed)
    - If new major features were added that need README updates:
      - Add to features list
      - Add to examples section
      - Update usage examples if API changed
    - If no significant user-facing changes, skip README update

20. **Update CLAUDE.md** (only if needed)
    - If new examples were added:
      - Add Maven exec commands for new examples
      - Update examples list in "Running Examples" section
      - Sort alphabetically
    - If new build commands or workflows were added, document them
    - If no workflow changes, skip CLAUDE.md update

### Phase 6: Prepare changes for review

21. **Stage Changes**
    - Stage all modified/new files:
      - Source code in `sdk/src/main/java/`
      - Tests in `sdk/src/test/java/`
      - Examples in `examples/src/main/java/`
      - Documentation: `docs/PYTHON_SDK_PARITY.md`, `docs/CHANGELOG.md`
      - Only stage `README.md` and `CLAUDE.md` if they were updated
    - Do NOT stage any other files

22. **Do NOT Create Commit**
    - Changes will be reviewed and committed later

23. **Summary Report**
    - Provide detailed summary:
      - Python SDK version synced from → to
      - Commit hash range
      - Number of files changed in each category:
        - Source files added/modified
        - Test files added/modified
        - Examples added/modified
        - Documentation files updated
      - List of new features/APIs ported
      - Any issues encountered and resolutions
      - Test results (all passing)
      - Build status (successful)

## Guidelines

### Porting Principles

**Language Idioms:**
- Python `async/await` → Java virtual threads or `CompletableFuture`
- Python Union types → Java sealed interfaces
- Python `@dataclass` → Java `record`
- Python `TypedDict` → Java class with builder
- Python `Literal` → Java `enum`
- Python decorators → Java annotations
- Python duck typing → Java interfaces

**Naming Conventions:**
- `snake_case` → `camelCase` (methods, fields, variables)
- `SCREAMING_SNAKE_CASE` → `SCREAMING_SNAKE_CASE` (constants)
- Classes remain TitleCase
- Packages use lowercase

**Type Safety:**
- Use `@Nullable` for optional fields
- Use `@NotNull` for required fields
- Prefer immutable records over mutable classes
- Use sealed interfaces for exhaustive pattern matching

**Error Handling:**
- Python exceptions → Java exceptions (same hierarchy)
- Maintain exception names and messages
- Include all exception fields and metadata

### Verification Requirements

**Must Verify:**
- All tests pass
- All examples compile
- No compilation errors
- Feature parity documented
- 100% parity maintained

**Code Quality:**
- Follow existing code patterns in Java SDK
- Match indentation and formatting
- Add Javadoc for public APIs
- Include comments for complex logic

### Documentation Standards

**PYTHON_SDK_PARITY.md:**
- Must remain comprehensive and accurate
- All new features marked as ✅ implemented
- Maintain 100% parity status
- Update all relevant sections

**CHANGELOG.md:**
- Clear, concise change descriptions
- Grouped by category (Added, Changed, Fixed)
- Include Python SDK version reference

**Selective Updates:**
- Only update README.md if user-facing changes exist
- Only update CLAUDE.md if workflow changes exist
- Do NOT update other docs (will be handled separately)

### What NOT to Port

- Python-specific tooling (pytest configs, mypy, etc.)
- Python virtual environment files
- Python packaging files (setup.py, requirements.txt)
- Python SDK's internal implementation details that don't map to Java
- IPython or Trio-specific examples (Java doesn't have equivalents)

### Handling Edge Cases

**No Changes:**
- If already in sync, ask user if they want to:
  - Force re-verification
  - Exit
  - Check for unreported changes

**Breaking Changes:**
- If breaking changes detected in Python SDK:
  - Clearly document in CHANGELOG.md
  - Consider updating major version
  - Add migration guide comments in code

**Incomplete Port:**
- If some features cannot be ported (platform-specific):
  - Document in PYTHON_SDK_PARITY.md
  - Explain why in the assessment section
  - Mark as N/A instead of missing

**Test Failures:**
- Do NOT commit if tests fail
- Debug and fix issues
- If unfixable, document the issue and ask user for guidance

## Output Format

After completion, provide a detailed report:

```markdown
# Python SDK Sync Report

## Summary
- **Python SDK**: v0.1.33 (ea81d41) → v0.1.35 (a1b2c3d)
- **Date**: 2026-02-16
- **Status**: ✅ Complete

## Changes Ported

### Source Code
- ✅ Added `StreamEvent.partialMessage` field
- ✅ Implemented `ErrorHandler` class
- ✅ Updated `HookInput` types with new fields

### Tests
- ✅ Added test for partial message streaming
- ✅ Updated error handling tests
- ✅ Added hook field validation tests

### Examples
- ✅ Created `PartialMessageExample.java`
- ✅ Updated `ErrorHandlingExample.java`

## Verification
- ✅ Compilation: SUCCESS
- ✅ Tests: 47/47 passed
- ✅ Examples: All compile successfully
- ✅ Feature Parity: 100% maintained

## Documentation Updated
- ✅ `docs/PYTHON_SDK_PARITY.md`
- ✅ `docs/CHANGELOG.md`
- ✅ `CLAUDE.md` (new examples added)
- ⏭️ `README.md` (no changes needed)

## Next Steps
- Review the changes
- Run examples to verify functionality
- Consider creating a release
```

## Notes

- This skill should be run periodically to maintain parity
- Always verify tests pass before committing
- Focus on maintaining 100% feature parity
- Document any platform-specific limitations
- The skill handles all porting and documentation updates automatically
