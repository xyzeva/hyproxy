plugins {
    id("java")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":common"))
    compileOnly(libs.hytale)
}


tasks.shadowJar {
    archiveFileName.set("hyproxy-backend-$version.jar")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
