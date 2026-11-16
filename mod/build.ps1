param(
    [Parameter(Mandatory)] [string] $MinecraftRoot,
    [Parameter(Mandatory)] [string] $Jdk,
    [string] $Instance,
    [ValidateSet('26.2', '26.3')] [string] $MinecraftVersion = '26.3'
)
$ErrorActionPreference = 'Stop'
$targets = @{
    '26.2' = @{ Loader = '0.19.5'; Api = '0.160.0+26.2' }
    '26.3' = @{ Loader = '0.20.1'; Api = '0.165.2+26.3' }
}
$target = $targets[$MinecraftVersion]
if (!$Instance) { $Instance = "$MinecraftVersion-Fabric $($target.Loader)" }
$game = Join-Path $MinecraftRoot "versions/$Instance"
$meta = Get-Content -LiteralPath "$game/$Instance.json" -Raw | ConvertFrom-Json
if ($meta.libraries.name -notcontains "net.fabricmc:fabric-loader:$($target.Loader)") {
    throw "Expected Fabric Loader $($target.Loader) in the $MinecraftVersion instance."
}
$api = Get-Item -LiteralPath "$game/mods/fabric-api-$($target.Api).jar"
$build = Join-Path $PSScriptRoot ('build/' + [guid]::NewGuid())
$classes = New-Item -ItemType Directory -Path "$build/classes" -Force
$nested = New-Item -ItemType Directory -Path "$build/api" -Force
Push-Location $nested.FullName
try {
    & "$Jdk/bin/jar.exe" xf $api.FullName META-INF/jars
    if ($LASTEXITCODE) { throw 'Fabric API extraction failed.' }
} finally { Pop-Location }
$classpath = @("$game/$Instance.jar", $api.FullName)
$classpath += Get-ChildItem -LiteralPath $nested.FullName -Recurse -Filter '*.jar' | ForEach-Object FullName
foreach ($lib in $meta.libraries) {
    $relative = $lib.downloads.artifact.path
    if (!$relative -and $lib.name) {
        $group, $artifact, $version = $lib.name.Split(':')
        $relative = "$( $group.Replace('.','/') )/$artifact/$version/$artifact-$version.jar"
    }
    if ($relative) {
        $file = Join-Path $MinecraftRoot "libraries/$relative"
        if (Test-Path -LiteralPath $file) { $classpath += $file }
    }
}
$sources = Get-ChildItem -LiteralPath "$PSScriptRoot/src" -Recurse -Filter '*.java' | ForEach-Object FullName
& "$Jdk/bin/javac.exe" --release 25 -encoding UTF-8 -cp ($classpath -join ';') -d $classes.FullName @sources
if ($LASTEXITCODE) { throw 'Compilation failed.' }
$manifest = Get-Content -LiteralPath "$PSScriptRoot/resources/fabric.mod.json" -Raw | ConvertFrom-Json
$manifest.depends.minecraft = $MinecraftVersion
$manifest.depends.fabricloader = ">=$($target.Loader)"
$manifest.depends.'fabric-api' = ">=$($target.Api)"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$($classes.FullName)/fabric.mod.json" -Encoding utf8
$out = Join-Path $PSScriptRoot "build/minebottom-$($manifest.version)+$MinecraftVersion.jar"
& "$Jdk/bin/jar.exe" --create --file $out -C $classes.FullName .
if ($LASTEXITCODE) { throw 'JAR packaging failed.' }
Write-Output $out
