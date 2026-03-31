package io.last9.otel.nats.helper;

import io.nats.client.Message;
import io.nats.client.MessageHandler;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

/**
 * Wraps any MessageHandler (including lambdas) with a CONSUMER span per OTel
 * messaging semantic conventions.
 *
 * Lambdas implementing MessageHandler are hidden classes (Java 15+) and cannot be
 * intercepted by ByteBuddy at class-load time. NatsMessageHandlerInstrumentation
 * wraps the handler in this class at NatsDispatcher construction time so every
 * onMessage() call goes through a concrete, instrumentable class.
 *
 * Declared as a helper class in NatsInstrumentationModule so ByteBuddy injects it
 * into the app classloader — required because this code runs in app classloader context.
 */
public class TracingMessageHandler implements MessageHandler {

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("io.last9.otel.nats");

    private final MessageHandler delegate;

    public TracingMessageHandler(MessageHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onMessage(Message msg) throws InterruptedException {
        Context parentContext = GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), msg, NatsHeadersGetter.INSTANCE);

        String subject = msg.getSubject();
        byte[] body = msg.getData();

        SpanBuilder builder = TRACER
                .spanBuilder(subject + " process")
                .setSpanKind(SpanKind.CONSUMER)
                .setParent(parentContext)
                .setAttribute("messaging.system", "nats")
                .setAttribute("messaging.destination.name", subject)
                .setAttribute("messaging.operation.type", "process")
                .setAttribute("messaging.operation.name", "process")
                .setAttribute("messaging.message.body.size", body != null ? (long) body.length : 0L)
                .setAttribute("network.transport", "tcp");

        NatsSpanHelper.applyConnectionAttributes(builder, msg.getConnection());

        Span span = builder.startSpan();
        try (Scope ignored = span.makeCurrent()) {
            delegate.onMessage(msg);
        } catch (Throwable t) {
            span.recordException(t);
            span.setStatus(StatusCode.ERROR, t.getClass().getName());
            span.setAttribute("error.type", t.getClass().getName());
            throw t;
        } finally {
            span.end();
        }
    }
}
