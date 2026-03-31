package io.last9.otel.nats.helper;

import io.nats.client.Message;
import io.opentelemetry.context.propagation.TextMapGetter;

import javax.annotation.Nullable;
import java.util.Collections;

/**
 * Bridges OTel W3C context propagation to NATS message headers (consume side).
 *
 * Inbound NATS message headers are read-only (frozen after calculate()).
 * This getter only reads — never writes.
 */
public enum NatsHeadersGetter implements TextMapGetter<Message> {
    INSTANCE;

    @Override
    public Iterable<String> keys(Message carrier) {
        if (carrier == null || !carrier.hasHeaders()) {
            return Collections.emptyList();
        }
        return carrier.getHeaders().keySet();
    }

    @Override
    @Nullable
    public String get(@Nullable Message carrier, String key) {
        if (carrier == null || !carrier.hasHeaders()) {
            return null;
        }
        return carrier.getHeaders().getFirst(key);
    }
}
