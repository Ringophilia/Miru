plugins { id("com.android.application") version "9.1.1" }
android {
    namespace = "app.miru.releaseprobe"
    compileSdk = 37
    defaultConfig { applicationId = "app.miru.releaseprobe"; minSdk = 26; targetSdk = 37; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets.getByName("main").assets.srcDir(file(providers.gradleProperty("miruRoot").get()).resolve("app/src/androidTest/assets/media/seek-preview"))
}
