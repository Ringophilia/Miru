plugins { id("com.android.application") version "9.1.1" }
abstract class AvProbeAssetsTask : DefaultTask() {
    @get:InputFile abstract val movie: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun copyMovie() {
        val directory = outputDirectory.get().asFile
        directory.mkdirs()
        movie.get().asFile.copyTo(directory.resolve("av-colors.mp4"), overwrite = true)
    }
}
val avProbeAssets = tasks.register<AvProbeAssetsTask>("copyAvProbeAssets") {
    movie.set(file(providers.gradleProperty("miruRoot").get()).resolve("app/src/androidTest/assets/media/cached-ranges/av-colors.mp4"))
    outputDirectory.set(layout.buildDirectory.dir("generated/av-probe-assets"))
}
android {
    namespace = "app.miru.releaseprobe"
    compileSdk = 37
    defaultConfig { applicationId = "app.miru.releaseprobe"; minSdk = 26; targetSdk = 37; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets.getByName("main").assets.srcDir(file(providers.gradleProperty("miruRoot").get()).resolve("app/src/androidTest/assets/media/seek-preview"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(avProbeAssets, AvProbeAssetsTask::outputDirectory)
}
