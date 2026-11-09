plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.buiducluan23011767.ck"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.buiducluan23011767.ck"

        minSdk = 24
        targetSdk = 37

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true

                packageScope = setOf(
                    "androidx.**",
                    "kotlin.**",
                    "kotlinx.**"
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)

    testImplementation(libs.junit)

    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)

    // HTTP REST API
    implementation("com.android.volley:volley:1.2.1")

    // MQTT Client
    implementation(
        "org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5"
    )
}