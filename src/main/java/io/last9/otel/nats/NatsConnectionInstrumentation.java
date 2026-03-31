package io.last9.otel.nats;

import io.last9.otel.nats.helper.NatsHeadersSetter;
import io.nats.client.Connection;
import io.nats.client.api.ServerInfo;
import io.nats.client.impl.Headers;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Instruments NatsConnection.publishInternal() — the single chokepoint for all
 * publish overloads in the NATS Java client (jnats 2.20.4 signature: 6 args).
 *
 * Produces a PRODUCER span per OTel messaging semantic conventions with:
 *  - messaging.system, messaging.destination.name, messaging.operation.*
 *  - messaging.message.body.size, messaging.client.id
 *  - server.address, server.port, network.transport
 *  - W3C traceparent injected into outgoing headers (creates a new Headers if null)
 *  - error.type on exception
 */
public class NatsConnectionInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("io.nats.client.impl.NatsConnection");
    }

    @Override
    public void transform(TypeTransformer typeTransformer) {
        typeTransformer.applyAdviceToMethod(
                named("publishInternal")
                        .and(takesArguments(6))
                        .and(takesArgument(0, String.class))
                        .and(takesArgument(2, named("io.nats.client.impl.Headers"))),
                NatsConnectionInstrumentation.class.getName() + "$PublishAdvice");
    }

    @SuppressWarnings("unused")
    public static class PublishAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(
                @Advice.This Connection connection,
                @Advice.Argument(value = 0) String subject,
                @Advice.Argument(value = 2, readOnly = false) Headers headers,
                @Advice.Argument(value = 3) byte[] data,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {

            SpanBuilder builder = GlobalOpenTelemetry.getTracer("io.last9.otel.nats")
                    .spanBuilder(subject + " publish")
                    .setSpanKind(SpanKind.PRODUCER)
                    .setParent(Context.current())
                    .setAttribute("messaging.system", "nats")
                    .setAttribute("messaging.destination.name", subject)
                    .setAttribute("messaging.operation.type", "publish")
                    .setAttribute("messaging.operation.name", "publish")
                    .setAttribute("messaging.message.body.size", data != null ? (long) data.length : 0L)
                    .setAttribute("network.transport", "tcp");

            // server.address/port from the connected URL (not ServerInfo.getHost which is the bind address)
            // getConnectedUrl() returns e.g. "nats://nats:4222"
            String connectedUrl = connection.getConnectedUrl();
            if (connectedUrl != null) {
                try {
                    java.net.URI uri = new java.net.URI(connectedUrl);
                    String host = uri.getHost();
                    if (host != null && !host.isEmpty()) {
                        builder.setAttribute("server.address", host);
                    }
                    int port = uri.getPort();
                    if (port > 0) {
                        builder.setAttribute("server.port", (long) port);
                    }
                } catch (Exception ignored) { /* malformed URI — skip */ }
            }
            ServerInfo info = connection.getServerInfo();
            if (info != null) {
                builder.setAttribute("messaging.client.id", String.valueOf(info.getClientId()));
            }

            span = builder.startSpan();
            scope = span.makeCurrent();

            // Create headers if the publish call didn't include any
            if (headers == null) {
                headers = new Headers();
            }
            // Inject traceparent/tracestate so the subscriber can link its span
            GlobalOpenTelemetry.getPropagators()
                    .getTextMapPropagator()
                    .inject(Context.current(), headers, NatsHeadersSetter.INSTANCE);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope,
                @Advice.Thrown Throwable thrown) {

            if (scope != null) {
                scope.close();
            }
            if (span != null) {
                if (thrown != null) {
                    span.recordException(thrown);
                    span.setStatus(StatusCode.ERROR, thrown.getClass().getName());
                    span.setAttribute("error.type", thrown.getClass().getName());
                }
                span.end();
            }
        }
    }
}
