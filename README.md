# opentelemetry-nats-java

OpenTelemetry Java agent extension that auto-instruments the [NATS Java client](https://github.com/nats-io/nats.java) (`io.nats:jnats`).

The OTel Java agent does not include NATS instrumentation. This extension adds it without any code changes to your application.

## What it instruments

| Operation | Class | Method | Span kind |
|-----------|-------|--------|-----------|
| Publish | `io.nats.client.impl.NatsConnection` | `publishInternal` (all publish variants) | `PRODUCER` |
| Subscribe | All `io.nats.client.MessageHandler` implementations | `onMessage` | `CONSUMER` |

### Context propagation

W3C `traceparent` and `tracestate` headers are injected into outgoing messages and extracted from incoming messages, linking producer and consumer spans into a single distributed trace.

## Requirements

- Java 8+
- `io.nats:jnats` 2.x (headers require 2.2+)
- OpenTelemetry Java agent 2.x

## Usage

Download the latest release JAR and add it as an agent extension:

```bash
java \
  -javaagent:/path/to/opentelemetry-javaagent.jar \
  -Dotel.javaagent.extensions=/path/to/opentelemetry-nats-java-0.1.0.jar \
  -Dotel.service.name=my-service \
  -Dotel.exporter.otlp.endpoint=http://localhost:4318 \
  -Dotel.exporter.otlp.protocol=http/protobuf \
  -jar your-app.jar
```

No changes to your application code are required. All `connection.publish(...)` calls and `MessageHandler.onMessage(...)` implementations are instrumented automatically.

## Building

```bash
./gradlew assemble
# Output: build/libs/opentelemetry-nats-java-0.1.0.jar
```

## Disabling

```bash
-Dotel.instrumentation.nats.enabled=false
```

## Span attributes

| Attribute | Value |
|-----------|-------|
| `messaging.system` | `nats` |
| `messaging.destination` | Subject name |
| `messaging.operation` | `publish` or `process` |

## Architecture

```
NatsInstrumentationModule          (InstrumentationModule, loaded via SPI)
├── NatsConnectionInstrumentation  (TypeInstrumentation → NatsConnection.publishInternal)
│   └── PublishAdvice              (@OnMethodEnter: start span, inject headers)
│                                  (@OnMethodExit:  end span, record exception)
└── NatsMessageHandlerInstrumentation  (TypeInstrumentation → MessageHandler.onMessage)
    └── OnMessageAdvice                (@OnMethodEnter: extract context, start span)
                                       (@OnMethodExit:  end span, record exception)

helper/
├── NatsHeadersSetter  (TextMapSetter<Headers>  — inject traceparent into outgoing headers)
└── NatsHeadersGetter  (TextMapGetter<Message>  — extract traceparent from incoming headers)
```

## How it works

The OTel Java agent loads this JAR via the `-Dotel.javaagent.extensions` system property. At class-load time, ByteBuddy intercepts `NatsConnection` and all `MessageHandler` implementations, inlining the Advice bytecode before and after the target methods.

Helper classes (`NatsHeadersSetter`, `NatsHeadersGetter`) are injected into the app classloader by the agent so they are accessible from the inlined Advice code.
