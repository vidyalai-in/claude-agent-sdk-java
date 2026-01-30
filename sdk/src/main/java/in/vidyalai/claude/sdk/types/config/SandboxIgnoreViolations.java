package in.vidyalai.claude.sdk.types.config;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Violations to ignore in sandbox.
 *
 * @param file    file paths for which violations should be ignored
 * @param network network hosts for which violations should be ignored
 */
public record SandboxIgnoreViolations(
                @JsonProperty("file") @Nullable List<String> file,
                @JsonProperty("network") @Nullable List<String> network) {

}
