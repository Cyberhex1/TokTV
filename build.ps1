# Windows version of build.sh. Builds TokTV.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Set ANDROID_SDK (folder with build-tools\ and platforms\) and JAVA_HOME (JDK 11+), or edit the defaults below.
# KEYSTORE / KEYSTORE_PASS work like in build.sh.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$sdk = if ($env:ANDROID_SDK) { $env:ANDROID_SDK } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = Get-ChildItem "$sdk\build-tools" | Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } | Select-Object -Last 1
$jar = Get-ChildItem "$sdk\platforms" -Directory | Sort-Object Name | Select-Object -Last 1 | ForEach-Object { "$($_.FullName)\android.jar" }
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME\bin;$env:PATH" }
$env:PATH = "$($bt.FullName);$env:PATH"
function Run($exe) { & $exe @args; if ($LASTEXITCODE -ne 0) { throw "$exe failed ($LASTEXITCODE)" } }

# Libraries: download once into libs\, check SHA-1, unpack classes.jar from AARs.
Add-Type -AssemblyName System.IO.Compression.FileSystem
New-Item -ItemType Directory -Force libs\jars | Out-Null
foreach ($line in Get-Content libs.txt) {
    if ($line -match '^\s*#' -or $line.Trim() -eq '') { continue }
    $url, $sha = $line -split '\s+'
    $f = "libs\" + ($url -split '/')[-1]
    if (-not (Test-Path $f)) { Invoke-WebRequest $url -OutFile $f -UseBasicParsing }
    if ((Get-FileHash $f -Algorithm SHA1).Hash.ToLower() -ne $sha) { throw "SHA-1 mismatch for $f" }
    if ($f.EndsWith('.aar')) {
        $z = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $f))
        [IO.Compression.ZipFileExtensions]::ExtractToFile($z.GetEntry('classes.jar'), "$PWD\libs\jars\$([IO.Path]::GetFileNameWithoutExtension($f)).jar", $true)
        $z.Dispose()
    } else { Copy-Item $f libs\jars\ -Force }
}

if (Test-Path build) { Remove-Item -Recurse -Force build }
New-Item -ItemType Directory build\gen, build\classes | Out-Null
Run aapt2 compile --dir res -o build\res.zip
Run aapt2 link -o build\unsigned.apk -I $jar --manifest AndroidManifest.xml -A assets --java build\gen --min-sdk-version 22 --target-sdk-version 30 --version-code 9 --version-name 2.5 build\res.zip
Get-ChildItem src, build\gen -Recurse -Filter *.java | ForEach-Object FullName | Set-Content build\sources.txt
Run javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath $jar -classpath "$jar;libs\jars\*" -d build\classes "@build\sources.txt"
$jars = Get-ChildItem libs\jars -Filter *.jar | ForEach-Object FullName
Run jar cf build\app-classes.jar -C build\classes .
Run d8.bat --release --min-api 22 --lib $jar --output build build\app-classes.jar @jars
Push-Location build; Run jar uf unsigned.apk classes.dex; Pop-Location
Run zipalign.exe -f -p 4 build\unsigned.apk build\aligned.apk

$ks = if ($env:KEYSTORE) { $env:KEYSTORE } else { 'keystore\toktv.jks' }
$pass = if ($env:KEYSTORE_PASS) { $env:KEYSTORE_PASS } else { 'changeit' }
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force (Split-Path $ks) | Out-Null
    Run keytool -genkeypair -keystore $ks -alias toktv -keyalg RSA -keysize 2048 -validity 10000 -storepass $pass -keypass $pass -dname "CN=TokTV"
}
Run apksigner.bat sign --ks $ks --ks-pass "pass:$pass" --key-pass "pass:$pass" --out TokTV.apk build\aligned.apk
Run apksigner.bat verify TokTV.apk
Get-Item TokTV.apk | Select-Object FullName, Length
