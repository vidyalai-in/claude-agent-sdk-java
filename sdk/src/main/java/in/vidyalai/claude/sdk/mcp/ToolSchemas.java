package in.vidyalai.claude.sdk.mcp;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Turns Java types into JSON Schema, and JSON arguments back into those
 * types.
 *
 * <p>
 * Both directions are driven by one {@link Binding} per type, so whatever a
 * generated schema admits is exactly what the conversion can build: a tool is
 * never published with a contract its own arguments cannot meet. The mapping
 * follows the Python SDK's {@code _python_type_to_json_schema} where the two
 * languages overlap ({@code list[str]} and {@code List<String>} both become an
 * array of strings, a {@code TypedDict} and a record both become a nested
 * object) and extends it where Java has more to say (enums, {@code char},
 * {@code Optional}, value types written as strings).
 */
final class ToolSchemas {

    private static final Logger logger = Logger.getLogger(ToolSchemas.class.getName());
    private static final ObjectMapper MAPPER = McpJson.MAPPER;

    private static final String TYPE = "type";
    private static final String OBJECT = "object";
    private static final String PROPERTIES = "properties";
    private static final String REQUIRED = "required";
    private static final String STRING = "string";
    private static final String DESCRIPTION = "description";

    private ToolSchemas() {
    }

    // ------------------------------------------------------------------
    // Declared inputSchema maps: full JSON Schema or {name: type} shorthand
    // ------------------------------------------------------------------

    /**
     * Whether {@code declared} is taken as a complete JSON Schema and
     * published verbatim.
     *
     * <p>
     * The shorthand is recognized only when every value is a Java type (a
     * {@link Type} or a Jackson {@link TypeReference}), which no JSON Schema
     * can contain; any other map is published as written. That differs from
     * the Python SDK on purpose. Python treats a dict as a schema only when it
     * has a string {@code type} and a {@code properties} key, and reads
     * anything else as shorthand. In Java that would rewrite valid schemas
     * that have no top-level {@code type}, such as
     * {@code {"properties": {...}, "required": [...]}} or an {@code anyOf},
     * into a tool taking arguments named after the schema keywords.
     */
    /** Top-level JSON Schema keywords, to tell a schema from a mistaken shorthand. */
    private static final java.util.Set<String> SCHEMA_KEYWORDS = java.util.Set.of(
            "type", "properties", "required", "additionalProperties", "patternProperties",
            "propertyNames", "anyOf", "oneOf", "allOf", "not", "if", "then", "else", "$ref",
            "$defs", "definitions", "$schema", "$id", "enum", "const", "items", "prefixItems",
            "minProperties", "maxProperties", "dependentRequired", "dependentSchemas", "title",
            "description", "default", "examples", "unevaluatedProperties");

    static boolean isFullSchema(Map<String, Object> declared) {
        for (Object value : declared.values()) {
            if (asType(value) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The JSON Schema sent on the wire for a tool's declared
     * {@code inputSchema}, the counterpart of Python's
     * {@code _build_input_schema}.
     *
     * <p>
     * A full schema (see {@link #isFullSchema}) is returned unchanged, except
     * that one with no top-level {@code type} gets {@code "type": "object"}
     * (MCP requires an object input schema). A
     * shorthand map, an empty one included, becomes an object schema in which
     * every key is a required property whose schema comes from its value: a
     * {@link Type} (a {@code Class} such as {@code String.class}, or a
     * parameterized type) or a Jackson {@link TypeReference} for generics such
     * as {@code List<String>}.
     *
     * @param toolName the tool the schema belongs to
     * @param declared what the tool declared; null for no schema
     * @return the schema to publish and validate against
     */
    static Map<String, Object> wireSchema(String toolName, @Nullable Map<String, Object> declared) {
        if (declared == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put(TYPE, OBJECT);
            empty.put(PROPERTIES, new LinkedHashMap<>());
            return empty;
        }
        if (isFullSchema(declared)) {
            if (declared.containsKey(TYPE)) {
                return declared;
            }
            // MCP requires an object input schema and the Python SDK never
            // sends one without "type", so supply it. A map with no schema
            // keyword at all is almost certainly a Python-style shorthand
            // with string values ({"text": "string"}), which Java reads only
            // with Class/Type values; say so rather than accept anything.
            boolean hasSchemaKeyword = false;
            for (String key : declared.keySet()) {
                if (SCHEMA_KEYWORDS.contains(key)) {
                    hasSchemaKeyword = true;
                    break;
                }
            }
            if (!hasSchemaKeyword) {
                logger.warning(() -> "Tool '" + toolName + "' declares an inputSchema " + declared.keySet()
                        + " with no JSON Schema keyword; it is published as a schema that accepts any"
                        + " arguments. For the {name: type} shorthand use Java types as the values,"
                        + " e.g. Map.of(\"text\", String.class)");
            }
            Map<String, Object> withType = new LinkedHashMap<>();
            withType.put(TYPE, OBJECT);
            withType.putAll(declared);
            return withType;
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : declared.entrySet()) {
            properties.put(entry.getKey(), bind(java.util.Objects.requireNonNull(asType(entry.getValue()))).schema());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE, OBJECT);
        schema.put(PROPERTIES, properties);
        schema.put(REQUIRED, new ArrayList<>(properties.keySet()));
        return schema;
    }

    @Nullable
    private static Type asType(@Nullable Object value) {
        if (value instanceof Type type) {
            return type;
        }
        if (value instanceof TypeReference<?> reference) {
            return reference.getType();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // @Tool methods
    // ------------------------------------------------------------------

    /**
     * How one {@code @Tool} method is published and called.
     *
     * @param schema    the generated {@code inputSchema}
     * @param arguments builds the method's argument array from a call
     */
    record MethodBinding(Map<String, Object> schema, ArgumentBuilder arguments) {
    }

    /** Builds the argument array for one call of a {@code @Tool} method. */
    @FunctionalInterface
    interface ArgumentBuilder {
        Object[] build(Map<String, Object> args, ToolCallContext context);
    }

    /** Whether this parameter is the injected {@link ToolCallContext}. */
    static boolean isContextParameter(Parameter parameter) {
        return ToolCallContext.class.isAssignableFrom(parameter.getType());
    }

    /** One caller-supplied parameter of a method. */
    private record Param(int index, String name, Binding binding, boolean required) {
    }

    /**
     * Generates the schema for {@code method} and the matching argument
     * builder.
     *
     * <p>
     * A method whose only argument parameter is a {@code Map} receives the
     * whole argument object and publishes an empty object schema. Otherwise
     * every non-context parameter becomes a property, named by
     * {@link ToolParam#name()} or the Java parameter name, and required
     * unless it is {@code Optional} or marked {@code required = false}.
     */
    static MethodBinding bindMethod(Method method) {
        Parameter[] parameters = method.getParameters();
        List<Parameter> declared = new ArrayList<>();
        for (Parameter parameter : parameters) {
            if (!isContextParameter(parameter)) {
                declared.add(parameter);
            }
        }

        if ((declared.size() == 1) && Map.class.isAssignableFrom(declared.get(0).getType())) {
            return new MethodBinding(emptyObjectSchema(), (args, context) -> {
                Object[] values = new Object[parameters.length];
                for (int i = 0; i < parameters.length; i++) {
                    values[i] = (isContextParameter(parameters[i]) ? context : args);
                }
                return values;
            });
        }

        List<Param> params = new ArrayList<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            if (isContextParameter(parameter)) {
                continue;
            }
            @Nullable
            ToolParam meta = parameter.getAnnotation(ToolParam.class);
            String name = (((meta != null) && !meta.name().isEmpty())
                    ? meta.name()
                    : (parameter.isNamePresent() ? parameter.getName() : null));
            if (name == null) {
                String problem = "Parameter names of " + method.getDeclaringClass().getSimpleName() + "#"
                        + method.getName() + " are not available; compile with -parameters or name each "
                        + "parameter with @ToolParam(name = ...)";
                logger.warning(problem);
                return new MethodBinding(emptyObjectSchema(), (args, context) -> {
                    throw new IllegalArgumentException(problem);
                });
            }
            Binding binding = bind(parameter.getParameterizedType());
            boolean isRequired = !binding.optional() && ((meta == null) || meta.required());
            properties.put(name, describe(binding.schema(), meta));
            if (isRequired) {
                required.add(name);
            }
            params.add(new Param(i, name, binding, isRequired));
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE, OBJECT);
        schema.put(PROPERTIES, properties);
        if (!required.isEmpty()) {
            schema.put(REQUIRED, required);
        }

        return new MethodBinding(schema, (args, context) -> {
            Object[] values = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                if (isContextParameter(parameters[i])) {
                    values[i] = context;
                }
            }
            for (Param param : params) {
                values[param.index()] = convertField(param.name(), param.binding(), args, "argument");
            }
            return values;
        });
    }

    /** The value for one named field of {@code source}, converted. */
    @Nullable
    private static Object convertField(
            String name, Binding binding, Map<?, ?> source, String what) {
        Object raw = source.get(name);
        if (raw == null) {
            return binding.absent();
        }
        try {
            return binding.converter().convert(raw);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Invalid value for " + what + " '" + name + "': " + describeFailure(e), e);
        }
    }

    private static Map<String, Object> describe(Map<String, Object> schema, @Nullable ToolParam meta) {
        if ((meta == null) || meta.description().isEmpty()) {
            return schema;
        }
        Map<String, Object> described = new LinkedHashMap<>(schema);
        described.put(DESCRIPTION, meta.description());
        return described;
    }

    private static Map<String, Object> emptyObjectSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE, OBJECT);
        schema.put(PROPERTIES, new LinkedHashMap<>());
        return schema;
    }

    // ------------------------------------------------------------------
    // Type bindings
    // ------------------------------------------------------------------

    /** Converts one already-validated JSON value into a Java value. */
    @FunctionalInterface
    interface Converter {
        @Nullable
        Object convert(Object raw);
    }

    /**
     * A type's schema and its converter.
     *
     * @param schema    the JSON Schema for a value of this type
     * @param converter JSON value to Java value
     * @param optional  whether the value may be left out ({@code Optional})
     * @param absent    the value to use when it is left out
     */
    record Binding(Map<String, Object> schema, Converter converter, boolean optional, @Nullable Object absent) {

        Binding(Map<String, Object> schema, Converter converter) {
            this(schema, converter, false, null);
        }

    }

    static Binding bind(Type type) {
        return bind(type, new HashSet<>());
    }

    private static Binding bind(Type type, Set<Class<?>> visiting) {
        if (type instanceof WildcardType wildcard) {
            Type[] upper = wildcard.getUpperBounds();
            return bind((upper.length > 0) ? upper[0] : Object.class, visiting);
        }
        if (type instanceof TypeVariable<?> variable) {
            Type[] bounds = variable.getBounds();
            return bind((bounds.length > 0) ? bounds[0] : Object.class, visiting);
        }
        if (type instanceof GenericArrayType array) {
            Type component = array.getGenericComponentType();
            return arrayBinding(rawClass(component), bind(component, visiting));
        }

        Class<?> raw = rawClass(type);
        Type[] typeArgs = ((type instanceof ParameterizedType parameterized)
                ? parameterized.getActualTypeArguments()
                : new Type[0]);

        Binding scalar = scalarBinding(raw);
        if (scalar != null) {
            return scalar;
        }
        if (raw.isEnum()) {
            return enumBinding(raw);
        }
        if (raw == Optional.class) {
            Binding inner = bind(typeArg(typeArgs, 0), visiting);
            return new Binding(inner.schema(), v -> Optional.ofNullable(inner.converter().convert(v)),
                    true, Optional.empty());
        }
        if (raw == OptionalInt.class) {
            return new Binding(schemaOf("integer"),
                    v -> OptionalInt.of(MAPPER.convertValue(v, Integer.class)), true, OptionalInt.empty());
        }
        if (raw == OptionalLong.class) {
            return new Binding(schemaOf("integer"),
                    v -> OptionalLong.of(MAPPER.convertValue(v, Long.class)), true, OptionalLong.empty());
        }
        if (raw == OptionalDouble.class) {
            return new Binding(schemaOf("number"),
                    v -> OptionalDouble.of(MAPPER.convertValue(v, Double.class)), true, OptionalDouble.empty());
        }
        if (raw.isArray()) {
            Class<?> component = raw.getComponentType();
            return arrayBinding(component, bind(component, visiting));
        }
        if (Collection.class.isAssignableFrom(raw) || (raw == Iterable.class)) {
            Type element = (Collection.class.isAssignableFrom(raw)
                    ? collectionElement(type, raw)
                    : typeArg(typeArgs, 0));
            return collectionBinding(raw, rawClass(element), bind(element, visiting));
        }
        if (Map.class.isAssignableFrom(raw)) {
            return mapBinding(raw, typeArgs, visiting);
        }
        if (raw.isRecord()) {
            if (!visiting.add(raw)) {
                // Recursive record: describe the inner occurrence as an
                // object and bind it lazily, one level per nesting in the data.
                return new Binding(schemaOf(OBJECT), v -> bind(type).converter().convert(v));
            }
            try {
                return recordBinding(raw, type, visiting);
            } finally {
                visiting.remove(raw);
            }
        }
        if (raw == Object.class) {
            // Anything JSON can say; published as an object, as it always was.
            return new Binding(schemaOf(OBJECT), v -> v);
        }
        if (hasStringCreator(raw)) {
            // The Python SDK's default for a type it does not know is a
            // string; a Java type that Jackson can build from one is the
            // closest match.
            return new Binding(stringSchema(), v -> MAPPER.convertValue(v, raw));
        }

        // A bean: Jackson decides which properties it takes. Use a record for
        // a nested schema the model can see.
        JavaType javaType = MAPPER.getTypeFactory().constructType(type);
        if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers())) {
            logger.warning(() -> "Parameter type " + raw.getName() + " is abstract, so arguments for it "
                    + "can only be converted if Jackson knows a concrete type; publishing it as an object");
        }
        return new Binding(schemaOf(OBJECT), v -> MAPPER.convertValue(v, javaType));
    }

    @Nullable
    private static Binding scalarBinding(Class<?> raw) {
        if ((raw == String.class) || (raw == CharSequence.class)) {
            return new Binding(stringSchema(), String::valueOf);
        }
        if ((raw == char.class) || (raw == Character.class)) {
            Map<String, Object> schema = stringSchema();
            schema.put("minLength", 1);
            schema.put("maxLength", 1);
            return primitiveAware(raw, schema, v -> {
                String text = String.valueOf(v);
                if (text.length() != 1) {
                    throw new IllegalArgumentException("expected a single character, got \"" + text + "\"");
                }
                return text.charAt(0);
            });
        }
        if ((raw == boolean.class) || (raw == Boolean.class)) {
            return primitiveAware(raw, schemaOf("boolean"), v -> MAPPER.convertValue(v, Boolean.class));
        }
        if ((raw == int.class) || (raw == long.class) || (raw == short.class) || (raw == byte.class)
                || (raw == Integer.class) || (raw == Long.class) || (raw == Short.class) || (raw == Byte.class)
                || (raw == BigInteger.class)) {
            Class<?> boxed = boxed(raw);
            return primitiveAware(raw, schemaOf("integer"), v -> MAPPER.convertValue(v, boxed));
        }
        if ((raw == double.class) || (raw == float.class) || Number.class.isAssignableFrom(raw)) {
            Class<?> boxed = boxed(raw);
            return primitiveAware(raw, schemaOf("number"), v -> MAPPER.convertValue(v, boxed));
        }
        return stringValueBinding(raw);
    }

    /** A primitive left out takes its zero value: null cannot be passed. */
    private static Binding primitiveAware(Class<?> raw, Map<String, Object> schema, Converter converter) {
        return new Binding(schema, converter, false, (raw.isPrimitive() ? zero(raw) : null));
    }

    /** Value types written as a string in JSON. */
    @Nullable
    private static Binding stringValueBinding(Class<?> raw) {
        if (raw == UUID.class) {
            return new Binding(stringSchema("uuid"), v -> UUID.fromString(String.valueOf(v)));
        }
        if (raw == URI.class) {
            return new Binding(stringSchema("uri"), v -> URI.create(String.valueOf(v)));
        }
        if (raw == Path.class) {
            return new Binding(stringSchema(), v -> Path.of(String.valueOf(v)));
        }
        if (raw == File.class) {
            return new Binding(stringSchema(), v -> new File(String.valueOf(v)));
        }
        if (raw == LocalDate.class) {
            return new Binding(stringSchema("date"), v -> LocalDate.parse(String.valueOf(v)));
        }
        if (raw == Instant.class) {
            return new Binding(stringSchema("date-time"), v -> Instant.parse(String.valueOf(v)));
        }
        if (raw == OffsetDateTime.class) {
            return new Binding(stringSchema("date-time"), v -> OffsetDateTime.parse(String.valueOf(v)));
        }
        if (ZoneId.class.isAssignableFrom(raw)) {
            return (raw == ZoneOffset.class)
                    ? new Binding(stringSchema(), v -> ZoneOffset.of(String.valueOf(v)))
                    : new Binding(stringSchema(), v -> ZoneId.of(String.valueOf(v)));
        }
        if ("java.time".equals(raw.getPackageName())) {
            Method parse = staticParse(raw);
            if (parse != null) {
                return new Binding(stringSchema(), v -> invokeParse(parse, String.valueOf(v)));
            }
        }
        return null;
    }

    @Nullable
    private static Method staticParse(Class<?> raw) {
        try {
            Method parse = raw.getMethod("parse", CharSequence.class);
            return (Modifier.isStatic(parse.getModifiers()) ? parse : null);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Object invokeParse(Method parse, String text) {
        try {
            return parse.invoke(null, text);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new IllegalArgumentException(
                    (cause != null) ? describeFailure(cause) : describeFailure(e), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * An enum is a string limited to the constants' JSON names: the name, or
     * whatever {@code @JsonValue}/{@code @JsonProperty} makes Jackson write.
     */
    private static Binding enumBinding(Class<?> raw) {
        Map<String, Object> byName = new LinkedHashMap<>();
        for (Object constant : raw.getEnumConstants()) {
            if (!(constant instanceof Enum<?> value)) {
                continue;
            }
            @Nullable
            Object written = MAPPER.convertValue(value, Object.class);
            byName.put((written instanceof String s) ? s : value.name(), value);
        }
        Map<String, Object> schema = stringSchema();
        schema.put("enum", new ArrayList<>(byName.keySet()));
        return new Binding(schema, v -> {
            Object constant = byName.get(String.valueOf(v));
            if (constant == null) {
                throw new IllegalArgumentException(
                        "\"" + v + "\" is not one of " + byName.keySet());
            }
            return constant;
        });
    }

    private static Binding arrayBinding(Class<?> component, Binding element) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE, "array");
        if (component != Object.class) {
            schema.put("items", element.schema());
        }
        return new Binding(schema, v -> {
            List<?> list = asList(v);
            Object array = Array.newInstance(component, list.size());
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                Object converted = ((item == null) ? element.absent() : element.converter().convert(item));
                if ((converted == null) && component.isPrimitive()) {
                    converted = zero(component);
                }
                Array.set(array, i, converted);
            }
            return array;
        });
    }

    private static Binding collectionBinding(Class<?> raw, Class<?> elementClass, Binding element) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE, "array");
        if (elementClass != Object.class) {
            // A bare List (or List<Object>) says nothing about its items, as
            // with Python's bare list.
            schema.put("items", element.schema());
        }
        return new Binding(schema, v -> {
            Collection<Object> target = newCollection(raw, elementClass);
            for (Object item : asList(v)) {
                target.add((item == null) ? element.absent() : element.converter().convert(item));
            }
            return target;
        });
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Collection<Object> newCollection(Class<?> raw, Class<?> elementClass) {
        if (EnumSet.class.isAssignableFrom(raw) && elementClass.isEnum()) {
            return (Collection<Object>) (Collection) EnumSet.noneOf((Class<Enum>) elementClass);
        }
        if (!raw.isInterface() && !Modifier.isAbstract(raw.getModifiers())) {
            Object created = newInstance(raw);
            if (created instanceof Collection<?> collection) {
                return (Collection<Object>) collection;
            }
        }
        if (SortedSet.class.isAssignableFrom(raw) || NavigableSet.class.isAssignableFrom(raw)) {
            return new TreeSet<>();
        }
        if (Set.class.isAssignableFrom(raw)) {
            return new LinkedHashSet<>();
        }
        if (Queue.class.isAssignableFrom(raw)) {
            return new ArrayDeque<>();
        }
        return new ArrayList<>();
    }

    private static Binding mapBinding(Class<?> raw, Type[] typeArgs, Set<Class<?>> visiting) {
        Type keyType = typeArg(typeArgs, 0);
        Type valueType = typeArg(typeArgs, 1);
        Class<?> keyClass = rawClass(keyType);
        Binding key = bind(keyType, visiting);
        Binding value = bind(valueType, visiting);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE, OBJECT);
        // Map<String, Object> and a raw Map take anything, as they always did.
        schema.put("additionalProperties",
                (rawClass(valueType) == Object.class) ? Boolean.TRUE : value.schema());
        if (keyClass.isEnum()) {
            schema.put("propertyNames", key.schema());
        }
        boolean keysAsIs = (keyClass == String.class) || (keyClass == Object.class)
                || (keyClass == CharSequence.class);
        return new Binding(schema, v -> {
            if (!(v instanceof Map<?, ?> source)) {
                throw new IllegalArgumentException("expected an object");
            }
            Map<Object, Object> target = newMap(raw);
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                Object k = entry.getKey();
                Object convertedKey = ((keysAsIs || (k == null)) ? k : key.converter().convert(k));
                Object item = entry.getValue();
                target.put(convertedKey, (item == null) ? value.absent() : value.converter().convert(item));
            }
            return target;
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> newMap(Class<?> raw) {
        if (!raw.isInterface() && !Modifier.isAbstract(raw.getModifiers())) {
            Object created = newInstance(raw);
            if (created instanceof Map<?, ?> map) {
                return (Map<Object, Object>) map;
            }
        }
        if (SortedMap.class.isAssignableFrom(raw) || NavigableMap.class.isAssignableFrom(raw)) {
            return new TreeMap<>();
        }
        return new LinkedHashMap<>();
    }

    /**
     * A record is a nested object: one property per component, required
     * unless it is {@code Optional} or marked {@code @ToolParam(required =
     * false)} — the counterpart of a nested {@code TypedDict} in Python.
     */
    private static Binding recordBinding(Class<?> raw, Type type, Set<Class<?>> visiting) {
        RecordComponent[] components = raw.getRecordComponents();
        Map<TypeVariable<?>, Type> resolved = new HashMap<>();
        if (type instanceof ParameterizedType parameterized) {
            TypeVariable<?>[] variables = raw.getTypeParameters();
            Type[] actual = parameterized.getActualTypeArguments();
            for (int i = 0; (i < variables.length) && (i < actual.length); i++) {
                resolved.put(variables[i], actual[i]);
            }
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        String[] names = new String[components.length];
        Binding[] bindings = new Binding[components.length];
        Class<?>[] ctorTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            @Nullable
            ToolParam meta = component.getAnnotation(ToolParam.class);
            names[i] = (((meta != null) && !meta.name().isEmpty()) ? meta.name() : component.getName());
            Type componentType = component.getGenericType();
            if (componentType instanceof TypeVariable<?> variable) {
                componentType = resolved.getOrDefault(variable, componentType);
            }
            bindings[i] = bind(componentType, visiting);
            ctorTypes[i] = component.getType();
            properties.put(names[i], describe(bindings[i].schema(), meta));
            if (!bindings[i].optional() && ((meta == null) || meta.required())) {
                required.add(names[i]);
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE, OBJECT);
        schema.put(PROPERTIES, properties);
        if (!required.isEmpty()) {
            schema.put(REQUIRED, required);
        }

        return new Binding(schema, v -> {
            if (!(v instanceof Map<?, ?> source)) {
                throw new IllegalArgumentException("expected an object");
            }
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                values[i] = convertField(names[i], bindings[i], source, "field");
            }
            try {
                Constructor<?> constructor = raw.getDeclaredConstructor(ctorTypes);
                constructor.setAccessible(true);
                return constructor.newInstance(values);
            } catch (InvocationTargetException e) {
                // The record's own validation rejected the value.
                Throwable cause = e.getCause();
                throw new IllegalArgumentException(
                        (cause != null) ? describeFailure(cause) : describeFailure(e), e);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot construct " + raw.getName() + ": " + e, e);
            }
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Whether Jackson can build {@code raw} from a JSON string. */
    private static boolean hasStringCreator(Class<?> raw) {
        if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers())) {
            return false;
        }
        try {
            if (Modifier.isPublic(raw.getConstructor(String.class).getModifiers())) {
                return true;
            }
        } catch (NoSuchMethodException e) {
            // try the factory
        }
        try {
            Method valueOf = raw.getMethod("valueOf", String.class);
            return Modifier.isStatic(valueOf.getModifiers()) && raw.isAssignableFrom(valueOf.getReturnType());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static List<?> asList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        throw new IllegalArgumentException("expected an array");
    }

    /** The element type of a collection type, through any subclassing. */
    private static Type collectionElement(Type type, Class<?> raw) {
        JavaType javaType = MAPPER.getTypeFactory().constructType(type);
        JavaType asCollection = javaType.findSuperType(Collection.class);
        if ((asCollection != null) && (type instanceof ParameterizedType parameterized)
                && (raw.getTypeParameters().length == 1)) {
            return parameterized.getActualTypeArguments()[0];
        }
        if ((asCollection != null) && (asCollection.containedTypeCount() == 1)) {
            return asCollection.containedType(0).getRawClass();
        }
        return Object.class;
    }

    private static Type typeArg(Type[] typeArgs, int index) {
        return ((index < typeArgs.length) ? typeArgs[index] : Object.class);
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> cls) {
            return cls;
        }
        if (type instanceof ParameterizedType parameterized) {
            return rawClass(parameterized.getRawType());
        }
        if (type instanceof GenericArrayType array) {
            return Array.newInstance(rawClass(array.getGenericComponentType()), 0).getClass();
        }
        if (type instanceof WildcardType wildcard) {
            Type[] upper = wildcard.getUpperBounds();
            return ((upper.length > 0) ? rawClass(upper[0]) : Object.class);
        }
        if (type instanceof TypeVariable<?> variable) {
            Type[] bounds = variable.getBounds();
            return ((bounds.length > 0) ? rawClass(bounds[0]) : Object.class);
        }
        return Object.class;
    }

    @Nullable
    private static Object newInstance(Class<?> raw) {
        try {
            return raw.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Class<?> boxed(Class<?> raw) {
        if (!raw.isPrimitive()) {
            return raw;
        }
        if (raw == int.class) {
            return Integer.class;
        }
        if (raw == long.class) {
            return Long.class;
        }
        if (raw == short.class) {
            return Short.class;
        }
        if (raw == byte.class) {
            return Byte.class;
        }
        if (raw == double.class) {
            return Double.class;
        }
        if (raw == float.class) {
            return Float.class;
        }
        if (raw == boolean.class) {
            return Boolean.class;
        }
        if (raw == char.class) {
            return Character.class;
        }
        return raw;
    }

    @Nullable
    private static Object zero(Class<?> primitive) {
        if (!primitive.isPrimitive()) {
            return null;
        }
        // An empty primitive array's first element would be out of bounds, so
        // read the zero value from a one-element array instead.
        return Array.get(Array.newInstance(primitive, 1), 0);
    }

    private static Map<String, Object> schemaOf(String type) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE, type);
        return schema;
    }

    private static Map<String, Object> stringSchema() {
        return schemaOf(STRING);
    }

    private static Map<String, Object> stringSchema(String format) {
        Map<String, Object> schema = stringSchema();
        schema.put("format", format);
        return schema;
    }

    /** Exception text for a model to read; never null, never empty. */
    static String describeFailure(Throwable cause) {
        String message = cause.getMessage();
        return ((message != null) && !message.isBlank())
                ? message
                : cause.getClass().getSimpleName();
    }

}
