plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":common"))
    implementation(libs.bundles.netty)
    implementation(libs.bundles.log4j)
    implementation(libs.bundles.unirest)
    api(libs.bundles.cloud)

    implementation(libs.terminalconsoleappender)
    implementation(libs.nightconfig.toml)
    implementation(libs.fastutil)
    implementation(libs.jose.jwt)
    implementation(libs.tink)
    implementation(libs.guava)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    val nativeQuicDep = libs.netty.codec.native.quic.get()
    listOf(
        "linux-x86_64",
        "linux-aarch_64",
        "osx-x86_64",
        "osx-aarch_64",
        "windows-x86_64"
    ).forEach {
        implementation("${nativeQuicDep.module}:${nativeQuicDep.version}:$it")
    }

    // i honestly dont think these would make sense in the catalog
    implementation("org.bouncycastle:bcpkix-jdk18on:1.83")
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
}

tasks.shadowJar {
    archiveFileName.set("hyproxy-$version.jar")
    mergeServiceFiles()

    manifest {
        attributes (
            "Main-Class" to "ac.eva.hyproxy.Main"
        )
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
