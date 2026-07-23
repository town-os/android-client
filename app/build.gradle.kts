import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The version that ships is whatever tag was pushed: the release workflow passes
// -PversionName=<tag>, and this literal is only the fallback for local builds.
// Keeping one source of truth means a release can never be cut with a stale
// version baked into it.
val appVersionName = (findProperty("versionName") as String?) ?: "0.0.1"

// versionCode must increase monotonically or Android refuses to install an
// update over an existing install, so derive it from the semver rather than
// hand-maintaining a counter that will inevitably drift. 1.2.3 -> 10203, which
// leaves room for 100 minors and 100 patches per major.
val appVersionCode = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(appVersionName)
    ?.destructured
    ?.let { (major, minor, patch) -> major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt() }
    ?: 1

android {
    namespace = "com.townos.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.townos.client"
        // 26 is the floor for the WireGuard GoBackend's VpnService use and for
        // LinkProperties.isPrivateDnsActive(), which we need to warn the user
        // when Android's Private DNS would bypass the box's resolver.
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest and compiled resources to
            // inflate anything (Compose included). Without this every Robolectric
            // test dies on a missing resource table.
            isIncludeAndroidResources = true
        }
    }
}

/**
 * On an ARM host, fork unit-test workers with an x86_64 JVM.
 *
 * Robolectric — how the Android-framework code is tested without a phone —
 * ships native libraries for linux-x86_64 only and has NO linux-aarch64 build
 * ("The Robolectric native runtime is not supported on Linux (aarch64)"). The
 * repo already has an x86_64 execution environment for aapt2 (FEX, inside muvm
 * on a 16K-page kernel; see make/emulation.sh), and an x86_64 JVM runs there
 * fine — so the test workers, and only the test workers, use it.
 *
 * Gradle and the Kotlin compiler keep running on the host's native aarch64 JVM,
 * so the emulation cost is confined to the tests that actually need it. Falls
 * back to the default JVM when the cross JDK is absent (x86_64 hosts, CI), so
 * the build never depends on it existing.
 */
val crossJdk = rootProject.file(".x86-jdk/bin/java")

tasks.withType<Test>().configureEach {
    if (System.getProperty("os.arch") == "aarch64" && crossJdk.exists()) {
        executable = crossJdk.absolutePath
    }

    // `make test` must actually run the tests, every time.
    //
    // Gradle's up-to-date check is right for a build but wrong for a test target:
    // with unchanged inputs it reports "26 actionable tasks: 26 up-to-date",
    // skips the suite, and prints nothing at all. You then cannot tell "106 tests
    // passed" from "the tests never ran" — and when you asked for tests, you want
    // to see them run.
    outputs.upToDateWhen { false }

    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = true
    }

    // Gradle prints no summary of its own, and the HTML report is not where
    // anyone looks from a terminal. Print the counts so a run is self-evidently
    // a run.
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) = Unit
        override fun beforeTest(test: TestDescriptor) = Unit
        override fun afterTest(test: TestDescriptor, result: TestResult) = Unit

        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            // parent == null is the root suite: the totals for the whole run.
            if (suite.parent != null) return
            println(
                "\n${result.resultType}: ${result.testCount} tests, " +
                    "${result.successfulTestCount} passed, " +
                    "${result.failedTestCount} failed, " +
                    "${result.skippedTestCount} skipped",
            )
        }
    })
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.wireguard.tunnel)
    implementation(libs.dnsjava)

    testImplementation(libs.junit)
    testImplementation(libs.bouncycastle.pkix)
    testImplementation(libs.okhttp.mockwebserver)

    // Android-framework and Compose tests, run on the JVM under Robolectric so
    // they stay part of `make test` rather than needing an emulator.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
