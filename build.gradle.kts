plugins {
    id("com.diffplug.spotless") version "8.10.4"
    kotlin("jvm") version "2.4.20" apply false
    id("net.fabricmc.fabric-loom") version "1.17.21" apply false
}
allprojects {
    group = "io.github.minimap"
    version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0")
    repositories { mavenCentral() }
}
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}

spotless {
    kotlin {
        target("**/src/**/*.kt")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint("1.7.1").editorConfigOverride(mapOf("ktlint_code_style" to "intellij_idea"))
    }
    kotlinGradle {
        target("*.gradle.kts", "server/*.gradle.kts", "client/*.gradle.kts")
        ktlint("1.7.1").editorConfigOverride(mapOf("ktlint_code_style" to "intellij_idea"))
    }
}
