# opentelemetry-nats-java

The OTel Java agent doesn't instrument NATS. This extension does.

Drop it next to the agent and every `connection.publish()` becomes a PRODUCER span, every message handler becomes a CONSUMER span, and W3C trace context propagates through message headers automatically. Zero code changes.

## Installation

**Gradle**

```kotlin
implementation("io.last9:opentelemetry-nats-java:0.1.0")
```

**Maven**

```xml
<dependency>
    <groupId>io.last9</groupId>
    <artifactId>opentelemetry-nats-java</artifactId>
    <version>0.1.0</version>
</dependency>
```

Or grab the JAR directly from [GitHub Releases](https://github.com/last9/opentelemetry-nats-java/releases).

## Usage

```bash
java \
  -javaagent:/path/to/opentelemetry-javaagent.jar \
  -Dotel.javaagent.extensions=/path/to/opentelemetry-nats-java-0.1.0.jar \
  -Dotel.service.name=my-service \
  -jar your-app.jar
```

Point the exporter at your backend via standard OTel env vars:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT="https://otlp.last9.io"
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic <your-credentials>"
export OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf"
```

## What you get

Every publish span carries `messaging.system`, `messaging.destination.name`, `messaging.operation.type`, `messaging.message.body.size`, `messaging.client.id`, `server.address`, `server.port`, and `network.transport`. Exceptions land on the span as `error.type`. PRODUCER and CONSUMER spans share a trace ID — you see the full publish→subscribe path as a single distributed trace.

To disable at runtime: `-Dotel.instrumentation.nats.enabled=false`

## Requirements

- Java 11, 17, or 21 (bytecode targets Java 8+)
- `io.nats:jnats` 2.2+
- OpenTelemetry Java agent 2.x

## How it works

The agent loads this JAR via SPI at startup. ByteBuddy then intercepts two points:

**Publish** — `NatsConnection.publishInternal()` is the single chokepoint all publish overloads funnel through. Advice starts a PRODUCER span on entry, injects `traceparent` into the headers (creating a `Headers` object if the call didn't include one), and ends the span on exit.

**Subscribe** — Java 15+ compiles lambda handlers to hidden classes via `LambdaMetafactory`. Hidden classes bypass the agent's class-load hook entirely — ByteBuddy never sees them. The fix: intercept `NatsConnection.createDispatcher(MessageHandler)`, wrap the raw handler in a `TracingMessageHandler`, and let the dispatcher store the wrapper. Every `onMessage()` call then goes through a concrete class that extracts upstream context and creates a CONSUMER span.

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

Helper classes are injected into the app classloader via `getAdditionalHelperClassNames()` so they're accessible from inlined Advice bytecode and from `TracingMessageHandler` at runtime.

## Building

```bash
./gradlew assemble
# Output: build/libs/opentelemetry-nats-java-0.1.0.jar
```
