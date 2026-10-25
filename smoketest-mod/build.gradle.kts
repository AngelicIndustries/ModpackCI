plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.compileJava {
    options.release = 8
}

repositories {
    maven("https://maven.minecraftforge.net/") {
        metadataSources { artifact() }
    }
}

dependencies {
    compileOnly("net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10:universal")
}

tasks.compileJava {
    options.compilerArgs.add("-Xlint:-options")
}
