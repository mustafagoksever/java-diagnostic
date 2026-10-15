param([switch]$SkipBuild)
$ErrorActionPreference='Stop'
$collectorRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
function Assert-JarNotRunning([string]$jarPath) {
    $expected=[IO.Path]::GetFullPath($jarPath)
    foreach($process in @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction Stop)) {
        $launch=[regex]::Match([string]$process.CommandLine,'(?i)(?:^|\s)-jar\s+(?:"(?<jar>[^"]+)"|(?<jar>\S+))')
        if(!$launch.Success){continue}
        $argument=$launch.Groups['jar'].Value
        $same=if([IO.Path]::IsPathRooted($argument)){
            [IO.Path]::GetFullPath($argument).Equals($expected,[StringComparison]::OrdinalIgnoreCase)
        }else{
            # The remote process working directory is unknown. A matching
            # relative JAR name is conservatively treated as in use.
            [IO.Path]::GetFileName($argument).Equals([IO.Path]::GetFileName($expected),[StringComparison]::OrdinalIgnoreCase)
        }
        if($same){throw ('Collector JAR is running (PID '+$process.ProcessId+'). Close that Collector window before building/publishing: '+$expected)}
    }
}
Push-Location -LiteralPath $collectorRoot
try {
    $builtJar=Join-Path $collectorRoot 'target\java-diagnostic-collector.jar'
    $publishedJar=Join-Path $collectorRoot 'java-diagnostic-collector.jar'
    Assert-JarNotRunning $publishedJar
    if(!$SkipBuild){Assert-JarNotRunning $builtJar;& mvn -B -ntp verify;if($LASTEXITCODE -ne 0){throw 'Maven build failed; package was not updated'}}
    if(!(Test-Path -LiteralPath $builtJar -PathType Leaf)){throw 'Built JAR not found'}
    $builtHash=(Get-FileHash -LiteralPath $builtJar -Algorithm SHA256).Hash
    $candidate=Join-Path $collectorRoot ('.collector-publish-'+[Guid]::NewGuid()+'.tmp')
    try {
        [IO.File]::Copy($builtJar,$candidate,$false)
        if((Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash -ne $builtHash){throw 'Candidate JAR hash mismatch'}
        Assert-JarNotRunning $publishedJar
        # Never truncate the old archive. Replacing its directory entry either
        # succeeds atomically or leaves the previous JAR intact (e.g. if locked).
        if([IO.File]::Exists($publishedJar)){[IO.File]::Replace($candidate,$publishedJar,[System.Management.Automation.Language.NullString]::Value)}
        else{[IO.File]::Move($candidate,$publishedJar)}
    } finally {if([IO.File]::Exists($candidate)){[IO.File]::Delete($candidate)}}
    $zipPath=Join-Path $collectorRoot 'target\diagnostic-collector-single-jar.zip'
    Compress-Archive -LiteralPath $publishedJar -DestinationPath $zipPath -Force
    if((Get-FileHash -LiteralPath $publishedJar -Algorithm SHA256).Hash -ne $builtHash){throw 'Published JAR hash mismatch'}
    Write-Output $publishedJar
    Write-Output $zipPath
} finally {Pop-Location}
