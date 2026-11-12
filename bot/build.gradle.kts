import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Built as a subproject of Unciv's own Gradle build (see ../setup.sh), so it
// shares Unciv's version catalog and compiles against the real `core` module.
plugins {
    id("kotlin")
    application
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_21 }
}
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(project(":core"))
    implementation(rootProject.libs.gdx)
    implementation(rootProject.libs.gdx.backend.headless)
    implementation(rootProject.libs.coroutines.core)
    implementation(rootProject.libs.kotlin.reflect)
    implementation(rootProject.libs.logback)

    // Discord. Voice/encryption extras are not needed.
    implementation("net.dv8tion:JDA:5.6.1") {
        exclude(module = "opus-java")
        exclude(module = "tink")
    }

    // Optional Activity add-on web server
    implementation(rootProject.libs.ktor.server.core)
    implementation(rootProject.libs.ktor.server.netty)
    implementation(rootProject.libs.ktor.client.core)
    implementation(rootProject.libs.ktor.client.cio)
}

application {
    mainClass.set("civbot.MainKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true", "-Xmx2g")
}
