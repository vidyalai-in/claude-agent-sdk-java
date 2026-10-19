package in.vidyalai.claude.sdk.types.message;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A loaded plugin listed in {@link SystemInitData}.
 *
 * @param name    plugin name
 * @param path    plugin path
 * @param version the version declared in the plugin's manifest, verbatim.
 *                Omitted when the manifest declares none
 */
public record SystemInitPlugin(
        @JsonProperty("name") String name,
        @JsonProperty("path") String path,
        @JsonProperty("version") @Nullable String version) {

}
