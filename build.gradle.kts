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

// When consumed via includeBuild(), raw project configurations are exposed
// instead of published module metadata.  AGP's published metadata includes
// these attributes automatically, but the raw configurations do not, so we
// add them here for composite-build compatibility.
afterEvaluate {
    val categoryAttr = Attribute.of("org.gradle.category", Named::class.java)
    val jvmEnvAttr = Attribute.of("org.gradle.jvm.environment", Named::class.java)
    val kotlinPlatformAttr = Attribute.of("org.jetbrains.kotlin.platform.type", Named::class.java)

    configurations.configureEach {
        if (isCanBeConsumed && !isCanBeResolved) {
            attributes {
                attribute(categoryAttr, objects.named("library"))
                attribute(jvmEnvAttr, objects.named("android"))
                attribute(kotlinPlatformAttr, objects.named("androidJvm"))
            }
        }
    }
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
