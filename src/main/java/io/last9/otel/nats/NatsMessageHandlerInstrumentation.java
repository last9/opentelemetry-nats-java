package io.last9.otel.nats;

import io.last9.otel.nats.helper.NatsHeadersGetter;
import io.nats.client.Message;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Instruments all concrete implementations of io.nats.client.MessageHandler.onMessage().
 *
 * What this does:
 *  1. Extracts W3C trace context from incoming message headers
 *  2. Starts a CONSUMER span parented to the extracted context (linking this
 *     span back to the publisher's trace)
 *  3. Records exceptions and ends the span on exit
 *
 * Note: inbound NATS message headers are read-only (frozen after calculate()).
 * We only read them here — never write.
 */
public class NatsMessageHandlerInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        // Match all concrete implementations of the MessageHandler functional interface.
        // isInterface() exclusion prevents ByteBuddy from attempting to transform the
        // interface declaration itself (which has no method body to advise).
        return hasSuperType(named("io.nats.client.MessageHandler"))
                .and(not(isInterface()));
    }

    @Override
    public void transform(TypeTransformer typeTransformer) {
        typeTransformer.applyAdviceToMethod(
                named("onMessage").and(takesArguments(1)),
                NatsMessageHandlerInstrumentation.class.getName() + "$OnMessageAdvice");
    }

    @SuppressWarnings("unused")
    public static class OnMessageAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(
                @Advice.Argument(0) Message message,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {

            // Extract upstream trace context from message headers.
            // If no headers are present (e.g. publisher not instrumented), this
            // returns the current context unchanged — span still created, just unlinked.
            Context parentContext = GlobalOpenTelemetry.getPropagators()
                    .getTextMapPropagator()
                    .extract(Context.current(), message, NatsHeadersGetter.INSTANCE);

            String subject = message.getSubject();

            span = GlobalOpenTelemetry.getTracer("io.last9.otel.nats")
                    .spanBuilder(subject + " process")
                    .setSpanKind(SpanKind.CONSUMER)
                    .setParent(parentContext)
                    .setAttribute("messaging.system", "nats")
                    .setAttribute("messaging.destination", subject)
                    .setAttribute("messaging.operation", "process")
                    .startSpan();
            scope = span.makeCurrent();
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
                }
                span.end();
            }
        }
    }
}
