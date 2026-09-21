param(
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$WindowsSocketWorkaround
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $JavaHome) {
    $candidate = Get-ChildItem -LiteralPath "$env:USERPROFILE/.jdks" -Directory -ErrorAction SilentlyContinue |
        Where-Object Name -Like '*21*' | Select-Object -First 1
    if ($candidate) { $JavaHome = $candidate.FullName }
}
if (-not (Test-Path -LiteralPath "$JavaHome/bin/javac.exe")) { throw 'Set -JavaHome to a JDK 21 installation.' }
$savedJava = $env:JAVA_HOME
$savedOptions = $env:JAVA_TOOL_OPTIONS
Push-Location $projectRoot
try {
    $env:JAVA_HOME = $JavaHome
    if ($WindowsSocketWorkaround) {
        $output = Join-Path $projectRoot 'build/local-tools'
        New-Item -ItemType Directory -Force -Path $output | Out-Null
        $sourceHash = (Get-FileHash -LiteralPath "$PSScriptRoot/build-support/LocalSocketAgent.java").Hash.Substring(0, 12)
        $agentJar = Join-Path $output "socket-agent-$sourceHash.jar"
        if (-not (Test-Path -LiteralPath $agentJar)) {
            & "$JavaHome/bin/javac.exe" -d $output "$PSScriptRoot/build-support/LocalSocketAgent.java"
            if ($LASTEXITCODE -ne 0) { throw 'Could not compile the local build workaround.' }
            'Premain-Class: LocalSocketAgent' | Set-Content -Encoding ASCII -LiteralPath "$output/MANIFEST.MF"
            & "$JavaHome/bin/jar.exe" cfm $agentJar "$output/MANIFEST.MF" -C $output LocalSocketAgent.class
            if ($LASTEXITCODE -ne 0) { throw 'Could not package the local build workaround.' }
        }
        $env:JAVA_TOOL_OPTIONS = "$savedOptions -javaagent:`"$agentJar`"".Trim()
    }
    & ./gradlew.bat test buildPlugin --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally {
    $env:JAVA_HOME = $savedJava
    $env:JAVA_TOOL_OPTIONS = $savedOptions
    Pop-Location
}
