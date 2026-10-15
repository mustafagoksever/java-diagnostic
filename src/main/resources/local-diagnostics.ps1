param([Parameter(Mandatory=$true)][string]$RequestFile)
# Reuse the same bounded helpers and BAT handling as remote collection.
# Dot-sourcing only defines/initializes the worker; it does not scan or collect JVMs.
. (Join-Path $PSScriptRoot 'remote-worker.ps1') -RequestFile $RequestFile -LibraryOnly
$result.discovery=$settings.discovery
$result|Add-Member -NotePropertyName logsReady -NotePropertyValue ([string]::IsNullOrWhiteSpace([string]$settings.beforeLogCopyBatch))
try {
    Activity '[Yerel] Windows tanilama adimlari basliyor.' 45 'Windows tanılama bilgileri toplanıyor...'
    if($settings.windowsDiagnostics){
        try{. (Join-Path $jobRoot 'remote-windows.ps1');Collect-WindowsDiagnostics}
        catch{$result.errors+=,('WINDOWS_DIAGNOSTICS_FAILED: '+$_.Exception.Message);Deadline}
    }
    if($settings.ping.enabled -or $settings.vxworks.enabled){
        try{. (Join-Path $jobRoot 'remote-extras.ps1');Collect-RemoteExtraDiagnostics}
        catch{$result.errors+=,('EXTRA_DIAGNOSTICS_FAILED: '+$_.Exception.Message);Deadline}
    }
    if(![string]::IsNullOrWhiteSpace([string]$settings.beforeLogCopyBatch)){
        Activity '[Yerel] Log hazirligi basliyor.' 65 'Log dosyaları hazırlanıyor...'
        $preparation=Run-BeforeLogCopy ([string]$settings.applicationDirectory)
        $result.operations+=,$preparation;$result.logsReady=[bool]$preparation.success
        if(!$preparation.success){$result.errors+=,('BEFORE_LOG_COPY_FAILED: '+$preparation.error+'; log kopyalama yapilmadi.')}
    }
}catch{$result.errors+=,('LOCAL_DIAGNOSTICS_FAILED: '+$_.Exception.Message)}
finally {
    $finished=[DateTimeOffset]::Now
    $result.serverCompletedAtUtc=$finished.UtcDateTime.ToString('o')
    $result.serverCompletedAtLocal=$finished.ToString('o');$result.serverElapsedMillis=$clock.ElapsedMilliseconds
    Write-Json (Join-Path $jobRoot 'complete.json.tmp') $result
    Move-Item -LiteralPath (Join-Path $jobRoot 'complete.json.tmp') -Destination (Join-Path $jobRoot 'complete.json')
}
