param([Parameter(Mandatory=$true)][string]$RequestFile)
# Runs in the selected, already logged-on user's desktop via Task Scheduler.
# No password, target-JVM termination, or permanent installed component.
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$jobRoot=[IO.Path]::GetFullPath($PSScriptRoot)
if($jobRoot -notmatch '^[CD]:\\Temp\\diagnostic-remote-([0-9a-f-]{36})$'){throw 'Unsafe interactive job directory'}
$jobId=$matches[1];$taskName='DiagnosticCollector-BAT-'+$jobId
$requestPath=[IO.Path]::GetFullPath($RequestFile)
if($requestPath -ne (Join-Path $jobRoot 'batch-request.json')){throw 'Unsafe interactive request path'}
$helper=$null;$started=$false;$output=$null;$errorOutput=$null
$state=[ordered]@{jobId=$jobId;success=$false;timedOut=$false;exitCode=$null;error=$null;sessionId=[Diagnostics.Process]::GetCurrentProcess().SessionId;userSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value;pid=$PID}
$watch=[Diagnostics.Stopwatch]::StartNew();$lastHeartbeat=$null;$lastHeartbeatAt=0.0
try{
    if(Test-Path -LiteralPath (Join-Path $jobRoot 'batch-result.json')){return}
    $request=Get-Content -LiteralPath $requestPath -Raw -Encoding UTF8|ConvertFrom-Json
    if($request.jobId -ne $jobId -or $state.sessionId -le 0 -or $state.sessionId -ne $request.sessionId -or $state.userSid -ne $request.userSid -or ![Environment]::UserInteractive){throw 'INTERACTIVE_IDENTITY_MISMATCH'}
    # Do not execute a delayed/orphaned task after the owning worker has exited.
    $parent=[Diagnostics.Process]::GetProcessById([int]$request.workerPid)
    try{if([Math]::Abs($parent.StartTime.ToUniversalTime().Ticks-[long]$request.workerStartTicks) -ge 10){throw 'INTERACTIVE_WORKER_REUSED'}}finally{$parent.Dispose()}
    $target=[Diagnostics.Process]::GetProcessById([int]$request.targetPid)
    try{if($target.SessionId -ne $request.sessionId -or [Math]::Abs($target.StartTime.ToUniversalTime().Ticks-[long]$request.targetStartTicks) -ge 10){throw 'INTERACTIVE_TARGET_CHANGED'}}finally{$target.Dispose()}
    $batch=[IO.Path]::GetFullPath([string]$request.batch)
    if($batch -notmatch '^[A-Za-z]:\\' -or [IO.Path]::GetFileName($batch) -notmatch '^[A-Za-z0-9_. -]+\.bat$'){throw 'Unsafe BAT path'}
    $item=Get-Item -LiteralPath $batch -Force
    if($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'BAT must be a regular file'}
    $outDir=Join-Path $jobRoot 'data\pre-log';[void][IO.Directory]::CreateDirectory($outDir)
    $output=[IO.File]::Open((Join-Path $outDir 'batch.stdout.txt'),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
    $errorOutput=[IO.File]::Open((Join-Path $outDir 'batch.stderr.txt'),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
    $start=New-Object Diagnostics.ProcessStartInfo;$start.FileName=Join-Path $env:SystemRoot 'System32\cmd.exe';$start.WorkingDirectory=Split-Path $batch -Parent
    $start.Arguments='/d /s /c "call "'+[IO.Path]::GetFileName($batch)+'""'
    $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true;$start.RedirectStandardInput=$true
    $helper=New-Object Diagnostics.Process;$helper.StartInfo=$start;$started=$helper.Start();$helper.StandardInput.Close()
    $copy=$helper.StandardOutput.BaseStream.CopyToAsync($output);$copyError=$helper.StandardError.BaseStream.CopyToAsync($errorOutput)
    do{
        if((Test-Path -LiteralPath (Join-Path $jobRoot 'cancel.txt')) -or (Test-Path -LiteralPath (Join-Path $jobRoot 'batch-cancel.txt'))){throw 'INTERACTIVE_BATCH_CANCELLED'}
        $heartbeatReader=$null
        try{
            $heartbeatStream=[IO.File]::Open((Join-Path $jobRoot 'batch-heartbeat.txt'),[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
            $heartbeatReader=New-Object IO.StreamReader($heartbeatStream)
            $heartbeat=$heartbeatReader.ReadToEnd()
            if(![string]::IsNullOrWhiteSpace($heartbeat) -and $heartbeat -ne $lastHeartbeat){$lastHeartbeat=$heartbeat;$lastHeartbeatAt=$watch.Elapsed.TotalSeconds}
        }catch{}finally{if($heartbeatReader){$heartbeatReader.Dispose()}}
        if($watch.Elapsed.TotalSeconds-$lastHeartbeatAt -ge 15){throw 'INTERACTIVE_WORKER_DISCONNECTED'}
        if($watch.Elapsed.TotalSeconds -ge $request.timeoutSeconds){$state.timedOut=$true;throw 'BEFORE_LOG_COPY_TIMEOUT'}
        if($helper.WaitForExit(250)){break}
    }while($true)
    $state.exitCode=$helper.ExitCode
    if(!$copy.Wait(3000) -or !$copyError.Wait(3000)){throw 'BEFORE_LOG_COPY_OUTPUT_TIMEOUT'}
    if($state.exitCode -ne 0){throw ('BEFORE_LOG_COPY_EXIT_CODE: '+$state.exitCode)}
    $state.success=$true
}catch{$state.error=$_.Exception.Message}
finally{
    if($helper){try{if($started -and !$helper.HasExited){$helper.Kill();[void]$helper.WaitForExit(3000)}}finally{$helper.Dispose()}}
    if($output){$output.Dispose()};if($errorOutput){$errorOutput.Dispose()}
    # Publish after all output handles close. Self-clean the unique task even if
    # the WMI worker has disappeared; the worker also cleans it in its finally.
    try{
        $state.completedAtUtc=[DateTime]::UtcNow.ToString('o')
        $marker=Join-Path $jobRoot 'batch-result.json'
        if(!(Test-Path -LiteralPath $marker)){
            [IO.File]::WriteAllText(($marker+'.tmp'),($state|ConvertTo-Json -Depth 5),(New-Object Text.UTF8Encoding($false)))
            Move-Item -LiteralPath ($marker+'.tmp') -Destination $marker
        }
    }finally{
        try{
            $scheduler=New-Object -ComObject Schedule.Service;$scheduler.Connect();$folder=$scheduler.GetFolder('\')
            $own=$folder.GetTask($taskName)
            if($own.Definition.RegistrationInfo.Description -eq ('DiagnosticCollector batch job '+$jobId)){$folder.DeleteTask($taskName,0)}
        }catch{}
    }
}
