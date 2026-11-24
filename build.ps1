$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path $PSScriptRoot).Path
$buildDir = Join-Path $projectRoot 'build'
$distDir = Join-Path $projectRoot 'dist-minimal'
$classesDir = Join-Path $buildDir 'classes'
$inputDir = Join-Path $buildDir 'input'

foreach ($target in @($buildDir, $distDir)) {
    $resolvedTarget = [System.IO.Path]::GetFullPath($target)
    if (-not $resolvedTarget.StartsWith($projectRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean a path outside the project: $resolvedTarget"
    }
    if (Test-Path -LiteralPath $resolvedTarget) {
        Remove-Item -LiteralPath $resolvedTarget -Recurse -Force
    }
    New-Item -ItemType Directory -Path $resolvedTarget -Force | Out-Null
}

$javaProcess = New-Object System.Diagnostics.Process
$javaProcess.StartInfo.FileName = (Get-Command java.exe).Source
$javaProcess.StartInfo.Arguments = '-XshowSettings:properties -version'
$javaProcess.StartInfo.UseShellExecute = $false
$javaProcess.StartInfo.RedirectStandardOutput = $true
$javaProcess.StartInfo.RedirectStandardError = $true
$null = $javaProcess.Start()
$javaSettings = $javaProcess.StandardError.ReadToEnd() + $javaProcess.StandardOutput.ReadToEnd()
$javaProcess.WaitForExit()
$homeMatch = [regex]::Match($javaSettings, 'java\.home\s*=\s*(.+)')
if (-not $homeMatch.Success) {
    throw 'A Java Development Kit is required. Install JDK 17 or newer, then run build.ps1 again.'
}
$javaHome = $homeMatch.Groups[1].Value.Trim()
$javac = Join-Path $javaHome 'bin\javac.exe'
$jarTool = Join-Path $javaHome 'bin\jar.exe'
$jdeps = Join-Path $javaHome 'bin\jdeps.exe'
$jlink = Join-Path $javaHome 'bin\jlink.exe'
$jpackage = Join-Path $javaHome 'bin\jpackage.exe'
foreach ($tool in @($javac, $jarTool, $jdeps, $jlink, $jpackage)) {
    if (-not (Test-Path -LiteralPath $tool)) {
        throw "Required JDK tool was not found: $tool"
    }
}

$sourceDir = Join-Path $projectRoot 'src\main\java'
$sources = @(Get-ChildItem -LiteralPath $sourceDir -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) { throw "No Java source files were found in $sourceDir" }
New-Item -ItemType Directory -Path $classesDir -Force | Out-Null
& $javac --release 17 -encoding UTF-8 -d $classesDir $sources
if ($LASTEXITCODE -ne 0) { throw "Java compilation failed with exit code $LASTEXITCODE." }

New-Item -ItemType Directory -Path $inputDir -Force | Out-Null
$jarFile = Join-Path $inputDir 'NetPulse.jar'
& $jarTool --create --file $jarFile --main-class com.netpulse.NetPulseApp -C $classesDir .
if ($LASTEXITCODE -ne 0) { throw "Creating the NetPulse jar failed with exit code $LASTEXITCODE." }

$moduleOutput = & $jdeps --print-module-deps --ignore-missing-deps $jarFile
$jdepsExitCode = $LASTEXITCODE
$modules = ($moduleOutput | Out-String).Trim()
if ($jdepsExitCode -ne 0 -or $modules -notmatch '^[A-Za-z0-9_.]+(,[A-Za-z0-9_.]+)*$') {
    throw "jdeps could not determine the runtime modules for NetPulse: $modules"
}
Write-Host "jdeps runtime modules: $modules"

$runtimeDir = Join-Path $buildDir 'runtime'
& $jlink --module-path (Join-Path $javaHome 'jmods') --add-modules $modules --output $runtimeDir --strip-debug --no-header-files --no-man-pages --compress=zip-9
if ($LASTEXITCODE -ne 0) { throw "jlink failed with exit code $LASTEXITCODE." }

& $jpackage --type app-image --name NetPulse --input $inputDir --main-jar NetPulse.jar --main-class com.netpulse.NetPulseApp --runtime-image $runtimeDir --dest $distDir --app-version 1.0.0 --vendor NetPulse --description 'Windows network usage monitor'
if ($LASTEXITCODE -ne 0) { throw "Creating the Windows app image failed with exit code $LASTEXITCODE." }
Write-Host "Created the self-contained Windows app: $(Join-Path $distDir 'NetPulse\NetPulse.exe')"
Write-Host "Created the Java archive: $jarFile"