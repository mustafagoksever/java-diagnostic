param(
    [Parameter(Mandatory = $true)][long]$TargetPid,
    [Parameter(Mandatory = $true)][string]$ExpectedOwner,
    [switch]$ExpectAdministrator
)
$ErrorActionPreference = 'Stop'
if ($TargetPid -le 0) { throw 'TargetPid pozitif olmalı.' }
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
$elevated = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($ExpectAdministrator -and !$elevated) { throw 'Bu testi Yönetici PowerShell oturumunda çalıştırın.' }
Write-Host ('Collector: ' + $identity.Name + '; Administrator: ' + $elevated + '; Hedef: ' + $TargetPid)
Push-Location (Join-Path $PSScriptRoot '..')
try {
    & mvn -B -ntp -Pwindows-it '-Dit.test=WindowsAttachIT#externalUserOrServiceJvmProbeWhenConfigured' "-Ddiagnostic.externalPid=$TargetPid" "-Ddiagnostic.expectedOwner=$ExpectedOwner" verify
    if ($LASTEXITCODE -ne 0) { throw 'Windows attach testi başarısız; target\failsafe-reports klasörünü inceleyin.' }
    Write-Host 'Sonuçlar: target\windows-evidence\external-*.json. Attach hatası beklenen bir sonuç olabilir; test hedef JVM yi durdurmaz.'
} finally { Pop-Location }
