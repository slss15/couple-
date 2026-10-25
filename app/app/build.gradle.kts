plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---- Release versioning & signing (owned by release pipeline, see ../../deploy/README.md) ----
// versionName comes from -Pcouple.versionName=X.Y.Z or env COUPLE_VERSION_NAME (CI sets it from the git tag);
// versionCode = X*10000 + Y*100 + Z. Local builds default to 0.1.0 (100).
val coupleVersionName: String = (findProperty("couple.versionName") as String?)
    ?: System.getenv("COUPLE_VERSION_NAME")?.takeIf { it.isNotBlank() }
    ?: "0.1.0"
val coupleVersionCode: Int = run {
    val m = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(coupleVersionName)
        ?: throw GradleException("versionName '$coupleVersionName' must be X.Y.Z")
    val (x, y, z) = m.destructured.toList().map { it.toInt() }
    if (y > 99 || z > 99) throw GradleException("minor/patch must be <= 99 (got $coupleVersionName)")
    x * 10000 + y * 100 + z
}
// Release signing from env (CI decodes SIGNING_KEYSTORE_BASE64 into SIGNING_STORE_FILE).
// Without it a local `assembleRelease` produces an *unsigned* APK — never debug-signed.
// With -Pcouple.requireReleaseSigning=true (the release workflow) missing signing is a hard error.
val signingStoreFile: String? = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
val signingStorePassword: String? = System.getenv("SIGNING_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val signingKeyAlias: String? = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val signingKeyPassword: String? = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val hasReleaseSigning = listOf(signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword).all { it != null }
if (!hasReleaseSigning && (findProperty("couple.requireReleaseSigning") as String?) == "true") {
    throw GradleException(
        "Release signing required but SIGNING_STORE_FILE / SIGNING_STORE_PASSWORD / SIGNING_KEY_ALIAS / " +
            "SIGNING_KEY_PASSWORD are not all set. Refusing to build an unsigned or debug-signed release."
    )
}

// OTA release-manifest public key (base64 raw Ed25519, see deploy/scripts/ota-keygen.sh), pinned at build time.
// -Pcouple.otaPublicKey=... or env OTA_PUBLIC_KEY. Empty = manifest verification skipped (debug/local builds only);
// the release workflow passes -Pcouple.requireOtaPublicKey=true so a release can never ship without a pin.
val otaPublicKey: String = ((findProperty("couple.otaPublicKey") as String?) ?: System.getenv("OTA_PUBLIC_KEY") ?: "").trim()
if (otaPublicKey.isNotEmpty() && !Regex("^[A-Za-z0-9+/]{43}=$").matches(otaPublicKey)) {
    throw GradleException("OTA_PUBLIC_KEY must be standard base64 of a 32-byte Ed25519 public key")
}
if (otaPublicKey.isEmpty() && (findProperty("couple.requireOtaPublicKey") as String?) == "true") {
    throw GradleException("OTA public key required (-Pcouple.otaPublicKey or env OTA_PUBLIC_KEY) but not set.")
}
val coupleProtocolVersion: Int = (findProperty("couple.protocolVersion") as String?)?.toInt() ?: 1
val coupleMinPeerProtocol: Int = (findProperty("couple.minPeerProtocol") as String?)?.toInt() ?: 1

android {
    namespace = "com.qimingnan.couple"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.qimingnan.couple"
        minSdk = 26
        targetSdk = 35
        versionCode = coupleVersionCode
        versionName = coupleVersionName
        // 与 contract.md 中 /health 的 protocolVersion 对齐（客户端<->服务器）
        buildConfigField("int", "PROTOCOL_VERSION", "1")
        // App<->app (partner) protocol, distinct from the client<->server PROTOCOL_VERSION above (docs/ota-design.md §4)
        buildConfigField("int", "PEER_PROTOCOL_VERSION", coupleProtocolVersion.toString())
        buildConfigField("int", "MIN_PEER_PROTOCOL", coupleMinPeerProtocol.toString())
        buildConfigField("String", "OTA_PUBLIC_KEY", "\"$otaPublicKey\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                enableV1Signing = false // minSdk 26: v2+v3 only
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "ALLOW_CLEARTEXT_DEBUG", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            buildConfigField("boolean", "ALLOW_CLEARTEXT_DEBUG", "false")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)

    implementation(libs.work.runtime.ktx)
    implementation(libs.paho.mqtt)
    implementation(libs.bouncycastle)
    // Silent self-update through Shizuku when available (see data/update/ApkInstaller.kt)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
