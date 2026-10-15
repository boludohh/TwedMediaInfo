/*
 * Copyright 2026 TwedMediaInfo — boludohh
 */

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

// Versión única, definida en gradle.properties (VERSION_NAME).
val libraryVersion: String = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "com.twedmediainfo.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 24

        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_ARM_MODE=arm"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "28.2.13676358"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = false)

    signAllPublications()

    coordinates(
        "io.github.boludohh",
        "twedmediainfo",
        libraryVersion
    )

    pom {
        name = "TwedMediaInfo"
        description = "Librería Android que adapta MediaInfo para extraer información y metadatos de archivos multimedia."
        inceptionYear = "2026"
        url = "https://github.com/boludohh/TwedMediaInfo"

        licenses {
            license {
                name = "Apache License 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0"
                distribution = "repo"
            }
        }

        developers {
            developer {
                id = "boludohh"
                name = "boludohh"
                email = "lautyryal@gmail.com"
                url = "https://github.com/boludohh"
            }
        }

        scm {
            url = "https://github.com/boludohh/TwedMediaInfo"
            connection = "scm:git:git://github.com/boludohh/TwedMediaInfo.git"
            developerConnection = "scm:git:ssh://git@github.com/boludohh/TwedMediaInfo.git"
        }
    }
}