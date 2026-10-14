param([string]$JobRoot,[string]$RequestFile)
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$root=[IO.Path]::GetFullPath($JobRoot)
if($root -ne [IO.Path]::GetFullPath($PSScriptRoot) -or $root -notmatch '^[CD]:\\Temp\\diagnostic-remote-[0-9a-f-]{36}$'){throw 'Unsafe ping job directory'}
$request=Get-Content -LiteralPath $RequestFile -Raw -Encoding UTF8|ConvertFrom-Json
$config=$request.ping;$output=Join-Path $root 'data\ping';[void][IO.Directory]::CreateDirectory($output)
$utf8=New-Object Text.UTF8Encoding($false)
$parallelism=if($config.parallelism){[int]$config.parallelism}else{16}
$report=[ordered]@{sourceComputer=[Environment]::MachineName;subnet=$config.subnet;parallelism=$parallelism;targetCount=@($config.addresses).Count;startedAtUtc=[DateTime]::UtcNow.ToString('o');completedAtUtc=$null;durationMillis=0;completed=$false;success=$false;results=@();errors=@()}
$clock=[Diagnostics.Stopwatch]::StartNew();$states=New-Object Collections.ArrayList;$active=New-Object Collections.ArrayList
function Check-Cancellation {
    if(Test-Path -LiteralPath (Join-Path $root 'cancel.txt')){throw 'REMOTE_CANCELLED'}
}
function Complete-Attempt($state,$reply,[string]$errorMessage){
    $attempt=$state.currentAttempt
    if($errorMessage){$attempt.status='Exception';$attempt.error=$errorMessage;$report.errors+=,($state.address+': '+$errorMessage)}
    else{
        $attempt.status=$reply.Status.ToString()
        if($reply.Address){$attempt.replyAddress=$reply.Address.ToString()}
        if($reply.Status -eq [Net.NetworkInformation.IPStatus]::Success){$attempt.roundTripMillis=$reply.RoundtripTime}
    }
    [void]$state.attempts.Add([pscustomobject]$attempt)
    $state.currentAttempt=$null;$state.task=$null
    if($state.ping){$state.ping.Dispose();$state.ping=$null}
    $state.nextDue=$clock.ElapsedMilliseconds+[int]$config.intervalMillis
}
try{
    if($parallelism -lt 1 -or $parallelism -gt 32 -or $report.targetCount -lt 1 -or $report.targetCount -gt 512){throw 'Invalid ping target count or parallelism'}
    if($config.attempts -lt 1 -or $config.attempts -gt 10 -or $config.timeoutMillis -lt 100 -or $config.timeoutMillis -gt 5000 -or $config.intervalMillis -lt 0 -or $config.intervalMillis -gt 1000){throw 'Invalid ping timeout or attempt settings'}
    foreach($address in $config.addresses){
        $ip=$null
        if(![Net.IPAddress]::TryParse([string]$address,[ref]$ip) -or $ip.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork){throw 'Ping requires explicit IPv4 targets'}
        [void]$states.Add([pscustomobject]@{address=[string]$address;attempts=(New-Object Collections.ArrayList);currentAttempt=$null;task=$null;ping=$null;nextDue=0;done=$false})
    }
    $next=0;$finished=0;$lastProgress=-1
    while($finished -lt $states.Count){
        Check-Cancellation
        while($active.Count -lt $parallelism -and $next -lt $states.Count){[void]$active.Add($states[$next]);$next++}
        foreach($state in @($active.ToArray())){
            Check-Cancellation
            if(!$state.task -and $clock.ElapsedMilliseconds -ge $state.nextDue){
                $state.currentAttempt=[ordered]@{sequence=($state.attempts.Count+1);timestampUtc=[DateTime]::UtcNow.ToString('o');status=$null;roundTripMillis=$null;replyAddress=$null;error=$null}
                try{
                    $state.ping=New-Object Net.NetworkInformation.Ping
                    # Native asynchronous ICMP: no PowerShell jobs, extra processes or server install.
                    $state.task=$state.ping.SendPingAsync([Net.IPAddress]::Parse($state.address),[int]$config.timeoutMillis)
                }catch{Complete-Attempt $state $null $_.Exception.Message}
            }
            if($state.task -and $state.task.IsCompleted){
                try{$reply=$state.task.GetAwaiter().GetResult();Complete-Attempt $state $reply $null}
                catch{Complete-Attempt $state $null $_.Exception.Message}
            }
            if($state.attempts.Count -eq [int]$config.attempts){$state.done=$true;[void]$active.Remove($state);$finished++}
        }
        if($finished -ne $lastProgress){
            [IO.File]::WriteAllText((Join-Path $root 'progress.json'),(@{jobId=$request.jobId;phase='ping';message=([Environment]::MachineName+': ping taramasi '+$finished+'/'+$states.Count+' IP; paralel='+$parallelism)}|ConvertTo-Json -Compress),$utf8)
            $lastProgress=$finished
        }
        if($finished -lt $states.Count){Start-Sleep -Milliseconds 20}
    }
    $report.completed=$true;$report.success=($report.errors.Count -eq 0)
}catch{$report.errors+=,$_.Exception.Message}
finally{
    # Cancel/dispose pending ICMP requests on every exit, and retain partial results.
    foreach($state in $states){
        if($state.currentAttempt){
            $state.currentAttempt.status='Cancelled';$state.currentAttempt.error='Collection stopped before the reply was recorded'
            [void]$state.attempts.Add([pscustomobject]$state.currentAttempt)
        }
        if($state.ping){try{$state.ping.SendAsyncCancel()}catch{};try{$state.ping.Dispose()}catch{}}
        $attempts=@($state.attempts.ToArray());$sent=$attempts.Count
        $timings=@($attempts|Where-Object {$_.status -eq 'Success'}|ForEach-Object {$_.roundTripMillis});$received=$timings.Count
        $statistics=if($received){$timings|Measure-Object -Minimum -Maximum -Average}else{$null}
        $hasError=@($attempts|Where-Object{$_.status -eq 'Exception'}).Count -gt 0
        $item=[pscustomobject]@{address=$state.address;sent=$sent;expectedAttempts=[int]$config.attempts;received=$received;lost=($sent-$received);lossPercent=if($sent){100.0*($sent-$received)/$sent}else{$null};status=if(!$sent){'NOT_TESTED'}elseif(!$state.done){'INCOMPLETE'}elseif($hasError){'ERROR'}elseif($received -eq $sent){'REACHABLE'}elseif($received){'INTERMITTENT'}else{'NO_ECHO_REPLY'};averageRoundTripMillis=if($statistics){$statistics.Average}else{$null};minRoundTripMillis=if($statistics){$statistics.Minimum}else{$null};maxRoundTripMillis=if($statistics){$statistics.Maximum}else{$null};attempts=$attempts}
        $report.results+=,$item
        # Keep the older explicit-list format; subnet scans have only the combined files.
        if(!$config.subnet){
            $text=New-Object Text.StringBuilder
            [void]$text.AppendLine('Kaynak: '+[Environment]::MachineName+'; hedef: '+$state.address)
            foreach($attempt in $attempts){[void]$text.AppendLine(('Istek {0}: {1}; sureMs={2}; UTC={3}; hata={4}' -f $attempt.sequence,$attempt.status,$attempt.roundTripMillis,$attempt.timestampUtc,$attempt.error))}
            [IO.File]::WriteAllText((Join-Path $output ('ping-'+$state.address+'.txt')),$text.ToString(),$utf8)
        }
    }
    $report.durationMillis=$clock.ElapsedMilliseconds;$report.completedAtUtc=[DateTime]::UtcNow.ToString('o')
    [IO.File]::WriteAllText((Join-Path $output 'results.json'),($report|ConvertTo-Json -Depth 10),$utf8)
    if($report.results.Count){$report.results|Select-Object address,status,sent,expectedAttempts,received,lost,lossPercent,averageRoundTripMillis|Export-Csv -LiteralPath (Join-Path $output 'results.csv') -NoTypeInformation -Encoding UTF8}
}
[pscustomobject]$report|ConvertTo-Json -Depth 10 -Compress
