plugins {
    java
}

group = "example"
version = "1.0.0"

repositories {
    mavenLocal()
    mavenCentral()
}

val skisVersion = providers.gradleProperty("skisVersion").get()

dependencies {
    implementation("io.github.xhufang:skis-annotations:$skisVersion")
    implementation("io.github.xhufang:skis-runtime:$skisVersion")
    annotationProcessor("io.github.xhufang:skis-processor:$skisVersion")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}
