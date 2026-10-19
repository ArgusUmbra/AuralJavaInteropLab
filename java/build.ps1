$ErrorActionPreference = 'Stop'

$javaRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$workspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $javaRoot '..'))
$buildRoot = [System.IO.Path]::GetFullPath((Join-Path $workspaceRoot 'build'))
$mainOutput = [System.IO.Path]::GetFullPath((Join-Path $buildRoot 'classes-main'))
$testOutput = [System.IO.Path]::GetFullPath((Join-Path $buildRoot 'classes-test'))

foreach ($target in @($mainOutput, $testOutput)) {
    if (-not $target.StartsWith($buildRoot + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean build path outside the workspace build directory: $target"
    }
    if (Test-Path -LiteralPath $target) {
        Remove-Item -LiteralPath $target -Recurse -Force
    }
    New-Item -ItemType Directory -Path $target | Out-Null
}

$mainSources = @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'src\main\java') -Filter '*.java' -File -Recurse |
    Sort-Object FullName | ForEach-Object FullName)
$testSources = @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'src\test\java') -Filter '*.java' -File -Recurse |
    Sort-Object FullName | ForEach-Object FullName)

if ($mainSources.Count -eq 0 -or $testSources.Count -eq 0) {
    throw 'Expected both main and test Java sources.'
}

& javac --release 21 -encoding UTF-8 -Xlint:all -Werror -d $mainOutput $mainSources
if ($LASTEXITCODE -ne 0) { throw "Main compilation failed with exit code $LASTEXITCODE" }

& javac --release 21 -encoding UTF-8 -Xlint:all -Werror -cp $mainOutput -d $testOutput $testSources
if ($LASTEXITCODE -ne 0) { throw "Test compilation failed with exit code $LASTEXITCODE" }

Write-Output "Compiled $($mainSources.Count) main and $($testSources.Count) test source files."
