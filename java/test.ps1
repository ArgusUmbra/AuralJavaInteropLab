$ErrorActionPreference = 'Stop'

$javaRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$workspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $javaRoot '..'))

& (Join-Path $javaRoot 'build.ps1')
if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE" }

$mainOutput = Join-Path $workspaceRoot 'build\classes-main'
$testOutput = Join-Path $workspaceRoot 'build\classes-test'
$goldenManifest = Join-Path $workspaceRoot 'frozen-input\tests\fixtures\conformance\golden-manifest.json'
$classPath = $mainOutput + [System.IO.Path]::PathSeparator + $testOutput

& java -cp $classPath org.auralinterop.tests.AllTests $goldenManifest
if ($LASTEXITCODE -ne 0) { throw "Foundation tests failed with exit code $LASTEXITCODE" }
