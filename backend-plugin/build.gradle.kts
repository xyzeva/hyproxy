plugins {
    id("java")
    alias(libs.plugins.shadow)
}

repositories {
    maven("https://maven.hytale.com/release")
}

dependencies {
    implementation(project(":common"))
    compileOnly(libs.hytale.server)
}


tasks.shadowJar {
    archiveFileName.set("hyproxy-backend-$version.jar")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
