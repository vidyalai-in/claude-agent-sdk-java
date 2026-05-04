package in.vidyalai.claude.sdk.types.config;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Network configuration for sandbox.
 *
 * @param allowedDomains          domain names that sandboxed processes can
 *                                access
 * @param deniedDomains           domains that are always blocked, even if
 *                                matched by {@code allowedDomains}
 * @param allowManagedDomainsOnly when true in managed settings, only
 *                                managed-settings {@code allowedDomains} are
 *                                respected
 * @param allowUnixSockets        Unix socket paths accessible in sandbox (e.g.
 *                                SSH agents)
 * @param allowAllUnixSockets     allow all Unix sockets (less secure)
 * @param allowLocalBinding       allow binding to localhost ports (macOS only)
 * @param allowMachLookup         macOS only: XPC/Mach service names to allow
 *                                (supports trailing wildcard)
 * @param httpProxyPort           HTTP proxy port if bringing your own proxy
 * @param socksProxyPort          SOCKS5 proxy port if bringing your own proxy
 */
public record SandboxNetworkConfig(
                @JsonProperty("allowedDomains") @Nullable List<String> allowedDomains,
                @JsonProperty("deniedDomains") @Nullable List<String> deniedDomains,
                @JsonProperty("allowManagedDomainsOnly") @Nullable Boolean allowManagedDomainsOnly,
                @JsonProperty("allowUnixSockets") @Nullable List<String> allowUnixSockets,
                @JsonProperty("allowAllUnixSockets") @Nullable Boolean allowAllUnixSockets,
                @JsonProperty("allowLocalBinding") @Nullable Boolean allowLocalBinding,
                @JsonProperty("allowMachLookup") @Nullable List<String> allowMachLookup,
                @JsonProperty("httpProxyPort") @Nullable Integer httpProxyPort,
                @JsonProperty("socksProxyPort") @Nullable Integer socksProxyPort) {

    /**
     * Backwards-compatible constructor for existing call sites that don't use
     * the new domain allowlist or Mach-lookup fields.
     */
    public SandboxNetworkConfig(
            @Nullable List<String> allowUnixSockets,
            @Nullable Boolean allowAllUnixSockets,
            @Nullable Boolean allowLocalBinding,
            @Nullable Integer httpProxyPort,
            @Nullable Integer socksProxyPort) {
        this(null, null, null, allowUnixSockets, allowAllUnixSockets, allowLocalBinding,
                null, httpProxyPort, socksProxyPort);
    }
}
