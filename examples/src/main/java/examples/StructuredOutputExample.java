package examples;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;
import in.vidyalai.claude.sdk.ClaudeSDK;
import in.vidyalai.claude.sdk.types.message.Message;
import in.vidyalai.claude.sdk.types.message.ResultMessage;
import in.vidyalai.claude.sdk.types.permission.PermissionMode;

/**
 * Example demonstrating structured output with JSON Schema validation.
 *
 * <p>
 * Structured output allows you to constrain Claude's responses to a specific
 * JSON schema, ensuring consistent output format. This is useful for:
 * </p>
 * <ul>
 * <li>Data extraction and analysis</li>
 * <li>API integrations requiring specific JSON formats</li>
 * <li>Type-safe response parsing</li>
 * <li>Enum validation and constraints</li>
 * </ul>
 *
 * <p>
 * This example covers four scenarios:
 * </p>
 * <ol>
 * <li><b>Simple structured output</b> - Basic schema with required fields</li>
 * <li><b>Nested structured output</b> - Complex schemas with nested objects and
 * arrays</li>
 * <li><b>Enum constraints</b> - Restricting values to predefined options</li>
 * <li><b>Structured output with tools</b> - Combining schema validation with
 * tool use</li>
 * </ol>
 *
 * @see ClaudeAgentOptions.Builder#outputFormat(Map)
 * @see ResultMessage#structuredOutput()
 */
public class StructuredOutputExample {

    public static void main(String[] args) {
        System.out.println("=== Structured Output Examples ===\n");

        // Example 1: Simple structured output
        System.out.println("1. Simple Structured Output");
        System.out.println("   Schema: {file_count, has_tests, test_file_count}");
        simpleStructuredOutput();

        // Example 2: Nested structured output
        System.out.println("\n2. Nested Structured Output");
        System.out.println("   Schema: {analysis: {word_count, character_count}, words: []}");
        nestedStructuredOutput();

        // Example 3: Structured output with enum
        System.out.println("\n3. Structured Output with Enum Constraints");
        System.out.println("   Schema: {has_tests, test_framework: enum, test_count}");
        structuredOutputWithEnum();

        // Example 4: Structured output with tools
        System.out.println("\n4. Structured Output with Tool Usage");
        System.out.println("   Schema: {file_count, has_readme}");
        structuredOutputWithTools();

        System.out.println("\n=== All Examples Complete ===");
    }

    /**
     * Example 1: Simple structured output with basic schema.
     *
     * <p>
     * Demonstrates file counting with tool use, ensuring the response conforms to a
     * predefined schema with number and boolean fields.
     * </p>
     */
    static void simpleStructuredOutput() {
        // Define JSON schema for file analysis
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_count", Map.of("type", "number"),
                        "has_tests", Map.of("type", "boolean"),
                        "test_file_count", Map.of("type", "number")),
                "required", List.of("file_count", "has_tests"));

        // Configure output format with schema
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .outputFormat(Map.of(
                        "type", "json_schema",
                        "schema", schema))
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .cwd(Path.of("."))
                .maxTurns(5)
                .build();

        try {
            System.out.println("   Querying: Count Java files in sdk/src/main/java/...");

            // Query with structured output
            List<Message> messages = ClaudeSDK.query(
                    "Count how many Java files are in sdk/src/main/java/in/vidyalai/claude/sdk/ and check if there are any test files. Use tools to explore the filesystem.",
                    options);

            // Process response
            ResultMessage result = null;
            for (Message msg : messages) {
                if (msg instanceof ResultMessage r) {
                    result = r;
                }
            }

            // Verify structured output
            if (result != null && !result.isError()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) result.structuredOutput();

                if (output != null) {
                    System.out.println("   ✓ Structured output received:");
                    System.out.println("     - File count: " + output.get("file_count"));
                    System.out.println("     - Has tests: " + output.get("has_tests"));
                    if (output.containsKey("test_file_count")) {
                        System.out.println("     - Test file count: " + output.get("test_file_count"));
                    }

                    // Validate types
                    Object fileCount = output.get("file_count");
                    Object hasTests = output.get("has_tests");

                    if (fileCount instanceof Number && hasTests instanceof Boolean) {
                        System.out.println("   ✓ Schema validation successful!");
                    } else {
                        System.out.println("   ✗ Schema validation failed: incorrect types");
                    }
                } else {
                    System.out.println("   ✗ No structured output received");
                }
            } else {
                System.out.println("   ✗ Query failed: " + (result != null ? result.result() : "No result"));
            }
        } catch (Exception e) {
            System.err.println("   ✗ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Example 2: Nested structured output with complex schema.
     *
     * <p>
     * Demonstrates nested objects and arrays in the schema, showing how to validate
     * hierarchical data structures.
     * </p>
     */
    static void nestedStructuredOutput() {
        // Define schema with nested structure
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "analysis", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "word_count", Map.of("type", "number"),
                                        "character_count", Map.of("type", "number")),
                                "required", List.of("word_count", "character_count")),
                        "words", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"))),
                "required", List.of("analysis", "words"));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .outputFormat(Map.of(
                        "type", "json_schema",
                        "schema", schema))
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .maxTurns(3)
                .build();

        try {
            System.out.println("   Querying: Analyze text 'Hello world'...");

            List<Message> messages = ClaudeSDK.query(
                    "Analyze this text: 'Hello world'. Provide word count, character count, and list of words.",
                    options);

            ResultMessage result = null;
            for (Message msg : messages) {
                if (msg instanceof ResultMessage r) {
                    result = r;
                }
            }

            if (result != null && !result.isError()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) result.structuredOutput();

                if (output != null) {
                    System.out.println("   ✓ Structured output received:");

                    // Extract nested analysis object
                    @SuppressWarnings("unchecked")
                    Map<String, Object> analysis = (Map<String, Object>) output.get("analysis");
                    if (analysis != null) {
                        System.out.println("     - Analysis:");
                        System.out.println("       * Word count: " + analysis.get("word_count"));
                        System.out.println("       * Character count: " + analysis.get("character_count"));
                    }

                    // Extract words array
                    @SuppressWarnings("unchecked")
                    List<String> words = (List<String>) output.get("words");
                    if (words != null) {
                        System.out.println("     - Words: " + words);
                    }

                    System.out.println("   ✓ Nested schema validation successful!");
                } else {
                    System.out.println("   ✗ No structured output received");
                }
            } else {
                System.out.println("   ✗ Query failed: " + (result != null ? result.result() : "No result"));
            }
        } catch (Exception e) {
            System.err.println("   ✗ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Example 3: Structured output with enum constraints.
     *
     * <p>
     * Demonstrates using enums to restrict field values to a predefined set of
     * options, useful for classification tasks.
     * </p>
     */
    static void structuredOutputWithEnum() {
        // Define schema with enum constraint
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "has_tests", Map.of("type", "boolean"),
                        "test_framework", Map.of(
                                "type", "string",
                                "enum", List.of("junit", "testng", "pytest", "unittest", "unknown")),
                        "test_count", Map.of("type", "number")),
                "required", List.of("has_tests", "test_framework"));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .outputFormat(Map.of(
                        "type", "json_schema",
                        "schema", schema))
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .cwd(Path.of("."))
                .maxTurns(5)
                .build();

        try {
            System.out.println("   Querying: Detect test framework in sdk/src/test/...");

            List<Message> messages = ClaudeSDK.query(
                    "Search for test files in sdk/src/test/ directory. Determine which test framework is being used (junit/testng/pytest/unittest) and count how many test files exist. Use Grep to search for framework imports.",
                    options);

            ResultMessage result = null;
            for (Message msg : messages) {
                if (msg instanceof ResultMessage r) {
                    result = r;
                }
            }

            if (result != null && !result.isError()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) result.structuredOutput();

                if (output != null) {
                    System.out.println("   ✓ Structured output received:");
                    System.out.println("     - Has tests: " + output.get("has_tests"));
                    System.out.println("     - Test framework: " + output.get("test_framework"));
                    if (output.containsKey("test_count")) {
                        System.out.println("     - Test count: " + output.get("test_count"));
                    }

                    // Validate enum constraint
                    String framework = (String) output.get("test_framework");
                    List<String> validFrameworks = List.of("junit", "testng", "pytest", "unittest", "unknown");

                    if (validFrameworks.contains(framework)) {
                        System.out.println("   ✓ Enum constraint satisfied!");
                    } else {
                        System.out.println("   ✗ Enum constraint violated: " + framework);
                    }
                } else {
                    System.out.println("   ✗ No structured output received");
                }
            } else {
                System.out.println("   ✗ Query failed: " + (result != null ? result.result() : "No result"));
            }
        } catch (Exception e) {
            System.err.println("   ✗ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Example 4: Structured output combined with tool usage.
     *
     * <p>
     * Demonstrates how structured output works seamlessly with Claude's tool use,
     * ensuring the final response after tool execution conforms to the schema.
     * </p>
     */
    static void structuredOutputWithTools() {
        // Define schema for file analysis
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_count", Map.of("type", "number"),
                        "has_readme", Map.of("type", "boolean")),
                "required", List.of("file_count", "has_readme"));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .outputFormat(Map.of(
                        "type", "json_schema",
                        "schema", schema))
                .permissionMode(PermissionMode.ACCEPT_EDITS)
                .cwd(Path.of("."))
                .allowedTools(List.of("Bash", "Glob", "Read"))
                .maxTurns(5)
                .build();

        try {
            System.out.println("   Querying: Count files and check for README...");

            List<Message> messages = ClaudeSDK.query(
                    "Count how many files are in the current directory and check if there's a README.md file. Use tools as needed.",
                    options);

            ResultMessage result = null;
            for (Message msg : messages) {
                if (msg instanceof ResultMessage r) {
                    result = r;
                }
            }

            if (result != null && !result.isError()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) result.structuredOutput();

                if (output != null) {
                    System.out.println("   ✓ Structured output received:");
                    System.out.println("     - File count: " + output.get("file_count"));
                    System.out.println("     - Has README: " + output.get("has_readme"));

                    // Validate structured output after tool use
                    Object fileCount = output.get("file_count");
                    Object hasReadme = output.get("has_readme");

                    if (fileCount instanceof Number && hasReadme instanceof Boolean) {
                        Number count = (Number) fileCount;
                        if (count.doubleValue() >= 0) {
                            System.out.println("   ✓ Schema validation successful with tools!");
                        } else {
                            System.out.println("   ✗ Invalid file count: " + count);
                        }
                    } else {
                        System.out.println("   ✗ Schema validation failed: incorrect types");
                    }
                } else {
                    System.out.println("   ✗ No structured output received");
                }
            } else {
                System.out.println("   ✗ Query failed: " + (result != null ? result.result() : "No result"));
            }
        } catch (Exception e) {
            System.err.println("   ✗ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

}
