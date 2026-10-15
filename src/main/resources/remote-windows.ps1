# Dot-sourced by the server worker. Fixed read-only commands; never configure or
# restart services, disconnect shares, close sockets, or dump native processes.
function Invoke-WindowsCommand([string]$name,[string]$exe,[string]$arguments,[string]$artifact) {
    Deadline
    $op=[pscustomobject]@{type=('windows-'+$name);command=@($exe,$arguments);startedAt=[DateTime]::UtcNow.ToString('o');completedAt=$null;success=$false;timedOut=$false;exitCode=$null;durationMillis=0;artifact=$artifact;stderrArtifact=($artifact+'.stderr.txt');error=$null}
    $watch=[Diagnostics.Stopwatch]::StartNew();$child=$null;$started=$false;$stdout=$null;$stderr=$null
    Activity ('[Windows] BASLADI: '+$name+' | '+$exe+' '+$arguments)
    try {
        $outFile=Join-Path $dataRoot $artifact
        [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outFile))
        $stdout=[IO.File]::Open($outFile,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
        $stderr=[IO.File]::Open(($outFile+'.stderr.txt'),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
        $start=New-Object Diagnostics.ProcessStartInfo
        $start.FileName=$exe;$start.Arguments=$arguments;$start.WorkingDirectory=$env:SystemRoot
        $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
        $child=New-Object Diagnostics.Process;$child.StartInfo=$start;$started=$child.Start()
        $copy=$child.StandardOutput.BaseStream.CopyToAsync($stdout);$copyErr=$child.StandardError.BaseStream.CopyToAsync($stderr)
        $budget=[int][Math]::Max(1,[Math]::Min(15000,($settings.timeoutSeconds-$clock.Elapsed.TotalSeconds)*1000))
        if(!(Wait-Helper $child $budget)){$op.timedOut=$true;throw ('WINDOWS_COMMAND_TIMEOUT: '+$name)}
        if(!$copy.Wait(3000) -or !$copyErr.Wait(3000)){throw 'WINDOWS_OUTPUT_TIMEOUT'}
        $op.exitCode=$child.ExitCode;$op.success=($op.exitCode -eq 0)
        if(!$op.success){$op.error='Exit '+$op.exitCode+'; see stdout/stderr'}
    }catch{$op.error=$_.Exception.Message}
    finally {
        try{Stop-Helper $child $started}finally{if($stdout){$stdout.Dispose()};if($stderr){$stderr.Dispose()}}
        $op.completedAt=[DateTime]::UtcNow.ToString('o');$op.durationMillis=$watch.ElapsedMilliseconds
        $result.operations+=,$op
        Activity ('[Windows] '+$name+' | '+$(if($op.success){'TAMAMLANDI'}else{'ALINAMADI: '+$op.error})+' | '+$artifact)
    }
}
function Write-WindowsRecords([string]$artifact,$records) {
    # InputObject preserves [] and single-record arrays (the pipeline unwraps
    # them). Every record file, including an empty event log, is valid UTF-8 JSON.
    $path=Join-Path $dataRoot $artifact
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))
    [IO.File]::WriteAllText($path,(ConvertTo-Json -InputObject @($records) -Depth 6),(New-Object Text.UTF8Encoding($false)))
}
function Collect-WindowsDiagnostics {
    if($settings.action -ne 'collect'){return}
    [void][IO.Directory]::CreateDirectory((Join-Path $dataRoot 'server/diagnostics'))
    $eventRecords=@()
    try {
    $system32=Join-Path $env:SystemRoot 'System32'
    foreach($entry in @(
        @('ipconfig','ipconfig.exe','/all','network/ipconfig.txt'),
        @('connections','netstat.exe','-ano','network/netstat-ano.txt'),
        @('network-counters','netstat.exe','-s','network/netstat-statistics.txt'),
        @('routes','route.exe','print','network/routes.txt'),
        @('arp','arp.exe','-a','network/arp.txt'),
        @('shares','net.exe','use','network/share-connections.txt'),
        @('identity','whoami.exe','/all','diagnostics/whoami.txt'),
        @('tasks','tasklist.exe','/v /fo csv','diagnostics/tasklist.csv'),
        @('services','tasklist.exe','/svc /fo csv','diagnostics/process-services.csv'),
        @('drivers','driverquery.exe','/v /fo csv','diagnostics/drivers.csv'),
        @('system','systeminfo.exe','/fo list','diagnostics/systeminfo.txt'),
        @('sessions','query.exe','user','diagnostics/sessions.txt'),
        @('clock','w32tm.exe','/query /status','diagnostics/time-service.txt')
    )) {
        Invoke-WindowsCommand $entry[0] (Join-Path $system32 $entry[1]) $entry[2] ('server/'+$entry[3])
    }
    # Disk/RAM/CPU snapshots and per-JVM counters are already in remote-system.json
    # and kaynak-izleme.*. Query the rest in disposable, bounded helpers.
    $queries=@(
        @('process-details',"Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,Name,CreationDate,ExecutablePath,SessionId,ThreadCount,HandleCount,WorkingSetSize,PrivatePageCount,KernelModeTime,UserModeTime"),
        @('services',"Get-CimInstance Win32_Service | Select-Object Name,DisplayName,State,StartMode,ProcessId,StartName,PathName")
    )
    foreach($eventLog in @('System','Application')){
        # One denied/slow channel must not discard the other channel's records.
        # The names are a fixed allowlist, never shell input from configuration.
        $queries+=,@(('events-'+$eventLog.ToLowerInvariant()),("try{Get-WinEvent -FilterHashtable @{LogName='"+$eventLog+"';Level=1,2,3;StartTime=[DateTime]::Now.AddHours(-48)} -MaxEvents 100 -ErrorAction Stop | Select-Object TimeCreated,LogName,ProviderName,Id,LevelDisplayName,Message}catch{if(`$_.FullyQualifiedErrorId -notlike 'NoMatchingEventsFound*'){throw}}"))
    }
    foreach($query in $queries) {
        Deadline
        $artifact='server/diagnostics/'+$query[0]+'.json'
        $op=[pscustomobject]@{type=('windows-'+$query[0]);command=@('PowerShell', $query[1]);startedAt=[DateTime]::UtcNow.ToString('o');completedAt=$null;success=$false;timedOut=$false;durationMillis=0;artifact=$artifact;error=$null}
        $watch=[Diagnostics.Stopwatch]::StartNew()
        try {
            $value=Run-Script ('@('+ $query[1]+') | ConvertTo-Json -Depth 6 -Compress') '' ('windows-'+$query[0]) ([Math]::Min(15,$settings.scanTimeoutSeconds))
            $records=@($value|Where-Object{$null -ne $_})
            Write-WindowsRecords $artifact $records
            if($query[0] -like 'events-*'){$eventRecords+=$records}
            $op.success=$true
        }catch{$op.error=$_.Exception.Message;$op.timedOut=$op.error -match 'TIMEOUT|DEADLINE'}
        finally{$op.completedAt=[DateTime]::UtcNow.ToString('o');$op.durationMillis=$watch.ElapsedMilliseconds;$result.operations+=,$op;Activity ('[Windows] '+$query[0]+' | '+$(if($op.success){'TAMAMLANDI'}else{'ALINAMADI: '+$op.error})+' | '+$artifact)}
    }
    } finally {
    # Keep the existing combined path, including any channel collected before
    # a later failure/deadline. Per-channel operations retain errors separately.
    Write-WindowsRecords 'server/diagnostics/events.json' $eventRecords
    $summary=New-Object 'Collections.Generic.List[string]'
    $summary.Add('SUNUCUDA WINDOWS TANILAMASI')
    $summary.Add('Bu komutlar baglanilan Windows sunucusunda calistirildi. Ham yerel komut ciktilari Windows OEM kodlamasindadir; JSON UTF-8 kullanir.')
    $summary.Add('CPU/RAM: ../sunucu-kaynak-kullanimi.txt ve ../kaynak-izleme-ozeti.txt; disk: ../disk-bilgileri.txt')
    $summary.Add('Olaylar: diagnostics/events.json (alinabilen kayitlar); kanal bazinda events-system.json ve events-application.json. Bos liste hata olmadiginda [] olarak yazilir; erisim hatalari asagida ve manifestte bulunur.')
    $summary.Add('VxWorks tt/ti/checkStack ve uygulamaya ozel komutlarin Windows icin birebir karsiligi yoktur. Java thread dump dosyalari ../jvms altindadir.')
    foreach($op in @($result.operations|Where-Object{$_.type -like 'windows-*'})){$summary.Add(($op.type+' | '+$(if($op.success){'OK'}else{'FAILED: '+$op.error})+' | '+$op.artifact))}
    [IO.File]::WriteAllLines((Join-Path $dataRoot 'server/SONUC.txt'),$summary.ToArray(),(New-Object Text.UTF8Encoding($false)))
    }
}
