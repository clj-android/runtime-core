plugins {
    id("com.android.library")
    `maven-publish`
}

group = "org.clojure-android"
version = "0.1.0-SNAPSHOT"

android {
    namespace = "org.clojure_android.runtime.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    compileOnly("org.clojure:clojure:1.12.0")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate {
                from(components["release"])
            }
            groupId = "org.clojure-android"
            artifactId = "runtime-core"
            version = project.version.toString()
        }
    }
}
