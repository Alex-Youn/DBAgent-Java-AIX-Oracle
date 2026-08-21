# Rebuilds target\dbagent-java-0.1.0.jar with Maven.
#
# Windows locks a jar file while a running JVM has it open on its classpath, so `mvn package`
# fails with "Unable to rename ... .jar.original" if start.ps1's instance is still up. This script
# stops it first (only if it was actually running), builds, then restarts it - so a normal
# edit-build-test loop is just ".\build.ps1" instead of stop / mvn package / start by hand.
#
# -Dist also copies the freshly built jar into dist\ (the self-contained jlink distribution),
# stopping/restarting dist\'s own instance the same way if it happens to be running, and keeping a
# one-generation .bak of the previous dist jar in case the new one needs to be rolled back.
param(
    [switch]$Dist
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$pidFile = "dbagent-java.pid"
$jar = "target\dbagent-java-0.1.0.jar"
$distDir = Join-Path $PSScriptRoot "dist"
$distJar = Join-Path $distDir "dbagent-java-0.1.0.jar"
$distPidFile = Join-Path $distDir "dbagent-java.pid"

function Get-MvnExe {
    # Bundled Maven (this folder) first, same reasoning as start.ps1's Get-JavaExe: don't depend on
    # whatever Maven/JDK happen to be on the host's PATH.
    $bundled = Join-Path $PSScriptRoot "maven39\bin\mvn.cmd"
    if (Test-Path $bundled) {
        return $bundled
    }
    $cmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($cmd) {
        Write-Output "WARNING: bundled maven39\ not found, falling back to PATH mvn ($($cmd.Source))."
        return $cmd.Source
    }
    throw "mvn not found in PATH and no bundled maven39\ found."
}

$wasRunning = $false
if (Test-Path $pidFile) {
    $existingPid = Get-Content $pidFile
    $existing = Get-Process -Id $existingPid -ErrorAction SilentlyContinue
    if ($existing) {
        $wasRunning = $true
        Write-Output "DBAgent-Java is running (PID $existingPid) - stopping it so the jar can be rebuilt..."
        & (Join-Path $PSScriptRoot "stop.ps1")
    }
}

$mvnExe = Get-MvnExe
Write-Output "Building with Maven ($mvnExe) ..."
& $mvnExe -q -DskipTests package
if ($LASTEXITCODE -ne 0) {
    throw "Maven build failed (exit code $LASTEXITCODE). See the Maven output above."
}
Write-Output "Build succeeded: $jar"

if ($Dist) {
    if (-not (Test-Path $distDir)) {
        Write-Output "WARNING: -Dist given but '$distDir' does not exist; skipping dist sync."
    } else {
        $distWasRunning = $false
        if (Test-Path $distPidFile) {
            $distExistingPid = Get-Content $distPidFile
            $distExisting = Get-Process -Id $distExistingPid -ErrorAction SilentlyContinue
            if ($distExisting) {
                $distWasRunning = $true
                Write-Output "dist\ instance is running (PID $distExistingPid) - stopping it so its jar can be replaced..."
                & (Join-Path $distDir "stop.ps1")
            }
        }

        if (Test-Path $distJar) {
            Copy-Item $distJar "$distJar.bak" -Force
        }
        Copy-Item $jar $distJar -Force
        Write-Output "Synced dist\dbagent-java-0.1.0.jar from $jar"

        if ($distWasRunning) {
            Write-Output "Restarting dist\ instance..."
            & (Join-Path $distDir "start.ps1")
        }
    }
}

if ($wasRunning) {
    Write-Output "Restarting DBAgent-Java..."
    & (Join-Path $PSScriptRoot "start.ps1")
} else {
    Write-Output "DBAgent-Java was not running before the build, so it was not started. Run start.ps1 (or start.bat) to launch it."
}
