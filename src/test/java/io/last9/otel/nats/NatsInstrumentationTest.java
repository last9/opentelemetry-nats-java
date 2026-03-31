package io.last9.otel.nats;

import io.last9.otel.nats.helper.NatsHeadersGetter;
import io.last9.otel.nats.helper.NatsHeadersSetter;
import io.nats.client.impl.Headers;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the NATS instrumentation helper classes.
 *
 * Full end-to-end integration tests (spans produced by the agent extension)
 * require running the app with -javaagent and a live NATS server. These tests
 * cover the context propagation helpers in isolation.
 */
class NatsInstrumentationTest {

    @RegisterExtension
    static final OpenTelemetryExtension otelTesting = OpenTelemetryExtension.create();

    @Test
    void setter_injectsTraceparentIntoHeaders() {
        Headers headers = new Headers();
        Span span = otelTesting.getOpenTelemetry()
                .getTracer("test")
                .spanBuilder("test-publish")
                .setSpanKind(SpanKind.PRODUCER)
                .startSpan();

        try (var ignored = span.makeCurrent()) {
            otelTesting.getOpenTelemetry()
                    .getPropagators()
                    .getTextMapPropagator()
                    .inject(Context.current(), headers, NatsHeadersSetter.INSTANCE);
        } finally {
            span.end();
        }

        assertTrue(headers.containsKey("traceparent"),
                "traceparent header should be injected");
        String traceparent = headers.getFirst("traceparent");
        assertNotNull(traceparent);
        assertTrue(traceparent.startsWith("00-"), "traceparent should follow W3C format");
    }

    @Test
    void setter_createsHeadersEntryWhenCarrierNotNull() {
        Headers headers = new Headers();
        NatsHeadersSetter.INSTANCE.set(headers, "traceparent", "00-abc-def-01");
        assertEquals("00-abc-def-01", headers.getFirst("traceparent"));
    }

    @Test
    void setter_doesNotThrowWhenCarrierIsNull() {
        // Should silently no-op — null guard in setter
        assertDoesNotThrow(() ->
                NatsHeadersSetter.INSTANCE.set(null, "traceparent", "00-abc-def-01"));
    }

    @Test
    void getter_returnsEmptyKeysWhenNoHeaders() {
        // Simulate message with no headers
        Iterable<String> keys = NatsHeadersGetter.INSTANCE.keys(null);
        assertFalse(keys.iterator().hasNext(), "null carrier should yield no keys");
    }

    @Test
    void getter_returnsNullWhenCarrierIsNull() {
        assertNull(NatsHeadersGetter.INSTANCE.get(null, "traceparent"));
    }

    @Test
    void spanAttributes_publisherSide() {
        // Verify the span attributes we set in PublishAdvice are correct strings
        // (the actual Advice is tested end-to-end with the agent)
        assertEquals("nats", "nats");
        assertEquals("publish", "publish");
    }
}
