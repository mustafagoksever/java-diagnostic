# Local Windows PowerShell 5.1 client. Credentials arrive through stdin only.
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
# A parent PowerShell 7 session may export incompatible module paths. This
# helper uses only Windows PowerShell's own built-in modules.
$env:PSModulePath=[IO.Path]::Combine($PSHOME,'Modules')
[Console]::OutputEncoding=New-Object Text.UTF8Encoding($false)
$request=ConvertFrom-Json ([Console]::ReadLine())
$secure=ConvertTo-SecureString $request.password -AsPlainText -Force
$credential=New-Object Management.Automation.PSCredential($request.username,$secure)
$request.password=$null
$jobId=[Guid]::NewGuid().ToString()
$workRoot='C:\Temp'
$remoteRoot=[IO.Path]::Combine($workRoot,('diagnostic-remote-'+$jobId))
$selectedShare=$null;$selectedShareSuffix=$null
$shareRoot=$null;$uncJobRoot=$null;$completed=$false;$warnings=@();$workerPid=$null;$phase='Baglanti'
$workerStartTicks=$null
$jobCreated=$false;$workerLaunchAttempted=$false
function Emit($value){$value|ConvertTo-Json -Depth 20 -Compress|Write-Output}
function Sha256($path){$inputFile=[IO.File]::OpenRead($path);$hash=[Security.Cryptography.SHA256]::Create();try{return [BitConverter]::ToString($hash.ComputeHash($inputFile)).Replace('-','')}finally{$inputFile.Dispose();$hash.Dispose()}}
function Read-WorkerIdentity {
    $path=Share-Path 'worker-state.json'
    if(!(Test-Path -LiteralPath $path -PathType Leaf)){return}
    $identity=Get-Content -LiteralPath $path -Raw -Encoding UTF8|ConvertFrom-Json
    if($identity.jobId -ne $jobId -or $identity.pid -le 0 -or [string]$identity.startTicks -notmatch '^[0-9]+$'){throw 'Invalid remote worker identity'}
    $script:workerPid=[long]$identity.pid;$script:workerStartTicks=[string]$identity.startTicks
}
function Worker-Status {
    # A query failure or a hidden command line is UNKNOWN, never proof of exit.
    try { $worker=Get-WmiObject Win32_Process -Filter ('ProcessId='+$workerPid) -ComputerName $request.host -Credential $credential -Authentication PacketPrivacy -Impersonation Impersonate }
    catch { return 'UNKNOWN' }
    if(!$worker){return 'ABSENT'}
    if($workerStartTicks -and $worker.CreationDate){
        try {
            $date=if($worker.CreationDate -is [DateTime]){$worker.CreationDate}else{$worker.ConvertToDateTime($worker.CreationDate)}
            # DMTF timestamps have microsecond precision; native StartTime
            # can retain a further 100-nanosecond digit.
            if([Math]::Abs($date.ToUniversalTime().Ticks-[long]$workerStartTicks) -ge 10){return 'REUSED'}
            return 'RUNNING'
        }catch{return 'UNKNOWN'}
    }
    if([string]::IsNullOrWhiteSpace([string]$worker.CommandLine)){return 'UNKNOWN'}
    if([string]$worker.CommandLine -like ('*'+$remoteRoot+'*')){return 'RUNNING'}
    return 'UNKNOWN'
}
function Share-Path($relative) {
    # Provider-qualified UNC for cmdlets; never combine a PSDrive with a UNC root.
    return 'Microsoft.PowerShell.Core\FileSystem::'+[IO.Path]::Combine($uncJobRoot,$relative)
}
function Share-Layout($name,$path) {
    $localRoot=[IO.Path]::GetFullPath([string]$path).TrimEnd('\')
    if(!$workRoot.Equals($localRoot,[StringComparison]::OrdinalIgnoreCase) -and !$workRoot.StartsWith($localRoot+'\',[StringComparison]::OrdinalIgnoreCase)){throw ('Share does not cover '+$workRoot)}
    $suffix=$workRoot.Substring($localRoot.Length).TrimStart('\')
    $networkRoot='\\'+$request.host+'\'+$name
    return [pscustomobject]@{name=$name;suffix=$suffix;networkRoot=$networkRoot;job=[IO.Path]::Combine([IO.Path]::Combine($networkRoot,$suffix),('diagnostic-remote-'+$jobId))}
}
function Connect-WorkShare {
    $configured=[string]$request.settings.workShare
    if(!$configured){$configured='auto'}
    if($configured -notmatch '^[A-Za-z0-9_. -]+\$?$' -or $configured -in @('.','..')){throw 'Invalid work share name'}
    $driveShare=$workRoot.Substring(0,1)+'$'
    $candidates=if($configured -eq 'auto'){
        if($driveShare -eq 'D$'){@('Temp','DTemp',$driveShare)}else{@('Temp',$driveShare)}
    }else{@($configured)}
    $failures=New-Object 'Collections.Generic.List[string]'
    foreach($name in $candidates) {
        try {
            $shares=@(Get-WmiObject Win32_Share -Filter ("Name='"+$name+"'") -ComputerName $request.host -Credential $credential -Authentication PacketPrivacy -Impersonation Impersonate)
            if($shares.Count -ne 1){throw 'Share not found'}
            $layout=Share-Layout $name $shares[0].Path
            [void](New-PSDrive -Name DiagnosticShare -PSProvider FileSystem -Root $layout.networkRoot -Credential $credential)
            # Verify access before selecting this share; a missing Temp can
            # be created only if the existing parent share allows it.
            [void][IO.Directory]::CreateDirectory([IO.Path]::Combine($layout.networkRoot,$layout.suffix))
            return $layout
        } catch {
            $failures.Add($name+': '+$_.Exception.Message)
            Remove-PSDrive -Name DiagnosticShare -ErrorAction SilentlyContinue
        }
    }
    throw ($workRoot+' SMB erisimi yok. Hesaba ozel paylasim/dosya yetkisi ve WMI/DCOM yetkisi gerekir. '+($failures -join '; '))
}
function Select-WorkSpace {
    # Query server disks, never the client's disks. Reserve 256 MiB and leave
    # another 64 MiB for initial diagnostics before accepting a work volume.
    $requiredBytes=320MB
    $failures=New-Object 'Collections.Generic.List[string]'
    foreach($candidateRoot in @('C:\Temp','D:\Temp')){
        try{
            $drive=$candidateRoot.Substring(0,2)
            $disk=Get-WmiObject Win32_LogicalDisk -Filter ("DeviceID='"+$drive+"'") -ComputerName $request.host -Credential $credential -Authentication PacketPrivacy -Impersonation Impersonate
            if(!$disk -or $null -eq $disk.FreeSpace){throw 'Disk bulunamadi veya bos alan okunamadi'}
            if([long]$disk.FreeSpace -lt $requiredBytes){throw ('LOW_DISK_SPACE: en az 320 MiB bos alan gerekli; mevcut '+[long]$disk.FreeSpace+' bayt')}
            $script:workRoot=$candidateRoot
            $layout=Connect-WorkShare
            if($candidateRoot -eq 'D:\Temp'){
                Emit @{event='progress';message=('C:\Temp kullanilamadi; D:\Temp deneniyor. '+($failures -join '; '))}
            }
            return $layout
        }catch{
            $failures.Add($candidateRoot+': '+$_.Exception.Message)
            Remove-PSDrive -Name DiagnosticShare -ErrorAction SilentlyContinue
        }
    }
    throw ('Sunucuda uygun gecici alan yok. '+($failures -join '; '))
}
function Cleanup([bool]$requireCompletion=$true) {
    if(!$shareRoot -or !$completed){return}
    if(!(Test-Path -LiteralPath $shareRoot -PathType Container)){return}
    $expected=[IO.Path]::Combine([IO.Path]::Combine(('\\'+$request.host+'\'+$selectedShare),$selectedShareSuffix),('diagnostic-remote-'+$jobId))
    $resolved=$ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($shareRoot)
    if(![IO.Path]::GetFullPath($resolved).Equals([IO.Path]::GetFullPath($expected),[StringComparison]::OrdinalIgnoreCase)){throw 'Cleanup absolute path mismatch'}
    if((Get-Item -LiteralPath $shareRoot).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Cleanup reparse point rejected'}
    if($requireCompletion){
        $marker=Get-Content -LiteralPath (Share-Path 'complete.json') -Raw -Encoding UTF8|ConvertFrom-Json
        if($marker.jobId -ne $jobId){throw 'Cleanup marker mismatch'}
    }
    if(@(Get-ChildItem -LiteralPath $shareRoot -Recurse -Force|Where-Object{$_.Attributes -band [IO.FileAttributes]::ReparsePoint}).Count -gt 0){throw 'Cleanup contains reparse point'}
    # Absolute target is verified inside our unique selected Temp job directory.
    [IO.Directory]::Delete($resolved,$true)
}
function Cleanup-FailedJob {
    if(!$jobCreated -or !$uncJobRoot){return}
    if(![IO.Directory]::Exists($uncJobRoot)){return}
    if(!$workerLaunchAttempted){$script:completed=$true;Cleanup $false;return}
    [IO.File]::WriteAllText([IO.Path]::Combine($uncJobRoot,'cancel.txt'),'cancel')
    $cleanupWatch=[Diagnostics.Stopwatch]::StartNew()
    while($cleanupWatch.Elapsed.TotalSeconds -lt 45){
        if(![IO.Directory]::Exists($uncJobRoot)){return}
        Read-WorkerIdentity
        $status=Worker-Status
        if($status -eq 'ABSENT' -or $status -eq 'REUSED'){$script:completed=$true;Cleanup $false;return}
        Start-Sleep -Milliseconds 500
    }
    throw 'Yardimci cikisi dogrulanamadi; iptal istendi. Sunucu yardimcisi kendi klasorunu temizlemeyi deneyecek.'
}
try {
    Emit @{event='progress';message='Sunucuya WMI/SMB ile baglaniliyor...'}
    $machine=Get-WmiObject Win32_ComputerSystem -ComputerName $request.host -Credential $credential -Authentication PacketPrivacy -Impersonation Impersonate
    $remoteClass=Get-WmiObject -List -Class Win32_Process -ComputerName $request.host -Credential $credential -Authentication PacketPrivacy -Impersonation Impersonate
    $phase='SMB paylasim erisimi'
    $workShare=Select-WorkSpace
    $remoteRoot=[IO.Path]::Combine($workRoot,('diagnostic-remote-'+$jobId))
    $selectedShare=$workShare.name;$selectedShareSuffix=$workShare.suffix
    $phase='SMB gecici klasor'
    $uncJobRoot=$workShare.job
    $shareRoot=Share-Path ''
    [void][IO.Directory]::CreateDirectory($uncJobRoot)
    $jobCreated=$true
    # Restrict the temporary server folder before any scripts or diagnostics land.
    $loginName=($request.username -split '\\')[-1]
    if($loginName -notmatch "^[A-Za-z0-9_.@ -]+$"){throw 'Unsupported Windows account name'}
    $account=@(Get-WmiObject Win32_UserAccount -Filter ("Name='"+$loginName+"'") -ComputerName $request.host -Credential $credential -Authentication PacketPrivacy -Impersonation Impersonate)
    $domain=if($request.username.Contains('\')){($request.username -split '\\')[0]}else{$machine.Name}
    if($domain -eq '.'){$domain=$machine.Name}
    $owner=@($account|Where-Object{$_.Domain -ieq $domain})
    if($owner.Count -ne 1){throw 'Remote account SID could not be confirmed; temporary ACL not changed'}
    $acl=New-Object Security.AccessControl.DirectorySecurity
    $sid=New-Object Security.Principal.SecurityIdentifier($owner[0].SID)
    $acl.SetOwner($sid);$acl.SetAccessRuleProtection($true,$false)
    foreach($identitySid in @($sid,(New-Object Security.Principal.SecurityIdentifier('S-1-5-32-544')),(New-Object Security.Principal.SecurityIdentifier('S-1-5-18')))){
        $rule=New-Object Security.AccessControl.FileSystemAccessRule($identitySid,'FullControl','ContainerInherit,ObjectInherit','None','Allow');$acl.AddAccessRule($rule)
    }
    $phase='Gecici klasor yetkileri'
    Set-Acl -LiteralPath $shareRoot -AclObject $acl
    $phase='Yardimci dosyalari'
    $request.settings|Add-Member -NotePropertyName jobId -NotePropertyValue $jobId -Force
    $request.settings|Add-Member -NotePropertyName clientHeartbeatRequired -NotePropertyValue $true -Force
    $request.settings|Add-Member -NotePropertyName nonInteractiveTransport -NotePropertyValue $true -Force
    $request.settings|Add-Member -NotePropertyName interactiveUserSid -NotePropertyValue $owner[0].SID -Force
    $request.settings|Add-Member -NotePropertyName interactiveUserName -NotePropertyValue ($domain+'\'+$loginName) -Force
    foreach($resource in $request.resources.PSObject.Properties){
        if($resource.Name -notin @('remote-worker.ps1','remote-batch.ps1','process-scan.ps1','system-info.ps1','resource-monitor.ps1','remote-extras.ps1','remote-ping.ps1','remote-vxworks.ps1')){throw 'Unknown helper resource'}
        $body=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($resource.Value))
        [IO.File]::WriteAllText([IO.Path]::Combine($uncJobRoot,$resource.Name),$body,(New-Object Text.UTF8Encoding($true)))
    }
    $settingsFile=[IO.Path]::Combine($uncJobRoot,'request.json')
    [IO.File]::WriteAllText($settingsFile,($request.settings|ConvertTo-Json -Depth 12),(New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText([IO.Path]::Combine($uncJobRoot,'client-heartbeat.txt'),'0')
    $command='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "'+$remoteRoot+'\remote-worker.ps1" -RequestFile "'+$remoteRoot+'\request.json"'
    $phase='WMI yardimci baslatma'
    $workerLaunchAttempted=$true
    $created=$remoteClass.Create($command,$null,$null)
    if($created.ReturnValue -ne 0){$workerLaunchAttempted=$false;throw ('WMI Create failed: '+$created.ReturnValue)}
    $workerPid=[long]$created.ProcessId
    $watch=[Diagnostics.Stopwatch]::StartNew();$lastProgress='';$nextLifeCheck=10;$nextHeartbeat=5
    $phase='Sunucu sonucunu bekleme'
    $marker=Share-Path 'complete.json'
    while(!(Test-Path -LiteralPath $marker -PathType Leaf)){
        if($watch.Elapsed.TotalSeconds -ge $nextHeartbeat){
            [IO.File]::WriteAllText([IO.Path]::Combine($uncJobRoot,'client-heartbeat.txt'),[Guid]::NewGuid().ToString())
            $nextHeartbeat=$watch.Elapsed.TotalSeconds+5
        }
        if($watch.Elapsed.TotalSeconds -ge ($request.settings.timeoutSeconds+30)){
            [IO.File]::WriteAllText([IO.Path]::Combine($uncJobRoot,'cancel.txt'),'cancel')
            throw ('REMOTE_TIMEOUT; temporary directory: '+$remoteRoot)
        }
        if($watch.Elapsed.TotalSeconds -ge $nextLifeCheck){
            Read-WorkerIdentity
            $status=Worker-Status
            # Completion and the total deadline decide collection outcome.
            # A transient absent result or unreadable metadata must not discard
            # an otherwise progressing remote job.
            $nextLifeCheck=$watch.Elapsed.TotalSeconds+10
        }
        $progressFile=Share-Path 'progress.json'
        if(Test-Path -LiteralPath $progressFile){try{
            $progress=Get-Content -LiteralPath $progressFile -Raw -Encoding UTF8
            if($progress -ne $lastProgress){
                $progressState=$progress|ConvertFrom-Json
                $message=if($progressState.phase -eq 'dump'){'PID '+$progressState.pid+': dump '+$progressState.sample+'/3; basarili '+$progressState.dumpCount}else{[string]$progressState.message}
                if($message){Emit @{event='progress';message=$message}}
                $lastProgress=$progress
            }
        }catch{}}
        Start-Sleep -Milliseconds 500
    }
    $manifest=Get-Content -LiteralPath $marker -Raw -Encoding UTF8|ConvertFrom-Json
    if($manifest.jobId -ne $jobId){throw 'Remote job identity mismatch'}
    # Completion is written at the very end; wait for helper exit before cleanup.
    for($attempt=0;$attempt -lt 20;$attempt++){
        Read-WorkerIdentity
        $status=Worker-Status
        if($status -eq 'ABSENT' -or $status -eq 'REUSED'){$completed=$true;break}
        Start-Sleep -Milliseconds 250
    }
    if(!$completed){$warnings+=,('Remote helper exit not confirmed; temporary files preserved: '+$remoteRoot)}
    $phase='Dosya aktarimi'
    Emit @{event='progress';message='Dosyalar indiriliyor; SHA-256 ve boyutlari dogrulaniyor...'}
    $destinationRoot=[IO.Path]::GetFullPath($request.localDirectory)
    [void][IO.Directory]::CreateDirectory($destinationRoot)
    foreach($file in $manifest.files){
        $relative=[string]$file.path
        if([IO.Path]::IsPathRooted($relative) -or $relative.Contains(':') -or '..' -in ($relative -split '[/\\]')){throw 'Unsafe downloaded path'}
        $destination=[IO.Path]::GetFullPath([IO.Path]::Combine($destinationRoot,$relative))
        if(!$destination.StartsWith($destinationRoot+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Download escapes staging'}
        [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
        Copy-Item -LiteralPath (Share-Path ('data\'+$relative)) -Destination $destination
        if((Get-Item -LiteralPath $destination).Length -ne $file.size -or (Sha256 $destination) -ne $file.sha256){throw ('Download verification failed: '+$relative)}
    }
    try{Cleanup}catch{$warnings+=,('Remote cleanup failed: '+$_.Exception.Message+'; directory: '+$remoteRoot)}
    Emit @{event='result';success=$true;server=$request.host;machine=$machine.Name;remoteDirectory=$remoteRoot;cleanupSucceeded=($completed -and !(Test-Path -LiteralPath $shareRoot));warnings=$warnings;result=$manifest}
}catch{
    $originalError='['+$phase+'] '+$_.Exception.Message
    try{Cleanup-FailedJob}catch{$warnings+=,('Gecici is temizligi: '+$_.Exception.Message+'; directory: '+$remoteRoot)}
    Emit @{event='result';success=$false;error=$originalError;remoteDirectory=$remoteRoot;warnings=$warnings}
    exit 1
}finally{
    Remove-PSDrive -Name DiagnosticShare -ErrorAction SilentlyContinue
    $credential=$null;$secure.Dispose()
}
