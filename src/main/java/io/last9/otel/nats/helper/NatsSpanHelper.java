package io.last9.otel.nats.helper;

import io.nats.client.Connection;
import io.nats.client.api.ServerInfo;
import io.opentelemetry.api.trace.SpanBuilder;

/**
 * Shared utilities for building NATS spans.
 *
 * Declared as a helper class in NatsInstrumentationModule so ByteBuddy injects
 * it into the app classloader — required for use from both Advice code and
 * TracingMessageHandler at runtime.
 */
public final class NatsSpanHelper {

    private NatsSpanHelper() {}

    /**
     * Adds server.address, server.port, and messaging.client.id to a span builder
     * from the given connection.
     *
     * Uses getConnectedUrl() (the actual peer address) rather than ServerInfo.getHost()
     * (the server's bind address, which is often 0.0.0.0).
     */
    public static void applyConnectionAttributes(SpanBuilder builder, Connection connection) {
        if (connection == null) return;
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
            } catch (Exception ignored) {}
        }
        ServerInfo info = connection.getServerInfo();
        if (info != null) {
            builder.setAttribute("messaging.client.id", String.valueOf(info.getClientId()));
        }
    }
}
