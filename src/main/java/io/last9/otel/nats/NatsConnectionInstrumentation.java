package io.last9.otel.nats;

import io.last9.otel.nats.helper.NatsHeadersSetter;
import io.last9.otel.nats.helper.NatsSpanHelper;
import io.nats.client.Connection;
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

            NatsSpanHelper.applyConnectionAttributes(builder, connection);

            span = builder.startSpan();
            scope = span.makeCurrent();

            // readOnly = false on the headers arg lets ByteBuddy write the new value
            // back into publishInternal's local variable before the method body runs
            if (headers == null) {
                headers = new Headers();
            }
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
