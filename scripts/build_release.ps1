param(
    [string]$Keystore = "C:\dev\task\task-release.jks",
    [string]$Alias = "task",
    [string]$StorePass = "task-tal-2026"
)

$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$buildTools = "C:\Android\Sdk\build-tools\36.0.0"

Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & .\gradlew.bat :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Gradle assembleRelease failed ($LASTEXITCODE)" }

    $unsignedApk = Get-ChildItem -Path .\app\build\outputs\apk\release -Filter "*.apk" | Select-Object -First 1 -ExpandProperty FullName
    $alignedApk = Join-Path (Split-Path $unsignedApk -Parent) "release-aligned.apk"
    $outApk = Join-Path (Get-Location) "release.apk"

    & "$buildTools\zipalign.exe" -p -f 4 $unsignedApk $alignedApk
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed ($LASTEXITCODE)" }

    & "$buildTools\apksigner.bat" sign `
        --v1-signing-enabled true `
        --v2-signing-enabled true `
        --v3-signing-enabled true `
        --ks $Keystore `
        --ks-key-alias $Alias `
        --ks-pass "pass:$StorePass" `
        --key-pass "pass:$StorePass" `
        --out $outApk `
        $alignedApk
    if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed ($LASTEXITCODE)" }

    Remove-Item $alignedApk -Force -ErrorAction SilentlyContinue

    & "$buildTools\apksigner.bat" verify --verbose --print-certs $outApk
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed ($LASTEXITCODE)" }

    $hash = (Get-FileHash $outApk -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -Path "$outApk.sha256" -Value "$hash  release.apk" -NoNewline
    Write-Host "RELEASE_READY=$outApk"
    Write-Host "SHA256=$hash"
} finally {
    Pop-Location
}
