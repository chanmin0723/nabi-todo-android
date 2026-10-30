plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nabi.todo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nabi.todo"
        minSdk = 26
        targetSdk = 36
        // CI 실행 번호로 버전을 올려 업데이트 설치가 되도록 (로컬 빌드는 1)
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.0.$run"

        // 웹과 같은 Firebase 프로젝트(todo-657ec)에 연결합니다.
        buildConfigField("String", "FB_API_KEY", "\"AIzaSyAaAtNMNmm5Ygrbd_LAHYOYGHNQ4aT44-A\"")
        buildConfigField("String", "FB_APP_ID", "\"1:586546571470:web:9f868100c4ad7b37d6c89f\"")
        buildConfigField("String", "FB_PROJECT_ID", "\"todo-657ec\"")
        buildConfigField("String", "FB_SENDER_ID", "\"586546571470\"")
        buildConfigField("String", "FB_BUCKET", "\"todo-657ec.firebasestorage.app\"")
    }

    // 빌드할 때마다 서명키가 바뀌면 업데이트 설치가 막히므로 고정 키 사용 (개인용)
    signingConfigs {
        getByName("debug") {
            storeFile = file("nabi-todo.keystore")
            storePassword = "android"
            keyAlias = "nabitodo"
            keyPassword = "android"
        }
    }

    // direct: GitHub에서 받아 쓰는 버전(자동 업데이트 포함)
    // store : Google Play용 버전(자동 업데이트·APK 설치 권한 없음)
    flavorDimensions += "dist"
    productFlavors {
        create("direct") { dimension = "dist" }
        create("store") { dimension = "dist" }
    }

    // Play 업로드 키: CI가 환경변수로 넘겨 줄 때만 사용(키 파일은 저장소에 두지 않음)
    signingConfigs {
        if (System.getenv("STORE_KEYSTORE_FILE") != null) {
            create("upload") {
                storeFile = file(System.getenv("STORE_KEYSTORE_FILE"))
                storePassword = System.getenv("STORE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("STORE_KEY_ALIAS")
                keyPassword = System.getenv("STORE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (System.getenv("STORE_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("upload")
            }
            isMinifyEnabled = false
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.fragment:fragment:1.8.5") // 결과 런처가 요구하는 최소 버전(lint)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-firestore")
}
