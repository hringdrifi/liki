param([switch]$Test)
$ErrorActionPreference = 'Stop'
$source = $PSScriptRoot
$root = Split-Path (Split-Path $source -Parent) -Parent
$output = Join-Path $root 'build\windows-reconnect'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$framework = Split-Path $compiler -Parent
$sdk = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\UnionMetadata'
$metadata = Get-ChildItem -LiteralPath $sdk -Filter Windows.winmd -Recurse |
    Where-Object { $_.Directory.Name -match '^\d+\.\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Directory.Name } -Descending | Select-Object -First 1
if (!$metadata) { throw 'Windows SDKのWindows.winmdが見つかりません。' }
$references = @(
    $metadata.FullName,
    (Join-Path $framework 'System.Runtime.WindowsRuntime.dll'),
    (Join-Path $framework 'System.Runtime.InteropServices.WindowsRuntime.dll'),
    (Join-Path ${env:ProgramFiles(x86)} 'Reference Assemblies\Microsoft\Framework\.NETFramework\v4.5\Facades\System.Runtime.dll'),
    'System.Drawing.dll', 'System.Windows.Forms.dll', 'System.Runtime.Serialization.dll'
)
$common = @('/nologo', '/langversion:5', '/optimize+', '/utf8output') + ($references | ForEach-Object { '/reference:' + $_ })
$sources = @('Settings.cs', 'DeviceConnection.cs', 'Program.cs') | ForEach-Object { Join-Path $source $_ }
$exe = Join-Path $output 'LikiReconnect.exe'
& $compiler @common /target:winexe "/win32icon:$(Join-Path $source 'liki.ico')" "/out:$exe" @sources
if ($LASTEXITCODE -ne 0) { throw 'ビルドに失敗しました。' }
if ($Test) {
    $tests = Join-Path $output 'LikiReconnect.Tests.exe'
    & $compiler @common /target:exe "/out:$tests" (Join-Path $source 'Settings.cs') (Join-Path $source 'Tests.cs')
    if ($LASTEXITCODE -ne 0) { throw 'テストのビルドに失敗しました。' }
    & $tests
    if ($LASTEXITCODE -ne 0) { throw 'テストに失敗しました。' }
}
Write-Output $exe
