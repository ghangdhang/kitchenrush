# Builds the resource pack and the KitchenRush plugin jar into ..\KitchenServer\plugins
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$build = $PSScriptRoot
$server = Join-Path $root 'KitchenServer'
$jdk = 'C:\Program Files\Java\jdk-25.0.4.1\bin'

# Original music/jingles: needs the build venv (python -m venv build\.venv; pip install numpy soundfile).
$venvPy = Join-Path $build '.venv\Scripts\python.exe'
if (Test-Path $venvPy) {
    & $venvPy "$build\gen_audio.py" "$build\audio" "$build\plugin\resources\audio.txt"
    if ($LASTEXITCODE -ne 0) { throw 'gen_audio failed' }
} elseif (-not (Test-Path "$build\audio")) {
    throw 'No audio: create build\.venv with numpy and soundfile, then rerun.'
}
py -I "$build\gen_pack.py" "$build\plugin\resources\kitchen-pack.zip" "$build\plugin\resources\glyphs.txt"

# Libraries plus the patched server jar (dummy tab-list rows use server packet classes).
$cp = ((Get-ChildItem "$server\libraries" -Recurse -Filter *.jar | ForEach-Object FullName) + (Get-ChildItem "$server\versions" -Recurse -Filter 'paper-*.jar' | ForEach-Object FullName)) -join ';'
$out = Join-Path $build 'out'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory $out | Out-Null
$sources = Get-ChildItem "$build\plugin\src" -Recurse -Filter *.java | ForEach-Object FullName
& "$jdk\javac.exe" --release 25 -encoding UTF-8 -nowarn -cp $cp -d $out @sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
foreach ($f in 'plugin.yml', 'config.yml', 'glyphs.txt', 'audio.txt') { Copy-Item "$build\plugin\resources\$f" $out }
New-Item -ItemType Directory -Force "$server\plugins" | Out-Null
$jarFile = Join-Path $build 'KitchenRush.jar'
if (Test-Path $jarFile) { Remove-Item -Force $jarFile }
& "$jdk\jar.exe" --create --file $jarFile -C $out .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
Copy-Item $jarFile "$server\plugins\KitchenRush.jar" -Force

# ResourcepackBuilder publishes the server's "pack" folder to GitHub with /build.
$pack = Join-Path $server 'pack'
if (Test-Path $pack) { Remove-Item -Recurse -Force $pack }
Expand-Archive -LiteralPath "$build\plugin\resources\kitchen-pack.zip" -DestinationPath $pack
Write-Host "Built KitchenRush.jar and refreshed $pack"
