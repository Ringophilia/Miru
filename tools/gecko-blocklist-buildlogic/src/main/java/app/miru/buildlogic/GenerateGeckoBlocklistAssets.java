// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.buildlogic;

import java.io.IOException;
import java.nio.file.Path;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Explicitly wired by the app's generated assets source; this class performs no configuration IO. */
@CacheableTask
public abstract class GenerateGeckoBlocklistAssets extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getGeckoAar();

    @Input
    public abstract Property<String> getGeckoArtifactVersion();

    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSnapshotDirectory();

    @OutputDirectory
    public abstract DirectoryProperty getOutputAssets();

    /** Deflater is supplied by the running JVM; prevent cache reuse across different writers. */
    @Input
    public String getZipWriterRuntime() {
        return System.getProperty("java.vendor") + "|" + System.getProperty("java.runtime.version")
            + "|" + System.getProperty("os.name") + "|" + System.getProperty("os.arch");
    }

    @TaskAction
    public void generate() throws IOException {
        Path outputDirectory = getOutputAssets().get().getAsFile().toPath();
        GeckoBlocklistOverlay.build(
            getGeckoAar().get().getAsFile().toPath(),
            getGeckoArtifactVersion().get(),
            getSnapshotDirectory().get().getAsFile().toPath(),
            outputDirectory.resolve("omni.ja"),
            getTemporaryDir().toPath()
        );
        getLogger().lifecycle("Generated pinned Gecko overlay: {} original resources preserved, 1 exact geolocation policy patch, 5 official blocklist resources added", GeckoBlocklistOverlay.UNCHANGED_ENTRY_COUNT);
    }
}
