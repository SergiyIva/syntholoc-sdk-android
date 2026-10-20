plugins {
    id("com.android.library") version "8.10.1"
    id("org.jetbrains.kotlin.android") version "2.2.0"
    id("maven-publish")
}
group = "com.syntholoc"
version = providers.gradleProperty("sdkVersion").getOrElse("0.1.1")
android {
    namespace = "com.syntholoc.ota"
    compileSdk = 35
    defaultConfig { minSdk = 24; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("test").resources.srcDir("protocol-fixtures")
    publishing { singleVariant("release") { withSourcesJar() } }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
afterEvaluate {
    publishing { publications { create<MavenPublication>("release") { from(components["release"]); artifactId = "ota" } } }
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
