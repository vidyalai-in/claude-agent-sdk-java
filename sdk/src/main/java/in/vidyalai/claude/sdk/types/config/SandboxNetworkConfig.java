package in.vidyalai.claude.sdk.types.config;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Network configuration for sandbox.
 *
 * @param allowUnixSockets    Unix socket paths accessible in sandbox (e.g. SSH
 *                            agents)
 * @param allowAllUnixSockets allow all Unix sockets (less secure)
 * @param allowLocalBinding   allow binding to localhost ports (macOS only)
 * @param httpProxyPort       HTTP proxy port if bringing your own proxy
 * @param socksProxyPort      SOCKS5 proxy port if bringing your own proxy
 */
public record SandboxNetworkConfig(
                @JsonProperty("allowUnixSockets") @Nullable List<String> allowUnixSockets,
                @JsonProperty("allowAllUnixSockets") @Nullable Boolean allowAllUnixSockets,
                @JsonProperty("allowLocalBinding") @Nullable Boolean allowLocalBinding,
                @JsonProperty("httpProxyPort") @Nullable Integer httpProxyPort,
                @JsonProperty("socksProxyPort") @Nullable Integer socksProxyPort) {

}
