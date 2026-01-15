plugins {
    java
    application
    id("org.graalvm.buildtools.native") version "0.10.4"
}

group = "com.github.youssefagagg.jnignx"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("com.github.youssefagagg.jnignx.NanoServer")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--enable-preview"))
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-preview")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("jnignx")
            mainClass.set("com.github.youssefagagg.jnignx.NanoServer")
            buildArgs.add("--enable-preview")
            // GraalVM Native Image configuration for zero-reflection
            buildArgs.add("--no-fallback")
            buildArgs.add("-H:+ReportExceptionStackTraces")
        }
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
