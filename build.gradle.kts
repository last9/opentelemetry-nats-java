plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
    id("com.vanniktech.maven.publish") version "0.29.0"
    id("signing")
}

group = "io.last9"
version = "0.1.0-beta.1"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
}

val otelVersion = "1.44.0"
val otelAgentVersion = "2.10.0-alpha"
val testcontainersVersion = "1.19.7"

// Integration test source set — separate from unit tests, needs a live NATS server
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.implementation.get())
}

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

    // JSR-305 @Nullable — compileOnly, not needed at runtime
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")

    // Unit tests
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("io.nats:jnats:2.20.4")
    testImplementation("io.opentelemetry:opentelemetry-api:$otelVersion")
    testImplementation("io.opentelemetry:opentelemetry-sdk:$otelVersion")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:$otelVersion")
    testImplementation("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api:$otelAgentVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Integration tests — full deps (no compileOnly) for programmatic ByteBuddy + Testcontainers
    integrationTestImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    integrationTestImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    integrationTestImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    integrationTestImplementation("io.nats:jnats:2.20.4")
    integrationTestImplementation("net.bytebuddy:byte-buddy:1.14.18")
    integrationTestImplementation("net.bytebuddy:byte-buddy-agent:1.14.18")
    integrationTestImplementation("io.opentelemetry:opentelemetry-api:$otelVersion")
    integrationTestImplementation("io.opentelemetry:opentelemetry-sdk:$otelVersion")
    integrationTestImplementation("io.opentelemetry:opentelemetry-sdk-testing:$otelVersion")
    integrationTestImplementation("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api:$otelAgentVersion")
    configurations["integrationTestRuntimeOnly"]("org.junit.platform:junit-platform-launcher")
}

tasks {
    compileJava {
        options.release.set(8)
    }

    shadowJar {
        dependencies {
            exclude(dependency("io.opentelemetry:.*"))
            exclude(dependency("io.opentelemetry.javaagent:.*"))
            exclude(dependency("net.bytebuddy:.*"))
            exclude(dependency("com.google.auto.service:.*"))
        }
        archiveClassifier.set("")
    }

    assemble {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
    }
}

// Integration test task — runs against live NATS via Testcontainers
// ByteBuddyAgent.install() requires -Djdk.attach.allowAttachSelf=true
tasks.register<Test>("integrationTest") {
    description = "Runs integration tests with live NATS via Testcontainers"
    group = "verification"

    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath

    jvmArgs("-Djdk.attach.allowAttachSelf=true")
    useJUnitPlatform()
}

afterEvaluate {
    signing {
        useGpgCmd()
        sign(publishing.publications)
    }
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)

    pom {
        name.set("opentelemetry-nats-java")
        description.set("OTel Java agent extension for NATS auto-instrumentation — zero code changes, full messaging semconv")
        url.set("https://github.com/last9/opentelemetry-nats-java")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                name.set("Last9")
                email.set("support@last9.io")
                organization.set("Last9")
                organizationUrl.set("https://last9.io")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/last9/opentelemetry-nats-java.git")
            developerConnection.set("scm:git:ssh://github.com:last9/opentelemetry-nats-java.git")
            url.set("https://github.com/last9/opentelemetry-nats-java")
        }
    }
}
