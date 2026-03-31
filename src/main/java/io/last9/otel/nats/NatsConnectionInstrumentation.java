package io.last9.otel.nats;

import io.last9.otel.nats.helper.NatsHeadersSetter;
import io.nats.client.impl.Headers;
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
 * Instruments NatsConnection.publishInternal() — the single chokepoint for all
 * five publish overloads in the NATS Java client.
 *
 * What this does:
 *  1. Starts a PRODUCER span with messaging semantic convention attributes
 *  2. Injects W3C traceparent/tracestate into the outgoing message headers
 *     so the consumer can link its span back to this one
 *  3. Records exceptions and ends the span on exit
 *
 * Header injection trick: publishInternal takes a @Nullable Headers parameter.
 * Using @Advice.Argument(readOnly = false), we can create a new Headers instance
 * when the argument is null and write it back into the method's local variable
 * before the method body executes.
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
                        .and(takesArguments(5))
                        .and(takesArgument(0, String.class))
                        .and(takesArgument(2, named("io.nats.client.impl.Headers"))),
                NatsConnectionInstrumentation.class.getName() + "$PublishAdvice");
    }

    @SuppressWarnings("unused")
    public static class PublishAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(
                @Advice.Argument(value = 0) String subject,
                @Advice.Argument(value = 2, readOnly = false) Headers headers,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {

            Context parentContext = Context.current();

            span = GlobalOpenTelemetry.getTracer("io.last9.otel.nats")
                    .spanBuilder(subject + " publish")
                    .setSpanKind(SpanKind.PRODUCER)
                    .setParent(parentContext)
                    .setAttribute("messaging.system", "nats")
                    .setAttribute("messaging.destination", subject)
                    .setAttribute("messaging.operation", "publish")
                    .startSpan();
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
                }
                span.end();
            }
        }
    }
}
