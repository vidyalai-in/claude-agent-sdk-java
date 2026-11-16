package in.vidyalai.claude.sdk.types.control.request;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import in.vidyalai.claude.sdk.types.permission.PermissionUpdate;

/**
 * Reads a {@code can_use_tool} request's {@code permission_suggestions}
 * leniently, one suggestion at a time.
 *
 * <p>
 * The Python SDK keeps whatever strings the CLI sends; this SDK's
 * {@link PermissionUpdate} uses enums, so a value it does not know cannot be
 * represented. Such a suggestion is skipped with a FINE log rather than
 * failing the whole request, which would keep the user's callback from ever
 * running. A list that is not an array reads as empty.
 */
final class LenientPermissionUpdateListDeserializer extends StdDeserializer<List<PermissionUpdate>> {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = Logger.getLogger(LenientPermissionUpdateListDeserializer.class.getName());
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    LenientPermissionUpdateListDeserializer() {
        super(List.class);
    }

    @Override
    public @Nullable List<PermissionUpdate> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode tree = p.readValueAsTree();
        List<PermissionUpdate> suggestions = new ArrayList<>();
        if ((tree == null) || !tree.isArray()) {
            return suggestions;
        }
        for (JsonNode element : tree) {
            try {
                Map<String, Object> map = ctxt.readTreeAsValue(element,
                        ctxt.getTypeFactory().constructType(MAP_TYPE));
                suggestions.add(PermissionUpdate.fromMap(map));
            } catch (IOException | RuntimeException e) {
                logger.fine("Skipping a permission suggestion this SDK cannot represent: " + element
                        + " (" + e.getMessage() + ")");
            }
        }
        return suggestions;
    }

}
