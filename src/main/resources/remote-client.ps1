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
$activityOffsets=@{}
$activityWarnings=@{}
function Emit($value){[Console]::Out.WriteLine(($value|ConvertTo-Json -Depth 20 -Compress))}
function Activity-Warning([string]$key,[string]$message){
    if(!$activityWarnings.ContainsKey($key)){
        $activityWarnings[$key]=$true;$script:warnings+=,$message
        Emit @{event='progress';message=('[Gunluk] UYARI: '+$message)}
    }
}
function Forward-Activity {
    $script:activityBytesRead=0
    $entries=New-Object 'Collections.Generic.List[object]'
    try{$journals=@(Get-ChildItem -LiteralPath (Share-Path '') -Filter 'activity-*.jsonl' -File -ErrorAction Stop)}
    catch{Activity-Warning 'list' ('Sunucu islem gunlukleri listelenemedi; sonraki kontrolde tekrar denenecek. '+$_.Exception.Message);return}
    foreach($file in $journals){
        $offset=if($activityOffsets.ContainsKey($file.Name)){[long]$activityOffsets[$file.Name]}else{0L}
        $inputLog=$null
        try{
            $inputLog=[IO.File]::Open($file.FullName,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
            if($inputLog.Length -lt $offset){throw 'Activity log was truncated'}
            $inputLog.Position=$offset;$count=[int][Math]::Min(1MB,$inputLog.Length-$offset)
            if(!$count){continue}
            $bytes=New-Object byte[] $count;$read=0
            while($read -lt $count){$n=$inputLog.Read($bytes,$read,$count-$read);if(!$n){break};$read+=$n}
            $lastNewline=-1;for($i=$read-1;$i -ge 0;$i--){if($bytes[$i] -eq 10){$lastNewline=$i;break}}
            if($lastNewline -lt 0){continue}
            $body=[Text.Encoding]::UTF8.GetString($bytes,0,$lastNewline+1)
            foreach($line in ($body -split "`n")){
                if(!$line.Trim()){continue}
                try{
                    $entry=$line|ConvertFrom-Json
                    if([string]::IsNullOrWhiteSpace([string]$entry.message)){throw 'Activity message missing'}
                    $entries.Add([pscustomobject]@{timestampUtc=$entry.timestampUtc;readOrder=$entries.Count;message=[string]$entry.message;percent=$entry.percent;stage=$entry.stage})
                }catch{Activity-Warning ($file.Name+':record') ($file.Name+' icinde gecersiz gunluk satiri atlandi; diger kayitlar ve rapor toplama devam ediyor.')}
            }
            $activityOffsets[$file.Name]=$offset+$lastNewline+1
            $script:activityBytesRead+=$lastNewline+1
        }catch{Activity-Warning ($file.Name+':read') ($file.Name+' okunamadi; sonraki kontrolde tekrar denenecek. '+$_.Exception.Message)}
        finally{if($inputLog){$inputLog.Dispose()}}
    }
    foreach($entry in @($entries|Sort-Object timestampUtc,readOrder)){
        Emit @{event='progress';message=[string]$entry.message;percent=$entry.percent;stage=$entry.stage}
    }
}
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
            Emit @{event='progress';message=('[SMB] '+$name+' paylasimi kontrol ediliyor; erisim deneniyor...')}
            $shares=@(Get-WmiObject Win32_Share -Filter ("Name='"+$name+"'") -ComputerName $request.host -Credential $credential -Authentication PacketPrivacy -Impersonation Impersonate)
            if($shares.Count -ne 1){throw 'Share not found'}
            $layout=Share-Layout $name $shares[0].Path
            [void](New-PSDrive -Name DiagnosticShare -PSProvider FileSystem -Root $layout.networkRoot -Credential $credential)
            # Verify access before selecting this share; a missing Temp can
            # be created only if the existing parent share allows it.
            [void][IO.Directory]::CreateDirectory([IO.Path]::Combine($layout.networkRoot,$layout.suffix))
            Emit @{event='progress';message=('[SMB] BAGLANDI: '+$name+' paylasimi ve gecici kok klasorune erisim dogrulandi; is klasorune yazma henuz kontrol edilecek.')}
            return $layout
        } catch {
            Emit @{event='progress';message=('[SMB] '+$name+' paylasimina BAGLANILAMADI: '+$_.Exception.Message)}
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
            Emit @{event='progress';message=('[Disk] Sunucudaki '+$candidateRoot+' icin bos alan kontrol ediliyor...')}
            $drive=$candidateRoot.Substring(0,2)
            $disk=Get-WmiObject Win32_LogicalDisk -Filter ("DeviceID='"+$drive+"'") -ComputerName $request.host -Credential $credential -Authentication PacketPrivacy -Impersonation Impersonate
            if(!$disk -or $null -eq $disk.FreeSpace){throw 'Disk bulunamadi veya bos alan okunamadi'}
            if([long]$disk.FreeSpace -lt $requiredBytes){throw ('LOW_DISK_SPACE: en az 320 MiB bos alan gerekli; mevcut '+[long]$disk.FreeSpace+' bayt')}
            Emit @{event='progress';message=('[Disk] Bos alan yeterli: '+[Math]::Round([long]$disk.FreeSpace/1MB)+' MiB.')}
            $script:workRoot=$candidateRoot
            $layout=Connect-WorkShare
            if($candidateRoot -eq 'D:\Temp'){
                Emit @{event='progress';message=('C:\Temp kullanilamadi; D:\Temp deneniyor. '+($failures -join '; '))}
            }
            return $layout
        }catch{
            Emit @{event='progress';message=('[Disk/SMB] '+$candidateRoot+' kullanilamadi: '+$_.Exception.Message)}
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
    # SMB/antivirus can briefly retain directory handles after all streams close.
    # Retry only this exact verified UUID directory; never close foreign handles.
    for($retry=0;$retry -lt 11;$retry++){
        try{if([IO.Directory]::Exists($resolved)){[IO.Directory]::Delete($resolved,$true)};return}
        catch [IO.IOException]{if($retry -eq 10){throw};Start-Sleep -Milliseconds 500}
    }
}
function Cleanup-FailedJob {
    if(!$jobCreated -or !$uncJobRoot){Emit @{event='progress';message='[Temizlik] Bu is icin sunucuda gecici klasor olusturulmadi; temizlenecek is klasoru yok.'};return}
    if(![IO.Directory]::Exists($uncJobRoot)){Emit @{event='progress';message='[Temizlik] Sunucudaki is klasoru zaten kaldirilmis.'};return}
    Emit @{event='progress';message='[Temizlik] Basarisiz is icin sunucudaki gecici klasor temizligi basladi.'}
    if(!$workerLaunchAttempted){$script:completed=$true;Cleanup $false;Emit @{event='progress';message='[Temizlik] Yardimci baslatilmadi; sunucudaki gecici is klasoru temizlendi.'};return}
    [IO.File]::WriteAllText([IO.Path]::Combine($uncJobRoot,'cancel.txt'),'cancel')
    Emit @{event='progress';message='[Temizlik] Sunucu yardimcisina iptal istendi; cikisi ve kendi dosyalarini temizlemesi bekleniyor.'}
    $cleanupWatch=[Diagnostics.Stopwatch]::StartNew()
    while($cleanupWatch.Elapsed.TotalSeconds -lt 45){
        if(![IO.Directory]::Exists($uncJobRoot)){Emit @{event='progress';message='[Temizlik] Sunucu yardimcisi is klasorunu temizledi.'};return}
        Read-WorkerIdentity
        $status=Worker-Status
        if($status -eq 'ABSENT' -or $status -eq 'REUSED'){$script:completed=$true;Cleanup $false;Emit @{event='progress';message='[Temizlik] Yardimcinin cikisi dogrulandi; basarisiz isin gecici klasoru temizlendi.'};return}
        Start-Sleep -Milliseconds 500
    }
    throw 'Yardimci cikisi dogrulanamadi; iptal istendi. Sunucu yardimcisi kendi klasorunu temizlemeyi deneyecek.'
}
try {
    Emit @{event='progress';message='Sunucuya WMI/SMB ile baglaniliyor...'}
    $phase='WMI/DCOM baglantisi'
    Emit @{event='progress';message='[WMI] Sunucu sistem bilgisi ve uzaktan islem baslatma erisimi kontrol ediliyor...'}
    $machine=Get-WmiObject Win32_ComputerSystem -ComputerName $request.host -Credential $credential -Authentication PacketPrivacy -Impersonation Impersonate
    $remoteClass=Get-WmiObject -List -Class Win32_Process -ComputerName $request.host -Credential $credential -Authentication PacketPrivacy -Impersonation Impersonate
    if(!$machine -or !$remoteClass){throw 'WMI server system/process metadata missing'}
    Emit @{event='progress';message='[WMI] BAGLANDI: sunucu sistem bilgisi ve islem sinifi erisimi dogrulandi.';percent=7;stage='Sunucu bağlantısı hazırlanıyor...'}
    $phase='SMB paylasim erisimi'
    $workShare=Select-WorkSpace
    $remoteRoot=[IO.Path]::Combine($workRoot,('diagnostic-remote-'+$jobId))
    $selectedShare=$workShare.name;$selectedShareSuffix=$workShare.suffix
    $phase='SMB gecici klasor'
    $uncJobRoot=$workShare.job
    $shareRoot=Share-Path ''
    [void][IO.Directory]::CreateDirectory($uncJobRoot)
    $jobCreated=$true
    Emit @{event='progress';message='[SMB] YAZMA DOGRULANDI: bu rapora ait yeni gecici is klasoru olusturuldu.'}
    Emit @{event='progress';message='[Hazirlik] Sunucuda bu rapora ait gecici is klasoru olusturuldu.'}
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
    Emit @{event='progress';message='[Hazirlik] Gecici is klasorunun hesap/yetki kontrolu tamamlandi.'}
    $phase='Yardimci dosyalari'
    $request.settings|Add-Member -NotePropertyName jobId -NotePropertyValue $jobId -Force
    $request.settings|Add-Member -NotePropertyName clientHeartbeatRequired -NotePropertyValue $true -Force
    $request.settings|Add-Member -NotePropertyName nonInteractiveTransport -NotePropertyValue $true -Force
    $request.settings|Add-Member -NotePropertyName interactiveUserSid -NotePropertyValue $owner[0].SID -Force
    $request.settings|Add-Member -NotePropertyName interactiveUserName -NotePropertyValue ($domain+'\'+$loginName) -Force
    foreach($resource in $request.resources.PSObject.Properties){
        if($resource.Name -notin @('remote-worker.ps1','remote-batch.ps1','process-scan.ps1','system-info.ps1','resource-monitor.ps1','remote-extras.ps1','remote-ping.ps1','remote-vxworks.ps1','jdk-discovery.ps1','remote-windows.ps1')){throw 'Unknown helper resource'}
        $body=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($resource.Value))
        [IO.File]::WriteAllText([IO.Path]::Combine($uncJobRoot,$resource.Name),$body,(New-Object Text.UTF8Encoding($true)))
        Emit @{event='progress';message=('[Hazirlik] Sunucu yardimcisi kopyalandi: '+$resource.Name)}
    }
    $settingsFile=[IO.Path]::Combine($uncJobRoot,'request.json')
    [IO.File]::WriteAllText($settingsFile,($request.settings|ConvertTo-Json -Depth 12),(New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText([IO.Path]::Combine($uncJobRoot,'client-heartbeat.txt'),'0')
    $command='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "'+$remoteRoot+'\remote-worker.ps1" -RequestFile "'+$remoteRoot+'\request.json"'
    $phase='WMI yardimci baslatma'
    Emit @{event='progress';message='[WMI] Sunucudaki toplama yardimcisi baslatiliyor...'}
    $workerLaunchAttempted=$true
    $created=$remoteClass.Create($command,'C:\Windows',$null)
    if($created.ReturnValue -ne 0){$workerLaunchAttempted=$false;throw ('WMI Create failed: '+$created.ReturnValue)}
    $workerPid=[long]$created.ProcessId
    Emit @{event='progress';message=('[WMI] Toplama yardimcisi baslatildi; PID '+$workerPid+'. Sunucu adimlari izleniyor.');percent=10;stage='Sunucuda tanılama başlatıldı...'}
    $watch=[Diagnostics.Stopwatch]::StartNew();$lastProgress='';$nextLifeCheck=10;$nextHeartbeat=5
    $phase='Sunucu sonucunu bekleme'
    $marker=Share-Path 'complete.json'
    while(!(Test-Path -LiteralPath $marker -PathType Leaf)){
        Forward-Activity
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
        if(!$activityOffsets.Count -and (Test-Path -LiteralPath $progressFile)){try{
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
    do{Forward-Activity}while($activityBytesRead -gt 0)
    Emit @{event='progress';message='[Sunucu] Toplama sonuc isareti alindi ve rapor kimligi dogrulandi.'}
    # Completion is written at the very end; wait for helper exit before cleanup.
    for($attempt=0;$attempt -lt 20;$attempt++){
        Read-WorkerIdentity
        $status=Worker-Status
        if($status -eq 'ABSENT' -or $status -eq 'REUSED'){$completed=$true;break}
        Start-Sleep -Milliseconds 250
    }
    if(!$completed){$warnings+=,('Remote helper exit not confirmed; temporary files preserved: '+$remoteRoot)}
    $phase='Dosya aktarimi'
    Emit @{event='progress';message='Dosyalar indiriliyor; SHA-256 ve boyutlari dogrulaniyor...';percent=72;stage='Dosyalar indiriliyor ve doğrulanıyor...'}
    $destinationRoot=[IO.Path]::GetFullPath($request.localDirectory)
    [void][IO.Directory]::CreateDirectory($destinationRoot)
    $downloaded=0
    foreach($file in $manifest.files){
        $relative=[string]$file.path
        if([IO.Path]::IsPathRooted($relative) -or $relative.Contains(':') -or '..' -in ($relative -split '[/\\]')){throw 'Unsafe downloaded path'}
        $destination=[IO.Path]::GetFullPath([IO.Path]::Combine($destinationRoot,$relative))
        if(!$destination.StartsWith($destinationRoot+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Download escapes staging'}
        [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
        Copy-Item -LiteralPath (Share-Path ('data\'+$relative)) -Destination $destination
        if((Get-Item -LiteralPath $destination).Length -ne $file.size -or (Sha256 $destination) -ne $file.sha256){throw ('Download verification failed: '+$relative)}
        $downloaded++
        Emit @{event='progress';message=('[Aktarim] '+$downloaded+'/'+@($manifest.files).Count+' | indirildi ve SHA-256/boyut dogrulandi: '+$relative);percent=[int][Math]::Floor(72+13*$downloaded/[Math]::Max(1,@($manifest.files).Count));stage=('Dosyalar indiriliyor ve doğrulanıyor ('+$downloaded+'/'+@($manifest.files).Count+')...')}
    }
    Emit @{event='progress';message=('[Aktarim] TAMAMLANDI: '+$downloaded+' dosya indirildi; butunluk kontrolleri gecti.')}
    Emit @{event='progress';message='[Temizlik] Sunucudaki bu rapora ait gecici is klasoru temizleniyor...'}
    try{Cleanup}catch{$warnings+=,('Remote cleanup failed: '+$_.Exception.Message+'; directory: '+$remoteRoot)}
    Emit @{event='progress';message=$(if($completed -and !(Test-Path -LiteralPath $shareRoot)){'[Temizlik] Sunucudaki gecici is klasoru temizlendi.'}else{'[Temizlik] Sunucu gecici klasoru temizlenemedi veya yardimcinin cikisi dogrulanamadi; uyari kaydedildi.'})}
    Emit @{event='result';success=$true;server=$request.host;machine=$machine.Name;remoteDirectory=$remoteRoot;cleanupSucceeded=($completed -and !(Test-Path -LiteralPath $shareRoot));warnings=$warnings;result=$manifest}
}catch{
    $originalError='['+$phase+'] '+$_.Exception.Message
    Emit @{event='progress';message=('[HATA] '+$phase+' BASARISIZ: '+$_.Exception.Message)}
    if($phase -eq 'WMI/DCOM baglantisi'){Emit @{event='progress';message='[WMI] BAGLANILAMADI; sunucuda toplama baslatilmadi.'}}
    try{Cleanup-FailedJob}catch{$warnings+=,('Gecici is temizligi: '+$_.Exception.Message+'; directory: '+$remoteRoot);Emit @{event='progress';message=('[Temizlik] BASARISIZ: '+$_.Exception.Message)}}
    Emit @{event='result';success=$false;error=$originalError;remoteDirectory=$remoteRoot;warnings=$warnings}
    exit 1
}finally{
    Remove-PSDrive -Name DiagnosticShare -ErrorAction SilentlyContinue
    $credential=$null;$secure.Dispose()
}
