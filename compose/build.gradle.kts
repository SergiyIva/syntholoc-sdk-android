plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
    id("maven-publish")
}
group = "com.syntholoc"
version = providers.gradleProperty("sdkVersion").getOrElse("0.1.0")
android {
    namespace = "com.syntholoc.ota.compose"
    compileSdk = 35
    defaultConfig { minSdk = 24; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    publishing { singleVariant("release") { withSourcesJar() } }
}
dependencies {
    api(project(":"))
    implementation("androidx.compose.runtime:runtime:1.6.8")
    implementation("androidx.compose.ui:ui:1.6.8")
    androidTestImplementation("androidx.compose.foundation:foundation:1.6.8")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.6.8")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.6.8")
}
afterEvaluate {
    publishing { publications { create<MavenPublication>("release") { from(components["release"]); artifactId = "ota-compose" } } }
}

publishing {
    repositories {
        maven {
            name = "Sdk"
            url = uri(providers.gradleProperty("sdkRepository").getOrElse(rootProject.layout.buildDirectory.dir("maven").get().asFile.absolutePath))
        }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set(project.name)
            description.set("Syntholoc OTA translation delivery SDK")
            url.set("https://github.com/SergiyIva/syntholoc-sdk-android")
            licenses { license { name.set("MIT License"); url.set("https://opensource.org/licenses/MIT") } }
            scm { url.set("https://github.com/SergiyIva/syntholoc-sdk-android") }
        }
    }
}
