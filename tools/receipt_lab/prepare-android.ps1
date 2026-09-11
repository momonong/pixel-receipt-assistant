param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$GradleCache = $env:GRADLE_USER_HOME,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$Sdk -or !(Test-Path -LiteralPath "$Sdk/platform-tools/adb.exe")) { throw '請提供 -Sdk Android SDK 路徑。' }
$taskAdb = (Resolve-Path "$Sdk/platform-tools/adb.exe").Path
if (!(Test-Path -LiteralPath "$Sdk/system-images/android-35/google_apis/x86_64/system.img")) { throw '需要 SDK system-images;android-35;google_apis;x86_64。' }
$env:ANDROID_HOME = $Sdk
$env:ANDROID_USER_HOME = Join-Path $taskRoot '.gradle/android-user'
$env:ANDROID_AVD_HOME = Join-Path $taskRoot '.gradle/avd'
if ($Jdk) { $env:JAVA_HOME = $Jdk }
if ($GradleCache) { $env:GRADLE_USER_HOME = $GradleCache }
Push-Location $taskRoot
try {
    if (!$SkipBuild) {
        & .\gradlew.bat assembleDebug assembleDebugAndroidTest --no-daemon --max-workers=1
        if ($LASTEXITCODE -ne 0) { throw 'Android 測試 APK 建置失敗。' }
    }
    $taskAvd = Join-Path $env:ANDROID_AVD_HOME 'receipt-lab-test.avd'
    New-Item -ItemType Directory -Force $taskAvd | Out-Null
    if (!(Test-Path -LiteralPath "$taskAvd/config.ini")) {
        @('avd.ini.encoding=UTF-8','abi.type=x86_64','tag.id=google_apis','tag.display=Google APIs',
          'image.sysdir.1=system-images/android-35/google_apis/x86_64/','hw.cpu.arch=x86_64','hw.cpu.ncore=4',
          'hw.ramSize=2048','hw.lcd.width=1080','hw.lcd.height=2400','hw.lcd.density=420','hw.keyboard=yes',
          'hw.gpu.enabled=yes','hw.gpu.mode=swiftshader','hw.camera.back=none','hw.camera.front=none',
          'disk.dataPartition.size=6442450944') | Set-Content "$taskAvd/config.ini" -Encoding ascii
        @('avd.ini.encoding=UTF-8',"path=$taskAvd",'target=android-35') | Set-Content "$env:ANDROID_AVD_HOME/receipt-lab-test.ini" -Encoding utf8
    }
    $taskDevice = 'emulator-5584'
    $taskDevices = & $taskAdb devices
    if (!($taskDevices -match '^emulator-5584\s+device')) {
        New-Item -ItemType Directory -Force .receipt-lab | Out-Null
        Start-Process -FilePath "$Sdk/emulator/emulator.exe" -ArgumentList @('-avd','receipt-lab-test','-port','5584','-no-window','-no-snapshot','-no-audio','-gpu','swiftshader') -WindowStyle Hidden -RedirectStandardOutput "$taskRoot/.receipt-lab/emulator.log" -RedirectStandardError "$taskRoot/.receipt-lab/emulator-errors.log" | Out-Null
    } else {
        $taskAvdName = & $taskAdb -s $taskDevice emu avd name
        if ($taskAvdName[0] -ne 'receipt-lab-test') { throw 'emulator-5584 由其他 AVD 使用，沒有變更該裝置。' }
    }
    $taskDeadline = (Get-Date).AddSeconds(150)
    do {
        Start-Sleep -Seconds 2
        $taskBoot = & $taskAdb -s $taskDevice shell getprop sys.boot_completed 2>$null
    } while ($taskBoot -ne '1' -and (Get-Date) -lt $taskDeadline)
    if ($taskBoot -ne '1') { throw '專用模擬器尚未啟動完成，請查看 .receipt-lab/emulator-errors.log。' }
    if ((& $taskAdb -s $taskDevice shell getprop ro.kernel.qemu).Trim() -ne '1') { throw '只允許專用模擬器。' }
    & $taskAdb -s $taskDevice install -r app/build/outputs/apk/debug/app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw '專用模擬器主 APK 安裝失敗；未清除資料。' }
    & $taskAdb -s $taskDevice install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw '專用模擬器測試 APK 安裝失敗。' }
    New-Item -ItemType Directory -Force .receipt-lab | Out-Null
    @{adb=$taskAdb;serial=$taskDevice} | ConvertTo-Json | Set-Content .receipt-lab/config.json -Encoding utf8
    Write-Output '本機 OCR 測試已就緒。執行 .\receipt-lab.ps1 serve 開啟上傳介面。'
} finally { Pop-Location }
