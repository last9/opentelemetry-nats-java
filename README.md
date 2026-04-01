# opentelemetry-nats-java

[![Maven Central](https://img.shields.io/maven-central/v/io.last9/opentelemetry-nats-java)](https://central.sonatype.com/artifact/io.last9/opentelemetry-nats-java)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Java 8+](https://img.shields.io/badge/Java-8%2B-blue)](https://openjdk.org/)

**Auto-instrumentation for NATS in Java. No code changes. Full distributed traces.**

The OpenTelemetry Java agent doesn't instrument NATS. This extension fixes that. Drop it next to the agent and every `publish()` gets a PRODUCER span, every message handler gets a CONSUMER span, and W3C trace context flows through message headers automatically.

You get the full publish-to-subscribe path as a single distributed trace. That's it. That's the pitch.

## Install

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

Or grab the JAR from [GitHub Releases](https://github.com/last9/opentelemetry-nats-java/releases).

## Use it

```bash
java \
  -javaagent:/path/to/opentelemetry-javaagent.jar \
  -Dotel.javaagent.extensions=/path/to/opentelemetry-nats-java-0.1.0.jar \
  -Dotel.service.name=my-service \
  -jar your-app.jar
```

Point the exporter at your backend:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT="https://otlp.last9.io"
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic <your-credentials>"
export OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf"
```

To disable: `-Dotel.instrumentation.nats.enabled=false`

## What you get

Every span carries the full messaging semantic conventions: `messaging.system`, `messaging.destination.name`, `messaging.operation.type`, `messaging.message.body.size`, `messaging.client.id`, `server.address`, `server.port`, and `network.transport`. Exceptions land as `error.type`. PRODUCER and CONSUMER spans share a trace ID.

## How it works

The agent loads this JAR via SPI at startup. ByteBuddy intercepts two points:

**Publish** — `NatsConnection.publishInternal()` is the single chokepoint all publish overloads funnel through. Advice starts a PRODUCER span on entry, injects `traceparent` into the headers, and ends the span on exit.

**Subscribe** — Java 15+ compiles lambda handlers to hidden classes via `LambdaMetafactory`. Hidden classes bypass the agent's class-load hook — ByteBuddy never sees them. The fix: intercept `NatsConnection.createDispatcher(MessageHandler)`, wrap the handler in a `TracingMessageHandler`. Every `onMessage()` call then extracts upstream context and creates a CONSUMER span.

```
NatsInstrumentationModule            loaded via SPI
├── NatsConnectionInstrumentation    intercepts NatsConnection
│   ├── publishInternal()            → PRODUCER span + traceparent inject
│   └── createDispatcher()           → wraps handler in TracingMessageHandler
│
helper/
├── NatsSpanHelper                   shared server attribute extraction
├── NatsHeadersSetter                writes traceparent into Headers
├── NatsHeadersGetter                reads traceparent from Message
└── TracingMessageHandler            wraps MessageHandler with CONSUMER span
```

## Requirements

- Java 8+ (tested on 11, 17, 21)
- `io.nats:jnats` 2.2+
- OpenTelemetry Java agent 2.x

## Build

```bash
./gradlew assemble
# → build/libs/opentelemetry-nats-java-0.1.0.jar
```

## Contributing

Contributions are welcome. Open an issue first for anything non-trivial.

1. Fork the repo
2. Create your branch (`git checkout -b my-change`)
3. Make your changes and add tests
4. Run `./gradlew test` and `./gradlew integrationTest`
5. Open a pull request

## License

[MIT](LICENSE) — do whatever you want with it.
