# opentelemetry-nats-java

The OTel Java agent doesn't instrument NATS. This extension does.

Load it alongside the agent and every `connection.publish()` becomes a PRODUCER span, every message handler becomes a CONSUMER span, and W3C trace context propagates through the message headers automatically. Your application code stays untouched.

## What you get

Every span carries the full set of OTel messaging semantic conventions:

| Attribute | Value |
|-----------|-------|
| `messaging.system` | `nats` |
| `messaging.destination.name` | Subject name |
| `messaging.operation.type` | `publish` or `process` |
| `messaging.operation.name` | `publish` or `process` |
| `messaging.message.body.size` | Payload bytes |
| `messaging.client.id` | Server-assigned client ID |
| `server.address` | Connected NATS host |
| `server.port` | Connected NATS port |
| `network.transport` | `tcp` |
| `error.type` | Exception class name (on failure) |

PRODUCER and CONSUMER spans share the same trace ID. The W3C `traceparent` header is injected on publish and extracted on receive — you see the full publish→subscribe path as a single distributed trace.

## Requirements

- Java 8+
- `io.nats:jnats` 2.2+ (headers require 2.2+)
- OpenTelemetry Java agent 2.x

## Usage

```bash
java \
  -javaagent:/path/to/opentelemetry-javaagent.jar \
  -Dotel.javaagent.extensions=/path/to/opentelemetry-nats-java-0.1.0.jar \
  -Dotel.service.name=my-service \
  -jar your-app.jar
```

Configure the exporter via standard OTel env vars — no extra JVM flags needed:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT="https://otlp.last9.io"
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic <your-credentials>"
export OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf"
```

## Building

```bash
./gradlew assemble
# Output: build/libs/opentelemetry-nats-java-0.1.0.jar
```

## Disabling at runtime

```bash
-Dotel.instrumentation.nats.enabled=false
```

## How it actually works

The Java agent loads this JAR via SPI (`META-INF/services/InstrumentationModule`) at startup. ByteBuddy then intercepts two points:

**Publish side** — `NatsConnection.publishInternal()` is the single chokepoint all five publish overloads funnel through. Advice fires before and after: start a PRODUCER span, inject `traceparent` into the headers (creating a `Headers` object if the call didn't include one), end the span on exit.

**Subscribe side** — Java 15+ compiles lambda message handlers to hidden classes via `LambdaMetafactory`. Hidden classes bypass the agent's class-load-time hook entirely — ByteBuddy never sees them. The fix: intercept `NatsConnection.createDispatcher(MessageHandler)` before the method body runs, wrap the raw handler in a `TracingMessageHandler`, and let the dispatcher store the wrapper. Every subsequent `onMessage()` call goes through our concrete class, which extracts upstream trace context and creates a CONSUMER span.

```
NatsInstrumentationModule            loaded via SPI
├── NatsConnectionInstrumentation    intercepts NatsConnection
│   ├── publishInternal()            → PRODUCER span + traceparent inject
│   └── createDispatcher()           → wraps handler in TracingMessageHandler
│
helper/
├── NatsSpanHelper                   shared server attribute extraction
├── NatsHeadersSetter                TextMapSetter — writes traceparent into Headers
├── NatsHeadersGetter                TextMapGetter — reads traceparent from Message
└── TracingMessageHandler            wraps any MessageHandler with CONSUMER span logic
```

Helper classes are injected into the app classloader via `getAdditionalHelperClassNames()` so they're accessible from the inlined Advice bytecode and from `TracingMessageHandler` at runtime.
