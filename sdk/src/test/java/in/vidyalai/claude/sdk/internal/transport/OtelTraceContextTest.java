package in.vidyalai.claude.sdk.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;

import in.vidyalai.claude.sdk.ClaudeAgentOptions;

/**
 * Tests for the W3C trace-context propagation in
 * {@link SubprocessCLITransport#applyEnvDefaults}.
 *
 * <p>The SDK uses reflection to talk to OpenTelemetry so the dep stays
 * optional at runtime. These tests exercise the active-span code paths by
 * registering a real {@link OpenTelemetry} as the global instance with a
 * {@link W3CTraceContextPropagator} — the same configuration a typical
 * caller would set up.
 */
class OtelTraceContextTest {

    private static final String ACTIVE_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String ACTIVE_SPAN_ID = "b7ad6b7169203331";
    private static final String EXPECTED_TRACEPARENT =
            "00-" + ACTIVE_TRACE_ID + "-" + ACTIVE_SPAN_ID + "-01";
    private static final String STALE_TRACEPARENT =
            "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";

    @BeforeEach
    void resetGlobalOtel() {
        GlobalOpenTelemetry.resetForTest();
    }

    @AfterEach
    void cleanupGlobalOtel() {
        GlobalOpenTelemetry.resetForTest();
    }

    private static void registerW3cPropagator() {
        OpenTelemetry otel = OpenTelemetry.propagating(
                ContextPropagators.create(W3CTraceContextPropagator.getInstance()));
        GlobalOpenTelemetry.set(otel);
    }

    private static SpanContext activeSpanContext() {
        return SpanContext.create(
                ACTIVE_TRACE_ID, ACTIVE_SPAN_ID,
                TraceFlags.getSampled(), TraceState.getDefault());
    }

    @Test
    void testActiveSpanInjectsTraceparent() {
        registerW3cPropagator();
        ClaudeAgentOptions options = ClaudeAgentOptions.builder().build();
        Map<String, String> env = new HashMap<>();

        Context ctx = Context.current().with(Span.wrap(activeSpanContext()));
        try (Scope ignored = ctx.makeCurrent()) {
            SubprocessCLITransport.applyEnvDefaults(options, env);
        }

        assertThat(env.get("TRACEPARENT")).isEqualTo(EXPECTED_TRACEPARENT);
    }

    @Test
    void testActiveSpanDoesNotOverrideUserSuppliedEnv() {
        registerW3cPropagator();
        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .env(Map.of("TRACEPARENT", "custom"))
                .build();
        Map<String, String> env = new HashMap<>();

        Context ctx = Context.current().with(Span.wrap(activeSpanContext()));
        try (Scope ignored = ctx.makeCurrent()) {
            SubprocessCLITransport.applyEnvDefaults(options, env);
        }

        assertThat(env.get("TRACEPARENT")).isEqualTo("custom");
    }

    @Test
    void testActiveSpanScrubsStaleInheritedEnv() {
        registerW3cPropagator();
        ClaudeAgentOptions options = ClaudeAgentOptions.builder().build();
        Map<String, String> env = new HashMap<>();
        env.put("TRACEPARENT", STALE_TRACEPARENT);
        env.put("TRACESTATE", "vendor=stale");

        Context ctx = Context.current().with(Span.wrap(activeSpanContext()));
        try (Scope ignored = ctx.makeCurrent()) {
            SubprocessCLITransport.applyEnvDefaults(options, env);
        }

        // Stale TRACEPARENT replaced with the active span's value.
        assertThat(env.get("TRACEPARENT")).isEqualTo(EXPECTED_TRACEPARENT);
        assertThat(env.get("TRACEPARENT")).isNotEqualTo(STALE_TRACEPARENT);
        // Stale TRACESTATE scrubbed (active span has empty trace_state, so
        // pairing it with stale state would be incorrect).
        assertThat(env).doesNotContainKey("TRACESTATE");
    }

    @Test
    void testActiveSpanWritesTraceState() {
        registerW3cPropagator();
        ClaudeAgentOptions options = ClaudeAgentOptions.builder().build();
        Map<String, String> env = new HashMap<>();

        SpanContext spanCtx = SpanContext.create(
                ACTIVE_TRACE_ID, ACTIVE_SPAN_ID,
                TraceFlags.getSampled(),
                TraceState.builder().put("vendor", "value").build());
        Context ctx = Context.current().with(Span.wrap(spanCtx));
        try (Scope ignored = ctx.makeCurrent()) {
            SubprocessCLITransport.applyEnvDefaults(options, env);
        }

        assertThat(env.get("TRACEPARENT")).isEqualTo(EXPECTED_TRACEPARENT);
        assertThat(env.get("TRACESTATE")).isEqualTo("vendor=value");
    }

    @Test
    void testNoActiveSpanPreservesInheritedEnv() {
        // Propagator registered but no active span — inject() emits nothing,
        // so the launcher's W3C context must pass through unchanged.
        registerW3cPropagator();
        ClaudeAgentOptions options = ClaudeAgentOptions.builder().build();
        Map<String, String> env = new HashMap<>();
        env.put("TRACEPARENT", STALE_TRACEPARENT);
        env.put("TRACESTATE", "vendor=abc");

        SubprocessCLITransport.applyEnvDefaults(options, env);

        assertThat(env.get("TRACEPARENT")).isEqualTo(STALE_TRACEPARENT);
        assertThat(env.get("TRACESTATE")).isEqualTo("vendor=abc");
    }

    @Test
    void testBaggageOnlyCarrierPreservesInheritedEnv() {
        // Composite(tracecontext, baggage) propagator with baggage in Context
        // but no active span: inject() emits a "baggage" key only, NOT
        // "traceparent". The launcher's stale W3C context must NOT be scrubbed.
        OpenTelemetry otel = OpenTelemetry.propagating(
                ContextPropagators.create(TextMapPropagator.composite(
                        W3CTraceContextPropagator.getInstance(),
                        W3CBaggagePropagator.getInstance())));
        GlobalOpenTelemetry.set(otel);

        ClaudeAgentOptions options = ClaudeAgentOptions.builder().build();
        Map<String, String> env = new HashMap<>();
        env.put("TRACEPARENT", STALE_TRACEPARENT);
        env.put("TRACESTATE", "vendor=abc");

        Context ctx = Context.current().with(Baggage.builder().put("user.id", "123").build());
        try (Scope ignored = ctx.makeCurrent()) {
            SubprocessCLITransport.applyEnvDefaults(options, env);
        }

        assertThat(env.get("TRACEPARENT")).isEqualTo(STALE_TRACEPARENT);
        assertThat(env.get("TRACESTATE")).isEqualTo("vendor=abc");
    }

    @Test
    void testPropagatorErrorDoesNotBreakConnect() {
        // A propagator whose inject() throws must not surface — best-effort
        // tracing must never break connect().
        TextMapPropagator failing = new TextMapPropagator() {
            @Override
            public java.util.Collection<String> fields() {
                return java.util.List.of("traceparent");
            }

            @Override
            public <C> void inject(Context context, C carrier,
                    io.opentelemetry.context.propagation.TextMapSetter<C> setter) {
                throw new RuntimeException("boom");
            }

            @Override
            public <C> Context extract(Context context, C carrier,
                    io.opentelemetry.context.propagation.TextMapGetter<C> getter) {
                return context;
            }
        };
        GlobalOpenTelemetry.set(OpenTelemetry.propagating(ContextPropagators.create(failing)));

        ClaudeAgentOptions options = ClaudeAgentOptions.builder().build();
        Map<String, String> env = new HashMap<>();

        // Must not throw.
        SubprocessCLITransport.applyEnvDefaults(options, env);

        // Best-effort: nothing was injected, but the SDK env defaults still applied.
        assertThat(env).doesNotContainKey("TRACEPARENT");
        assertThat(env.get("CLAUDE_CODE_ENTRYPOINT")).isEqualTo("sdk-java");
    }
}
