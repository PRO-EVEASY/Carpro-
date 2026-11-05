param([switch]$Unsigned)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$sdkRoot = $env:ANDROID_SDK_ROOT
if (-not $sdkRoot) { $sdkRoot = $env:ANDROID_HOME }
if (-not $sdkRoot -or -not (Test-Path -LiteralPath $sdkRoot -PathType Container)) { throw 'Set ANDROID_SDK_ROOT or ANDROID_HOME to your installed Android SDK.' }
$javaRoot = $env:JAVA_HOME
if (-not $javaRoot) {
    $compiler = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($compiler) { $javaRoot = Split-Path -Parent (Split-Path -Parent $compiler.Source) }
}
if (-not $javaRoot -or -not (Test-Path -LiteralPath (Join-Path $javaRoot 'bin\javac.exe') -PathType Leaf)) { throw 'Set JAVA_HOME to a JDK 17+ installation.' }
$javaBin = Join-Path $javaRoot 'bin'
$env:JAVA_HOME = $javaRoot
$androidJar = Join-Path $sdkRoot 'platforms\android-34\android.jar'
if (-not (Test-Path -LiteralPath $androidJar -PathType Leaf)) { throw 'Install Android SDK platform 34.' }
$tools = @(Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory | Where-Object {
    (Test-Path -LiteralPath (Join-Path $_.FullName 'aapt.exe')) -and
    (Test-Path -LiteralPath (Join-Path $_.FullName 'lib\d8.jar')) -and
    (Test-Path -LiteralPath (Join-Path $_.FullName 'apksigner.bat')) -and
    (Test-Path -LiteralPath (Join-Path $_.FullName 'zipalign.exe'))
} | Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending)
if ($tools.Count -eq 0) { throw 'Install Android SDK Build Tools 34.0.0 or newer.' }
$buildTools = $tools[0].FullName
$ndkRoot = $env:ANDROID_NDK_HOME
if (-not $ndkRoot) {
    $ndks = @(Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'ndk') -Directory | Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending)
    if ($ndks.Count -eq 0) { throw 'Install an Android NDK or set ANDROID_NDK_HOME.' }
    $ndkRoot = $ndks[0].FullName
}
$clang = Join-Path $ndkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android29-clang.cmd'
if (-not (Test-Path -LiteralPath $clang -PathType Leaf)) { throw 'The selected NDK does not provide arm64 Android API 29 clang for Windows.' }
function Invoke-Checked([string]$Executable, [Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build tool failed: $Executable (exit $LASTEXITCODE)" }
}
# Each build uses a new private directory; no recursive deletion of an existing checkout.
$buildRoot = Join-Path $projectRoot ('.local-build\run-' + [Guid]::NewGuid().ToString('N'))
foreach ($name in @('classes', 'dex', 'lib\arm64-v8a')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $buildRoot $name) | Out-Null
}
$outputRoot = Join-Path $projectRoot 'output'
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$nativeSources = @('native\own\android_yuv.c', 'native\bridge.c', 'native\own\hab_transport.c', 'native\own\own_camera.c', 'native\own\own_camera_jni.c', 'native\own\own_mosaic.c', 'native\own\own_mosaic_jni.c', 'native\own\own_lens.c', 'native\own\own_lens_jni.c') | ForEach-Object { Join-Path $projectRoot $_ }
foreach ($source in $nativeSources) { if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Missing source: $source" } }
$nativeOutput = Join-Path $buildRoot 'lib\arm64-v8a\libcameraadapter.so'
Invoke-Checked $clang (@('-std=c11', '-D_POSIX_C_SOURCE=200809L', '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-pthread', '-Wl,--no-undefined') + $nativeSources + @('-o', $nativeOutput, '-llog', '-landroid', '-lm'))
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src') -Recurse -Filter '*.java' -File | ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) { throw 'No Java sources.' }
Invoke-Checked (Join-Path $javaBin 'javac.exe') (@('-encoding','UTF-8','-source','8','-target','8','-cp',$androidJar,'-d',(Join-Path $buildRoot 'classes')) + $sources)
Invoke-Checked (Join-Path $javaBin 'jar.exe') @('cf',(Join-Path $buildRoot 'classes.jar'),'-C',(Join-Path $buildRoot 'classes'),'.')
Invoke-Checked (Join-Path $javaBin 'java.exe') @('-cp',(Join-Path $buildTools 'lib\d8.jar'),'com.android.tools.r8.D8','--min-api','29','--lib',$androidJar,'--output',(Join-Path $buildRoot 'dex'),(Join-Path $buildRoot 'classes.jar'))
$unsignedApk = Join-Path $buildRoot 'unsigned.apk'
Invoke-Checked (Join-Path $buildTools 'aapt.exe') @('package','-f','-M',(Join-Path $projectRoot 'AndroidManifest.xml'),'-I',$androidJar,'-F',$unsignedApk)
Invoke-Checked (Join-Path $javaBin 'jar.exe') @('uf',$unsignedApk,'-C',(Join-Path $buildRoot 'dex'),'classes.dex')
Invoke-Checked (Join-Path $javaBin 'jar.exe') @('uf',$unsignedApk,'-C',$buildRoot,'lib')
$entries = @(& (Join-Path $javaBin 'jar.exe') tf $unsignedApk)
if ($LASTEXITCODE -ne 0) { throw 'APK inventory failed.' }
$libraries = @($entries | Where-Object { $_ -match '^lib/.+\.so$' })
if ($libraries.Count -ne 1 -or $libraries[0] -ne 'lib/arm64-v8a/libcameraadapter.so') { throw "Unexpected native APK entries: $libraries" }
$alignedApk = Join-Path $buildRoot 'aligned.apk'
Invoke-Checked (Join-Path $buildTools 'zipalign.exe') @('-f','4',$unsignedApk,$alignedApk)
if ($Unsigned) {
    $artifact = Join-Path $outputRoot 'jaecoo-recorder-unsigned.apk'
    Copy-Item -LiteralPath $alignedApk -Destination $artifact -Force
} else {
    $localKey = Join-Path $projectRoot '.local-build\debug.keystore'
    if (-not (Test-Path -LiteralPath $localKey -PathType Leaf)) {
        Invoke-Checked (Join-Path $javaBin 'keytool.exe') @('-genkeypair','-keystore',$localKey,'-storepass','android','-keypass','android','-alias','androiddebugkey','-dname','CN=Android Debug,O=Local Development,C=US','-keyalg','RSA','-keysize','2048','-validity','10000','-noprompt')
    }
    $artifact = Join-Path $outputRoot 'jaecoo-recorder-debug.apk'
    Invoke-Checked (Join-Path $buildTools 'apksigner.bat') @('sign','--ks',$localKey,'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',$artifact,$alignedApk)
    Invoke-Checked (Join-Path $buildTools 'apksigner.bat') @('verify','--print-certs',$artifact)
}
Get-FileHash -LiteralPath $artifact -Algorithm SHA256
Write-Output "Built: $artifact"
