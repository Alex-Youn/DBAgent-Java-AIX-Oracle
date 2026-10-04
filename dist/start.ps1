# Starts DBAgent-Java using the bundled minimal jlink runtime. Fully self-contained: this folder
# does not read or depend on any system-installed Java (JAVA_HOME/PATH are never consulted).
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$jar = "dbagent-java-0.1.0.jar"
$pidFile = "dbagent-java.pid"
$outLog = "dbagent-java.out.log"
$errLog = "dbagent-java.err.log"
$javaExe = Join-Path $PSScriptRoot "runtime\bin\javaw.exe"

# Reads server.port from the external application.properties next to this script (same file Spring
# Boot itself reads), so changing the port there doesn't also require editing this script by hand.
# Falls back to Spring Boot's own default (8005, see src/main/resources/application.properties) if
# the file or the setting is missing.
$port = 8005
$propsFile = Join-Path $PSScriptRoot "application.properties"
if (Test-Path $propsFile) {
    $portLine = Get-Content $propsFile | Where-Object { $_ -match '^\s*server\.port\s*=\s*(\d+)\s*$' } | Select-Object -Last 1
    if ($portLine -match '^\s*server\.port\s*=\s*(\d+)\s*$') {
        $port = [int]$Matches[1]
    }
}
# conf\dbagent.conf(부트스트랩 설정, 2026-10-04)가 있으면 그 포트가 application.properties보다 우선한다 -
# Spring도 conf 값을 우선 적용하므로 안내 포트·브라우저 열기 주소가 실제 기동 포트와 맞도록 같은 순서로 읽는다.
$confFile = Join-Path $PSScriptRoot "conf\dbagent.conf"
if (Test-Path $confFile) {
    $confPortLine = Get-Content $confFile | Where-Object { $_ -match '^\s*server\.port\s*=\s*(\d+)\s*$' } | Select-Object -Last 1
    if ($confPortLine -match '^\s*server\.port\s*=\s*(\d+)\s*$') {
        $port = [int]$Matches[1]
    }
}

if (-not (Test-Path $javaExe)) {
    throw "Bundled runtime not found at '$javaExe'. This folder must be copied whole, including runtime\."
}

function Get-BrowserExe {
    # Chrome first, then Edge - looked up via the registry App Paths keys the installer sets (works
    # regardless of Program Files vs (x86)), with fixed common paths as a fallback.
    function Find-App($exeName, $fixedPaths) {
        foreach ($hive in @("HKLM:", "HKCU:")) {
            $key = "$hive\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\$exeName"
            try {
                $path = (Get-ItemProperty -Path $key -ErrorAction Stop).'(default)'
                if ($path -and (Test-Path $path)) { return $path }
            } catch {}
        }
        foreach ($p in $fixedPaths) {
            if (Test-Path $p) { return $p }
        }
        return $null
    }

    $chrome = Find-App "chrome.exe" @(
        "C:\Program Files\Google\Chrome\Application\chrome.exe",
        "C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"
    )
    if ($chrome) { return $chrome }

    return Find-App "msedge.exe" @(
        "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        "C:\Program Files\Microsoft\Edge\Application\msedge.exe"
    )
}

function Open-AppInBrowser($url) {
    $browser = Get-BrowserExe
    if ($browser) {
        Write-Output "Opening $url in $browser"
        Start-Process -FilePath $browser -ArgumentList $url
    } else {
        Write-Output "Chrome/Edge not found in standard locations; opening $url with the default browser."
        Start-Process $url
    }
}

if (Test-Path $pidFile) {
    $existingPid = Get-Content $pidFile
    $existing = Get-Process -Id $existingPid -ErrorAction SilentlyContinue
    if ($existing) {
        Write-Output "DBAgent-Java is already running (PID $existingPid)."
        Open-AppInBrowser "http://localhost:$port"
        exit 0
    }
    Remove-Item $pidFile -Force
}

if (-not (Test-Path $jar)) {
    throw "$jar not found next to start.ps1."
}

Write-Output "Starting DBAgent-Java (port $port) using bundled runtime ($javaExe) ..."
$proc = Start-Process -FilePath $javaExe -ArgumentList "-jar `"$jar`"" -WorkingDirectory $PSScriptRoot `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog -WindowStyle Hidden -PassThru
$proc.Id | Out-File $pidFile -Encoding ascii

Write-Output "Started with PID $($proc.Id). Waiting for port $port to open..."
$ready = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Milliseconds 500
    $test = Test-NetConnection -ComputerName localhost -Port $port -WarningAction SilentlyContinue
    if ($test.TcpTestSucceeded) { $ready = $true; break }
}

if ($ready) {
    Write-Output "DBAgent-Java is up: http://localhost:$port"
    Open-AppInBrowser "http://localhost:$port"
} else {
    Write-Output "Timed out waiting for port $port. Check $errLog for startup errors."
}
