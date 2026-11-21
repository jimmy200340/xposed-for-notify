$ErrorActionPreference = "Stop"

$projDir = $PSScriptRoot
$defaultBuildTools = Join-Path $env:USERPROFILE ".gemini\antigravity\scratch\google_map_mod\build_tools"

# 1. Resolve JDK 17+
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
    $jdkDir = $env:JAVA_HOME
} elseif (Test-Path "$defaultBuildTools\jdk17\jdk-17.0.10+7\bin\javac.exe") {
    $jdkDir = "$defaultBuildTools\jdk17\jdk-17.0.10+7"
} else {
    $javacCmd = Get-Command javac -ErrorAction SilentlyContinue
    if ($javacCmd) {
        $jdkDir = Split-Path (Split-Path $javacCmd.Source -Parent) -Parent
    } else {
        throw "JDK not found. Please set JAVA_HOME or install JDK 17."
    }
}
$javac = Join-Path $jdkDir "bin\javac.exe"
$jarExe = Join-Path $jdkDir "bin\jar.exe"
$keytool = Join-Path $jdkDir "bin\keytool.exe"

# 2. Resolve Android SDK (platforms/android-34 and build-tools)
$androidSdk = $null
foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$defaultBuildTools\android_sdk", "$env:LOCALAPPDATA\Android\Sdk")) {
    if ($candidate -and (Test-Path $candidate)) {
        $androidSdk = $candidate
        break
    }
}
if (-not $androidSdk) {
    throw "Android SDK not found. Please set ANDROID_HOME / ANDROID_SDK_ROOT."
}

$androidJar = Join-Path $androidSdk "platforms\android-34\android.jar"
if (-not (Test-Path $androidJar)) {
    $platformDir = Get-ChildItem (Join-Path $androidSdk "platforms") -Directory | Sort-Object Name -Descending | Select-Object -First 1
    if ($platformDir) {
        $androidJar = Join-Path $platformDir.FullName "android.jar"
    }
}
if (-not (Test-Path $androidJar)) {
    throw "android.jar not found under $androidSdk\platforms"
}

$btDir = Join-Path $androidSdk "build-tools\34.0.0"
if (-not (Test-Path $btDir)) {
    $btDirObj = Get-ChildItem (Join-Path $androidSdk "build-tools") -Directory | Sort-Object Name -Descending | Select-Object -First 1
    if ($btDirObj) { $btDir = $btDirObj.FullName }
}
$d8 = Join-Path $btDir "d8.bat"
$aapt2 = Join-Path $btDir "aapt2.exe"
$zipalign = Join-Path $btDir "zipalign.exe"
$apksigner = Join-Path $btDir "apksigner.bat"

# 3. Resolve local xposed_stubs
$xposedStubs = Join-Path $projDir "xposed_stubs"
if (-not (Test-Path $xposedStubs)) {
    $xposedStubs = "$defaultBuildTools\xposed_stubs"
}
if (-not (Test-Path $xposedStubs)) {
    throw "xposed_stubs directory not found."
}

# 4. Resolve signing keystore (reuse existing debug keystore or auto-generate one in build_out)
$legacyKeystore = Join-Path $env:USERPROFILE ".gemini\antigravity\scratch\google_map_mod\MapAutoTwoWheeler\build_out\debug.keystore"
if ($env:NOTIFY_MOD_KEYSTORE -and (Test-Path $env:NOTIFY_MOD_KEYSTORE)) {
    $keystore = $env:NOTIFY_MOD_KEYSTORE
} elseif (Test-Path $legacyKeystore) {
    $keystore = $legacyKeystore
} else {
    $keystore = Join-Path $projDir "debug.keystore"
}

# Optional dexsql verification tool
$dexsql = $env:DEXSQL_PATH
if (-not $dexsql) {
    $dexsqlCmd = Get-Command dexsql -ErrorAction SilentlyContinue
    if ($dexsqlCmd) { $dexsql = $dexsqlCmd.Source }
}
if (-not $dexsql) {
    foreach ($candidateDexsql in @(
        (Join-Path $env:LOCALAPPDATA "DexSQL\0.1.1\dexsql.exe"),
        (Join-Path $env:USERPROFILE "Pictures\Google Map\dexsql-windows-amd64.exe")
    )) {
        if (Test-Path $candidateDexsql) { $dexsql = $candidateDexsql; break }
    }
}

$env:JAVA_HOME = $jdkDir
$env:PATH = "$jdkDir\bin;$env:PATH"

Set-Location $projDir

Write-Host "=== 1. Preparing build folders ==="
$xposedApiDir = "$projDir\build_out\xposed_api"
$classesDir = "$projDir\build_out\classes"
$dexDir = "$projDir\build_out\dex"
$apkDir = "$projDir\build_out\apk"

if (Test-Path "$projDir\build_out") {
    Remove-Item -Recurse -Force "$projDir\build_out"
}
New-Item -ItemType Directory -Force -Path $xposedApiDir | Out-Null
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
New-Item -ItemType Directory -Force -Path $dexDir | Out-Null
New-Item -ItemType Directory -Force -Path $apkDir | Out-Null

if (-not (Test-Path $keystore)) {
    Write-Host "Generating local debug keystore at $keystore ..."
    & $keytool -genkeypair -v -keystore $keystore -storepass android -alias debug -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=NotifyBalance2Fix, OU=Dev, O=Local, L=Taipei, ST=TW, C=TW"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE" }
}

Write-Host "=== 2. Compiling Xposed & libxposed API stubs separately into xposed_api ==="
$stubJavaFiles = Get-ChildItem -Recurse $xposedStubs -Filter *.java | ForEach-Object { $_.FullName }
& $javac -source 17 -target 17 -encoding UTF-8 -cp $androidJar -d $xposedApiDir $stubJavaFiles
if ($LASTEXITCODE -ne 0) { throw "Compiling Xposed API stubs failed with exit code $LASTEXITCODE" }
Write-Host "Xposed/libxposed API stubs compiled to $xposedApiDir"

Write-Host "=== 3. Compiling module Java classes with xposed_api on classpath (compileOnly) ==="
$modJavaFiles = Get-ChildItem -Recurse "$projDir\src" -Filter *.java | ForEach-Object { $_.FullName }
& $javac -source 17 -target 17 -encoding UTF-8 -cp "$androidJar;$xposedApiDir" -d $classesDir $modJavaFiles
if ($LASTEXITCODE -ne 0) { throw "Compiling module classes failed with exit code $LASTEXITCODE" }

# Verify that NO de.robv.android.xposed or io.github.libxposed classes were created in build_out\classes
if (Test-Path "$classesDir\de") {
    throw "CRITICAL ERROR: de.robv.android.xposed classes detected in $classesDir!"
}
if (Test-Path "$classesDir\io\github\libxposed") {
    throw "CRITICAL ERROR: io.github.libxposed classes detected in $classesDir!"
}
Write-Host "Verified: $classesDir contains ONLY module classes (compileOnly stubs excluded)!"

Write-Host "=== 4. Compiling and linking resources with AAPT2 ==="
& $aapt2 compile --dir "$projDir\res" -o "$projDir\build_out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed with exit code $LASTEXITCODE" }
& $aapt2 link -I $androidJar --manifest "$projDir\AndroidManifest.xml" -o "$apkDir\unaligned.apk" "$projDir\build_out\res.zip" -A "$projDir\assets"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed with exit code $LASTEXITCODE" }

Write-Host "=== 5. Dexing module classes with D8 ==="
$modJar = "$projDir\build_out\module_classes.jar"
$xposedJar = "$projDir\build_out\xposed_api.jar"
& $jarExe cf $modJar -C $classesDir .
& $jarExe cf $xposedJar -C $xposedApiDir .
& $d8 --min-api 28 --output $dexDir --lib $androidJar --classpath $xposedJar $modJar
if ($LASTEXITCODE -ne 0) { throw "Dexing module classes failed with exit code $LASTEXITCODE" }

Write-Host "=== 6. Packaging classes.dex and META-INF/xposed/* into APK ==="
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$apkDir\unaligned.apk", [System.IO.Compression.ZipArchiveMode]::Update)
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$dexDir\classes.dex", "classes.dex", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null

    $metaXposedDir = Join-Path $projDir "resources\META-INF\xposed"
    if (Test-Path $metaXposedDir) {
        Get-ChildItem -File $metaXposedDir | ForEach-Object {
            $entryName = "META-INF/xposed/$($_.Name)"
            $existing = $zip.GetEntry($entryName)
            if ($existing) { $existing.Delete() }
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $entryName, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
            Write-Host "Added $entryName"
        }
    }
} finally {
    $zip.Dispose()
}
Write-Host "Successfully added classes.dex and META-INF/xposed/* to APK"

Write-Host "=== 7. Zipaligning APK ==="
& $zipalign -p -f 4 "$apkDir\unaligned.apk" "$apkDir\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed with exit code $LASTEXITCODE" }

Write-Host "=== 8. Signing APK ==="
$finalApk = "$projDir\NotifyBalance2Fix.apk"
if (Test-Path $finalApk) {
    Remove-Item -Force $finalApk
}
& $apksigner sign --ks $keystore --ks-pass "pass:android" --ks-key-alias "debug" --out $finalApk "$apkDir\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed with exit code $LASTEXITCODE" }

Write-Host "=== 9. Verifying signed APK ==="
& $apksigner verify -v $finalApk

if ($dexsql -and (Test-Path $dexsql)) {
    Write-Host "=== 10. Verifying with dexsql: Ensure NO framework stub classes in APK ==="
    $stubCheck = & $dexsql query --input $finalApk --sql "SELECT descriptor FROM classes WHERE descriptor LIKE 'Lde/robv/android/xposed/%' OR descriptor LIKE 'Lio/github/libxposed/%';"
    Write-Host $stubCheck
    if ($stubCheck -match "Lde/robv/android/xposed/|Lio/github/libxposed/") {
        throw "CRITICAL ERROR: Framework stub classes found inside classes.dex!"
    }
    Write-Host "=== 11. Verifying API 102 isolation: Ensure NO legacy Xposed calls outside LegacyXposedEntry ==="
    $isoCheck = & $dexsql query --input $finalApk --sql "SELECT DISTINCT method_class, callee_class FROM method_calls WHERE callee_class LIKE 'Lde/robv/android/xposed/%' AND method_class NOT LIKE 'Lcom/amazfit/balance2fix/LegacyXposedEntry%';"
    Write-Host $isoCheck
    if ($isoCheck -match "Lde/robv/android/xposed/") {
        throw "CRITICAL ERROR: Legacy de.robv.android.xposed.* call detected outside LegacyXposedEntry!"
    }
    Write-Host "=== 12. Verifying no reflection strings to de.robv.android.xposed in core ==="
    $strCheck = & $dexsql query --input $finalApk --sql "SELECT value FROM strings WHERE value LIKE '%de.robv.android.xposed%';"
    Write-Host $strCheck
}

Write-Host "BUILD SUCCESS: $finalApk"
