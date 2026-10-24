param(
    [Parameter(Mandatory = $true)][string]$ZipflingerJar,
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [string]$OutputRoot = ''
)
$ErrorActionPreference = 'Stop'
$miruRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($OutputRoot)) { $OutputRoot = Join-Path $miruRepository 'artifacts\apk-size-reuse' }
$miruOutput = [IO.Path]::GetFullPath($OutputRoot)
$miruJar = (Resolve-Path -LiteralPath $ZipflingerJar).Path
if ((Get-FileHash -LiteralPath $miruJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'd2ad6ddec6da2ae142a8fe3af2f2529f3fbf77852e2db7ff261ad7b924b119fa') {
    throw 'Expected the pinned official com.android:zipflinger:9.1.1 JAR.'
}
$miruJavac = Join-Path $JdkHome 'bin\javac.exe'
$miruJava = Join-Path $JdkHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $miruJavac) -or -not (Test-Path -LiteralPath $miruJava)) { throw 'JDK 21 is required.' }
New-Item -ItemType Directory -Path $miruOutput -Force | Out-Null
$miruRun = Join-Path $miruOutput ('run-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $miruRun | Out-Null
$miruClasses = Join-Path $miruRun 'classes'
New-Item -ItemType Directory -Path $miruClasses | Out-Null
$miruSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
& $miruJavac --release 21 -encoding UTF-8 -cp $miruJar -d $miruClasses @miruSources 2>&1 | Tee-Object -FilePath (Join-Path $miruRun 'compile.log')
if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }
& $miruJava -cp ($miruClasses + [IO.Path]::PathSeparator + $miruJar) app.miru.release.ApkCompressionReuseTest (Join-Path $miruRun 'fixtures') 2>&1 | Tee-Object -FilePath (Join-Path $miruRun 'tests.log')
if ($LASTEXITCODE -ne 0) { throw "Standalone tests failed: $LASTEXITCODE" }
Write-Output "Evidence: $miruRun"
