plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nabi.todo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nabi.todo"
        minSdk = 26
        targetSdk = 34
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

    buildTypes {
        release {
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
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-firestore")
}
