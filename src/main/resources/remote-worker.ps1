param([Parameter(Mandatory=$true)][string]$RequestFile)
$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'
$settings=Get-Content -LiteralPath $RequestFile -Raw -Encoding UTF8 | ConvertFrom-Json
$jobRoot=[IO.Path]::GetFullPath($PSScriptRoot)
function Test-JobRoot([string]$path) {
    return [regex]::IsMatch($path,'^[CD]:\\Temp\\diagnostic-remote-[0-9a-f-]{36}$',([Text.RegularExpressions.RegexOptions]::IgnoreCase -bor [Text.RegularExpressions.RegexOptions]::CultureInvariant))
}
if(!(Test-JobRoot $jobRoot)) { throw 'Unsafe remote job directory' }
. (Join-Path $jobRoot 'jdk-discovery.ps1')
$jdkInventory=$null;$preferredDumps=@{}
$dataRoot=Join-Path $jobRoot 'data'
[void][IO.Directory]::CreateDirectory($dataRoot)
$clock=[Diagnostics.Stopwatch]::StartNew()
$serverStarted=[DateTimeOffset]::Now
$result=[pscustomobject]@{schemaVersion=1;jobId=$settings.jobId;serverStartedAtUtc=$serverStarted.UtcDateTime.ToString('o');serverStartedAtLocal=$serverStarted.ToString('o');serverTimeZoneId=[TimeZoneInfo]::Local.Id;serverCompletedAtUtc=$null;serverCompletedAtLocal=$null;serverElapsedMillis=$null;discovery=$null;jvms=@();excludedJvms=@();logs=@();operations=@();errors=@();files=@();resolved=$null}
$monitorProcess=$null; $monitorStarted=$false; $monitorOutput=$null; $monitorError=$null; $monitorTask=$null; $monitorErrTask=$null
$cleanupRequested=$false;$lastHeartbeat=$null;$lastHeartbeatAt=0.0;$nextHeartbeatCheck=0.0
$workDisk=New-Object IO.DriveInfo ([IO.Path]::GetPathRoot($jobRoot))
$minimumFreeBytes=256MB
function Write-Json($path,$value) { [IO.File]::WriteAllText($path,($value|ConvertTo-Json -Depth 18),(New-Object Text.UTF8Encoding($false))) }
function Activity([string]$message,[int]$percent=-1,[string]$stage='') {
    $entry=[ordered]@{timestampUtc=[DateTime]::UtcNow.ToString('o');source='worker';message=$message}
    if($percent -ge 0){$entry.percent=$percent;$entry.stage=$stage}
    [IO.File]::AppendAllText((Join-Path $jobRoot 'activity-worker.jsonl'),(($entry|ConvertTo-Json -Compress)+"`n"),(New-Object Text.UTF8Encoding($false)))
}
# Publish the actual worker identity. Win32_Process.Create or subsequent WMI
# command-line visibility must not be the sole evidence that this job is alive.
$workerIdentity=[pscustomobject]@{jobId=$settings.jobId;pid=$PID;startTicks=[Diagnostics.Process]::GetCurrentProcess().StartTime.ToUniversalTime().Ticks.ToString()}
Write-Json (Join-Path $jobRoot 'worker-state.json.tmp') $workerIdentity
Move-Item -LiteralPath (Join-Path $jobRoot 'worker-state.json.tmp') -Destination (Join-Path $jobRoot 'worker-state.json')
function Sha256($path){$inputFile=[IO.File]::OpenRead($path);$hash=[Security.Cryptography.SHA256]::Create();try{return [BitConverter]::ToString($hash.ComputeHash($inputFile)).Replace('-','')}finally{$inputFile.Dispose();$hash.Dispose()}}
function Relative($path) { return $path.Substring($dataRoot.Length+1).Replace('\','/') }
function Safe-Name($name) { $clean=([string]$name -replace '[^A-Za-z0-9._-]','_'); if(!$clean){$clean='jvm'}; return $clean.Substring(0,[Math]::Min(80,$clean.Length)) }
function Ensure-Space([long]$additionalBytes=0) {
    if($workDisk.AvailableFreeSpace -lt ($minimumFreeBytes+$additionalBytes)){throw ('LOW_DISK_SPACE: '+$workDisk.Name+' diski icin en az 256 MiB bos alan korunuyor.')}
}
function Deadline {
    if(Test-Path -LiteralPath (Join-Path $jobRoot 'cancel.txt')){$script:cleanupRequested=$true;throw 'REMOTE_CANCELLED'}
    if($settings.clientHeartbeatRequired -and $clock.Elapsed.TotalSeconds -ge $nextHeartbeatCheck){
        $script:nextHeartbeatCheck=$clock.Elapsed.TotalSeconds+1
        $heartbeatPath=Join-Path $jobRoot 'client-heartbeat.txt'
        try{
            $heartbeat=[IO.File]::ReadAllText($heartbeatPath)
            if($heartbeat -ne $lastHeartbeat){$script:lastHeartbeat=$heartbeat;$script:lastHeartbeatAt=$clock.Elapsed.TotalSeconds}
        }catch{}
        if($clock.Elapsed.TotalSeconds-$lastHeartbeatAt -ge 90){$script:cleanupRequested=$true;throw 'CLIENT_DISCONNECTED: 90 saniyedir istemci sinyali yok.'}
    }
    if($clock.Elapsed.TotalSeconds -ge $settings.timeoutSeconds){throw 'REMOTE_DEADLINE: collection time limit reached'}
    Ensure-Space
}
function Wait-Helper($helper,[int]$budget) {
    $waitWatch=[Diagnostics.Stopwatch]::StartNew()
    do {
        Deadline
        $remaining=$budget-$waitWatch.ElapsedMilliseconds
        if($remaining -le 0){return $false}
        if($helper.WaitForExit([int][Math]::Min(500,$remaining))){return $true}
    }while($true)
}
function Delete-OwnJob {
    # jobRoot was verified as an absolute C:/Temp or D:/Temp UUID directory.
    if((Get-Item -LiteralPath $jobRoot -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Cleanup reparse point rejected'}
    if(@(Get-ChildItem -LiteralPath $jobRoot -Recurse -Force|Where-Object{$_.Attributes -band [IO.FileAttributes]::ReparsePoint}).Count -gt 0){throw 'Cleanup contains reparse point'}
    for($retry=0;$retry -lt 11;$retry++){
        try{if([IO.Directory]::Exists($jobRoot)){[IO.Directory]::Delete($jobRoot,$true)};return}
        catch [IO.IOException]{if($retry -eq 10){throw};Start-Sleep -Milliseconds 500}
    }
}
function Stop-Helper($helper,$started) {
    if(!$helper){return}
    try { if($started -and !$helper.HasExited){$helper.Kill();[void]$helper.WaitForExit(3000)} }
    catch { $result.errors+=,('HELPER_CLEANUP_FAILED: '+$_.Exception.Message) }
    finally { $helper.Dispose() }
}
# A blocked CIM/WMI call cannot be interrupted in-process. Run queries in a
# disposable PowerShell helper; the target JVM is never killed.
function Run-Script($body,$setup,$label,$timeoutSeconds) {
    Deadline
    Activity ('[Sorgu] Basladi: '+$label)
    $helper=$null;$started=$false;$output=$null;$errorOutput=$null;$scriptFile=$null
    $folder=Join-Path $dataRoot 'debug\queries';[void][IO.Directory]::CreateDirectory($folder)
    $name=(Safe-Name $label)+'-'+[Guid]::NewGuid()
    $outPath=Join-Path $folder ($name+'.json');$errPath=Join-Path $folder ($name+'.stderr.txt')
    try {
        $script='$ErrorActionPreference=''Stop'';$ProgressPreference=''SilentlyContinue'';$env:PSModulePath=[IO.Path]::Combine($PSHOME,''Modules'');[Console]::OutputEncoding=New-Object Text.UTF8Encoding($false);'+$setup+$body
        $start=New-Object Diagnostics.ProcessStartInfo;$start.FileName=Join-Path $PSHOME 'powershell.exe';$start.WorkingDirectory=$env:SystemRoot
        $scriptFile=Join-Path $jobRoot ($name+'.ps1');[IO.File]::WriteAllText($scriptFile,$script,(New-Object Text.UTF8Encoding($true)))
        $start.Arguments='-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "'+$scriptFile+'"'
        $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
        $output=[IO.File]::Open($outPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
        $errorOutput=[IO.File]::Open($errPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
        $helper=New-Object Diagnostics.Process;$helper.StartInfo=$start;$started=$helper.Start()
        $copy=$helper.StandardOutput.BaseStream.CopyToAsync($output);$copyError=$helper.StandardError.BaseStream.CopyToAsync($errorOutput)
        $budget=[int][Math]::Max(1,[Math]::Min($timeoutSeconds*1000,($settings.timeoutSeconds-$clock.Elapsed.TotalSeconds)*1000))
        if(!(Wait-Helper $helper $budget)){throw ('QUERY_TIMEOUT: '+$label)}
        if(!$copy.Wait(3000) -or !$copyError.Wait(3000)){throw ('QUERY_OUTPUT_TIMEOUT: '+$label)}
        $output.Dispose();$output=$null;$errorOutput.Dispose();$errorOutput=$null
        if($helper.ExitCode -ne 0){throw ('QUERY_FAILED: '+$label+': '+(Read-Head $errPath 4096))}
        if((Get-Item -LiteralPath $outPath).Length -gt 8MB){throw ('QUERY_OUTPUT_TOO_LARGE: '+$label)}
        $value=((Read-Head $outPath 8388608)|ConvertFrom-Json)
        Activity ('[Sorgu] Tamamlandi: '+$label+' | JSON ciktisi: '+(Relative $outPath))
        return $value
    } catch {
        Activity ('[Sorgu] Basarisiz: '+$label+' | '+$_.Exception.Message);throw
    } finally {
        Stop-Helper $helper $started
        if($output){$output.Dispose()};if($errorOutput){$errorOutput.Dispose()}
        if($scriptFile -and [IO.File]::Exists($scriptFile)){try{[IO.File]::Delete($scriptFile)}catch{$result.errors+=,('QUERY_SCRIPT_CLEANUP_FAILED: '+$_.Exception.Message)}}
    }
}
function Query-Process($queryPid,[int]$budgetMillis=2147483647) {
    $body=@'
try { $p=Get-CimInstance Win32_Process -Filter ('ProcessId='+$queryPid);$source='CIM' }
catch { $p=Get-WmiObject Win32_Process -Filter ('ProcessId='+$queryPid);$source='WMI' }
if(!$p){'null';return}
$owner=$null;$ownerError=$null
try { $o=if($source -eq 'CIM'){Invoke-CimMethod -InputObject $p -MethodName GetOwner}else{$p.GetOwner()};if($o.ReturnValue -eq 0){$owner=$o.Domain+'\'+$o.User}else{$ownerError='GetOwner return code '+$o.ReturnValue} } catch {$ownerError=$_.Exception.Message}
$date=if($source -eq 'CIM'){$p.CreationDate}else{$p.ConvertToDateTime($p.CreationDate)}
[pscustomobject]@{pid=[long]$p.ProcessId;processName=$p.Name;owner=$owner;ownerError=$ownerError;sessionId=$p.SessionId;startTime=$date.ToUniversalTime().ToString('o');startTicks=$date.ToUniversalTime().Ticks.ToString();executablePath=$p.ExecutablePath;javaVersion=$null;versionSource='unknown';discoverySource=($source+':jps-enriched');collectorProcess=$false}|ConvertTo-Json -Compress
'@
    return (Run-Script $body ('$queryPid='+[long]$queryPid+';') ('identity-'+$queryPid) ([Math]::Max([double]0.001,[Math]::Min([double]$settings.commandTimeoutSeconds,([double]$budgetMillis/1000.0)))))
}
function Identity-Error($target,[int]$budgetMillis=2147483647) {
    if($target.startTicks -notmatch '^[0-9]+$' -or $target.pid -le 0) { return 'IDENTITY_UNVERIFIED' }
    try {
        $p=Query-Process $target.pid $budgetMillis
        if(!$p) { return 'TARGET_EXITED' }
    } catch { return 'IDENTITY_CHECK_FAILED: '+$_.Exception.Message }
    if($p.startTicks -ne $target.startTicks) { return 'PID_REUSED' }
    return $null
}
function Read-Head($path,$limit=65536) {
    if(!(Test-Path -LiteralPath $path -PathType Leaf)){return ''}
    $reader=New-Object IO.StreamReader($path,[Text.Encoding]::UTF8)
    try { $buffer=New-Object char[] $limit; $count=$reader.ReadBlock($buffer,0,$limit); return (New-Object string($buffer,0,$count)) }
    finally { $reader.Dispose() }
}
function Find-Tool($tool,$target) {
    $choices=@(Jdk-Attempts $target @($tool));if($choices.Count){return $choices[0].executable};return $null
}
function Jdk-Attempts($target,$tools) {
    $plan=@($jdkInventory.plans|Where-Object{if($target){$_.pid -eq $target.pid -and $_.startTicks -eq $target.startTicks}else{$_.pid -eq 0}}|Select-Object -First 1)
    if(!$plan.Count){$plan=@((New-JdkPlan $jdkInventory $target))}
    if($target){$target|Add-Member NoteProperty architecture $plan[0].architecture -Force}
    $attempts=@();$homes=0;$seen=New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach($row in $plan[0].candidates){
        if($row.reason){continue};$candidate=@($jdkInventory.candidates|Where-Object{$_.home -eq $row.home})[0];$added=$false
        foreach($tool in $tools){$exe=$candidate.tools.$tool;if($exe -and $seen.Add($exe)){$attempts+=,[pscustomobject]@{tool=$tool;executable=$exe;candidate=$candidate};$added=$true}}
        if($added -and ++$homes -ge 3){break}
    }
    return $attempts
}
function Try-Tools($report,$target,$tools,$type,$sample,$dir) {
    $attempts=@(Jdk-Attempts $target $tools);$key=if($target){[string]$target.pid+':'+$target.startTicks}else{'discovery'}
    if($type -eq 'thread-dump' -and $preferredDumps.ContainsKey($key)){$preferred=$preferredDumps[$key];$attempts=@($attempts|Sort-Object @{Expression={if($_.executable -eq $preferred){0}else{1}}})}
    if(!$attempts.Count){
        $op=[pscustomobject]@{type=$type;sample=$sample;startedAt=[DateTime]::UtcNow.ToString('o');completedAt=[DateTime]::UtcNow.ToString('o');success=$false;timedOut=$false;durationMillis=0;error='NO_COMPATIBLE_TOOL: see debug/jdk-discovery.json'}
        $report.operations+=,$op;Activity ('[JVM] PID '+$target.pid+' | '+$type+' | ornek '+$sample+' BASARISIZ: uyumlu arac bulunamadi; debug/jdk-discovery.json dosyasina bakin.');return $op
    }
    $watch=[Diagnostics.Stopwatch]::StartNew();$budget=$settings.commandTimeoutSeconds*1000*$(if($type -eq 'thread-dump'){2}else{1});$last=$null
    foreach($attempt in $attempts){
        Deadline;$left=[int]($budget-$watch.ElapsedMilliseconds);if($left -le 0){break}
        $arguments=switch($type){'thread-dump'{if($attempt.tool -eq 'jcmd'){@([string]$target.pid,'Thread.print','-l')}else{@('-l',[string]$target.pid)}};'VM.version'{@([string]$target.pid,'VM.version')};'gc-statistics'{@('-gcutil',[string]$target.pid,'1000','1')};'jps'{@('-l')}}
        $last=Run-Tool $target $attempt.tool @($arguments) $type $sample $dir $attempt $left;$report.operations+=,$last
        if($last.success){if($type -eq 'thread-dump'){$preferredDumps[$key]=$attempt.executable;$report.selectedDumpTool=$attempt.executable};return $last}
        if($last.error -match '^(TARGET_EXITED|PID_REUSED|IDENTITY_UNVERIFIED|IDENTITY_CHECK_FAILED)'){break}
    }
    return $last
}
function Run-Tool($target,$tool,$arguments,$type,$sample,$dir,$selection=$null,[int]$budgetMillis=2147483647) {
    Deadline
    $op=[pscustomobject]@{type=$type;sample=$sample;startedAt=[DateTime]::UtcNow.ToString('o');completedAt=$null;command=$null;success=$false;timedOut=$false;exitCode=$null;durationMillis=0;artifact=$null;stderrArtifact=$null;error=$null}
    $process=$null; $started=$false; $output=$null; $errorOutput=$null; $watch=[Diagnostics.Stopwatch]::StartNew()
    try {
        Activity ('[JVM] PID '+$target.pid+' | '+$type+' | ornek '+$sample+' | '+$tool+' denemesi basladi.')
        $exe=if($selection){$selection.executable}else{Find-Tool $tool $target}
        if(!$exe){throw ('TOOL_NOT_FOUND: '+$tool+'; server JDK path: '+$settings.jdkHome)}
        if($selection){$op|Add-Member NoteProperty toolHome $selection.candidate.home;$op|Add-Member NoteProperty toolArchitecture $selection.candidate.architecture;$op|Add-Member NoteProperty toolJavaVersion $selection.candidate.javaVersion;$op|Add-Member NoteProperty toolVendor $selection.candidate.vendor}
        if($target){
            $left=[int]($budgetMillis-$watch.ElapsedMilliseconds);if($left -le 0){throw 'TOOL_BUDGET_EXHAUSTED'}
            $problem=Identity-Error $target $left; if($problem){throw $problem}
        }
        $op.command=@($exe)+@($arguments)
        $label=if($target){(Safe-Name $target.processName)+'-'+$target.pid}else{'discovery'}
        $name=$label+'-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')+'-'+$type+'-'+$sample+'-'+$tool+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
        [void][IO.Directory]::CreateDirectory($dir)
        $outPath=Join-Path $dir ($name+'.txt'); $errPath=Join-Path $dir ($name+'.stderr.txt')
        $op.artifact=Relative $outPath; $op.stderrArtifact=Relative $errPath
        $output=[IO.File]::Open($outPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
        $errorOutput=[IO.File]::Open($errPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
        $start=New-Object Diagnostics.ProcessStartInfo; $start.FileName=$exe; $start.Arguments=($arguments -join ' ');$start.WorkingDirectory=$env:SystemRoot
        $start.UseShellExecute=$false; $start.CreateNoWindow=$true; $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
        $remainingBudget=$budgetMillis-$watch.ElapsedMilliseconds;if($remainingBudget -le 0){throw 'TOOL_BUDGET_EXHAUSTED'}
        $process=New-Object Diagnostics.Process; $process.StartInfo=$start; $started=$process.Start()
        $copy=$process.StandardOutput.BaseStream.CopyToAsync($output); $copyError=$process.StandardError.BaseStream.CopyToAsync($errorOutput)
        $commandBudget=[int][Math]::Max(1,[Math]::Min($remainingBudget,[Math]::Min($settings.commandTimeoutSeconds*1000,($settings.timeoutSeconds-$clock.Elapsed.TotalSeconds)*1000)))
        if(!(Wait-Helper $process $commandBudget)) {
            $op.timedOut=$true; $process.Kill(); if(!$process.WaitForExit(3000)){throw 'HELPER_DID_NOT_EXIT'}
        }
        $op.exitCode=$process.ExitCode
        if(!$copy.Wait(3000) -or !$copyError.Wait(3000)){throw 'OUTPUT_COPY_TIMEOUT'}
        $output.Dispose(); $output=$null; $errorOutput.Dispose(); $errorOutput=$null
        $op.success=(!$op.timedOut -and $op.exitCode -eq 0)
        if($type -eq 'thread-dump' -and $op.success) {
            $head=Read-Head $outPath
            $op.success=$head.Contains('Full thread dump') -or $head.Contains('java.lang.Thread.State:') -or $head.Contains('3XMTHREADINFO')
            if(!$op.success){$op.error='INVALID_DUMP'}
        }
        if(!$op.success -and !$op.error) { $op.error=if($op.timedOut){'TIMEOUT'}else{'Exit '+$op.exitCode+': '+(Read-Head $errPath 4096)} }
    } catch { $op.error=$_.Exception.Message; $op.success=$false }
    finally {
        Stop-Helper $process $started
        if($output){$output.Dispose()}; if($errorOutput){$errorOutput.Dispose()}
        $op.durationMillis=$watch.ElapsedMilliseconds; $op.completedAt=[DateTime]::UtcNow.ToString('o')
        Activity ('[JVM] PID '+$target.pid+' | '+$type+' | ornek '+$sample+' | '+$tool+' | '+$(if($op.success){'BASARILI'}else{'BASARISIZ: '+$op.error})+' | sure '+$op.durationMillis+' ms | dosya: '+$op.artifact)
    }
    return $op
}
function Scan-Java {
    $namesJson=ConvertTo-Json -InputObject @($settings.additionalProcessNames) -Compress
    $setup='$additionalNames=@(ConvertFrom-Json ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('''+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($namesJson))+'''))));'
    $scan=Run-Script ([IO.File]::ReadAllText((Join-Path $jobRoot 'process-scan.ps1'),[Text.Encoding]::UTF8)) $setup 'discovery' $settings.scanTimeoutSeconds
    $scan|Add-Member -NotePropertyName windowsDiscoverySucceeded -NotePropertyValue $true
    try{
        $rootsJson=ConvertTo-Json -InputObject @(@($settings.jdkHome)+@($settings.jdkHomes)) -Compress;$targetsJson=ConvertTo-Json -InputObject @($scan.processes) -Depth 8 -Compress
        $setup='$jdkRoots=ConvertFrom-Json ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('''+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($rootsJson))+''')));$jdkTargets=ConvertFrom-Json ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('''+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($targetsJson))+''')));'
        $script:jdkInventory=Run-Script ([IO.File]::ReadAllText((Join-Path $jobRoot 'jdk-discovery.ps1'))+';Get-JdkInventory $jdkRoots $jdkTargets|ConvertTo-Json -Depth 12 -Compress') $setup 'jdk-discovery' ([Math]::Min(15,$settings.scanTimeoutSeconds))
    }catch{$scan.errors+=,('JDK_DISCOVERY_FAILED: '+$_.Exception.Message);$script:jdkInventory=[pscustomobject]@{candidates=@();plans=@();notes=@($_.Exception.Message)}}
    $result|Add-Member NoteProperty toolDiscovery $jdkInventory -Force
    $jpsReport=[pscustomobject]@{operations=@()};$jps=Try-Tools $jpsReport $null @('jps') 'jps' 0 (Join-Path $dataRoot 'debug\discovery')
    $jdkInventory|Add-Member NoteProperty jpsAttempts @($jpsReport.operations) -Force
    Write-Json (Join-Path $dataRoot 'debug\jdk-discovery.json') $jdkInventory
    if($jps.success) {
        foreach($line in ((Read-Head (Join-Path $dataRoot $jps.artifact)) -split '\r?\n')) {
            if($line -notmatch '^(\d+)\s+(.+)$'){continue}; $foundPid=[long]$matches[1]; $main=$matches[2].Trim()
            if($main -match '(^|/)sun.tools.jps.Jps$'){continue}
            $entry=@($scan.processes|Where-Object{$_.pid -eq $foundPid})
            if($entry.Count -eq 0) {
                try{$p=Query-Process $foundPid}catch{$scan.errors+=,('JPS_IDENTITY_FAILED: '+$_.Exception.Message);continue}
                if(!$p){continue};$entry=@($p)
                $scan.processes+=,$entry[0]
            }
            $entry[0]|Add-Member -NotePropertyName mainClass -NotePropertyValue $main -Force
        }
    } else { $scan.errors+=,$jps.error }
    foreach($entry in $scan.processes) {
        if(!$entry.PSObject.Properties['mainClass']){$entry|Add-Member -NotePropertyName mainClass -NotePropertyValue $null}
        if($entry.collectorProcess -or $entry.mainClass -eq 'tr.diagnostic.Main' -or $entry.mainClass -match '(?i)(^|[/\\])java-diagnostic-collector(?:-[0-9][0-9A-Za-z.-]*)?\.jar$') {
            $entry.collectorProcess=$true; $entry|Add-Member -NotePropertyName collectionExclusionReason -NotePropertyValue 'Remote Collector JVM; excluded from diagnostic targets.' -Force
        }
    }
    return $scan
}
function Resolve-CollectionPaths {
    $install=$null;$app=$null
    if(![string]::IsNullOrWhiteSpace([string]$settings.installationDirectory)){
        if($settings.installationDirectory -notmatch '^[A-Za-z]:[/\\]'){throw 'REMOTE_ROOT_REQUIRED: installationDirectory must be an absolute server drive path'}
        $install=[IO.Path]::GetFullPath($settings.installationDirectory)
    }
    if($settings.applicationDirectory -eq 'auto'){
        if($install){
            $sub=Join-Path $install 'uygulama'
            $app=if(Test-Path -LiteralPath $sub -PathType Container){$sub}elseif((Test-Path -LiteralPath (Join-Path $install 'plugins') -PathType Container) -or (Test-Path -LiteralPath (Join-Path $install 'Log') -PathType Container)){$install}else{$sub}
        }
    }elseif($settings.applicationDirectory -match '^[A-Za-z]:[/\\]'){$app=[IO.Path]::GetFullPath($settings.applicationDirectory)}
    elseif([IO.Path]::IsPathRooted([string]$settings.applicationDirectory)){throw 'REMOTE_ROOT_REQUIRED: ambiguous applicationDirectory'}
    elseif($install){$app=[IO.Path]::GetFullPath((Join-Path $install $settings.applicationDirectory))}
    if(![string]::IsNullOrWhiteSpace([string]$settings.beforeLogCopyBatch) -and !$app){throw 'REMOTE_ROOT_REQUIRED: log preparation BAT needs an explicit server application root'}
    foreach($spec in @($settings.logs)){
        if([string]::IsNullOrWhiteSpace([string]$spec.path)){throw 'REMOTE_ROOT_REQUIRED: log path is empty'}
        if($spec.path -match '^[A-Za-z]:[/\\]'){continue}
        if([IO.Path]::IsPathRooted([string]$spec.path)){throw 'REMOTE_ROOT_REQUIRED: ambiguous log path'}
        $base=if($spec.base -eq 'application'){$app}else{$install}
        if(!$base){throw 'REMOTE_ROOT_REQUIRED: relative log path needs an explicit server root'}
    }
    return [pscustomobject]@{installationDirectory=$install;applicationDirectory=$app}
}
function Resolve-Source($spec,$install,$app) {
    $base=if($spec.base -eq 'application'){$app}else{$install}
    $path=if([IO.Path]::IsPathRooted($spec.path)){$spec.path}else{Join-Path $base $spec.path}
    $path=[IO.Path]::GetFullPath($path)
    if($path -notmatch '^[A-Za-z]:\\'){throw 'Remote sources must be local server drive paths'}
    return $path
}
function Needs-InteractiveDesktop([string]$body) {
    foreach($line in ($body -split '\r?\n')){
        if($line -match '^\s*(?:rem\b|::)'){continue}
        if($line -match '\bnircmd(?:c)?(?:\.exe)?"?\s+(?:sendkeypress|sendkey|sendmouse|win)\b'){return $true}
    }
    return $false
}
function Select-InteractiveTarget {
    $user=[string]$settings.interactiveUserName
    if(!$user -or [string]$settings.interactiveUserSid -notmatch '^S-1-[0-9-]+$'){throw 'INTERACTIVE_SESSION_NOT_FOUND: Oturum kullanici kimligi dogrulanamadi.'}
    $candidates=@($result.discovery.processes|Where-Object{$_.owner -ieq $user -and $_.sessionId -gt 0 -and !$_.collectorProcess})
    if($null -ne $settings.selectedPids){$candidates=@($candidates|Where-Object{$_.pid -in $settings.selectedPids})}
    $sessions=@($candidates|ForEach-Object{$_.sessionId}|Sort-Object -Unique)
    if($sessions.Count -eq 0){throw 'INTERACTIVE_SESSION_NOT_FOUND: BAT icin ayni kullanicinin uygulamasi acik bir Windows/RDP oturumunda olmali.'}
    if($sessions.Count -ne 1){throw 'INTERACTIVE_SESSION_AMBIGUOUS: Birden fazla uygulama oturumu var; yanlis masaustune komut gonderilmedi.'}
    # Prefer an OSGi target only within the one verified selected session.
    # An OSGi process in another session must never override PID selection.
    $osgi=@($candidates|Where-Object{$_.mainClass -match '(?i)org\.eclipse\.(osgi|equinox)'})
    if($osgi.Count -gt 0){$candidates=$osgi}
    return $candidates[0]
}
function Remove-InteractiveJobTask($folder,[string]$taskName,[string]$description) {
    try{
        $remaining=@($folder.GetTasks(1)|Where-Object{$_.Name -eq $taskName})
        if($remaining.Count -gt 0){
            if($remaining[0].Definition.RegistrationInfo.Description -ne $description){throw 'Interactive task ownership mismatch'}
            $remaining[0].Stop(0);$folder.DeleteTask($taskName,0)
        }
    }catch{
        $cleanupError=$_.Exception.Message
        # The wrapper also deletes this task after publishing its marker.
        # Accept that race only after a successful fresh absence check.
        if(@($folder.GetTasks(1)|Where-Object{$_.Name -eq $taskName}).Count -ne 0){throw $cleanupError}
    }
}
function Run-InteractiveBatch($batch,$op) {
    $target=Select-InteractiveTarget
    $identityError=Identity-Error $target
    if($identityError){throw ('INTERACTIVE_TARGET_INVALID: '+$identityError)}
    $op.executionMode='interactive-task';$op.sessionId=[int]$target.sessionId
    $taskName='DiagnosticCollector-BAT-'+$settings.jobId;$op.taskName=$taskName
    $description='DiagnosticCollector batch job '+$settings.jobId
    $taskCreated=$false;$registered=$null;$folder=$null
    $marker=Join-Path $jobRoot 'batch-result.json'
    $requestFile=Join-Path $jobRoot 'batch-request.json'
    $timeout=[int][Math]::Max(1,[Math]::Min($settings.beforeLogCopyTimeoutSeconds,($settings.timeoutSeconds-$clock.Elapsed.TotalSeconds)))
    try{
        Deadline
        $scheduler=New-Object -ComObject Schedule.Service;$scheduler.Connect();$folder=$scheduler.GetFolder('\')
        $definition=$scheduler.NewTask(0);$definition.RegistrationInfo.Description=$description
        $definition.Principal.UserId=[string]$settings.interactiveUserSid;$definition.Principal.LogonType=3
        $principal=New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
        $definition.Principal.RunLevel=if($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){1}else{0}
        $definition.Settings.Enabled=$true;$definition.Settings.AllowDemandStart=$true;$definition.Settings.Hidden=$true
        $definition.Settings.DisallowStartIfOnBatteries=$false;$definition.Settings.StopIfGoingOnBatteries=$false
        $definition.Settings.MultipleInstances=2;$definition.Settings.ExecutionTimeLimit='PT'+($timeout+15)+'S'
        # Expiry is a backstop if neither worker nor wrapper survives cleanup.
        # The wrapper checks worker identity and never reruns a completed job.
        $expiry=$definition.Triggers.Create(1)
        $expiry.StartBoundary=[DateTime]::Now.AddSeconds($timeout+60).ToString('s')
        $expiry.EndBoundary=[DateTime]::Now.AddSeconds($timeout+120).ToString('s')
        $definition.Settings.DeleteExpiredTaskAfter='PT1M'
        $action=$definition.Actions.Create(0);$action.Path=Join-Path $PSHOME 'powershell.exe'
        $action.Arguments='-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "'+(Join-Path $jobRoot 'remote-batch.ps1')+'" -RequestFile "'+$requestFile+'"'
        # A task process whose OS current directory is jobRoot can retain a
        # directory handle briefly after publishing its completion marker.
        # Keep that current directory outside the deletable job; CMD itself
        # still runs in the BAT's own Log directory.
        $action.WorkingDirectory=$env:SystemRoot
        Write-Json $requestFile @{jobId=$settings.jobId;batch=$batch;sessionId=$target.sessionId;userSid=$settings.interactiveUserSid;targetPid=$target.pid;targetStartTicks=$target.startTicks;workerPid=$workerIdentity.pid;workerStartTicks=$workerIdentity.startTicks;timeoutSeconds=$timeout}
        [IO.File]::WriteAllText((Join-Path $jobRoot 'batch-heartbeat.txt'),[Guid]::NewGuid().ToString())
        # TASK_CREATE only: never overwrite an unrelated existing task. No
        # password is registered; an existing interactive token is required.
        $registered=$folder.RegisterTaskDefinition($taskName,$definition,2,[string]$settings.interactiveUserSid,$null,3,$null)
        $taskCreated=$true
        Write-Json (Join-Path $jobRoot 'progress.json') @{jobId=$settings.jobId;phase='before-log-copy';message=('Sunucudaki '+$settings.beforeLogCopyBatch+' kullanici oturumunda cagriliyor; session '+$target.sessionId+'...')}
        [void]$registered.RunEx($null,4,[int]$target.sessionId,$null)
        $watch=[Diagnostics.Stopwatch]::StartNew()
        while(!(Test-Path -LiteralPath $marker)){
            Deadline
            if($watch.Elapsed.TotalSeconds -ge ($timeout+5)){$op.timedOut=$true;throw 'BEFORE_LOG_COPY_TIMEOUT: Etkilesimli BAT sonucu alinamadi.'}
            [IO.File]::WriteAllText((Join-Path $jobRoot 'batch-heartbeat.txt'),[Guid]::NewGuid().ToString())
            Start-Sleep -Milliseconds 250
        }
        $finished=Get-Content -LiteralPath $marker -Raw -Encoding UTF8|ConvertFrom-Json
        if($finished.jobId -ne $settings.jobId -or $finished.sessionId -ne $target.sessionId -or $finished.userSid -ne $settings.interactiveUserSid){throw 'INTERACTIVE_RESULT_IDENTITY_MISMATCH'}
        $op.exitCode=$finished.exitCode;$op.timedOut=[bool]$finished.timedOut
        foreach($name in @('batch.stdout.txt','batch.stderr.txt')){
            $path=Join-Path $dataRoot ('pre-log\'+$name)
            if(Test-Path -LiteralPath $path){if($name -eq 'batch.stdout.txt'){$op.artifact=Relative $path}else{$op.stderrArtifact=Relative $path}}
        }
        if(!$finished.success){
            $detail=if($op.stderrArtifact){Read-Head (Join-Path $dataRoot $op.stderrArtifact) 4096}else{''}
            throw ([string]$finished.error+'; '+$detail)
        }
    }finally{
        if($taskCreated){
            try{
                # Only the task created by this job is stopped/removed. Its
                # wrapper closes its own CMD; existing JVMs are never stopped.
                if(!(Test-Path -LiteralPath $marker)){
                    [IO.File]::WriteAllText((Join-Path $jobRoot 'batch-cancel.txt'),'cancel')
                    $stopWatch=[Diagnostics.Stopwatch]::StartNew()
                    while(!(Test-Path -LiteralPath $marker) -and $stopWatch.Elapsed.TotalSeconds -lt 5){Start-Sleep -Milliseconds 250}
                }
                Remove-InteractiveJobTask $folder $taskName $description
                $op.taskCleanupSucceeded=$true
            }catch{
                $op.taskCleanupSucceeded=$false;$result.errors+=,('INTERACTIVE_TASK_CLEANUP_FAILED: '+$_.Exception.Message+'; task '+$taskName)
            }
        }
    }
}
function Wait-AfterBatch($op) {
    $delay=if($null -eq $settings.beforeLogCopyDelaySeconds){30}else{[int]$settings.beforeLogCopyDelaySeconds}
    if($delay -lt 0 -or $delay -gt 300){throw 'Invalid post-BAT delay'}
    $op.batchCompletedAt=[DateTime]::UtcNow.ToString('o')
    if($delay -gt 0){
        Activity ('[Log hazirligi] BAT tamamlandi; log kopyalamadan once '+$delay+' saniye bekleniyor.')
        Write-Json (Join-Path $jobRoot 'progress.json') @{jobId=$settings.jobId;phase='before-log-copy-wait';message=('BAT bitti; log kopyalamadan once '+$delay+' saniye bekleniyor...')}
        $wait=[Diagnostics.Stopwatch]::StartNew()
        while($wait.Elapsed.TotalSeconds -lt $delay){Deadline;Start-Sleep -Milliseconds ([int][Math]::Min(500,[Math]::Max(1,$delay*1000-$wait.ElapsedMilliseconds)))}
        Deadline
    }
    $op.postBatchWaitSeconds=$delay
}
function Run-BeforeLogCopy($app) {
    Deadline
    Activity ('[Log hazirligi] Basladi: '+$settings.beforeLogCopyBatch+' | uygulama: '+$app)
    $op=[pscustomobject]@{type='before-log-copy';startedAt=[DateTime]::UtcNow.ToString('o');completedAt=$null;sample=0;command=$null;workingDirectory=$null;executionMode='direct-cmd';sessionId=$null;taskName=$null;taskCleanupSucceeded=$null;postBatchWaitSeconds=0;batchCompletedAt=$null;success=$false;timedOut=$false;exitCode=$null;durationMillis=0;artifact=$null;stderrArtifact=$null;error=$null}
    $helper=$null;$started=$false;$output=$null;$errorOutput=$null;$watch=[Diagnostics.Stopwatch]::StartNew()
    try {
        $relative=[string]$settings.beforeLogCopyBatch
        if($relative -notmatch '^[A-Za-z0-9_./\\ -]+\.bat$' -or [IO.Path]::IsPathRooted($relative) -or '..' -in ($relative -split '[/\\]')){throw 'Unsafe before-log-copy BAT path'}
        $batch=[IO.Path]::GetFullPath((Join-Path $app $relative))
        if(!$batch.StartsWith($app.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Before-log-copy BAT escapes application directory'}
        $op.workingDirectory=Split-Path $batch -Parent
        $cmd=Join-Path $env:SystemRoot 'System32\cmd.exe'
        $call='call "'+[IO.Path]::GetFileName($batch)+'"'
        $op.command=@($cmd,'/d','/s','/c',$call)
        $item=Get-Item -LiteralPath $batch -Force
        if($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Before-log-copy BAT must be a regular file'}
        if($settings.nonInteractiveTransport -and (Needs-InteractiveDesktop ([IO.File]::ReadAllText($batch)))){
            Run-InteractiveBatch $batch $op
            Wait-AfterBatch $op;$op.success=$true;return $op
        }
        $folder=Join-Path $dataRoot 'pre-log';[void][IO.Directory]::CreateDirectory($folder)
        $outPath=Join-Path $folder 'batch.stdout.txt';$errPath=Join-Path $folder 'batch.stderr.txt'
        $op.artifact=Relative $outPath;$op.stderrArtifact=Relative $errPath
        $output=[IO.File]::Open($outPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
        $errorOutput=[IO.File]::Open($errPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
        $start=New-Object Diagnostics.ProcessStartInfo;$start.FileName=$cmd;$start.WorkingDirectory=$op.workingDirectory
        # Keep the executable name out of shell input except for a validated
        # relative basename; WorkingDirectory is passed through ProcessStartInfo.
        $start.Arguments='/d /s /c "'+$call+'"'
        $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true;$start.RedirectStandardInput=$true
        Write-Json (Join-Path $jobRoot 'progress.json') @{jobId=$settings.jobId;phase='before-log-copy';message=('Sunucuda '+$relative+' calistiriliyor; log kopyalama bekliyor...')}
        $helper=New-Object Diagnostics.Process;$helper.StartInfo=$start;$started=$helper.Start();$helper.StandardInput.Close()
        $copy=$helper.StandardOutput.BaseStream.CopyToAsync($output);$copyError=$helper.StandardError.BaseStream.CopyToAsync($errorOutput)
        $budget=[int][Math]::Max(1,[Math]::Min($settings.beforeLogCopyTimeoutSeconds*1000,($settings.timeoutSeconds-$clock.Elapsed.TotalSeconds)*1000))
        if(!(Wait-Helper $helper $budget)){$op.timedOut=$true;throw 'BEFORE_LOG_COPY_TIMEOUT'}
        $op.exitCode=$helper.ExitCode
        if(!$copy.Wait(3000) -or !$copyError.Wait(3000)){throw 'BEFORE_LOG_COPY_OUTPUT_TIMEOUT: BAT alt islemleri tamamlanmamis olabilir'}
        $output.Dispose();$output=$null;$errorOutput.Dispose();$errorOutput=$null
        if($op.exitCode -ne 0){throw ('BEFORE_LOG_COPY_EXIT_CODE: '+$op.exitCode+'; '+(Read-Head $errPath 4096))}
        Wait-AfterBatch $op
        $op.success=$true
    }catch{$op.error=$_.Exception.Message}
    finally{
        Stop-Helper $helper $started
        if($output){$output.Dispose()};if($errorOutput){$errorOutput.Dispose()}
        $op.completedAt=[DateTime]::UtcNow.ToString('o');$op.durationMillis=$watch.ElapsedMilliseconds
        Activity ('[Log hazirligi] '+$settings.beforeLogCopyBatch+' | '+$(if($op.success){'TAMAMLANDI'}else{'BASARISIZ: '+$op.error})+' | exit='+$op.exitCode+' | bekleme='+$op.postBatchWaitSeconds+' sn | sure='+$op.durationMillis+' ms')
    }
    return $op
}
function Copy-Log($source,$target,$spec,$cutoff) {
    Deadline
    $log=[pscustomobject]@{source=$source;destination=(Relative $target);status='COPIED';error=$null;bytesCopied=0;changedDuringCopy=$false}
    $inputFile=$null;$outputFile=$null;$created=$false;$attempted=$false
    try {
        $before=Get-Item -LiteralPath $source -Force
        if(($before.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){Activity ('[Log] ATLANDI: '+$source+' | reparse point');return}
        if($before.LastWriteTimeUtc -lt $cutoff){Activity ('[Log] FILTRE DISI: '+$source+' | tarih filtresi');return}
        if(@($spec.extensions).Count -gt 0 -and $before.Extension.ToLowerInvariant() -notin $spec.extensions){Activity ('[Log] FILTRE DISI: '+$source+' | uzanti filtresi');return}
        $attempted=$true
        $observedAt=[DateTime]::UtcNow
        if($spec.modifiedWithinHours -gt 0 -and $before.LastWriteTimeUtc -gt $observedAt){
            $log.status='SKIPPED_FUTURE_TIMESTAMP';$log.error='FUTURE_TIMESTAMP: modified='+$before.LastWriteTimeUtc.ToString('o')+'; observed='+$observedAt.ToString('o');return $log
        }
        Activity ('[Log] Kopyalama basladi: '+$source+' -> '+$log.destination)
        if($before.Length -gt $spec.maxFileSizeBytes){$log.status='SKIPPED_SIZE';$log.error='Maximum file size exceeded';return $log}
        if(Test-Path -LiteralPath $target){$log.status='FAILED';$log.error='DESTINATION_COLLISION: previous copy preserved';return $log}
        $length=$before.Length;$modified=$before.LastWriteTimeUtc
        Ensure-Space $length
        [void][IO.Directory]::CreateDirectory((Split-Path $target -Parent))
        $inputFile=[IO.File]::Open($source,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
        $outputFile=[IO.File]::Open($target,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None);$created=$true
        $buffer=New-Object byte[] 65536
        while($log.bytesCopied -lt $length){Deadline; $count=$inputFile.Read($buffer,0,[int][Math]::Min($buffer.Length,$length-$log.bytesCopied));if($count -eq 0){break};$outputFile.Write($buffer,0,$count);$log.bytesCopied+=$count}
        $after=Get-Item -LiteralPath $source -Force
        $log.changedDuringCopy=($length -ne $after.Length -or $modified -ne $after.LastWriteTimeUtc -or $log.bytesCopied -ne $length)
        if($log.changedDuringCopy){$log.status='COPIED_CHANGING_SOURCE'}
    }catch{$log.status='FAILED';$log.error=$_.Exception.Message}
    finally{
        if($inputFile){$inputFile.Dispose()};if($outputFile){$outputFile.Dispose()};if($created -and $log.status -eq 'FAILED'){Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue}
        if($attempted -or $log.status -eq 'FAILED'){Activity ('[Log] '+$log.status+' | '+$source+' | '+$log.bytesCopied+' bayt | '+$log.error)}
    }
    return $log
}
function Write-CollectionSummary {
    $failedAttempts=@($result.operations|Where-Object{!$_.success}).Count
    foreach($jvm in $result.jvms){$failedAttempts+=@($jvm.operations|Where-Object{!$_.success}).Count}
    $incompleteLogs=@($result.logs|Where-Object{$_.status -ne 'COPIED' -or $_.changedDuringCopy}).Count
    Activity ('[Sunucu] Toplama adimlari bitti; ust duzey hata/not='+$result.errors.Count+' | basarisiz komut denemesi='+$failedAttempts+' | eksik/degisen log='+$incompleteLogs+'. Ciktilar envantere aliniyor ve SHA-256 hesaplanacak.') 72 'Sunucu dosyaları aktarım için doğrulanıyor...'
}
try {
    $collectionPaths=if($settings.action -eq 'collect'){Resolve-CollectionPaths}else{$null}
    Activity ('[Sunucu] Toplama yardimcisi calisiyor; islem: '+$settings.action)
    if($settings.action -eq 'collect' -and $settings.collectionDelaySeconds -gt 0){Activity ('[Sunucu] Baslangic beklemesi: '+$settings.collectionDelaySeconds+' sn');Start-Sleep -Seconds $settings.collectionDelaySeconds}
    Activity '[Kesif] Sunucudaki Java surecleri ve JDK araclari araniyor...' 12 'Java süreçleri aranıyor...'
    $result.discovery=Scan-Java
    Activity ('[Kesif] '+$(if($result.discovery.windowsDiscoverySucceeded){'TAMAMLANDI'}else{'KISMI/BASARISIZ'})+': '+@($result.discovery.processes).Count+' Java sureci bulundu.')
    foreach($issue in $result.discovery.errors){Activity ('[Kesif] HATA/NOT: '+$issue)}
    foreach($found in $result.discovery.processes){Activity ('[Kesif] PID '+$found.pid+' | '+$found.processName+' | '+$found.applicationName+' | oturum '+$found.sessionId)}
    if($settings.action -eq 'collect') {
        $result.errors+=@($result.discovery.errors)
        $targets=@()
        foreach($p in $result.discovery.processes) {
            if($settings.selectedPids -ne $null -and $p.pid -notin $settings.selectedPids){Activity ('[JVM] PID '+$p.pid+' ATLANDI: toplama icin secilmedi.');continue}
            if($p.collectorProcess){$result.excludedJvms+=,[pscustomobject]@{process=$p;reason=$p.collectionExclusionReason};Activity ('[JVM] PID '+$p.pid+' HARIC: '+$p.collectionExclusionReason)}else{$targets+=,$p}
        }
        if($null -ne $settings.selectedPids){foreach($chosen in $settings.selectedPids){if($chosen -notin @($result.discovery.processes.pid)){$result.errors+=,('TARGET_NOT_DISCOVERED: '+$chosen);Activity ('[JVM] PID '+$chosen+' BULUNAMADI: secilen surec kesif sonucunda yok.')}}}
        if($targets.Count -eq 0){$result.errors+=,'No target Java process found';Activity '[JVM] HEDEF YOK: surum/GC/thread dump toplama yapilamayacak.'}
        Activity ('[JVM] Toplama hedefi: '+$targets.Count+' surec; her hedef icin 3 dump istenecek.')
        # No comparison with the client PID: PIDs are scoped to each computer.
        if($settings.resourceMonitoringSeconds -gt 0) {
            Activity ('[Kaynak] CPU/RAM/disk takibi baslatiliyor: '+$settings.resourceMonitoringSeconds+' sn, hedef aralik '+$settings.resourceSampleIntervalSeconds+' sn.')
            $expectedStarts=@{};foreach($p in $targets){if($p.startTicks -match '^[0-9]+$'){$expectedStarts[[string]$p.pid]=[string]$p.startTicks}}
            $monitorSetup='$expectedStarts=ConvertFrom-Json ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('''+([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($expectedStarts|ConvertTo-Json -Compress))))+''')));$dict=@{};foreach($p in $expectedStarts.PSObject.Properties){$dict[$p.Name]=[string]$p.Value};$expectedStarts=$dict;$monitorDurationSeconds='+$settings.resourceMonitoringSeconds+';$sampleIntervalSeconds='+$settings.resourceSampleIntervalSeconds+';'
            $monitorScript='$ErrorActionPreference=''Stop''; [Console]::OutputEncoding=New-Object Text.UTF8Encoding($false); '+$monitorSetup+[IO.File]::ReadAllText((Join-Path $jobRoot 'resource-monitor.ps1'),[Text.Encoding]::UTF8)
            $start=New-Object Diagnostics.ProcessStartInfo; $start.FileName=Join-Path $PSHOME 'powershell.exe'; $start.Arguments='-NoLogo -NoProfile -NonInteractive -EncodedCommand '+[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($monitorScript));$start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
            $monitorOutput=[IO.File]::Open((Join-Path $dataRoot 'kaynak-izleme.json'),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
            $monitorError=[IO.File]::Open((Join-Path $dataRoot 'resource-monitor.stderr.txt'),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
            $start.WorkingDirectory=$env:SystemRoot
            $monitorProcess=New-Object Diagnostics.Process;$monitorProcess.StartInfo=$start;$monitorStarted=$monitorProcess.Start()
            $monitorTask=$monitorProcess.StandardOutput.BaseStream.CopyToAsync($monitorOutput);$monitorErrTask=$monitorProcess.StandardError.BaseStream.CopyToAsync($monitorError)
            Activity '[Kaynak] Olcum yardimcisi baslatildi; JVM toplama ile birlikte olcum suruyor.'
        }else{Activity '[Kaynak] Sureli kaynak takibi ayarlarda kapali.'}
        try {
            $pidsJson=ConvertTo-Json -InputObject @($targets.pid) -Compress
            Activity '[Ilerleme] Sistem bilgileri toplaniyor.' 18 'Sistem bilgileri toplanıyor...'
            $setup='$targetPids=@(ConvertFrom-Json ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('''+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pidsJson))+'''))));'
            $system=Run-Script ([IO.File]::ReadAllText((Join-Path $jobRoot 'system-info.ps1'),[Text.Encoding]::UTF8)) $setup 'system-info' $settings.scanTimeoutSeconds
            Write-Json (Join-Path $dataRoot 'remote-system.json') $system
            $result.errors+=@($system.errors)
        } catch {$result.errors+=,$_.Exception.Message}
        $finishedDumpSteps=0
        foreach($p in $targets) {
            Deadline
            Activity ('[JVM] PID '+$p.pid+' icin surum, GC ve thread dump toplama basladi.')
            $jvm=[pscustomobject]@{process=$p;requestedDumpCount=3;dumpCount=0;operations=@();selectedDumpTool=$null}
            $result.jvms+=,$jvm
            $dir=Join-Path $dataRoot ('jvms\'+(Safe-Name $p.processName)+'-'+$p.pid)
            $version=Try-Tools $jvm $p @('jcmd') 'VM.version' 0 $dir
            if($version.success){$p.javaVersion=(Read-Head (Join-Path $dataRoot $version.artifact) 8192).Trim();$p.versionSource='jcmd VM.version'}
            if($settings.collectGcStats){$null=Try-Tools $jvm $p @('jstat') 'gc-statistics' 0 $dir}else{Activity ('[JVM] PID '+$p.pid+' | GC istatistigi ayarlarda kapali.')}
            for($sample=1;$sample -le 3;$sample++){
                if($sample -gt 1){Activity ('[JVM] PID '+$p.pid+' | sonraki dump oncesi 5 sn bekleniyor.')}
                if($sample -gt 1){Start-Sleep -Seconds 5}
                $op=Try-Tools $jvm $p @('jcmd','jstack') 'thread-dump' $sample $dir
                if($op.success){$jvm.dumpCount++}
                $finishedDumpSteps++
                Activity ('[Ilerleme] Dump adimi '+$finishedDumpSteps+'/'+($targets.Count*3)+' tamamlandi.') ([int][Math]::Floor(22+23*$finishedDumpSteps/($targets.Count*3))) 'Java tanılama verileri toplanıyor...'
                Write-Json (Join-Path $jobRoot 'progress.json') ([pscustomobject]@{jobId=$settings.jobId;phase='dump';pid=$p.pid;sample=$sample;dumpCount=$jvm.dumpCount})
            }
            Activity ('[JVM] PID '+$p.pid+' TAMAMLANDI: '+$jvm.dumpCount+'/3 dump basarili.')
        }
        Activity '[Ilerleme] JVM toplama adimlari bitti.' 45 'Windows tanılama bilgileri toplanıyor...'
        if($settings.windowsDiagnostics){try{. (Join-Path $jobRoot 'remote-windows.ps1');Collect-WindowsDiagnostics}
        catch{Activity ('[Windows] BASARISIZ: '+$_.Exception.Message);Deadline;$result.errors+=,('WINDOWS_DIAGNOSTICS_FAILED: '+$_.Exception.Message)}
        }
        if($settings.ping.enabled -or $settings.vxworks.enabled){
            try{. (Join-Path $jobRoot 'remote-extras.ps1');Collect-RemoteExtraDiagnostics}
            catch{Activity ('[Ek tanilama] BASARISIZ: '+$_.Exception.Message);Deadline;$result.errors+=,('EXTRA_DIAGNOSTICS_FAILED: '+$_.Exception.Message)}
        }else{Activity '[Ek tanilama] Ping ve VxWorks ayarlarda kapali.'}
        $install=$collectionPaths.installationDirectory;$app=$collectionPaths.applicationDirectory
        Activity '[Ilerleme] Log hazirligi basliyor.' 65 'Log dosyaları hazırlanıyor...'
        $sources=@();$index=0;$logsReady=$true
        if(![string]::IsNullOrWhiteSpace([string]$settings.beforeLogCopyBatch)){
            $preparation=Run-BeforeLogCopy $app;$result.operations+=,$preparation
            if(!$preparation.success){
                $logsReady=$false;$result.errors+=,('BEFORE_LOG_COPY_FAILED: '+$preparation.error+'; log kopyalama yapilmadi.')
            }
            Write-Json (Join-Path $jobRoot 'progress.json') @{jobId=$settings.jobId;phase='logs';message=if($logsReady){'BAT tamamlandi; sunucu loglari kopyalaniyor...'}else{'BAT basarisiz; log kopyalama yapilmayacak.'}}
        }else{Activity '[Log hazirligi] Kopyalama oncesi BAT ayarlarda tanimli degil; dogrudan kaynaklar taranacak.'}
        $logFilterStartedAt=[DateTime]::UtcNow
        foreach($spec in $settings.logs) {
            Deadline
            Activity '[Ilerleme] Log kaynagi isleniyor.' ([int][Math]::Floor(66+4*$sources.Count/[Math]::Max(1,@($settings.logs).Count))) 'Log dosyaları kopyalanıyor...'
            $source=Resolve-Source $spec $install $app
            Activity ('[Log kaynagi] Tarama basladi: '+$source+' | hedef '+$spec.destination+' | tarih penceresi '+$spec.modifiedWithinHours+' saat')
            $sources+=,[pscustomobject]@{source=$source;destination=$spec.destination}
            if(!$logsReady){$result.logs+=,[pscustomobject]@{source=$source;status='SKIPPED_PREPARATION_FAILED';error=$preparation.error;bytesCopied=0};Activity ('[Log kaynagi] ATLANDI: '+$source+' | BAT hazirligi basarisiz.');continue}
            $dest=if($spec.destination){$spec.destination}else{$index++;'{0:d2}' -f $index}
            $logsRoot=Join-Path $dataRoot 'logs';$targetRoot=[IO.Path]::GetFullPath((Join-Path $logsRoot $dest))
            if(!$targetRoot.StartsWith($logsRoot+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe log destination'}
            $cutoff=if($spec.modifiedWithinHours -eq 0){[DateTime]::MinValue}else{$logFilterStartedAt.AddHours(-$spec.modifiedWithinHours)}
            $sourceResultStart=$result.logs.Count
            try {
                $item=Get-Item -LiteralPath $source -Force
                if(($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Source is a reparse point'}
                if(!$item.PSIsContainer){$result.logs+=@(Copy-Log $source (Join-Path $targetRoot $item.Name) $spec $cutoff);continue}
                $queue=New-Object 'Collections.Generic.Queue[string]';$queue.Enqueue($source)
                while($queue.Count -gt 0){Deadline;$folder=$queue.Dequeue();try{
                    foreach($file in @(Get-ChildItem -LiteralPath $folder -Force)){
                        if(($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){continue}
                        if($file.FullName.StartsWith($jobRoot,[StringComparison]::OrdinalIgnoreCase)){continue}
                        if($file.PSIsContainer){if($spec.recursive){$queue.Enqueue($file.FullName)};continue}
                        $relative=$file.FullName.Substring($source.TrimEnd('\').Length+1)
                        $result.logs+=@(Copy-Log $file.FullName (Join-Path $targetRoot $relative) $spec $cutoff)
                    }
                }catch{$result.logs+=,[pscustomobject]@{source=$folder;status='FAILED';error=$_.Exception.Message};Activity ('[Log kaynagi] KLASOR OKUNAMADI: '+$folder+' | '+$_.Exception.Message)}}
            }catch{$result.logs+=,[pscustomobject]@{source=$source;status='FAILED';error=$_.Exception.Message};Activity ('[Log kaynagi] BASARISIZ: '+$source+' | '+$_.Exception.Message)}
            finally{
                $newResults=@($result.logs|Select-Object -Skip $sourceResultStart)
                Activity ('[Log kaynagi] Tarama bitti: '+$source+' | kopyalanan='+@($newResults|Where-Object{$_.status -like 'COPIED*'}).Count+' | hatali='+@($newResults|Where-Object{$_.status -eq 'FAILED'}).Count+' | boyut nedeniyle atlanan='+@($newResults|Where-Object{$_.status -eq 'SKIPPED_SIZE'}).Count+' | gelecek tarih nedeniyle atlanan='+@($newResults|Where-Object{$_.status -eq 'SKIPPED_FUTURE_TIMESTAMP'}).Count+$(if(!$newResults.Count){' | Kaynak bos veya tum dosyalar filtre disi; filtre kayitlarina bakin.'}else{''}))
            }
        }
        $result.resolved=[pscustomobject]@{installationDirectory=$install;applicationDirectory=$app;sources=$sources;timeFilterClock='server UTC';logFilterStartedAtUtc=$logFilterStartedAt.ToString('o')}
    }
}catch{Activity ('[Sunucu] ISLEM HATASI: '+$_.Exception.Message);$result.errors+=,$_.Exception.Message}
finally{
    Activity '[Ilerleme] Sunucu olcumleri sonlandiriliyor.' 70 'Sunucu ölçümleri tamamlanıyor...'
    if($monitorProcess -and $monitorStarted){
        Activity '[Kaynak] Olcumun tamamlanmasi ve cikti akisinin kapanmasi bekleniyor...'
        try{
            $monitorBudget=[int][Math]::Max(1,[Math]::Min(($settings.resourceMonitoringSeconds+$settings.scanTimeoutSeconds)*1000,($settings.timeoutSeconds-$clock.Elapsed.TotalSeconds)*1000))
            if($cleanupRequested -or (Test-Path -LiteralPath (Join-Path $jobRoot 'cancel.txt'))){$cleanupRequested=$true;if(!$monitorProcess.HasExited){$monitorProcess.Kill();[void]$monitorProcess.WaitForExit(3000)}}
            elseif(!$monitorProcess.WaitForExit($monitorBudget)){$monitorProcess.Kill();[void]$monitorProcess.WaitForExit(3000);$result.errors+=,'ResourceMonitor TIMEOUT';Activity '[Kaynak] BASARISIZ: olcum suresi siniri asildi.'}
            if(!$monitorTask.Wait(3000) -or !$monitorErrTask.Wait(3000)){$result.errors+=,'ResourceMonitor output timeout';Activity '[Kaynak] BASARISIZ: olcum ciktisi zamaninda kapanmadi.'}
            if($monitorProcess.ExitCode -ne 0){$result.errors+=,'ResourceMonitor helper failed';Activity ('[Kaynak] BASARISIZ: olcum yardimcisi cikis kodu '+$monitorProcess.ExitCode)}else{Activity '[Kaynak] TAMAMLANDI: olcum yardimcisi basariyla cikti.'}
        }catch{$result.errors+=,$_.Exception.Message;Activity ('[Kaynak] BASARISIZ: '+$_.Exception.Message)}
        finally{Stop-Helper $monitorProcess $monitorStarted;$monitorProcess=$null;Activity '[Kaynak] Olcum yardimcisi kapatildi; sonuc/hatalar rapora eklendi.'}
    }
    if($monitorProcess){Stop-Helper $monitorProcess $monitorStarted}
    if($monitorOutput){$monitorOutput.Dispose()};if($monitorError){$monitorError.Dispose()}
    if($cleanupRequested -or (Test-Path -LiteralPath (Join-Path $jobRoot 'cancel.txt'))){Delete-OwnJob}
    else {
        Write-CollectionSummary
        $debugRoot=Join-Path $dataRoot 'debug';[void][IO.Directory]::CreateDirectory($debugRoot)
        foreach($journal in @(Get-ChildItem -LiteralPath $jobRoot -Filter 'activity-*.jsonl' -File)){[IO.File]::Copy($journal.FullName,(Join-Path $debugRoot $journal.Name),$true)}
        $serverCompleted=[DateTimeOffset]::Now
        $result.serverCompletedAtUtc=$serverCompleted.UtcDateTime.ToString('o')
        $result.serverCompletedAtLocal=$serverCompleted.ToString('o');$result.serverElapsedMillis=$clock.ElapsedMilliseconds
        Write-Json (Join-Path $dataRoot 'remote-result.json') $result
        foreach($file in @(Get-ChildItem -LiteralPath $dataRoot -File -Recurse)){$result.files+=,[pscustomobject]@{path=(Relative $file.FullName);size=$file.Length;sha256=(Sha256 $file.FullName)}}
        # Completion marker is published last, after outputs are closed and hashed.
        Write-Json (Join-Path $jobRoot 'complete.json.tmp') $result
        Move-Item -LiteralPath (Join-Path $jobRoot 'complete.json.tmp') -Destination (Join-Path $jobRoot 'complete.json')
    }
}
