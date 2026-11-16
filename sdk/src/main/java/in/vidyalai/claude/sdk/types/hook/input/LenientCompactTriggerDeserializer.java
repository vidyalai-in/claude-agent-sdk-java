package in.vidyalai.claude.sdk.types.hook.input;

import java.io.IOException;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import in.vidyalai.claude.sdk.types.config.CompactTriggerType;

/**
 * Reads {@link PreCompactHookInput#trigger()} leniently: a value this SDK does
 * not know reads as {@code null} instead of failing the whole hook callback,
 * as the Python SDK's plain dict would carry it. The original value stays
 * readable from {@link HookInput#raw()}.
 */
final class LenientCompactTriggerDeserializer extends StdDeserializer<CompactTriggerType> {

    private static final long serialVersionUID = 1L;

    LenientCompactTriggerDeserializer() {
        super(CompactTriggerType.class);
    }

    @Override
    public @Nullable CompactTriggerType deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if ((token == JsonToken.START_OBJECT) || (token == JsonToken.START_ARRAY)) {
            p.skipChildren();
            return null;
        }
        String value = ((token == JsonToken.VALUE_STRING) ? p.getText() : null);
        for (CompactTriggerType type : CompactTriggerType.values()) {
            if (type.getValue().equals(value)) {
                return type;
            }
        }
        return null;
    }

}
