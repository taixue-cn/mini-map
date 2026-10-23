import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `maven-publish`
}
repositories { maven("https://repo.papermc.io/repository/maven-public/") }
kotlin {
    jvmToolchain(25)
    compilerOptions { jvmTarget = JvmTarget.JVM_21 }
    sourceSets.main { kotlin.srcDir(rootProject.file("shared/src/main/kotlin")) }
    sourceSets.test { kotlin.srcDir(rootProject.file("shared/src/test/kotlin")) }
}
java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}
tasks.withType<JavaCompile>().configureEach { options.release = 21 }
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}
base { archivesName = "mini-map-server" }
tasks.jar {
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
tasks.processResources { filesMatching("plugin.yml") { expand("version" to project.version) } }
val fixture = sourceSets.create("fixture") {
    compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output
}
val fixtureJar = tasks.register<Jar>("fixtureJar") {
    dependsOn(tasks.named(fixture.classesTaskName))
    archiveClassifier = "fixture"
    from(fixture.output)
}
val providerApiJar = tasks.register<Jar>("providerApiJar") {
    dependsOn(tasks.classes)
    archiveBaseName = "mini-map-provider-api"
    from(sourceSets.main.get().output) { include("io/github/minimap/api/**") }
}
publishing {
    publications.create<MavenPublication>("providerApi") {
        artifactId = "mini-map-provider-api"
        artifact(providerApiJar)
        pom.withXml {
            val dependencies = asNode().appendNode("dependencies")
            val dependency = dependencies.appendNode("dependency")
            dependency.appendNode("groupId", "org.jetbrains.kotlin")
            dependency.appendNode("artifactId", "kotlin-stdlib")
            dependency.appendNode("version", "2.4.20")
        }
    }
}
tasks.assemble { dependsOn(providerApiJar) }
