plugins {
    java
}

group = "io.github.angelicindustries"
version = providers.gradleProperty("modpackciVersion").getOrElse("dev")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.15.0")
    implementation("org.tomlj:tomlj:2.2.0")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    // The smoke test helper mod ships inside the tool jar.
    from(project(":smoketest-mod").tasks.named("jar")) {
        into("smoketest")
        rename { "modpackci-smoketest.jar" }
    }
}

tasks.jar {
    archiveFileName = "modpackci-${project.version}.jar"
    manifest {
        attributes(
            "Main-Class" to "io.github.angelicindustries.modpackci.Main",
            "Implementation-Version" to project.version,
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.map { files -> files.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/versions/**", "module-info.class")
    }
}
