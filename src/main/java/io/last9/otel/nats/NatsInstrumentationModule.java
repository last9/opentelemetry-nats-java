package io.last9.otel.nats;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Arrays;
import java.util.List;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;

/**
 * Entry point for the NATS auto-instrumentation extension.
 *
 * Loaded by the OTel Java agent via SPI when it finds this class in
 * META-INF/services/io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule
 * (generated automatically by @AutoService).
 *
 * Usage:
 *   java -javaagent:opentelemetry-javaagent.jar \
 *        -Dotel.javaagent.extensions=opentelemetry-nats-java-0.1.0.jar \
 *        -jar your-app.jar
 */
@AutoService(InstrumentationModule.class)
public class NatsInstrumentationModule extends InstrumentationModule {

    public NatsInstrumentationModule() {
        // Primary name + versioned name — controls otel.instrumentation.nats.enabled=false
        super("nats", "nats-2.0");
    }

    /**
     * Only activate this module when the NATS client is on the classpath.
     * Prevents unnecessary type matching in environments without NATS.
     */
    @Override
    public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
        return hasClassesNamed("io.nats.client.impl.NatsConnection");
    }

    /**
     * Declare helper classes that ByteBuddy must inject into the app classloader
     * so Advice and wrapper code can reference them at runtime.
     *
     * isHelperClass() handles prefix-based matching. getAdditionalHelperClassNames()
     * provides an explicit list for the agent's class injector — belt-and-suspenders
     * for the TracingMessageHandler wrapper which is instantiated at method call time.
     */
    @Override
    public boolean isHelperClass(String className) {
        return className.startsWith("io.last9.otel.nats.helper.");
    }

    @Override
    public List<String> getAdditionalHelperClassNames() {
        return Arrays.asList(
                "io.last9.otel.nats.helper.NatsHeadersGetter",
                "io.last9.otel.nats.helper.NatsHeadersSetter",
                "io.last9.otel.nats.helper.TracingMessageHandler"
        );
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return Arrays.asList(
                new NatsConnectionInstrumentation(),
                new NatsMessageHandlerInstrumentation()
        );
    }
}
