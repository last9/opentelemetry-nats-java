plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "io.last9.otel"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
}

val otelVersion = "1.44.0"
val otelAgentVersion = "2.10.0-alpha"

dependencies {
    // OTel API + extension API — compileOnly, provided by the agent at runtime
    compileOnly("io.opentelemetry:opentelemetry-api:$otelVersion")
    compileOnly("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api:$otelAgentVersion")

    // ByteBuddy — compileOnly, provided by the agent
    compileOnly("net.bytebuddy:byte-buddy:1.14.18")

    // NATS Java client — compileOnly, present in the instrumented app
    compileOnly("io.nats:jnats:2.20.4")

    // AutoService — generates META-INF/services at compile time
    compileOnly("com.google.auto.service:auto-service-annotations:1.1.1")
    annotationProcessor("com.google.auto.service:auto-service:1.1.1")

    // Tests
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("io.nats:jnats:2.20.4")
    testImplementation("io.opentelemetry:opentelemetry-api:$otelVersion")
    testImplementation("io.opentelemetry:opentelemetry-sdk:$otelVersion")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:$otelVersion")
    testImplementation("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api:$otelAgentVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    compileJava {
        options.release.set(8)
    }

    shadowJar {
        // The extension JAR must NOT bundle OTel or ByteBuddy — the agent provides them
        dependencies {
            exclude(dependency("io.opentelemetry:.*"))
            exclude(dependency("io.opentelemetry.javaagent:.*"))
            exclude(dependency("net.bytebuddy:.*"))
            exclude(dependency("com.google.auto.service:.*"))
        }
        // No classifier — the shadow JAR is the primary artifact
        archiveClassifier.set("")
    }

    assemble {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
    }
}
