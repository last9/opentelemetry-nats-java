package io.last9.otel.nats.helper;

import io.nats.client.impl.Headers;
import io.opentelemetry.context.propagation.TextMapSetter;

import javax.annotation.Nullable;

/**
 * Bridges OTel W3C context propagation to NATS message headers (publish side).
 *
 * Declared as a helper class in NatsInstrumentationModule so ByteBuddy injects
 * it into the app classloader — required because Advice code runs in the app
 * classloader context, not the extension classloader.
 */
public enum NatsHeadersSetter implements TextMapSetter<Headers> {
    INSTANCE;

    @Override
    public void set(@Nullable Headers carrier, String key, String value) {
        if (carrier != null) {
            // put() replaces any existing value for the key (idempotent for W3C headers)
            carrier.put(key, value);
        }
    }
}
