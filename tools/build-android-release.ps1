param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (!$JavaHome) { throw 'Set JAVA_HOME to JDK 21 or pass -JavaHome.' }
if (!$AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:Path = "$(Join-Path $JavaHome 'bin');$env:Path"
$env:NODE_ENV = 'production'
$apkTools = Join-Path $AndroidSdk 'build-tools\37.0.0'
$node = (Get-Command node -ErrorAction Stop).Source

function Invoke-Checked([string]$Command, [string[]]$Arguments) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $Command $($Arguments -join ' ')" }
}

function Get-SourceHashes {
    $paths = & git ls-files --cached --others --exclude-standard -- App.tsx index.ts app.config.js package.json package-lock.json src modules plugins tools test
    if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate source files.' }
    @($paths | Sort-Object -Unique | ForEach-Object {
        $path = Join-Path $repo $_
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash }
        }
    })
}

Push-Location $repo
try {
    Invoke-Checked $node @('node_modules/typescript/bin/tsc', '--noEmit')
    Invoke-Checked $node @('--experimental-strip-types', '--test', 'test/parity.test.ts', 'test/ios.test.ts', 'test/android-plugin.test.cjs', 'test/zmk.test.ts')
    Invoke-Checked $node @('node_modules/expo/bin/cli', 'prebuild', '--platform', 'android', '--no-install')

    $config = (& $node -e 'process.stdout.write(JSON.stringify(require("./app.config.js")))') | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read app.config.js.' }
    $output = Join-Path $repo ('build\current-android\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $output | Out-Null
    $sourceHashes = Get-SourceHashes
    $sourceJson = ConvertTo-Json -InputObject $sourceHashes -Depth 4
    Set-Content -LiteralPath (Join-Path $output 'source-files.json') -Value $sourceJson -Encoding UTF8
    $commit = & git rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Cannot record source commit.' }
    $patchPath = Join-Path $output 'source.patch'
    & git diff --binary "--output=$patchPath" HEAD -- App.tsx index.ts app.config.js package.json package-lock.json src modules plugins tools test
    if ($LASTEXITCODE -ne 0) { throw 'Cannot record source changes.' }

    Push-Location (Join-Path $repo 'android')
    try {
        Invoke-Checked '.\gradlew.bat' @(':liki-hid:testDebugUnitTest', ':app:assembleRelease', '--init-script', (Join-Path $PSScriptRoot 'release-unsigned.gradle'), '--console=plain')
    } finally { Pop-Location }
    if ((ConvertTo-Json -InputObject (Get-SourceHashes) -Depth 4) -cne $sourceJson) {
        throw 'Source changed during the build. APK was not signed.'
    }

    $unsigned = Join-Path $repo 'android\app\build\outputs\apk\release\app-release-unsigned.apk'
    $aligned = Join-Path $output 'aligned.apk'
    $apk = Join-Path $output ("Liki-v$($config.version).apk")
    Invoke-Checked (Join-Path $apkTools 'zipalign.exe') @('-f', '-P', '16', '4', $unsigned, $aligned)
    $secure = ConvertTo-SecureString (Get-Content -LiteralPath (Join-Path $repo 'signing-backup\release-password.dpapi') -Raw).Trim()
    $env:LIKI_SIGNING_PASSWORD = ([pscredential]::new('liki', $secure)).GetNetworkCredential().Password
    try {
        Invoke-Checked (Join-Path $apkTools 'apksigner.bat') @('sign', '--ks', (Join-Path $repo 'signing-backup\release-signing.p12'), '--ks-key-alias', 'liki', '--ks-pass', 'env:LIKI_SIGNING_PASSWORD', '--key-pass', 'env:LIKI_SIGNING_PASSWORD', '--out', $apk, $aligned)
    } finally { Remove-Item Env:LIKI_SIGNING_PASSWORD -ErrorAction SilentlyContinue }
    Invoke-Checked (Join-Path $apkTools 'zipalign.exe') @('-c', '-P', '16', '4', $apk)
    $verification = & (Join-Path $apkTools 'apksigner.bat') verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $certificate = '5b4f187334a6f8213fbf71a26acf2522b34a81e63e19a417538ee94a50911afd'
    if (($verification -join "`n") -notmatch "certificate SHA-256 digest: $certificate") { throw 'Release certificate mismatch.' }
    $record = [ordered]@{
        sourceRoot = $repo; sourceCommit = "$commit"; versionName = $config.version
        versionCode = $config.android.versionCode; apk = $apk
        apkSha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
        certificateSha256 = $certificate
        sourceFiles = 'source-files.json'; sourceDiff = 'source.patch'
    }
    $record | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'build-record.json') -Encoding UTF8
    $record | ConvertTo-Json
} finally { Pop-Location }
