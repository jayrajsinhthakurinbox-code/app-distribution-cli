plugins {
    kotlin("jvm") version "2.4.20"
    application
}

group = "io.github.jayrajsinhthakurinbox-code"
version = providers.gradleProperty("version").get()

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.json:json:20250517")

    testImplementation(kotlin("test"))
}

kotlin {
    // Runs on the Java bundled with Android Studio / IntelliJ (JBR 21+)
    jvmToolchain(21)
}

application {
    mainClass = "io.github.jayrajsinh.appdistribution.cli.MainKt"
    applicationName = "appdist"
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}
