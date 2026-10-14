param([switch]$SkipBuild)
$ErrorActionPreference='Stop'
$collectorRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location -LiteralPath $collectorRoot
try {
    if(!$SkipBuild){& mvn -B -ntp verify;if($LASTEXITCODE -ne 0){throw 'Maven build failed; package was not updated'}}
    $builtJar=Join-Path $collectorRoot 'target\java-diagnostic-collector.jar'
    if(!(Test-Path -LiteralPath $builtJar -PathType Leaf)){throw 'Built JAR not found'}
    $publishedJar=Join-Path $collectorRoot 'java-diagnostic-collector.jar'
    Copy-Item -LiteralPath $builtJar -Destination $publishedJar -Force
    $zipPath=Join-Path $collectorRoot 'target\diagnostic-collector-single-jar.zip'
    Compress-Archive -LiteralPath $publishedJar -DestinationPath $zipPath -Force
    $builtHash=(Get-FileHash -LiteralPath $builtJar -Algorithm SHA256).Hash
    if((Get-FileHash -LiteralPath $publishedJar -Algorithm SHA256).Hash -ne $builtHash){throw 'Published JAR hash mismatch'}
    Write-Output $publishedJar
    Write-Output $zipPath
} finally {Pop-Location}
