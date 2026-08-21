# Starts the DBAgent-Java Spring Boot service in the background (builds the jar first if missing).
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$jar = "target\dbagent-java-0.1.0.jar"
$pidFile = "dbagent-java.pid"
$outLog = "dbagent-java.out.log"
$errLog = "dbagent-java.err.log"

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

function Get-JavaExe {
    # Bundled JDK (this folder) always wins, on purpose: the host may have an old system Java
    # (e.g. 1.6) on JAVA_HOME/PATH for other applications, and this app must never touch that -
    # it only ever launches its own co-located JDK 17, nothing system-wide is read or changed.
    $bundled = Join-Path $PSScriptRoot "jdk17\bin\javaw.exe"
    if (Test-Path $bundled) {
        return $bundled
    }
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javaw.exe")) {
        Write-Output "WARNING: bundled jdk17\ not found, falling back to system JAVA_HOME ($env:JAVA_HOME) - verify it is Java 17+."
        return "$env:JAVA_HOME\bin\javaw.exe"
    }
    $cmd = Get-Command javaw.exe -ErrorAction SilentlyContinue
    if ($cmd) {
        Write-Output "WARNING: bundled jdk17\ not found, falling back to PATH javaw.exe ($($cmd.Source)) - verify it is Java 17+."
        return $cmd.Source
    }
    throw "javaw.exe not found. Expected a bundled JDK at '$bundled'."
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
    Write-Output "Jar not found. Building with Maven..."
    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    if (-not $mvn) { throw "mvn not found in PATH and $jar does not exist. Build the project first." }
    & mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }
}

$javaExe = Get-JavaExe
Write-Output "Starting DBAgent-Java (port $port) using $javaExe ..."
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
