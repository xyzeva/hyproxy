plugins {
    id("java")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":proxy"))
}


tasks.shadowJar {
    archiveFileName.set("hyproxy-examples-$version.jar")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
