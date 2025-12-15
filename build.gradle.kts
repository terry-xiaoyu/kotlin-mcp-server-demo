plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

group = "org.example"
version = "0.1.0"

application {
    mainClass.set("io.modelcontextprotocol.sample.server.MainKt")
}

dependencies {
    implementation("com.github.terry-xiaoyu.kotlin-sdk:kotlin-sdk-jvm:0.8.1.3")
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.cio)
    implementation(ktorLibs.server.cors)
    implementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(ktorLibs.client.cio)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        javaParameters = true
        freeCompilerArgs.addAll(
            "-Xdebug",
        )
    }
}
