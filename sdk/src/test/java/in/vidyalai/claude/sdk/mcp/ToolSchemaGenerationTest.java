package in.vidyalai.claude.sdk.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Generated {@code inputSchema}s, and calls through {@code tools/call} that
 * prove the published contract is one the arguments can actually meet.
 *
 * <p>
 * A schema that looks right is not enough: every {@code List}, enum, array
 * and {@code char} parameter used to be published with a schema no valid
 * argument could pass ({@code items: {type: object}}, {@code {type: object}}),
 * so the tool could not be called at all. Every type here is called for real.
 */
@SuppressWarnings("null")
class ToolSchemaGenerationTest {

    private static final long TIMEOUT_SECONDS = 5;

    enum Color {
        RED, GREEN, BLUE
    }

    record Address(
            @ToolParam(description = "Street and number") String street,
            String city,
            Optional<String> postcode) {
    }

    record Order(String item, int quantity, List<Address> deliverTo) {
    }

    record Box<T>(T value) {
    }

    record Node(String name, List<Node> children) {
    }

    record Positive(int value) {
        Positive {
            if (value <= 0) {
                throw new IllegalArgumentException("value must be positive, got " + value);
            }
        }
    }

    /** Records what each method received, by parameter name. */
    static final class TypedTools {

        final AtomicReference<Map<String, Object>> seen = new AtomicReference<>();

        private ToolResult record(Object... namesAndValues) {
            Map<String, Object> values = new HashMap<>();
            for (int i = 0; i < namesAndValues.length; i += 2) {
                values.put((String) namesAndValues[i], namesAndValues[i + 1]);
            }
            seen.set(values);
            return ToolResult.text("ok");
        }

        @Tool(name = "collections", description = "Lists, sets and arrays")
        public ToolResult collections(List<String> tags, Set<Color> colors, int[] counts,
                String[] names, List<List<Integer>> grid) {
            return record("tags", tags, "colors", colors, "counts", counts, "names", names, "grid", grid);
        }

        @Tool(name = "scalars", description = "Enums, chars and value types")
        public ToolResult scalars(Color color, char initial, Character grade, UUID id, LocalDate day,
                Instant at, Path file) {
            return record("color", color, "initial", initial, "grade", grade, "id", id, "day", day,
                    "at", at, "file", file);
        }

        @Tool(name = "optionals", description = "Optional parameters")
        public ToolResult optionals(String query, Optional<Integer> limit, Optional<Color> color) {
            return record("query", query, "limit", limit, "color", color);
        }

        @Tool(name = "maps", description = "Typed maps")
        public ToolResult maps(Map<String, Integer> scores, Map<Color, List<String>> byColor) {
            return record("scores", scores, "byColor", byColor);
        }

        @Tool(name = "records", description = "Nested records")
        public ToolResult records(Order order, Box<Color> box, Node tree) {
            return record("order", order, "box", box, "tree", tree);
        }

        @Tool(name = "validated", description = "A record that validates itself")
        public ToolResult validated(Positive amount) {
            return record("amount", amount);
        }

        @Tool(name = "described", description = "Described parameters")
        public ToolResult described(
                @ToolParam(description = "What to look for") String query,
                @ToolParam(name = "max-results", description = "At most this many", required = false)
                Integer maxResults,
                @ToolParam(required = false) int page) {
            return record("query", query, "maxResults", maxResults, "page", page);
        }

        @Tool(name = "throws_bare", description = "Throws without a message")
        public ToolResult throwsBare() {
            throw new IllegalStateException();
        }

        @Tool(name = "throws_message", description = "Throws with a message")
        public ToolResult throwsMessage() {
            throw new IllegalStateException("the disk is full");
        }
    }

    // --- harness

    private static Map<String, Object> call(SdkMcpServer server, String tool, Map<String, Object> args)
            throws Exception {
        return server.handleMessage(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", args)))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultOf(Map<String, Object> response) {
        assertThat(response).as("expected a result, got: %s", response).containsKey("result");
        return (Map<String, Object>) response.get("result");
    }

    @SuppressWarnings("unchecked")
    private static String textOf(Map<String, Object> result) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        return String.valueOf(content.get(0).get("text"));
    }

    private static String okCall(SdkMcpServer server, String tool, Map<String, Object> args) throws Exception {
        Map<String, Object> result = resultOf(call(server, tool, args));
        assertThat(result).as("call to %s failed: %s", tool, result).doesNotContainEntry("isError", true);
        return textOf(result);
    }

    private static String failedCall(SdkMcpServer server, String tool, Map<String, Object> args)
            throws Exception {
        Map<String, Object> result = resultOf(call(server, tool, args));
        assertThat(result).as("call to %s should fail: %s", tool, result).containsEntry("isError", true);
        return textOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaOf(SdkMcpServer server, String tool) throws Exception {
        Map<String, Object> response = server.handleMessage(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "tools/list")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        List<Map<String, Object>> tools = (List<Map<String, Object>>) resultOf(response).get("tools");
        return (Map<String, Object>) tools.stream()
                .filter(t -> tool.equals(t.get("name")))
                .findFirst()
                .orElseThrow()
                .get("inputSchema");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Map<String, Object> schema, String name) {
        return (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get(name);
    }

    private static Map<String, Object> args(Object... namesAndValues) {
        Map<String, Object> args = new HashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            args.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return args;
    }

    // --- #6: collections, enums, chars, value types

    @Test
    void collectionsGetTheirItemSchemasAndCanBeCalled() throws Exception {
        TypedTools tools = new TypedTools();
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("t", tools);
        Map<String, Object> schema = schemaOf(server, "collections");

        assertThat(property(schema, "tags"))
                .isEqualTo(Map.of("type", "array", "items", Map.of("type", "string")));
        assertThat(property(schema, "colors")).isEqualTo(Map.of("type", "array",
                "items", Map.of("type", "string", "enum", List.of("RED", "GREEN", "BLUE"))));
        assertThat(property(schema, "counts"))
                .isEqualTo(Map.of("type", "array", "items", Map.of("type", "integer")));
        assertThat(property(schema, "grid")).isEqualTo(Map.of("type", "array",
                "items", Map.of("type", "array", "items", Map.of("type", "integer"))));

        okCall(server, "collections", args(
                "tags", List.of("a", "b"),
                "colors", List.of("RED", "BLUE"),
                "counts", List.of(1, 2, 3),
                "names", List.of("x"),
                "grid", List.of(List.of(1, 2), List.of(3))));

        Map<String, Object> seen = tools.seen.get();
        assertThat(seen.get("tags")).isEqualTo(List.of("a", "b"));
        assertThat(seen.get("colors")).isEqualTo(Set.of(Color.RED, Color.BLUE));
        assertThat((int[]) seen.get("counts")).containsExactly(1, 2, 3);
        assertThat((String[]) seen.get("names")).containsExactly("x");
        assertThat(seen.get("grid")).isEqualTo(List.of(List.of(1, 2), List.of(3)));
    }

    @Test
    void scalarsAndValueTypesAreStringsTheToolCanReceive() throws Exception {
        TypedTools tools = new TypedTools();
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("t", tools);
        Map<String, Object> schema = schemaOf(server, "scalars");

        assertThat(property(schema, "color"))
                .isEqualTo(Map.of("type", "string", "enum", List.of("RED", "GREEN", "BLUE")));
        assertThat(property(schema, "initial"))
                .isEqualTo(Map.of("type", "string", "minLength", 1, "maxLength", 1));
        assertThat(property(schema, "grade")).isEqualTo(property(schema, "initial"));
        assertThat(property(schema, "id")).isEqualTo(Map.of("type", "string", "format", "uuid"));
        assertThat(property(schema, "day")).isEqualTo(Map.of("type", "string", "format", "date"));
        assertThat(property(schema, "at")).isEqualTo(Map.of("type", "string", "format", "date-time"));
        assertThat(property(schema, "file")).isEqualTo(Map.of("type", "string"));

        UUID id = UUID.randomUUID();
        okCall(server, "scalars", args(
                "color", "GREEN", "initial", "x", "grade", "A", "id", id.toString(),
                "day", "2026-11-16", "at", "2026-11-16T10:15:30Z", "file", "/tmp/x.txt"));

        Map<String, Object> seen = tools.seen.get();
        assertThat(seen.get("color")).isEqualTo(Color.GREEN);
        assertThat(seen.get("initial")).isEqualTo('x');
        assertThat(seen.get("grade")).isEqualTo('A');
        assertThat(seen.get("id")).isEqualTo(id);
        assertThat(seen.get("day")).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(seen.get("at")).isEqualTo(Instant.parse("2026-11-16T10:15:30Z"));
        assertThat(seen.get("file")).isEqualTo(Path.of("/tmp/x.txt"));
    }

    @Test
    void anEnumOrCharOutsideTheSchemaIsRejectedBeforeTheMethodRuns() throws Exception {
        TypedTools tools = new TypedTools();
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("t", tools);
        Map<String, Object> valid = args(
                "color", "GREEN", "initial", "x", "grade", "A", "id", UUID.randomUUID().toString(),
                "day", "2026-11-16", "at", "2026-11-16T10:15:30Z", "file", "f");

        Map<String, Object> badColor = new HashMap<>(valid);
        badColor.put("color", "PURPLE");
        assertThat(failedCall(server, "scalars", badColor)).startsWith("Input validation error:").contains("color");

        Map<String, Object> badChar = new HashMap<>(valid);
        badChar.put("initial", "xy");
        assertThat(failedCall(server, "scalars", badChar)).startsWith("Input validation error:")
                .contains("initial");

        assertThat(tools.seen.get()).isNull();
    }

    @Test
    void optionalParametersAreNotRequiredAndArriveEmptyWhenLeftOut() throws Exception {
        TypedTools tools = new TypedTools();
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("t", tools);
        Map<String, Object> schema = schemaOf(server, "optionals");

        assertThat(schema.get("required")).isEqualTo(List.of("query"));
        assertThat(property(schema, "limit")).isEqualTo(Map.of("type", "integer"));

        okCall(server, "optionals", args("query", "q"));
        assertThat(tools.seen.get().get("limit")).isEqualTo(Optional.empty());
        assertThat(tools.seen.get().get("color")).isEqualTo(Optional.empty());

        okCall(server, "optionals", args("query", "q", "limit", 5, "color", "RED"));
        assertThat(tools.seen.get().get("limit")).isEqualTo(Optional.of(5));
        assertThat(tools.seen.get().get("color")).isEqualTo(Optional.of(Color.RED));
    }

    @Test
    void typedMapsDescribeTheirValuesAndConvertKeysAndValues() throws Exception {
        TypedTools tools = new TypedTools();
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("t", tools);
        Map<String, Object> schema = schemaOf(server, "maps");

        assertThat(property(schema, "scores"))
                .isEqualTo(Map.of("type", "object", "additionalProperties", Map.of("type", "integer")));
        assertThat(property(schema, "byColor")).containsEntry("propertyNames",
                Map.of("type", "string", "enum", List.of("RED", "GREEN", "BLUE")));

        okCall(server, "maps", args(
                "scores", Map.of("ann", 3),
                "byColor", Map.of("RED", List.of("apple"))));
        assertThat(tools.seen.get().get("scores")).isEqualTo(Map.of("ann", 3));
        assertThat(tools.seen.get().get("byColor")).isEqualTo(Map.of(Color.RED, List.of("apple")));

        assertThat(failedCall(server, "maps", args(
                "scores", Map.of("ann", "three"), "byColor", Map.of())))
                .startsWith("Input validation error:");
    }

    @Test
    void recordsBecomeNestedObjectsAndAreBuiltFromTheirArguments() throws Exception {
        TypedTools tools = new TypedTools();
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("t", tools);
        Map<String, Object> schema = schemaOf(server, "records");

        Map<String, Object> order = property(schema, "order");
        assertThat(order).containsEntry("type", "object")
                .containsEntry("required", List.of("item", "quantity", "deliverTo"));
        Map<String, Object> address = property(order, "deliverTo");
        @SuppressWarnings("unchecked")
        Map<String, Object> addressItem = (Map<String, Object>) address.get("items");
        assertThat(addressItem.get("required")).isEqualTo(List.of("street", "city"));
        assertThat(property(addressItem, "street"))
                .isEqualTo(Map.of("type", "string", "description", "Street and number"));
        // A generic record is resolved against its type argument.
        assertThat(property(property(schema, "box"), "value"))
                .isEqualTo(Map.of("type", "string", "enum", List.of("RED", "GREEN", "BLUE")));
        // A recursive record terminates.
        assertThat(property(schema, "tree")).containsEntry("type", "object");

        okCall(server, "records", args(
                "order", Map.of("item", "tea", "quantity", 2, "deliverTo", List.of(
                        Map.of("street", "1 Main St", "city", "Pune"),
                        Map.of("street", "2 Side St", "city", "Goa", "postcode", "403001"))),
                "box", Map.of("value", "BLUE"),
                "tree", Map.of("name", "root", "children", List.of(
                        Map.of("name", "leaf", "children", List.of())))));

        Map<String, Object> seen = tools.seen.get();
        assertThat(seen.get("order")).isEqualTo(new Order("tea", 2, List.of(
                new Address("1 Main St", "Pune", Optional.empty()),
                new Address("2 Side St", "Goa", Optional.of("403001")))));
        assertThat(seen.get("box")).isEqualTo(new Box<>(Color.BLUE));
        assertThat(seen.get("tree")).isEqualTo(
                new Node("root", List.of(new Node("leaf", List.of()))));

        assertThat(failedCall(server, "records", args(
                "order", Map.of("item", "tea", "deliverTo", List.of()),
                "box", Map.of("value", "BLUE"),
                "tree", Map.of("name", "r", "children", List.of()))))
                .startsWith("Input validation error:").contains("quantity");
    }

    @Test
    void aRecordThatRejectsItsValueReportsWhy() throws Exception {
        TypedTools tools = new TypedTools();
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("t", tools);

        assertThat(failedCall(server, "validated", args("amount", Map.of("value", -1))))
                .contains("amount")
                .contains("value must be positive, got -1");
    }

    // --- #32: @ToolParam

    @Test
    void toolParamDescribesNamesAndRelaxesParameters() throws Exception {
        TypedTools tools = new TypedTools();
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("t", tools);
        Map<String, Object> schema = schemaOf(server, "described");

        assertThat(property(schema, "query"))
                .isEqualTo(Map.of("type", "string", "description", "What to look for"));
        assertThat(property(schema, "max-results"))
                .isEqualTo(Map.of("type", "integer", "description", "At most this many"));
        assertThat(property(schema, "page")).isEqualTo(Map.of("type", "integer"));
        assertThat(schema.get("required")).isEqualTo(List.of("query"));

        okCall(server, "described", args("query", "q"));
        assertThat(tools.seen.get().get("maxResults")).isNull();
        assertThat(tools.seen.get().get("page")).isEqualTo(0);

        okCall(server, "described", args("query", "q", "max-results", 7, "page", 2));
        assertThat(tools.seen.get().get("maxResults")).isEqualTo(7);
        assertThat(tools.seen.get().get("page")).isEqualTo(2);
    }

    // --- #35: exceptions from an annotated method

    @Test
    void anAnnotatedMethodThatThrowsReportsTheRealException() throws Exception {
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("t", new TypedTools());

        // Used to be "NullPointerException": ToolResult.error(null) blew up
        // building the error for an exception with no message.
        assertThat(failedCall(server, "throws_bare", Map.of())).isEqualTo("IllegalStateException");
        assertThat(failedCall(server, "throws_message", Map.of())).isEqualTo("the disk is full");
    }

    // --- #33: the {name: type} shorthand

    @Test
    void aShorthandMapExpandsToAnObjectWithEveryKeyRequired() throws Exception {
        AtomicReference<Map<String, Object>> seen = new AtomicReference<>();
        Map<String, Object> shorthand = new java.util.LinkedHashMap<>();
        shorthand.put("text", String.class);
        shorthand.put("count", int.class);
        shorthand.put("tags", new TypeReference<List<String>>() {
        });
        shorthand.put("color", Color.class);
        SdkMcpTool<Map<String, Object>> echo = SdkMcpTool.create("echo", "Echo", shorthand,
                args -> {
                    seen.set(args);
                    return CompletableFuture.completedFuture(ToolResult.text("ok"));
                });
        SdkMcpServer server = SdkMcpServer.create("t", List.of(echo));

        Map<String, Object> schema = schemaOf(server, "echo");
        assertThat(schema).containsEntry("type", "object")
                .containsEntry("required", List.of("text", "count", "tags", "color"));
        assertThat(property(schema, "text")).isEqualTo(Map.of("type", "string"));
        assertThat(property(schema, "count")).isEqualTo(Map.of("type", "integer"));
        assertThat(property(schema, "tags"))
                .isEqualTo(Map.of("type", "array", "items", Map.of("type", "string")));
        assertThat(property(schema, "color")).containsEntry("enum", List.of("RED", "GREEN", "BLUE"));

        assertThat(failedCall(server, "echo", args("text", "hi"))).startsWith("Input validation error:");
        assertThat(seen.get()).isNull();
        okCall(server, "echo", args("text", "hi", "count", 1, "tags", List.of("a"), "color", "RED"));
        assertThat(seen.get()).containsEntry("text", "hi");
    }

    @Test
    void aMapWhoseValuesAreNotAllTypesIsPublishedVerbatim() throws Exception {
        // Only a map of Java types is the shorthand. Valid schemas with no
        // top-level "type" must not be rewritten into arguments named after
        // their keywords.
        Map<String, Object> propertiesOnly = Map.of(
                "properties", Map.of("a", Map.of("type", "string")),
                "required", List.of("a"));
        Map<String, Object> anyOf = Map.of("anyOf", List.of(
                Map.of("type", "object", "properties", Map.of("a", Map.of("type", "string"))),
                Map.of("type", "object", "properties", Map.of("b", Map.of("type", "integer")))));
        Map<String, Object> mixed = new HashMap<>();
        mixed.put("text", "string");
        for (Map<String, Object> declared : List.of(propertiesOnly, anyOf, mixed, Map.<String, Object>of("text", "string"))) {
            SdkMcpTool<Map<String, Object>> tool = SdkMcpTool.create("t", "T", declared,
                    args -> CompletableFuture.completedFuture(ToolResult.text("ok")));
            // Published as written, plus the "type": "object" MCP requires.
            Map<String, Object> expected = new HashMap<>(declared);
            expected.put("type", "object");
            assertThat(schemaOf(SdkMcpServer.create("s", List.of(tool)), "t")).isEqualTo(expected);
        }
        // A schema that has a type keeps it as written.
        Map<String, Object> typed = Map.of("type", "object", "properties", Map.of());
        SdkMcpTool<Map<String, Object>> typedTool = SdkMcpTool.create("t", "T", typed,
                args -> CompletableFuture.completedFuture(ToolResult.text("ok")));
        assertThat(schemaOf(SdkMcpServer.create("s", List.of(typedTool)), "t")).isEqualTo(typed);
        SdkMcpTool<Map<String, Object>> tool = SdkMcpTool.create("t", "T", propertiesOnly,
                args -> CompletableFuture.completedFuture(ToolResult.text(String.valueOf(args.get("a")))));
        SdkMcpServer server = SdkMcpServer.create("s", List.of(tool));
        assertThat(okCall(server, "t", args("a", "hi"))).isEqualTo("hi");
        assertThat(failedCall(server, "t", Map.of())).startsWith("Input validation error:");
    }

    @Test
    void aFullSchemaIsPublishedVerbatim() throws Exception {
        Map<String, Object> jsonSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string", "minLength", 1),
                        "age", Map.of("type", "integer", "minimum", 0)),
                "required", List.of("name"));
        SdkMcpTool<Map<String, Object>> tool = SdkMcpTool.create("validate", "Validate", jsonSchema,
                args -> CompletableFuture.completedFuture(ToolResult.text("OK")));

        assertThat(schemaOf(SdkMcpServer.create("t", List.of(tool)), "validate")).isEqualTo(jsonSchema);
    }

    @Test
    void aStringTypeWithoutPropertiesStaysTheOpenObjectSchemaItIs() throws Exception {
        // Deliberately not Python's rule, which would read this as one
        // required string argument named "type".
        SdkMcpTool<Map<String, Object>> tool = SdkMcpTool.create("open", "Open", Map.of("type", "object"),
                args -> CompletableFuture.completedFuture(ToolResult.text("ok")));
        SdkMcpServer server = SdkMcpServer.create("t", List.of(tool));

        assertThat(schemaOf(server, "open")).isEqualTo(Map.of("type", "object"));
        assertThat(okCall(server, "open", args("anything", 1))).isEqualTo("ok");
    }

    @Test
    void anEmptyShorthandIsAnObjectWithNoProperties() throws Exception {
        SdkMcpTool<Map<String, Object>> tool = SdkMcpTool.create("none", "No args", Map.of(),
                args -> CompletableFuture.completedFuture(ToolResult.text("ok")));

        assertThat(schemaOf(SdkMcpServer.create("t", List.of(tool)), "none")).isEqualTo(Map.of(
                "type", "object", "properties", Map.of(), "required", List.of()));
    }

    // --- #7: a handler that answers with nothing

    @Test
    void aFutureCompletingWithNullIsAnsweredWithAnError() throws Exception {
        SdkMcpTool<Map<String, Object>> tool = SdkMcpTool.create("nothing", "Returns null", Map.of(),
                args -> CompletableFuture.completedFuture(null));

        // Used to never answer: toolResult.toMap() threw inside whenComplete.
        assertThat(failedCall(SdkMcpServer.create("t", List.of(tool)), "nothing", Map.of()))
                .isEqualTo("Tool 'nothing' completed with no result");
    }

    @Test
    void aFutureCompletingWithNullLaterIsAnsweredWithAnError() throws Exception {
        CompletableFuture<ToolResult> later = new CompletableFuture<>();
        SdkMcpTool<Map<String, Object>> tool = SdkMcpTool.create("nothing", "Returns null", Map.of(),
                args -> later);
        SdkMcpServer server = SdkMcpServer.create("t", List.of(tool));

        CompletableFuture<@Nullable Map<String, Object>> response = server.handleMessage(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", "nothing", "arguments", Map.of())));
        later.complete(null);

        Map<String, Object> result = resultOf(response.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThat(result).containsEntry("isError", true);
    }

    @Test
    void aHandlerReturningANullFutureIsAnsweredWithAnError() throws Exception {
        SdkMcpTool<Map<String, Object>> tool = SdkMcpTool.create("nothing", "Returns no future", Map.of(),
                args -> null);

        assertThat(failedCall(SdkMcpServer.create("t", List.of(tool)), "nothing", Map.of()))
                .isEqualTo("Tool 'nothing' returned no result");
    }

    // --- #34: malformed content

    @Test
    void aMalformedContentBlockIsAnErrorResult() throws Exception {
        // Python: {"content": [{"type": "text"}]} gives isError naming 'text'.
        SdkMcpTool<Map<String, Object>> sloppy = SdkMcpTool.create("sloppy", "Forgets the text key", Map.of(),
                args -> CompletableFuture.completedFuture(
                        ToolResult.ofContent(List.of(Map.of("type", "text")))));
        SdkMcpTool<Map<String, Object>> noMime = SdkMcpTool.create("no_mime", "Image without mimeType", Map.of(),
                args -> CompletableFuture.supplyAsync(
                        () -> ToolResult.ofContent(List.of(Map.of("type", "image", "data", "AAAA")))));
        SdkMcpServer server = SdkMcpServer.create("t", List.of(sloppy, noMime));

        assertThat(failedCall(server, "sloppy", Map.of())).contains("'text'").contains("missing");
        assertThat(failedCall(server, "no_mime", Map.of())).contains("'mimeType'");
    }

    // --- tier 3

    @Test
    void nonObjectArgumentsAreInvalidParams() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        SdkMcpTool<Map<String, Object>> tool = SdkMcpTool.create("t", "T", Map.of(),
                args -> {
                    ran.set(true);
                    return CompletableFuture.completedFuture(ToolResult.text("ok"));
                });
        SdkMcpServer server = SdkMcpServer.create("s", List.of(tool));

        Map<String, Object> response = server.handleMessage(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", "t", "arguments", "str")))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(response).containsKey("error");
        assertThat(String.valueOf(response.get("error"))).contains("-32602");
        assertThat(ran).isFalse();
    }

    @Test
    void toolsAreListedInRegistrationOrder() throws Exception {
        List<SdkMcpTool<?>> tools = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String name = "tool_" + (char) ('z' - (i % 26)) + i;
            names.add(name);
            tools.add(SdkMcpTool.create(name, "T", Map.of(),
                    args -> CompletableFuture.completedFuture(ToolResult.text("ok"))));
        }
        SdkMcpServer server = SdkMcpServer.create("s", tools);

        Map<String, Object> response = server.handleMessage(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "tools/list")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listed = (List<Map<String, Object>>) resultOf(response).get("tools");

        assertThat(listed).extracting(t -> t.get("name")).containsExactlyElementsOf(names);
    }


    /** @Tool methods whose results are null, for the null-result tests. */
    public static class NullResultTools {
        @Tool(name = "null_string", description = "Returns null")
        public @Nullable String nullString() {
            return null;
        }

        @Tool(name = "null_future", description = "Completes with null")
        public CompletableFuture<@Nullable String> nullFuture() {
            return CompletableFuture.completedFuture(null);
        }

        @Tool(name = "void_tool", description = "Returns nothing")
        public void voidTool() {
        }

        @Tool(name = "boxed_void", description = "Returns Void")
        public @Nullable Void boxedVoid() {
            return null;
        }

        @Tool(name = "void_future", description = "Completes with nothing")
        public CompletableFuture<Void> voidFuture() {
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void annotatedMethodReturningNullIsAnErrorResult() throws Exception {
        SdkMcpServer server = SdkMcpServer.fromAnnotatedMethods("n", new NullResultTools());

        assertThat(failedCall(server, "null_string", Map.of())).contains("no result");
        assertThat(failedCall(server, "null_future", Map.of())).contains("no result");
        // A void method has no result to give; it keeps its plain success.
        okCall(server, "void_tool", Map.of());
        okCall(server, "void_future", Map.of());
        okCall(server, "boxed_void", Map.of());
    }

}
