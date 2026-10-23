plugins {
    kotlin("jvm")
    id("net.fabricmc.fabric-loom")
}
kotlin {
    jvmToolchain(25)
    sourceSets.main { kotlin.srcDir(rootProject.file("shared/src/main/kotlin")) }
}
repositories { maven("https://chocolateminecraft.com/maven/") }
dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.20.1")
    implementation("net.fabricmc.fabric-api:fabric-api:0.165.0+26.3")
    implementation("net.fabricmc:fabric-language-kotlin:1.14.1+kotlin.2.4.20")
    implementation("xaero.map:xaeroworldmap-fabric-26.3:1.47.1")
    implementation("xaero.lib:xaerolib-fabric-26.3:1.8.3")
}
base { archivesName = "mini-map-client" }
tasks.jar { from(rootProject.file("LICENSE")) }
tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}
val fixture = sourceSets.create("fixture") {
    compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output
}
tasks.register<Jar>("fixtureJar") {
    dependsOn(tasks.named(fixture.classesTaskName))
    archiveClassifier = "fixture"
    from(fixture.output)
}
