<#
.SYNOPSIS
  Пробрасывает Xiaomi/Redmi из Windows в WSL2 через usbipd.

.DESCRIPTION
  Делает:
    1. usbipd list
    2. находит строку с телефоном (по умолчанию ищет "Redmi" или "Xiaomi")
    3. если несколько интерфейсов — берёт тот, где в имени есть "ADB"
    4. если STATE = "Not shared"  — выполняет `usbipd bind --busid <id>`
       если STATE = "Shared"      — пропускает bind
       если STATE = "Attached"    — устройство уже в WSL, всё ок
    5. если ещё не в WSL — выполняет `usbipd attach --wsl --busid <id>`

  Запускать из PowerShell **от имени администратора** (usbipd требует).

.PARAMETER Search
  Подстрока для поиска устройства. По умолчанию "Redmi".

.EXAMPLE
  PS> .\attach-phone.ps1
  PS> .\attach-phone.ps1 -Search "Xiaomi"
#>

[CmdletBinding()]
param(
    [string]$Search = "Redmi"
)

$ErrorActionPreference = "Stop"

function Write-Section($msg) { Write-Host ">>> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)      { Write-Host "✓   $msg" -ForegroundColor Green }
function Write-Warn($msg)    { Write-Host "!   $msg" -ForegroundColor Yellow }
function Write-Err($msg)     { Write-Host "✗   $msg" -ForegroundColor Red }

# ---------- проверки ----------
if (-not (Get-Command usbipd -ErrorAction SilentlyContinue)) {
    Write-Err "usbipd не найден. Поставьте: winget install usbipd"
    exit 1
}

$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator
)
if (-not $isAdmin) {
    Write-Err "Нужен PowerShell от администратора (Start-Process powershell -Verb RunAs)"
    exit 1
}

# ---------- получить список устройств ----------
Write-Section "usbipd list…"
$raw = & usbipd list
$raw | ForEach-Object { Write-Host "    $_" }

# ---------- распарсить ----------
# Ожидаемый формат (колонки разделены 2+ пробелами):
#   BUSID  VID:PID    DEVICE                              STATE
#   1-2    2717:ff48  Redmi Note 8 Pro, ADB Interface     Attached
$devices = @()
$inConnected = $false
foreach ($line in $raw) {
    if ($line -match '^\s*Connected:\s*$') { $inConnected = $true; continue }
    if ($line -match '^\s*Persisted:\s*$')  { $inConnected = $false; continue }
    if (-not $inConnected) { continue }
    if ($line -match '^\s*BUSID\b')         { continue }  # заголовок

    # BUSID — это "цифра-цифра" (например, 1-2). Зацепимся за это.
    if ($line -match '^\s*(\d+-\d+)\s+([0-9a-fA-F]{4}:[0-9a-fA-F]{4})\s+(.+?)\s{2,}(Not shared|Shared|Attached)\s*$') {
        $devices += [pscustomobject]@{
            BusId  = $Matches[1]
            VidPid = $Matches[2]
            Name   = $Matches[3].Trim()
            State  = $Matches[4]
        }
    }
}

if ($devices.Count -eq 0) {
    Write-Err "Не удалось распарсить вывод usbipd list"
    exit 1
}

# ---------- выбрать нужное устройство ----------
$matches_phone = $devices | Where-Object { $_.Name -match [regex]::Escape($Search) }
if ($matches_phone.Count -eq 0) {
    Write-Err "Устройство с именем содержащим '$Search' не найдено в usbipd list"
    Write-Host "    Найдено всего USB-устройств: $($devices.Count)"
    $devices | ForEach-Object { Write-Host "      $($_.BusId)  $($_.VidPid)  $($_.Name) [$($_.State)]" }
    exit 1
}

# если несколько интерфейсов (MTP + ADB + …) — предпочитаем тот, где в имени "ADB"
$adbOnes = $matches_phone | Where-Object { $_.Name -match 'ADB' }
$chosen  = if ($adbOnes.Count -gt 0) { $adbOnes[0] } else { $matches_phone[0] }

Write-Section "Найдено: $($chosen.Name)"
Write-Host "    BUSID  = $($chosen.BusId)"
Write-Host "    VID:PID= $($chosen.VidPid)"
Write-Host "    STATE  = $($chosen.State)"

# ---------- выполнить bind/attach ----------
if ($chosen.State -eq 'Not shared') {
    Write-Section "usbipd bind --busid $($chosen.BusId)"
    & usbipd bind --busid $chosen.BusId
    if ($LASTEXITCODE -ne 0) { Write-Err "bind упал"; exit $LASTEXITCODE }
    Write-Ok "bind выполнен"
} elseif ($chosen.State -eq 'Shared') {
    Write-Ok "уже shared, bind не требуется"
} elseif ($chosen.State -eq 'Attached') {
    Write-Ok "уже проброшено в WSL, ничего делать не нужно"
    exit 0
}

Write-Section "usbipd attach --wsl --busid $($chosen.BusId)"
& usbipd attach --wsl --busid $chosen.BusId
if ($LASTEXITCODE -ne 0) { Write-Err "attach упал"; exit $LASTEXITCODE }

Write-Ok "готово. Дальше: в WSL запустите ./debug.sh"
