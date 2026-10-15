# Loaded only by the collect action. Uses the worker's bounded helper and cleanup.
function Invoke-ExtraHelper([string]$type,[string]$resource,[string]$arguments,[int]$timeout,[string]$artifact){
    Deadline
    Activity ('[Ek tanilama] BASLADI: '+$type+' | sonuc: '+$artifact)
    $op=[pscustomobject]@{type=$type;startedAt=[DateTime]::UtcNow.ToString('o');completedAt=$null;success=$false;timedOut=$false;durationMillis=0;artifact=$artifact;error=$null}
    $watch=[Diagnostics.Stopwatch]::StartNew();$value=$null
    try{
        $file=Join-Path $jobRoot $resource
        if(!(Test-Path -LiteralPath $file -PathType Leaf)){throw ('EXTRA_HELPER_MISSING: '+$resource)}
        # All paths and extra arguments are generated from the validated UUID
        # directory / COM identifier, never from free-form shell input.
        $command='& '''+$file+''' -JobRoot '''+$jobRoot+''' -RequestFile '''+(Join-Path $jobRoot 'request.json')+''''+$arguments
        $value=Run-Script $command '' $type $timeout
        $op.timedOut=[bool]$value.timedOut
        $op.success=[bool]$value.success
        if(!$op.success){$op.error=if($value.error){[string]$value.error}else{'Some requested diagnostics could not be collected; see the detailed result.'}}
    }catch{
        $op.error=$_.Exception.Message;$op.timedOut=$op.error -match 'TIMEOUT|DEADLINE'
        Deadline
    }finally{
        $op.completedAt=[DateTime]::UtcNow.ToString('o');$op.durationMillis=$watch.ElapsedMilliseconds
        $result.operations+=,$op
        Activity ('[Ek tanilama] '+$type+' | '+$(if($op.success){'TAMAMLANDI'}else{'KISMI/BASARISIZ: '+$op.error})+' | sure '+$op.durationMillis+' ms')
    }
    return $value
}
function Select-VxWorksPorts($vx){
    if(!$settings.vxworks.autoDiscoverPorts){Activity ('[VxWorks] Ayarlardaki portlar kullaniliyor: '+(@($settings.vxworks.ports) -join ', '));return @($settings.vxworks.ports)}
    $op=[pscustomobject]@{type='serial-port-discovery';startedAt=[DateTime]::UtcNow.ToString('o');completedAt=$null;success=$false;timedOut=$false;durationMillis=0;error=$null}
    $watch=[Diagnostics.Stopwatch]::StartNew();$ports=@()
    try{
        Deadline
        Write-Json (Join-Path $jobRoot 'progress.json') @{jobId=$settings.jobId;phase='serial-port-discovery';message='Sunucudaki COM portlari otomatik kesfediliyor...'}
        # This query executes in the bounded SERVER-side helper, never on the client.
        $found=Run-Script '[pscustomobject]@{ports=@([IO.Ports.SerialPort]::GetPortNames())}|ConvertTo-Json -Compress' '' 'serial-port-discovery' $settings.scanTimeoutSeconds
        foreach($name in @($found.ports)){
            $port=([string]$name).Trim().ToUpperInvariant()
            if($port -notmatch '^COM[1-9][0-9]{0,4}$' -or [int]$port.Substring(3) -gt 65535){$vx.errors+=,('UNSUPPORTED_SERIAL_PORT_NAME: '+[string]$name);$vx.success=$false;continue}
            if($port -notin $ports){$ports+=,$port}
        }
        $ports=@($ports|Sort-Object {[int]$_.Substring(3)})
        $vx.detectedPorts=$ports;$op.success=$true
        if(!$ports.Count){$vx.success=$false;$vx.errors+=,'Sunucuda kayitli COM portu bulunamadi.'}
    }catch{
        $op.error=$_.Exception.Message;$op.timedOut=$op.error -match 'TIMEOUT|DEADLINE'
        $vx.success=$false;$vx.errors+=,('SERIAL_PORT_DISCOVERY_FAILED: '+$op.error)
        Deadline
    }finally{
        $op.completedAt=[DateTime]::UtcNow.ToString('o');$op.durationMillis=$watch.ElapsedMilliseconds;$result.operations+=,$op
        Activity ('[VxWorks] Port kesfi '+$(if($op.success){'tamamlandi'}else{'basarisiz: '+$op.error})+' | portlar: '+($ports -join ', '))
    }
    return $ports
}
function Collect-RemoteExtraDiagnostics {
    if($settings.action -ne 'collect'){return}
    if($settings.ping.enabled){
        Activity ('[Ping] SUNUCUDAN test basliyor: '+$settings.ping.addresses.Count+' hedef, IP basina '+$settings.ping.attempts+' istek.')
        $parallelism=if($settings.ping.parallelism){[int]$settings.ping.parallelism}else{16}
        $waves=[Math]::Ceiling(@($settings.ping.addresses).Count/[double]$parallelism)
        $budget=[int][Math]::Ceiling($waves*($settings.ping.attempts*($settings.ping.timeoutMillis+500)+[Math]::Max(0,$settings.ping.attempts-1)*$settings.ping.intervalMillis)/1000.0+15)
        $ping=Invoke-ExtraHelper 'server-ping' 'remote-ping.ps1' '' $budget 'ping/results.json'
        if(!$ping){$ping=[pscustomobject]@{success=$false;completed=$false;results=@();errors=@('Ping helper did not return a result')}}
        $result|Add-Member -NotePropertyName ping -NotePropertyValue $ping -Force
        foreach($ip in $ping.results){Activity ('[Ping] '+$ip.address+' | '+$ip.status+' | yanit '+$ip.received+'/'+$ip.sent+' | ortalama '+$ip.averageRoundTripMillis+' ms')}
        Activity ('[Ping] Bitti: '+@($ping.results|Where-Object{$_.received -gt 0}).Count+' IP yanit verdi; toplam '+@($ping.results).Count+' sonuc.')
    }else{Activity '[Ping] Ayarlarda kapali; ping gonderilmeyecek.'}
    if($settings.vxworks.enabled){
        $vx=[pscustomobject]@{discoveryMode=if($settings.vxworks.autoDiscoverPorts){'auto'}else{'configured'};detectedPorts=@();ports=@();success=$true;errors=@()}
        $result|Add-Member -NotePropertyName vxworks -NotePropertyValue $vx -Force
        $ports=@(Select-VxWorksPorts $vx)
        foreach($port in $ports){
            Deadline
            if($port -notmatch '^COM[1-9][0-9]{0,4}$' -or [int]$port.Substring(3) -gt 65535){throw 'Invalid COM port'}
            $artifact='vxworks/ports/port-'+$port+'/port-result.json'
            Write-Json (Join-Path $jobRoot 'progress.json') @{jobId=$settings.jobId;phase='vxworks';message=($port+': VxWorks task ve ag bilgileri toplaniyor...')}
            $probe=Invoke-ExtraHelper ('vxworks-'+$port) 'remote-vxworks.ps1' (' -PortName '''+$port+'''') ([int]$settings.vxworks.portTimeoutSeconds+5) $artifact
            if(!$probe){
                $probe=[pscustomobject]@{port=$port;success=$false;completed=$false;status='HELPER_FAILED';error='See operation error and partial console output';tasks=@();commands=@();socketRows=@()}
                $folder=Join-Path $dataRoot ('vxworks\ports\port-'+$port)
                if(Test-Path -LiteralPath $folder -PathType Container){Write-Json (Join-Path $folder 'helper-failure.json') $probe}
            }
            $vx.ports+=,[pscustomobject]@{port=$port;success=[bool]$probe.success;completed=[bool]$probe.completed;status=$probe.status;identity=$probe.identity;customCommand=$probe.customCommand;initialTaskCount=[int]$probe.initialTaskCount;attemptedTaskCount=[int]$probe.attemptedTaskCount;tracesWithFrames=[int]$probe.tracesWithFrames;attemptedTaskInfoCount=[int]$probe.attemptedTaskInfoCount;taskInfosCollected=[int]$probe.taskInfosCollected;networkAvailable=[bool]$probe.networkAvailable;artifact=$artifact;error=$probe.error}
            if(!$probe.success){$vx.success=$false}
            $vx.ports[-1]|Add-Member -NotePropertyName versionConfirmed -NotePropertyValue ([bool]$probe.versionConfirmed)
            $vx.ports[-1]|Add-Member -NotePropertyName diagnosticAttemptedCount -NotePropertyValue (@($probe.diagnostics|Where-Object{$null -ne $_}).Count)
            $vx.ports[-1]|Add-Member -NotePropertyName diagnosticCollectedCount -NotePropertyValue (@($probe.diagnostics|Where-Object{$_.collected}).Count)
            Activity ('[VxWorks] '+$port+' SONUC: '+$probe.status+' | '+$probe.initialTaskCount+' task | tt frame='+$probe.tracesWithFrames+' | ti='+$probe.taskInfosCollected+' | '+$probe.error)
        }
    }else{Activity '[VxWorks] Ayarlarda kapali; seri port acilmayacak.'}
}
