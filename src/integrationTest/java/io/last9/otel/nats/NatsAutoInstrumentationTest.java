package io.last9.otel.nats;

import io.last9.otel.nats.helper.NatsHeadersGetter;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.extension.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static net.bytebuddy.matcher.ElementMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for NATS auto-instrumentation.
 *
 * Approach:
 *  - Testcontainers spins up a real NATS server
 *  - ByteBuddyAgent.install() enables programmatic instrumentation without -javaagent
 *    (requires -Djdk.attach.allowAttachSelf=true, set in build.gradle.kts)
 *  - Advice classes from NatsConnectionInstrumentation and NatsMessageHandlerInstrumentation
 *    are applied directly via AgentBuilder — same Advice code the real agent runs
 *  - InMemorySpanExporter captures spans synchronously for assertion
 *
 * This tests the actual ByteBuddy Advice bytecode, not mocks.
 */
@Testcontainers
class NatsAutoInstrumentationTest {

    @Container
    static final GenericContainer<?> nats = new GenericContainer<>("nats:2.10-alpine")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*\\n", 1))
            .withStartupTimeout(Duration.ofSeconds(30));

    static InMemorySpanExporter spanExporter;

    @BeforeAll
    static void installInstrumentation() {
        // Self-attach ByteBuddy agent — requires -Djdk.attach.allowAttachSelf=true
        ByteBuddyAgent.install();

        // Set up in-memory OTel SDK and register as GlobalOpenTelemetry
        spanExporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        GlobalOpenTelemetry.set(sdk);

        // Apply NatsConnection publish instrumentation
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(named("io.nats.client.impl.NatsConnection"))
                .transform((builder, type, classLoader, module, domain) ->
                        builder.visit(Advice
                                .to(NatsConnectionInstrumentation.PublishAdvice.class)
                                .on(named("publishInternal").and(takesArguments(5)))))
                .installOn(ByteBuddyAgent.getInstrumentation());

        // Apply MessageHandler consume instrumentation
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(hasSuperType(named("io.nats.client.MessageHandler")).and(not(isInterface())))
                .transform((builder, type, classLoader, module, domain) ->
                        builder.visit(Advice
                                .to(NatsMessageHandlerInstrumentation.OnMessageAdvice.class)
                                .on(named("onMessage").and(takesArguments(1)))))
                .installOn(ByteBuddyAgent.getInstrumentation());
    }

    @AfterAll
    static void reset() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void publish_createsProducerSpan() throws Exception {
        spanExporter.reset();
        String natsUrl = "nats://localhost:" + nats.getMappedPort(4222);

        try (Connection connection = Nats.connect(natsUrl)) {
            connection.publish("ticks.ltp", "hello".getBytes());
        }

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size(), "Expected exactly one span for publish");

        SpanData span = spans.get(0);
        assertEquals("ticks.ltp publish", span.getName());
        assertEquals(SpanKind.PRODUCER, span.getKind());
        assertEquals("nats", span.getAttributes().get(AttributeKey.stringKey("messaging.system")));
        assertEquals("ticks.ltp", span.getAttributes().get(AttributeKey.stringKey("messaging.destination")));
        assertEquals("publish", span.getAttributes().get(AttributeKey.stringKey("messaging.operation")));
    }

    @Test
    void subscribe_createsConsumerSpanLinkedToProducer() throws Exception {
        spanExporter.reset();
        String natsUrl = "nats://localhost:" + nats.getMappedPort(4222);
        CountDownLatch received = new CountDownLatch(1);

        try (Connection connection = Nats.connect(
                new Options.Builder().server(natsUrl).build())) {

            Dispatcher dispatcher = connection.createDispatcher(msg -> received.countDown());
            dispatcher.subscribe("ticks.price");

            connection.publish("ticks.price", "42.00".getBytes());

            assertTrue(received.await(5, TimeUnit.SECONDS), "Message not received within timeout");
            Thread.sleep(100); // let the consumer span finish
        }

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(2, spans.size(), "Expected producer span and consumer span");

        SpanData producer = spans.stream()
                .filter(s -> s.getKind() == SpanKind.PRODUCER)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No PRODUCER span found"));
        SpanData consumer = spans.stream()
                .filter(s -> s.getKind() == SpanKind.CONSUMER)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No CONSUMER span found"));

        assertEquals("ticks.price publish", producer.getName());
        assertEquals("ticks.price process", consumer.getName());

        // Consumer span must share the same trace as the producer (context propagated via headers)
        assertEquals(producer.getTraceId(), consumer.getTraceId(),
                "Producer and consumer should share the same trace ID");

        assertEquals("nats", consumer.getAttributes().get(AttributeKey.stringKey("messaging.system")));
        assertEquals("process", consumer.getAttributes().get(AttributeKey.stringKey("messaging.operation")));
    }

    @Test
    void publish_withNullHeaders_injectsTraceparent() throws Exception {
        spanExporter.reset();
        String natsUrl = "nats://localhost:" + nats.getMappedPort(4222);
        CountDownLatch received = new CountDownLatch(1);

        // Capture the message to inspect headers
        String[] capturedTraceparent = new String[1];

        try (Connection connection = Nats.connect(natsUrl)) {
            Dispatcher dispatcher = connection.createDispatcher(msg -> {
                // Use our getter to check the header was injected
                capturedTraceparent[0] = NatsHeadersGetter.INSTANCE.get(msg, "traceparent");
                received.countDown();
            });
            dispatcher.subscribe("ticks.headers.test");

            // Publish without explicit headers — extension should inject traceparent
            connection.publish("ticks.headers.test", "data".getBytes());

            assertTrue(received.await(5, TimeUnit.SECONDS), "Message not received");
        }

        assertNotNull(capturedTraceparent[0], "traceparent header must be injected even when publish has no headers");
        assertTrue(capturedTraceparent[0].startsWith("00-"), "traceparent must follow W3C format");
    }
}
