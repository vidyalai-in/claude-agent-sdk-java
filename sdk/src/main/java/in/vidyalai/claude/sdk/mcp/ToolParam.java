package in.vidyalai.claude.sdk.mcp;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes one parameter of a {@link Tool @Tool} method, or one component of
 * a record used as a parameter type.
 *
 * <p>
 * The Java counterpart of the Python SDK's {@code Annotated[str, "..."]}: the
 * {@link #description()} becomes the {@code description} of that property in
 * the generated {@code inputSchema}, which is what the model reads to decide
 * what to pass.
 *
 * <pre>{@code
 * @Tool(name = "search", description = "Search the catalog")
 * public ToolResult search(
 *         @ToolParam(description = "What to look for") String query,
 *         @ToolParam(description = "Categories to search in") List<Category> categories,
 *         @ToolParam(description = "Maximum number of hits", required = false) Integer limit) {
 *     ...
 * }
 * }</pre>
 *
 * <p>
 * Without this annotation a parameter is still published, named after the
 * Java parameter (which needs the {@code -parameters} compiler flag) and
 * required unless its type is {@link java.util.Optional}.
 *
 * @see Tool
 * @see SdkMcpServer#fromAnnotatedMethods(String, Object)
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
public @interface ToolParam {

    /**
     * The argument name the model uses.
     *
     * <p>
     * Defaults to the Java parameter name, which is only available when the
     * code is compiled with {@code -parameters}. Set it to name the argument
     * explicitly, or to publish a name that is not a legal Java identifier.
     *
     * @return the argument name, or empty for the Java name
     */
    String name() default "";

    /**
     * What the argument means, published as the property's
     * {@code description}.
     *
     * @return the description, or empty for none
     */
    String description() default "";

    /**
     * Whether the argument must be supplied.
     *
     * <p>
     * An {@link java.util.Optional} parameter is never required, whatever
     * this says. A parameter that is not required and is left out receives
     * {@code null}, or the zero value for a primitive.
     *
     * @return false to make the argument optional
     */
    boolean required() default true;

}
