param(
    [string]$JdkHome,
    [string]$Config,
    [switch]$Administrator,
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
$collectorRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$jarCandidate = Join-Path $collectorRoot 'target\java-diagnostic-collector.jar'
if (!(Test-Path -LiteralPath $jarCandidate -PathType Leaf)) { $jarCandidate = Join-Path $collectorRoot 'java-diagnostic-collector.jar' }
$jar = (Resolve-Path -LiteralPath $jarCandidate).Path
$configArguments=@()
if($Config){$configPath=(Resolve-Path -LiteralPath $Config).Path;$configArguments=@('--config',$configPath)}
function Is-Jdk8([string]$candidateHome) {
    if (!$candidateHome) { return $false }
    foreach ($tool in @('java.exe', 'javaw.exe', 'javac.exe', 'jcmd.exe', 'jstack.exe')) {
        if (!(Test-Path -LiteralPath (Join-Path $candidateHome ('bin\' + $tool)) -PathType Leaf)) { return $false }
    }
    $version = [Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $candidateHome 'bin\java.exe')).ProductVersion
    return $version -match '^(1\.8[._]|8[._])'
}
if ($JdkHome) {
    $JdkHome = (Resolve-Path -LiteralPath $JdkHome).Path
    if (!(Is-Jdk8 $JdkHome)) { throw 'Belirtilen klasor jcmd/jstack iceren bir JDK 8 degil.' }
} else {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    foreach ($local in @('jdk8', 'jdk', '.tools\jdk8')) {
        $directory = Join-Path $collectorRoot $local
        $candidates.Add($directory)
        if (Test-Path -LiteralPath $directory -PathType Container) {
            foreach ($child in @(Get-ChildItem -LiteralPath $directory -Directory)) { $candidates.Add($child.FullName) }
        }
    }
    foreach ($programRoot in @($env:ProgramFiles, ${env:ProgramFiles(x86)})) {
        if (!$programRoot) { continue }
        foreach ($vendor in @('Java', 'Amazon Corretto', 'Eclipse Adoptium')) {
            $directory = Join-Path $programRoot $vendor
            if (Test-Path -LiteralPath $directory -PathType Container) {
                foreach ($child in @(Get-ChildItem -LiteralPath $directory -Directory)) { $candidates.Add($child.FullName) }
            }
        }
    }
    foreach ($candidate in $candidates) {
        if (Is-Jdk8 $candidate) { $JdkHome = $candidate; break }
    }
    if (!$JdkHome) { throw 'JDK 8 bulunamadi. Tasinabilir JDK 8 klasorunu jdk8 olarak yanina koyun veya -JdkHome ile belirtin.' }
}
$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome\bin;$env:PATH"
$java = Join-Path $JdkHome 'bin\java.exe'
$javaw = Join-Path $JdkHome 'bin\javaw.exe'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
& $java -jar $jar validate-config @configArguments | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Konfigurasyon gecersiz; yukaridaki hatayi duzeltin.' }
if ($ValidateOnly) { exit 0 }
# Windows dosya yollarında çift tırnak bulunamaz. ArgumentList için boşluklu yolları tırnakla.
$arguments = @('-Ddiagnostic.collector=true', '-jar', ('"' + $jar + '"'), 'gui')
if($Config){$arguments+=@('--config',('"'+$configPath+'"'))}
if ($Administrator) {
    Start-Process -FilePath $javaw -ArgumentList $arguments -WorkingDirectory $collectorRoot -Verb RunAs -WindowStyle Hidden
} else {
    Start-Process -FilePath $javaw -ArgumentList $arguments -WorkingDirectory $collectorRoot -WindowStyle Hidden
}
