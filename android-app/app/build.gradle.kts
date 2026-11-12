import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val mqttProperties = Properties()
val mqttPropertiesFile = rootProject.file("mqtt.properties")
if (mqttPropertiesFile.isFile) {
    mqttPropertiesFile.inputStream().use { mqttProperties.load(it) }
}

fun javaString(value: String): String = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r") + "\""

android {
    namespace = "com.buiducluan23011767.ck"

    compileSdk {
        version = release(37)
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.buiducluan23011767.ck"

        minSdk = 24
        targetSdk = 37

        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "HIVEMQ_USERNAME",
            javaString(mqttProperties.getProperty("hivemq.username", "")))
        buildConfigField("String", "HIVEMQ_PASSWORD",
            javaString(mqttProperties.getProperty("hivemq.password", "")))

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
