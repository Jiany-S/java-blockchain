import org.gradle.api.plugins.BasePlugin

plugins {
    id("java")
    id("application")
    id("jacoco")
}

repositories { mavenCentral() }

dependencies {
    // --- app deps ---
    implementation("org.rocksdb:rocksdbjni:9.6.1")
    implementation("io.netty:netty-all:4.1.115.Final")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("io.micrometer:micrometer-core:1.13.3")
    implementation("io.micrometer:micrometer-registry-prometheus:1.13.3")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.6")

    // --- JUnit 5 (make platform + engine present at test runtime) ---
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")              // API + params
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")          // Engine
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")      // Ensure launcher is present on runtime
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        csv.required.set(false)
        html.required.set(true)
    }
}

tasks.register<Jar>("uberJar") {
    group = BasePlugin.BUILD_GROUP
    description = "Assembles an uber JAR containing all runtime dependencies."
    archiveBaseName.set("java-blockchain")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

application {
    mainClass.set("io.blockchain.core.Main")
}
