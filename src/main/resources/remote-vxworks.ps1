param([string]$PortName,[string]$JobRoot,[string]$RequestFile)
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$serialJobRoot=[IO.Path]::GetFullPath($JobRoot)
if($serialJobRoot -ne [IO.Path]::GetFullPath($PSScriptRoot)){throw 'Serial helper must run from its own job directory'}
$request=Get-Content -LiteralPath $RequestFile -Raw -Encoding UTF8|ConvertFrom-Json
$serialJobId=$request.jobId;$config=$request.vxworks
if($portName -notmatch '^COM[1-9][0-9]{0,4}$' -or [int]$portName.Substring(3) -gt 65535 -or (!$config.autoDiscoverPorts -and $portName -notin @($config.ports))){throw 'Port not permitted by console settings'}
if($serialJobRoot -notmatch '^[CD]:\\Temp\\diagnostic-remote-[0-9a-f-]{36}$'){throw 'Unsafe serial job directory'}
$portRoot=Join-Path $serialJobRoot ('data\vxworks\ports\port-'+$portName)
for($directoryAttempt=0;$directoryAttempt -lt 3;$directoryAttempt++){
    try{if(![IO.Directory]::Exists($portRoot)){[void][IO.Directory]::CreateDirectory($portRoot)};break}
    catch{if($directoryAttempt -eq 2){throw};Start-Sleep -Milliseconds 250}
}
$utf8=New-Object Text.UTF8Encoding($false)
$parity=[IO.Ports.Parity][Enum]::Parse([IO.Ports.Parity],[string]$config.parity,$true)
$stop=[IO.Ports.StopBits][int]$config.stopBits
$serial=New-Object IO.Ports.SerialPort($portName,[int]$config.baudRate,$parity,[int]$config.dataBits,$stop)
$serial.Handshake=[IO.Ports.Handshake]::None;$serial.DtrEnable=$false;$serial.RtsEnable=$false
$serial.ReadTimeout=200;$serial.WriteTimeout=1000;$serial.Encoding=[Text.Encoding]::ASCII
$consoleWriter=New-Object IO.StreamWriter((Join-Path $portRoot 'console.txt'),$false,$utf8)
$consoleWriter.AutoFlush=$true;$portWatch=[Diagnostics.Stopwatch]::StartNew();$pagerActive=$false;$lastResponse=''
$probe=[ordered]@{port=$portName;settings=$config;opened=$false;vxworksPrompt=$false;completed=$false;success=$false;timedOut=$false;status='FAILED';identity=$null;initialTaskCount=0;attemptedTaskCount=0;tracesWithFrames=0;attemptedTaskInfoCount=0;taskInfosCollected=0;networkAvailable=$false;socketRows=@();tasks=@();commands=@();afterTaskIds=@();addedTaskIds=@();removedTaskIds=@();error=$null}
$probe.tatlYaz=[pscustomobject]@{attempted=$false;collected=$false;artifact=$null;error=$null}
$probe.diagnostics=@();$probe.versionConfirmed=$false
function Save-Json([string]$path,$value){[IO.File]::WriteAllText($path,($value|ConvertTo-Json -Depth 12),$utf8)}
function Artifact([string]$name){return ('vxworks/ports/port-'+$portName+'/'+$name.Replace('\','/'))}
function Progress([string]$message){Save-Json (Join-Path $serialJobRoot 'progress.json') @{jobId=$serialJobId;phase='serial-all-tasks';message=$message}}
function Check-Limits {
    if(Test-Path -LiteralPath (Join-Path $serialJobRoot 'cancel.txt')){throw 'REMOTE_CANCELLED'}
    if($portWatch.Elapsed.TotalSeconds -gt $config.portTimeoutSeconds){throw 'SERIAL_PORT_TOTAL_TIMEOUT'}
    if((Get-Item -LiteralPath (Join-Path $portRoot 'console.txt')).Length -gt $config.maxOutputMb*1MB){throw 'SERIAL_OUTPUT_LIMIT'}
}
function Is-Prompt([string]$body){return ($body -match '(?m)(?:^|[\r\n])[ \t]*->[ \t]*(?:\r?$|\S)' -or $body -match '(?im)(?:^|[\r\n])[ \t]*\[vxWorks[^\]\r\n]*\]#[ \t]*')}
function Remove-Pagers([string]$body){return [regex]::Replace($body,'(?i)Type\s*<CR>\s*to continue,[^\r\n]*?to stop:[ \t]*',"`r`n")}
function Read-Response([int]$timeoutMillis=10000){
    $script:lastResponse=''
    $watch=[Diagnostics.Stopwatch]::StartNew();$builder=New-Object Text.StringBuilder
    $lastData=0;$promptAt=-1;$lastPromptIndex=-1;$handledPagerEnd=-1;$pages=0
    while($watch.ElapsedMilliseconds -lt $timeoutMillis){
        Check-Limits
        $part=$serial.ReadExisting()
        if($part){
            $consoleWriter.Write($part);[void]$builder.Append($part);$lastData=$watch.ElapsedMilliseconds
            if($builder.Length -gt 1MB){$script:lastResponse=$builder.ToString(0,1MB);throw 'SERIAL_RESPONSE_LIMIT: 1 MiB per command'}
        }
        $body=$builder.ToString();$script:lastResponse=$body
        $prompts=[regex]::Matches($body,'(?im)(?:^|[\r\n])[ \t]*(?:->[ \t]*(?:\r?$|\S)|\[vxWorks[^\]\r\n]*\]#[ \t]*)')
        if($prompts.Count -and $prompts[$prompts.Count-1].Index -ne $lastPromptIndex){$lastPromptIndex=$prompts[$prompts.Count-1].Index;$promptAt=$watch.ElapsedMilliseconds;$script:pagerActive=$false}
        $pagers=[regex]::Matches($body,'(?i)Type\s*<CR>\s*to continue,[^\r\n]*to stop:')
        if($pagers.Count){
            $lastPager=$pagers[$pagers.Count-1]
            if($lastPager.Index -gt $lastPromptIndex -and $lastPager.Index+$lastPager.Length -gt $handledPagerEnd){
                if(++$pages -gt 100){throw 'SERIAL_PAGINATION_LIMIT'}
                $script:pagerActive=$true;$serial.Write("`r");$handledPagerEnd=$lastPager.Index+$lastPager.Length;$promptAt=-1
            }
        }
        if(!$script:pagerActive -and $promptAt -ge 0 -and (($watch.ElapsedMilliseconds-$lastData -ge 150) -or ($watch.ElapsedMilliseconds-$promptAt -ge 400))){return $body}
        Start-Sleep -Milliseconds 25
    }
    throw 'SERIAL_RESPONSE_TIMEOUT: no completed prompt; further commands stopped'
}
function Run-Command([string]$command,[string]$artifact){
    # A failed Write must never reuse the previous command's response.
    $script:lastResponse=''
    $path=Join-Path $portRoot $artifact
    [void][IO.Directory]::CreateDirectory((Split-Path $path -Parent))
    $consoleWriter.WriteLine("`r`n[COLLECTOR COMMAND: $command]")
    $op=[ordered]@{command=$command;artifact=(Artifact $artifact);startedAtUtc=[DateTime]::UtcNow.ToString('o');durationMillis=0;returnedPrompt=$false;unsupported=$false;frameCount=0;error=$null}
    $watch=[Diagnostics.Stopwatch]::StartNew();$response=''
    try{
        Check-Limits
        $serial.Write($command+"`r")
        $response=Read-Response ([int]$config.commandTimeoutSeconds*1000)
        $op.returnedPrompt=Is-Prompt $response
        $op.unsupported=$response -match '(?i)unknown symbol name|undefined symbol|syntax error'
        $op.frameCount=[regex]::Matches((Remove-Pagers $response),'(?im)^\s*0x[0-9a-f]+\s+').Count
        if($command.StartsWith('tt ') -and $response -match '(?i)invalid task|task not found|task does not exist|trcStack.*(?:aborted|error|failed)|unable to (?:self trace|trace)|value\s*=\s*(?:-1|4294967295|18446744073709551615)'){$op.error='TASK_TRACE_FAILED: see raw response'}
        # ti returns void: the C interpreter's trailing value is not a status.
        if($command.StartsWith('ti ') -and $response -match '(?im)^\s*(?:ti(?:\(\))?:\s*)?(?:invalid[ \t]+task\b|task\b[^\r\n]*(?:\binvalid\b|\bnot[ \t]+found\b|\bdoes[ \t]+not[ \t]+exist\b))'){$op.error='TASK_INFO_FAILED: see raw response'}
        return $response
    }catch{$response=$script:lastResponse;$op.error=$_.Exception.Message;throw}
    finally{
        $op.durationMillis=$watch.ElapsedMilliseconds
        if($response){[IO.File]::WriteAllText($path,$response,$utf8)}
        $probe.commands+=,[pscustomobject]$op
        Save-Json (Join-Path $portRoot 'commands.json') $probe.commands
    }
}
function Collect-Diagnostic([string]$command,[string]$artifact){
    $entry=[pscustomobject]@{command=$command;artifact=(Artifact $artifact);collected=$false;unsupported=$false;error=$null}
    try{
        $response=Run-Command $command $artifact
        $op=$probe.commands[$probe.commands.Count-1];$entry.unsupported=$op.unsupported
        $entry.error=if($op.unsupported){'COMMAND_UNSUPPORTED: see raw response'}else{$op.error}
        if(!$entry.error -and $response -match '(?im)^\s*(?:tw(?:\(\))?:\s*)?(?:invalid[ \t]+task\b|task\b[^\r\n]*(?:\bnot[ \t]+found\b|\bdoes[ \t]+not[ \t]+exist\b))'){$entry.error='TASK_NO_LONGER_AVAILABLE: see raw response'}
        $entry.collected=($op.returnedPrompt -and !$entry.error)
        return $response
    }catch{$entry.error=$_.Exception.Message;throw}
    finally{$probe.diagnostics+=,$entry}
}
function Parse-Identity([string]$body,[string]$command,[string]$artifact){
    $body=Remove-Pagers $body
    $options=[Text.RegularExpressions.RegexOptions]::IgnoreCase -bor [Text.RegularExpressions.RegexOptions]::CultureInvariant -bor [Text.RegularExpressions.RegexOptions]::Multiline
    $addresses=@();$macs=@()
    # Read interface-address lines only. A gateway, netmask, broadcast or an
    # address merely mentioned in a help/error message is not a device IP.
    foreach($match in [regex]::Matches($body,'^[ \t]*(?:inet(?:[ \t]+addr)?[ \t]*:?[ \t]*|(?:Internet|IP|IPv4)[ \t]+address[ \t]*[:=]?[ \t]*)(?<ip>(?:[0-9]{1,3}\.){3}[0-9]{1,3})(?=[ \t\r\n/]|$)',$options)){
        $ip=$null
        if([Net.IPAddress]::TryParse($match.Groups['ip'].Value,[ref]$ip) -and $ip.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork){
            $first=$ip.GetAddressBytes()[0];$address=$ip.ToString()
            if($first -gt 0 -and $first -ne 127 -and $first -lt 224 -and $address -notin $addresses){$addresses+=,$address}
        }
    }
    foreach($match in [regex]::Matches($body,'(?:HWaddr|Ethernet[ \t]+address(?:[ \t]+is)?)[ \t]*[:=]?[ \t]*(?<mac>(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2})',$options)){
        $mac=$match.Groups['mac'].Value.Replace('-',':').ToUpperInvariant()
        if($mac -ne '00:00:00:00:00:00' -and $mac -notin $macs){$macs+=,$mac}
    }
    return [pscustomobject]@{addresses=$addresses;macAddresses=$macs;command=$command;artifact=$artifact;collected=($addresses.Count -gt 0)}
}
function Parse-Tasks([string]$body){
    $body=Remove-Pagers $body
    $tasks=@();$seen=@{}
    foreach($line in ($body -split '\r?\n')){
        $row=[regex]::Match($line,'^\s*(?<name>\S+)\s+(?<id>(?:0x)?[0-9a-fA-F]{9,16})\s+(?<priority>\d{1,3})\s+(?<state>[A-Z][A-Z+]+)\s+')
        if(!$row.Success){$row=[regex]::Match($line,'^\s*(?<name>\S+)\s+\S+\s+(?<id>(?:0x)?[0-9a-fA-F]{1,8})\s+(?<priority>\d{1,3})\s+(?<state>[A-Z][A-Z+]+)\s+')}
        if(!$row.Success){continue}
        $taskId='0x'+($row.Groups['id'].Value -replace '^0x','').ToLowerInvariant()
        if($seen.ContainsKey($taskId)){continue};$seen[$taskId]=$true
        $tasks+=,[pscustomobject]@{name=$row.Groups['name'].Value;taskId=$taskId;priority=[int]$row.Groups['priority'].Value;state=$row.Groups['state'].Value;attempted=$false;frameCount=0;returnedPrompt=$false;artifact=$null;error=$null;infoAttempted=$false;infoCollected=$false;infoArtifact=$null;infoError=$null}
    }
    return $tasks
}
function Parse-Sockets([string]$body,[string]$artifact){
    $body=Remove-Pagers $body
    $rows=@();$lineNumber=0
    foreach($line in ($body -split '\r?\n')){
        $lineNumber++
        $m=[regex]::Match($line,'^\s*(?:[0-9a-f]+\s+)?(?<proto>TCP6?|UDP6?)\s+(?<recv>\d+)\s+(?<send>\d+)\s+(?<local>\S+)\s+(?<remote>\S+)(?:\s+(?<state>[A-Z_]+))?\s*$',([Text.RegularExpressions.RegexOptions]::IgnoreCase -bor [Text.RegularExpressions.RegexOptions]::CultureInvariant))
        if(!$m.Success){continue}
        $endpoint=$m.Groups['local'].Value;$number=[regex]::Match($endpoint,'\.(\d+)$')
        $rows+=,[pscustomobject]@{port=$portName;protocol=$m.Groups['proto'].Value.ToUpperInvariant();addressFamily=if($endpoint.Contains(':')){'IPv6'}else{'IPv4'};localAddress=$endpoint;localPort=if($number.Success){[int]$number.Groups[1].Value}else{$null};foreignAddress=$m.Groups['remote'].Value;state=$m.Groups['state'].Value;receiveQueue=[long]$m.Groups['recv'].Value;sendQueue=[long]$m.Groups['send'].Value;artifact=$artifact;line=$lineNumber}
    }
    return $rows
}
function Trace-Error([string]$response,[int]$frames){
    if($response -match '(?i)unable to self trace'){return 'SELF_TRACE_UNSUPPORTED'}
    if($response -match '(?i)unable to trace unbreakable task'){return 'UNBREAKABLE_TASK'}
    if($response -match '(?i)trcStack aborted: error in top frame'){return 'TOP_FRAME_ERROR'}
    if(!$frames){return 'NO_STACK_FRAMES'}
    return $null
}
try{
    $serial.Open();$probe.opened=$true
    $serial.Write("`r");$probe.status='NO_VXWORKS_PROMPT';$initial=Read-Response ([Math]::Min(5000,$config.commandTimeoutSeconds*1000))
    if(!(Is-Prompt $initial)){throw 'VXWORKS_PROMPT_NOT_CONFIRMED'}
    $probe.vxworksPrompt=$true
    $probe.status='COLLECTING'
    $versionReply=Collect-Diagnostic 'version' 'diagnostics\version.txt'
    $probe.versionConfirmed=(!$probe.diagnostics[-1].unsupported -and $versionReply -match '(?im)^\s*VxWorks\b[^\r\n]*(?:\bversion\b|\b[5-9]\.\d)')
    if(!$probe.versionConfirmed){throw 'VXWORKS_VERSION_NOT_CONFIRMED: no VxWorks version banner; further commands stopped'}
    Progress ($portName+': once cihaz IP/MAC ve arayuz bilgileri aliniyor...')
    foreach($identityCommand in @('ifconfig','ifShow')){
        $identityArtifact='network\identity-'+$identityCommand+'.txt'
        $identityReply=Run-Command $identityCommand $identityArtifact
        $probe.identity=Parse-Identity $identityReply $identityCommand (Artifact $identityArtifact)
        if($probe.identity.collected){break}
    }
    Progress ($portName+': TatlYaz ciktisi aliniyor...')
    $probe.tatlYaz.attempted=$true;$probe.tatlYaz.artifact=Artifact 'TatlYaz.txt'
    try{
        [void](Run-Command 'TatlYaz' 'TatlYaz.txt')
        $tatlOp=$probe.commands[$probe.commands.Count-1]
        $probe.tatlYaz.error=if($tatlOp.unsupported){'TATLYAZ_UNSUPPORTED: see raw response'}else{$tatlOp.error}
        # Keep this application-specific output verbatim. Its trailing interpreter
        # return value has no assumed meaning, just as for other void functions.
        $probe.tatlYaz.collected=($tatlOp.returnedPrompt -and !$probe.tatlYaz.error)
    }catch{$probe.tatlYaz.error=$_.Exception.Message;throw}
    Progress ($portName+': guncel i task listesi aliniyor...')
    $taskList=Run-Command 'i' 'i-before.txt'
    $probe.tasks=@(Parse-Tasks $taskList);$probe.initialTaskCount=$probe.tasks.Count
    if(!$probe.initialTaskCount){throw 'TASK_LIST_PARSE_EMPTY: no TASKID could be verified'}
    if($probe.initialTaskCount -gt 4096){throw 'TASK_LIST_LIMIT: more than 4096 tasks'}
    Save-Json (Join-Path $portRoot 'task-index.json') $probe.tasks
    $index=0
    foreach($task in $probe.tasks){
        $index++;$task.attempted=$true;$probe.attemptedTaskCount++
        Progress ($portName+': tt '+$index+'/'+$probe.initialTaskCount+' ('+$task.taskId+', '+$task.name+')')
        $task.artifact=Artifact ('tasks\tt-'+$task.taskId+'.txt')
        try{
            try{$trace=Run-Command ('tt '+$task.taskId) ('tasks\tt-'+$task.taskId+'.txt')}
            catch{$task.error=$_.Exception.Message;throw}
            $last=$probe.commands[$probe.commands.Count-1];$task.returnedPrompt=$last.returnedPrompt;$task.frameCount=$last.frameCount;$task.error=$last.error
            $nativeError=Trace-Error $trace $task.frameCount
            if($nativeError){$task.error=$nativeError}elseif($last.unsupported){$task.error='TASK_TRACE_UNSUPPORTED'}
            if($task.frameCount -gt 0){$probe.tracesWithFrames++}
            $task.infoAttempted=$true;$probe.attemptedTaskInfoCount++
            $task.infoArtifact=Artifact ('tasks\ti-'+$task.taskId+'.txt')
            Progress ($portName+': ti '+$index+'/'+$probe.initialTaskCount+' ('+$task.taskId+', '+$task.name+')')
            try{
                $info=Run-Command ('ti '+$task.taskId) ('tasks\ti-'+$task.taskId+'.txt')
                $infoOp=$probe.commands[$probe.commands.Count-1]
                $task.infoError=$infoOp.error
                if($infoOp.unsupported){$task.infoError='TASK_INFO_UNSUPPORTED'}
                elseif(!$task.infoError -and !@((Parse-Tasks $info)|Where-Object{$_.taskId -eq $task.taskId}).Count){$task.infoError='TASK_INFO_NOT_CONFIRMED: matching TASKID missing in response'}
                $task.infoCollected=($infoOp.returnedPrompt -and !$task.infoError)
                if($task.infoCollected){$probe.taskInfosCollected++}
            }catch{$task.infoError=$_.Exception.Message;throw}
        }finally{Save-Json (Join-Path $portRoot 'task-index.json') $probe.tasks}
    }
    $after=Run-Command 'i' 'i-after.txt'
    $probe.afterTaskIds=@((Parse-Tasks $after)|ForEach-Object{$_.taskId})
    $beforeIds=@($probe.tasks|ForEach-Object{$_.taskId})
    $probe.addedTaskIds=@($probe.afterTaskIds|Where-Object{$_ -notin $beforeIds})
    $probe.removedTaskIds=@($beforeIds|Where-Object{$_ -notin $probe.afterTaskIds})
    # Read-only summaries after the trace snapshots. checkStack scans stack
    # memory and may take time; existing port/command/output limits still apply.
    foreach($command in @('checkStack 0','taskShow 0,2','devs','whoami','w')){
        Progress ($portName+': ek tanilama, '+$command)
        $label=$command -replace '[^A-Za-z0-9_-]','_'
        [void](Collect-Diagnostic $command ('diagnostics\'+$label+'.txt'))
    }
    # Explicit task IDs avoid relying on the shell's previously selected task.
    # Only tasks seen as PEND in the initial snapshot need wait-object details.
    foreach($task in @($probe.tasks|Where-Object{$_.state -match 'PEND'})){
        Progress ($portName+': tw '+$task.taskId+' ('+$task.name+')')
        [void](Collect-Diagnostic ('tw '+$task.taskId) ('tasks\tw-'+$task.taskId+'.txt'))
    }
    $networkCommands=@('inetstatShow','tcpstatShow 0','udpstatShow 0','ipstatShow 0','routeShow','nfsDevShow','memShow 0')
    $networkIndex=0
    foreach($command in $networkCommands){
        Progress ($portName+': socket/port durumu, '+$command)
        $networkIndex++;$label=$command -replace '[^A-Za-z0-9_-]','_'
        $reply=Run-Command $command ('network\'+('{0:d2}' -f $networkIndex)+'-'+$label+'.txt')
        if($command -eq 'inetstatShow'){$probe.socketRows+=@(Parse-Sockets $reply $probe.commands[$probe.commands.Count-1].artifact)}
    }
    if(!$probe.socketRows.Count){
        [void](Run-Command 'lkup "netstat"' 'network\netstat-symbols.txt')
        $reply=Run-Command 'netstat "-a -n"' 'network\netstat.txt'
        if($reply -match '(?i)usage:|unknown option|invalid option'){$reply=Run-Command 'netstat "-a"' 'network\netstat-fallback.txt'}
        $probe.socketRows+=@(Parse-Sockets $reply $probe.commands[$probe.commands.Count-1].artifact)
    }
    $probe.networkAvailable=$probe.socketRows.Count -gt 0
    $probe.completed=$true
    $probe.success=($probe.tracesWithFrames -eq $probe.initialTaskCount -and $probe.taskInfosCollected -eq $probe.initialTaskCount -and $probe.networkAvailable -and $probe.identity.collected -and $probe.tatlYaz.collected -and !@($probe.diagnostics|Where-Object{!$_.collected}).Count)
    $probe.status=if($probe.success){'COLLECTED'}else{'PARTIAL'}
}catch{
    $probe.error=$_.Exception.Message;$probe.timedOut=$probe.error -match 'TIMEOUT'
    if(!$probe.opened){$probe.status='OPEN_FAILED'}elseif($probe.timedOut -and $probe.vxworksPrompt){$probe.status='TIMED_OUT'}elseif($probe.vxworksPrompt){$probe.status='FAILED'}
}
finally{
    if($serial.IsOpen){if($pagerActive){try{$serial.Write("Q`r")}catch{}};$serial.Close()}
    $serial.Dispose();$consoleWriter.Dispose()
    Save-Json (Join-Path $portRoot 'port-result.json') $probe
}
[pscustomobject]$probe|ConvertTo-Json -Depth 12 -Compress
