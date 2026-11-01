# Workspace instructions

<!-- RINGO-IMAGEGEN DEFAULT START -->
## Default image generation
For requests to generate a new raster image (including “生成图片”, “画一张图”, and “generate an image”), use the ringo-imagegen skill and its MCP generate_image tool by default. Do not select the built-in imagegen skill or built-in image_gen tool for these requests unless the user explicitly chooses another backend.
The Ringo tool reads the existing Codex provider URL and credentials. Do not request or print API keys. Display the returned local image path with Markdown image syntax.
If the MCP tool is unavailable, report that Ringo is unavailable and check installation; do not silently switch providers. Ringo currently supports new images only, not editing existing images or reference-image uploads. Explain this limitation for editing requests.
This preference applies only to image generation; it does not change text-model traffic, including Responses API requests.
<!-- RINGO-IMAGEGEN DEFAULT END -->

## Validation artifacts and disk use

- Use `tools/build-player-validation.ps1` for frozen Debug validation builds and `tools/run-device-validation.ps1` for device regressions. Set `JAVA_HOME` and `ANDROID_HOME` or supply the runner's `-Adb` argument for this machine.
- Install directly from the pinned build snapshot. Never copy APKs into each test run directory. Pull installed APKs only to a temporary file, compare SHA-256 and size, and delete the temporary file in `finally`.
- Keep the latest two successful Debug snapshots, latest two ARM64 release APKs, and the latest published release's stock R8 input. Protect live validation inputs with the process/start-time lease used by the scripts. Supply `-Keep` for an additional input that is actively needed.
- After each build/validation batch and release publication, run `tools/clean-validation-artifacts.ps1 -Apply`. It must stay inside this repository's `artifacts` directory, reject reparse paths, and protect tracked files. Do not clean another checkout, shared SDK, Gradle dependency cache, source, or test fixture.
- Keep reports, logs, screenshots, source manifests and hashes as evidence. Historical report paths can refer to APKs subsequently pruned; consult the cleanup ledgers rather than claiming those binaries are still present.
- Aim to keep `artifacts` below 3 GB with current package sizes. If usage grows, inspect it and remove superseded generated binaries using the retention script. Do not accumulate full APKs merely to preserve evidence.
- Release optimization removes generated unsigned/signed scratch in `finally`, including on failure. Use `-KeepScratch` only for a specific investigation and prune it when finished.

See [docs/VALIDATION-ARTIFACTS.md](docs/VALIDATION-ARTIFACTS.md) for commands and retention details.
