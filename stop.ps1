# Stops the DBAgent-Java service started by start.ps1 (PID file first, port lookup as fallback).
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$pidFile = "dbagent-java.pid"

# Same server.port lookup as start.ps1, so the port-based fallback below still finds the right
# process if server.port was changed in application.properties.
$port = 8005
$propsFile = Join-Path $PSScriptRoot "application.properties"
if (Test-Path $propsFile) {
    $portLine = Get-Content $propsFile | Where-Object { $_ -match '^\s*server\.port\s*=\s*(\d+)\s*$' } | Select-Object -Last 1
    if ($portLine -match '^\s*server\.port\s*=\s*(\d+)\s*$') {
        $port = [int]$Matches[1]
    }
}
$stopped = $false

if (Test-Path $pidFile) {
    $storedPid = Get-Content $pidFile
    $proc = Get-Process -Id $storedPid -ErrorAction SilentlyContinue
    if ($proc) {
        Write-Output "Stopping DBAgent-Java (PID $storedPid)..."
        Stop-Process -Id $storedPid -Force
        $stopped = $true
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}

if (-not $stopped) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($conn) {
        Write-Output "No valid PID file; stopping process listening on port $port (PID $($conn.OwningProcess))..."
        Stop-Process -Id $conn.OwningProcess -Force
        $stopped = $true
    }
}

if ($stopped) {
    Write-Output "DBAgent-Java stopped."
} else {
    Write-Output "DBAgent-Java does not appear to be running."
}
