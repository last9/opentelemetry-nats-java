package io.last9.otel.nats;

import io.last9.otel.nats.helper.TracingMessageHandler;
import io.nats.client.MessageHandler;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Instruments NatsConnection.createDispatcher(MessageHandler) to wrap the handler
 * with a TracingMessageHandler before it is stored in the NatsDispatcher.
 *
 * Why not instrument MessageHandler.onMessage() directly?
 * Java 15+ compiles lambdas to hidden classes via LambdaMetafactory. Hidden classes
 * bypass the Java agent's class-load-time transformation hook — ByteBuddy never sees
 * them. hasSuperType("MessageHandler") silently matches nothing for lambda handlers.
 *
 * Why not instrument the NatsDispatcher constructor?
 * @Advice.Argument(readOnly = false) on constructors has JVM-level restrictions around
 * the super() call sequence that make it unreliable for field assignment.
 *
 * The fix: intercept createDispatcher(MessageHandler) — a plain instance method on
 * NatsConnection. @Advice.OnMethodEnter with readOnly=false replaces the MessageHandler
 * argument before the method body runs. The method then creates NatsDispatcher with the
 * already-wrapped handler, so every subsequent onMessage() call goes through our
 * concrete TracingMessageHandler class.
 */
public class NatsMessageHandlerInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("io.nats.client.impl.NatsConnection");
    }

    @Override
    public void transform(TypeTransformer typeTransformer) {
        typeTransformer.applyAdviceToMethod(
                named("createDispatcher")
                        .and(takesArguments(1))
                        .and(takesArgument(0, named("io.nats.client.MessageHandler"))),
                NatsMessageHandlerInstrumentation.class.getName() + "$CreateDispatcherAdvice");
    }

    @SuppressWarnings("unused")
    public static class CreateDispatcherAdvice {

        /**
         * Fires before createDispatcher(handler) runs. Replaces the raw MessageHandler
         * (which may be a lambda hidden class) with a TracingMessageHandler wrapper.
         * The method then creates NatsDispatcher(connection, wrappedHandler), storing
         * the wrapper as the defaultHandler for all future onMessage() calls.
         */
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(
                @Advice.Argument(value = 0, readOnly = false) MessageHandler handler) {

            if (handler != null && !(handler instanceof TracingMessageHandler)) {
                handler = new TracingMessageHandler(handler);
            }
        }
    }
}
