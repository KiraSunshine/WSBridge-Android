import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.l1ratch.wsbridge"
    // compileSdk берётся из version catalog; ключ -Pwsb.compileSdk=N позволяет
    // собрать на другой платформе. Нужен для локальной проверки: на машине
    // может стоять только один platform (например android-37.0), и без
    // переопределения юнит-тесты не запускаются вовсе.
    compileSdk = (project.findProperty("wsb.compileSdk") as String?)?.toIntOrNull()
        ?: libs.versions.android.compileSdk.get().toInt()

    // ── Версия: ДВА числа, источник — gradle.properties ───────────────
    // wsb.build  — номер сборки, монотонный, кормит versionCode. CI
    //              передаёт BUILD_NUMBER = wsb.build, так что max() —
    //              защита от случайного расхождения, а не источник.
    // wsb.version — маркетинговая semver (0.3.0), кормит versionName и
    //              проверку тега релиза; от номера сборки НЕ зависит.
    val wsbBuild = (project.findProperty("wsb.build") as String?)?.toIntOrNull() ?: 1
    val wsbVersion = (project.findProperty("wsb.version") as String?) ?: "0.0.0"
    val ciBuild = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 0
    val buildNo = maxOf(wsbBuild, ciBuild)
    // Нативная сборка выключается ключом -Pwsb.native=false (по умолчанию — вкл).
    val nativeBuild = (project.findProperty("wsb.native") as String?) != "false"

    defaultConfig {
        applicationId = "com.l1ratch.wsbridge"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = buildNo
        // Маркетинговая версия отдельно от номера сборки: numberCode в UI
        // не дублирует, тег релиза совпадает с versionName.
        versionName = wsbVersion
        if (nativeBuild) {
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    // Нативная часть (lwIP + JNI) собирается ТОЛЬКО когда есть NDK, и её
    // можно отключить ключом -Pwsb.native=false. Без NDK Gradle падает уже на
    // конфигурации проекта, то есть юнит-тесты (они нативной части не касаются)
    // нельзя было прогнать вообще — а именно они проверяют кадрирование DoT и
    // сборку DNS-пакетов, где ошибка молчит и ломает весь DNS в туннеле.
    if (nativeBuild) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val b64 = System.getenv("RELEASE_KEYSTORE_B64")
            if (b64 != null) {
                storeFile = file("${layout.buildDirectory.get().asFile}/release.keystore").apply {
                    parentFile.mkdirs()
                    writeBytes(Base64.getDecoder().decode(b64))
                }
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "wsbridge"
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // ponytail: релиз подписывается debug-ключом — self-hosted APK вне
            // Play Store (аналог unsigned IPA на iOS). Свой keystore подаётся из
            // секретов CI (RELEASE_KEYSTORE_B64 + пароли), как в krasava-app.
            signingConfig = if (System.getenv("RELEASE_KEYSTORE_B64") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.iconsExtended)
    implementation(libs.compose.ui)
    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
    implementation(libs.okhttp)

    testImplementation("junit:junit:4.13.2")
}
