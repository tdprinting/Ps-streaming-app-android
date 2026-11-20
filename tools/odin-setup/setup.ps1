# TS5 Streaming setup for the AYN Odin 2 Portal: ADB, TS5 Streaming, O2P Tweaks (EZ Root)
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Dir = Split-Path -Parent $MyInvocation.MyCommand.Path
Start-Transcript -Path (Join-Path $Dir 'log.txt') -Force | Out-Null
function Step($t) { Write-Host ""; Write-Host "== $t ==" -ForegroundColor Cyan }

Step "ADB"
$adb = $null
$cmd = Get-Command adb -ErrorAction SilentlyContinue
if ($cmd) { $adb = $cmd.Source }
elseif (Test-Path "$env:USERPROFILE\tools\platform-tools\adb.exe") { $adb = "$env:USERPROFILE\tools\platform-tools\adb.exe" }
else {
  Write-Host "Downloading Google's platform-tools..."
  $zip = Join-Path $Dir 'platform-tools.zip'
  Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip -UseBasicParsing
  New-Item -ItemType Directory -Force "$env:USERPROFILE\tools" | Out-Null
  Expand-Archive $zip -DestinationPath "$env:USERPROFILE\tools" -Force
  $adb = "$env:USERPROFILE\tools\platform-tools\adb.exe"
}
Write-Host "adb: $adb"
& $adb version

Step "Downloads"
$ts5 = Join-Path $Dir 'TS5-Streaming-preview.apk'
Invoke-WebRequest 'https://github.com/tdprinting/Ps-streaming-app-android/releases/download/preview/TS5-Streaming-preview.apk' -OutFile $ts5 -UseBasicParsing
Write-Host ("TS5 Streaming: {0:N1} MB" -f ((Get-Item $ts5).Length / 1MB))
$o2p = Join-Path $Dir 'o2ptweaks.apk'
try {
  $rels = Invoke-RestMethod 'https://api.github.com/repos/FeralAI/o2ptweaks.app/releases' -UseBasicParsing
  $rel = $rels | Where-Object { $_.assets | Where-Object { $_.name -like '*.apk' } } | Select-Object -First 1
  $asset = $rel.assets | Where-Object { $_.name -like '*.apk' } | Select-Object -First 1
  Write-Host "O2P Tweaks: $($rel.tag_name) $($asset.name)"
  Invoke-WebRequest $asset.browser_download_url -OutFile $o2p -UseBasicParsing
} catch { Write-Host "O2P Tweaks download failed: $_" -ForegroundColor Yellow; $o2p = $null }

Step "Odin over USB"
& $adb start-server | Out-Null
$state = ''
function Show-Usb {
  Write-Host "USB devices Windows sees (phones, ADB, Qualcomm):"
  Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue |
    Where-Object { $_.FriendlyName -match 'Android|ADB|Odin|AYN|Qualcomm|MTP|Portal|Composite|Bootloader|QUSB' -or $_.Class -match 'AndroidUsbDeviceClass|WPD' } |
    ForEach-Object { Write-Host ("  [{0}] {1} ({2}) {3}" -f $_.Status, $_.FriendlyName, $_.Class, $_.InstanceId) }
}
function Install-AdbDriver {
  Write-Host "Installing Google's USB driver for ADB (Windows will ask for admin permission)..." -ForegroundColor Yellow
  $zip = Join-Path $Dir 'usb_driver.zip'
  Invoke-WebRequest 'https://dl.google.com/android/repository/usb_driver_r13-windows.zip' -OutFile $zip -UseBasicParsing
  Expand-Archive $zip -DestinationPath $Dir -Force
  $inf = Join-Path $Dir 'usb_driver\android_winusb.inf'
  Start-Process pnputil -ArgumentList "/add-driver `"$inf`" /install" -Verb RunAs -Wait
  # Point any Android/ADB device with a driver problem at it
  Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | Where-Object { $_.Status -ne 'OK' -and $_.FriendlyName -match 'Android|ADB|Odin|Portal' } |
    ForEach-Object { Write-Host "  Rescanning $($_.FriendlyName)"; Start-Process pnputil -ArgumentList "/scan-devices" -Verb RunAs -Wait }
  & $adb kill-server | Out-Null; & $adb start-server | Out-Null
}
$driverTried = $false
for ($i = 0; $i -lt 150; $i++) {
  $raw = @(& $adb devices 2>&1)
  $lines = @($raw | Select-Object -Skip 1 | Where-Object { "$_" -match '\S' -and "$_" -notmatch '^\*' })
  $state = if ($lines.Count -gt 0) { (("$($lines[0])".Trim()) -split '\s+')[1] } else { 'none' }
  if ($i % 10 -eq 0) { Write-Host ("adb devices: " + (($raw | ForEach-Object { "$_".Trim() }) -join ' | ')) }
  if ($state -eq 'device') { break }
  if ($i % 10 -eq 0) {
    if ($state -eq 'unauthorized') { Write-Host "On the Odin: tap ALLOW on 'Allow USB debugging?' (tick 'Always allow')" -ForegroundColor Yellow }
    else { Write-Host "Waiting for the Odin (state: $state). Plug it in with USB debugging on, screen unlocked." -ForegroundColor Yellow }
  }
  if ($i -eq 10) { Show-Usb }
  # A phone-like device with a driver problem, or no ADB after 40 s: try Google's ADB driver once
  if (-not $driverTried -and $state -eq 'none' -and $i -ge 20) {
    $bad = Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | Where-Object { $_.Status -ne 'OK' -and $_.FriendlyName -match 'Android|ADB|Odin|Portal|AYN' }
    if ($bad) { $driverTried = $true; Install-AdbDriver; Show-Usb }
  }
  Start-Sleep 2
}
Write-Host "State: $state"
if ($state -ne 'device') { Show-Usb; Write-Host "No authorized device, stopping." -ForegroundColor Red; Stop-Transcript | Out-Null; Read-Host "Press Enter to close"; exit 1 }
Write-Host ("Model: " + (& $adb shell getprop ro.product.model) + " / " + (& $adb shell getprop ro.product.manufacturer))
Write-Host ("Build: " + (& $adb shell getprop ro.build.display.id) + " / Android " + (& $adb shell getprop ro.build.version.release))
Write-Host ("Slot: " + (& $adb shell getprop ro.boot.slot_suffix) + " / verified boot: " + (& $adb shell getprop ro.boot.verifiedbootstate) + " / flash locked: " + (& $adb shell getprop ro.boot.flash.locked))
Write-Host ("su: " + (& $adb shell 'command -v su || echo none'))
$before = & $adb shell pm list packages

Step "Install TS5 Streaming"
$out = & $adb install -r $ts5 2>&1
Write-Host $out
if ("$out" -match 'UPDATE_INCOMPATIBLE') { Write-Host "An older TS5 build with another key is installed: uninstall it on the Odin, then run this again." -ForegroundColor Yellow }

if ($o2p) {
  Step "Install O2P Tweaks (EZ Root)"
  Write-Host (& $adb install -r $o2p 2>&1)
}
$after = & $adb shell pm list packages
$new = Compare-Object $before $after | Where-Object SideIndicator -eq '=>' | ForEach-Object { $_.InputObject }
Write-Host "New packages: $new"

Step "Start TS5 Streaming"
& $adb shell monkey -p com.tdprinting.ts5 -c android.intent.category.LAUNCHER 1 2>&1 | Select-Object -Last 1
Write-Host ""
Write-Host "DONE" -ForegroundColor Green
Stop-Transcript | Out-Null
Start-Sleep 8
