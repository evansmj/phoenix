import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import java.io.ByteArrayOutputStream
import co.touchlab.skie.configuration.FlowInterop

plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")
    id("com.squareup.sqldelight")
    if (System.getProperty("includeAndroid")?.toBoolean() == true) {
        id("com.android.library")
    }
    id("co.touchlab.skie") version "0.6.1"
}

val includeAndroid = System.getProperty("includeAndroid")?.toBoolean() ?: false

/** Get the current git commit hash. */
fun gitCommitHash(): String {
    val stream = ByteArrayOutputStream()
    project.exec {
        commandLine = "git rev-parse --verify --long HEAD".split(" ")
        standardOutput = stream
    }
    return String(stream.toByteArray()).split("\n").first()
}

/**
 * Generates a `BuildVersions` file in phoenix-shared/build/generated-src containing the current git commit and the lightning-kmp version.
 * See https://stackoverflow.com/a/74771876 for details.
 */
val buildVersionsTask by tasks.registering(Sync::class) {
    from(
        resources.text.fromString(
            """
            |package fr.acinq.phoenix.shared
            |
            |object BuildVersions {
            |    const val PHOENIX_COMMIT = "${gitCommitHash()}"
            |    const val LIGHTNING_KMP_VERSION = "${Versions.lightningKmp}"
            |}
            |
            """.trimMargin()
        )
    ) {
        rename { "BuildVersions.kt" }
        into("fr/acinq/phoenix/shared")
    }
    into(layout.buildDirectory.dir("generated-src/kotlin/"))
}

kotlin {
    if (includeAndroid) {
        androidTarget {
            compilations.all {
                kotlinOptions.jvmTarget = "1.8"
            }
        }
    }

    listOf(iosX64(), iosArm64()).forEach {
        it.binaries {
            framework {
                optimized = false
                baseName = "PhoenixShared"
            }
            configureEach {
                it.compilations.all {
                    kotlinOptions.freeCompilerArgs += "-Xoverride-konan-properties=osVersionMin.ios_x64=15.0;osVersionMin.ios_arm64=15.0"
                    // The notification-service-extension is limited to 24 MB of memory.
                    // With mimalloc we can easily hit the 24 MB limit, and the OS kills the process.
                    // But with standard allocation, we're using less then half the limit.
                    kotlinOptions.freeCompilerArgs += "-Xallocator=std"
                    kotlinOptions.freeCompilerArgs += listOf("-linker-options", "-application_extension")
                    // workaround for xcode 15 and kotlin < 1.9.10: 
                    // https://youtrack.jetbrains.com/issue/KT-60230/Native-unknown-options-iossimulatorversionmin-sdkversion-with-Xcode-15-beta-3
                    linkerOpts += "-ld64"
                }
            }
        }
    }

    sourceSets {
        // -- common sources
        val commonMain by getting {
            // marks the dir generated by `buildVersionTask` as a source, and tells gradle to launch that task whenever the sources are compiled.
            kotlin.srcDir(buildVersionsTask.map { it.destinationDir })
            dependencies {
                // lightning-kmp
                api("fr.acinq.lightning:lightning-kmp:${Versions.lightningKmp}")
                api("fr.acinq.tor:tor-mobile-kmp:${Versions.torMobile}")
                // ktor
                implementation("io.ktor:ktor-client-core:${Versions.ktor}")
                implementation("io.ktor:ktor-client-json:${Versions.ktor}")
                implementation("io.ktor:ktor-serialization-kotlinx-json:${Versions.ktor}")
                implementation("io.ktor:ktor-client-content-negotiation:${Versions.ktor}")
                // sqldelight
                implementation("com.squareup.sqldelight:runtime:${Versions.sqlDelight}")
                implementation("com.squareup.sqldelight:coroutines-extensions:${Versions.sqlDelight}")
                // SKEI
                implementation("co.touchlab.skie:configuration-annotations:0.6.1")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation("io.ktor:ktor-client-mock:${Versions.ktor}")
            }
        }

        // -- android sources
        if (includeAndroid) {
            val androidMain by getting {
                dependencies {
                    implementation("androidx.core:core-ktx:${Versions.Android.coreKtx}")
                    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:${Versions.secp256k1}")
                    implementation("io.ktor:ktor-network:${Versions.ktor}")
                    implementation("io.ktor:ktor-network-tls:${Versions.ktor}")
                    implementation("io.ktor:ktor-client-android:${Versions.ktor}")
                    implementation("com.squareup.sqldelight:android-driver:${Versions.sqlDelight}")
                }
            }
            val androidUnitTest by getting {
                dependencies {
                    implementation(kotlin("test-junit"))
                    implementation("androidx.test.ext:junit:1.1.3")
                    implementation("androidx.test.espresso:espresso-core:3.4.0")
                    val currentOs = org.gradle.internal.os.OperatingSystem.current()
                    val target = when {
                        currentOs.isLinux -> "linux"
                        currentOs.isMacOsX -> "darwin"
                        currentOs.isWindows -> "mingw"
                        else -> error("Unsupported OS $currentOs")
                    }
                    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm-$target:${Versions.secp256k1}")
                    implementation("com.squareup.sqldelight:sqlite-driver:${Versions.sqlDelight}")
                }
            }
        }

        // -- ios sources
        val iosMain by creating {
            dependencies {
                implementation("io.ktor:ktor-client-ios:${Versions.ktor}")
                implementation("com.squareup.sqldelight:native-driver:${Versions.sqlDelight}")
            }
        }

        val iosTest by creating {
            dependencies {
                implementation("com.squareup.sqldelight:native-driver:${Versions.sqlDelight}")
            }
        }

        all {
            languageSettings.optIn("kotlin.RequiresOptIn")
        }
    }
}

sqldelight {
    database("ChannelsDatabase") {
        packageName = "fr.acinq.phoenix.db"
        sourceFolders = listOf("channelsdb")
    }
    database("PaymentsDatabase") {
        packageName = "fr.acinq.phoenix.db"
        sourceFolders = listOf("paymentsdb")
    }
    database("AppDatabase") {
        packageName = "fr.acinq.phoenix.db"
        sourceFolders = listOf("appdb")
    }
}

if (includeAndroid) {
    extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
        namespace = "fr.acinq.phoenix.shared"
        compileSdk = 33
        defaultConfig {
            minSdk = 26
            targetSdk = 33
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        testOptions {
            unitTests.isReturnDefaultValues = true
        }

        sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    }
}

skie {
    analytics {
        disableUpload.set(true)
    }
}
