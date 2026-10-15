# Shared local/remote, read-only JDK discovery. No target application is launched.
function Get-JavaMachine([string]$path) {
    $stream=$null;$reader=$null
    try {
        $stream=[IO.File]::Open($path,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
        $reader=New-Object IO.BinaryReader($stream)
        if($stream.Length -lt 64 -or $reader.ReadUInt16() -ne 0x5a4d){return $null}
        $stream.Position=60;$offset=$reader.ReadInt32()
        if($offset -lt 64 -or $offset -gt 1MB -or $offset+6 -gt $stream.Length){return $null}
        $stream.Position=$offset;if($reader.ReadUInt32() -ne 0x4550){return $null}
        switch($reader.ReadUInt16()){0x14c{return 'x86'};0x8664{return 'x64'};0xaa64{return 'arm64'}}
    }catch{}finally{if($reader){$reader.Dispose()}elseif($stream){$stream.Dispose()}}
    return $null
}
function Java-Major([string]$version) {
    if($version -match '^\s*(?:java|openjdk)?\s*(?:version\s*)?"?(?:1\.)?(\d+)'){return [int]$matches[1]}
    return $null
}
function Java-RealPath([string]$path) {
    if(!$path){return $null}
    try {
        $path=[IO.Path]::GetFullPath($path.Trim('"'))
        for($n=0;$n -lt 8;$n++){
            $item=Get-Item -LiteralPath $path -ErrorAction Stop
            # PowerShell 5.1 also exposes Target for NTFS hardlinks. They have
            # no canonical target and may refer back to the same inode/path.
            if($item.LinkType -notin @('SymbolicLink','Junction') -or !$item.Target){return $item.FullName.TrimEnd('\')}
            $link=[string]@($item.Target)[0]
            $path=if([IO.Path]::IsPathRooted($link)){$link}else{Join-Path (Split-Path $path -Parent) $link}
        }
    }catch{}
    return $null
}
function Java-HomeFromFile([string]$path) {
    $path=Java-RealPath $path;if(!$path){return $null}
    $root=Split-Path $path -Parent
    if([IO.Path]::GetFileName($root) -in @('server','client','default')){$root=Split-Path $root -Parent}
    if([IO.Path]::GetFileName($root) -ieq 'bin'){$root=Split-Path $root -Parent}
    return $root
}
function Get-JavaMetadata([string]$jdkRoot,[string]$file) {
    $version=$null;$vendor=$null;$architecture=$null
    foreach($root in @($jdkRoot,(Split-Path $jdkRoot -Parent))){
        if(!$root){continue};$release=Join-Path $root 'release'
        try{if((Get-Item -LiteralPath $release -ErrorAction Stop).Length -gt 64KB){continue};$body=[IO.File]::ReadAllText($release)
            if($body -match '(?m)^JAVA_VERSION="([^"]+)"'){$version=$matches[1]}
            if($body -match '(?m)^IMPLEMENTOR="([^"]+)"'){$vendor=$matches[1]}
            if($version){break}
        }catch{}
    }
    if($file){
        $architecture=Get-JavaMachine $file
        # A service wrapper's ProductVersion identifies the wrapper, not Java.
        # Its PE architecture is still useful; release metadata above may also
        # identify a bundled runtime. Only Java binaries supply file versions.
        if([IO.Path]::GetFileName($file) -in @('java.exe','javaw.exe','javaws.exe','j9.exe','jvm.dll','j9vm29.dll')){
            try{$info=[Diagnostics.FileVersionInfo]::GetVersionInfo($file);if(!$version){$version=$info.ProductVersion};if(!$vendor){$vendor=$info.CompanyName}}catch{}
        }
    }
    return [pscustomobject]@{home=$jdkRoot;javaVersion=$version;major=(Java-Major $version);vendor=$vendor;architecture=$architecture}
}
function New-JdkPlan($inventory,$target) {
    $targetHome=$null;$identity=$null
    if($target){
        $file=if($target.jvmLibraryPath){$target.jvmLibraryPath}else{$target.executablePath}
        $targetHome=Java-HomeFromFile $file
        if($targetHome){$identity=Get-JavaMetadata $targetHome $file}
        if(!$identity){$identity=[pscustomobject]@{architecture=$null;major=(Java-Major $target.javaVersion);javaVersion=$target.javaVersion;vendor=$null}}
    }
    $ranked=@();$position=0
    foreach($candidate in @($inventory.candidates)){
        $reason=$null;$score=0
        if(!@($candidate.tools.PSObject.Properties).Count){$reason='NO_DIAGNOSTIC_TOOLS'}
        if($target -and !$reason){
            if($identity.architecture -and $candidate.architecture -and $identity.architecture -ne $candidate.architecture){$reason='ARCHITECTURE_MISMATCH'}
            elseif($identity.major -and $candidate.major -and $identity.major -ne $candidate.major){$reason='JAVA_MAJOR_MISMATCH'}
            if($targetHome -and ($candidate.home -ieq $targetHome -or $candidate.home -ieq (Split-Path $targetHome -Parent))){$score+=10000}
            if($identity.javaVersion -and $identity.javaVersion -eq $candidate.javaVersion){$score+=1000}
            if($identity.vendor -and $identity.vendor -eq $candidate.vendor){$score+=100}
            if($identity.architecture -and $identity.architecture -eq $candidate.architecture){$score+=20}
            if($identity.major -and $identity.major -eq $candidate.major){$score+=50}
        }
        $ranked+=,[pscustomobject]@{home=$candidate.home;score=$score;reason=$reason;position=$position};$position++
    }
    return [pscustomobject]@{pid=if($target){$target.pid}else{0};startTicks=if($target){$target.startTicks}else{$null};architecture=if($identity){$identity.architecture}else{$null};javaVersion=if($identity){$identity.javaVersion}else{$null};candidates=@($ranked|Sort-Object @{Expression='score';Descending=$true},position)}
}
function Get-JdkInventory($roots,$targets,[switch]$OnlyProvided) {
    $homes=New-Object 'Collections.Generic.List[string]';$seen=New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    $notes=New-Object 'Collections.Generic.List[string]'
    function Add-JavaHome([string]$root){
        if(!$root -or $root -ieq 'auto' -or $homes.Count -ge 128){return}
        $root=Java-RealPath $root;if(!$root -or !(Test-Path -LiteralPath $root -PathType Container)){return}
        $hasJava=$false
        foreach($relative in @('bin\java.exe','bin\javaw.exe','jre\bin\java.exe','bin\jcmd.exe','bin\jstack.exe','bin\jstat.exe','bin\jps.exe','bin\server\jvm.dll','bin\client\jvm.dll','bin\default\j9vm29.dll')){
            if(Test-Path -LiteralPath (Join-Path $root $relative) -PathType Leaf){$hasJava=$true;break}
        }
        if(!$hasJava){return}
        if(!$seen.Add($root)){return};$homes.Add($root)
        if([IO.Path]::GetFileName($root) -ieq 'jre'){Add-JavaHome (Split-Path $root -Parent)}
    }
    foreach($target in @($targets)){foreach($file in @($target.executablePath,$target.jvmLibraryPath)){if($file){Add-JavaHome (Java-HomeFromFile $file)}}}
    foreach($root in @($roots)){Add-JavaHome $root}
    if(!$OnlyProvided){
        foreach($root in @($env:JAVA_HOME,$env:JDK_HOME)){Add-JavaHome $root}
        # A helper process bounds PATH searches, including inaccessible paths.
        $where=$null
        try{
            $start=New-Object Diagnostics.ProcessStartInfo;$start.FileName=Join-Path $env:SystemRoot 'System32\where.exe';$start.Arguments='java.exe jcmd.exe jstack.exe jstat.exe jps.exe';$start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
            $where=New-Object Diagnostics.Process;$where.StartInfo=$start;[void]$where.Start()
            $output=$where.StandardOutput.ReadToEndAsync();$errors=$where.StandardError.ReadToEndAsync()
            if(!$where.WaitForExit(2000)){$where.Kill();[void]$where.WaitForExit(1000);$notes.Add('WHERE_TIMEOUT')}
            elseif($output.Wait(1000)){foreach($line in ($output.Result -split '\r?\n')){if($line){Add-JavaHome (Java-HomeFromFile $line)}}}
        }catch{$notes.Add('WHERE_FAILED: '+$_.Exception.Message)}finally{if($where){try{if(!$where.HasExited){$where.Kill();[void]$where.WaitForExit(1000)}}catch{};$where.Dispose()}}
        foreach($view in @([Microsoft.Win32.RegistryView]::Registry64,[Microsoft.Win32.RegistryView]::Registry32)){
            $registry=$null
            try{
                $registry=[Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::LocalMachine,$view)
                foreach($branch in @('SOFTWARE\JavaSoft\Java Development Kit','SOFTWARE\JavaSoft\JDK','SOFTWARE\JavaSoft\Java Runtime Environment','SOFTWARE\JavaSoft\JRE')){
                    $key=$registry.OpenSubKey($branch)
                    if(!$key){continue}
                    try{foreach($name in @($key.GetSubKeyNames()|Select-Object -First 32)){$child=$key.OpenSubKey($name);try{if($child){Add-JavaHome ([string]$child.GetValue('JavaHome'))}}finally{if($child){$child.Dispose()}}}}finally{$key.Dispose()}
                }
            }catch{$notes.Add('REGISTRY_'+$view+': '+$_.Exception.Message)}finally{if($registry){$registry.Dispose()}}
        }
        foreach($program in @($env:ProgramFiles,${env:ProgramFiles(x86)},$env:ProgramW6432)){
            if(!$program){continue}
            foreach($vendor in @('Java','Amazon Corretto','Eclipse Adoptium','AdoptOpenJDK','BellSoft','Microsoft','Zulu')){
                $folder=Join-Path $program $vendor
                if(!(Test-Path -LiteralPath $folder -PathType Container)){continue}
                foreach($child in @(Get-ChildItem -LiteralPath $folder -Directory -ErrorAction SilentlyContinue|Select-Object -First 32)){Add-JavaHome $child.FullName}
            }
        }
    }
    $candidates=@()
    foreach($jdkRoot in $homes){
        $tools=[ordered]@{}
        foreach($tool in @('jcmd','jstack','jstat','jps')){$file=Join-Path $jdkRoot ('bin\'+$tool+'.exe');if(Test-Path -LiteralPath $file -PathType Leaf){$tools[$tool]=Java-RealPath $file}}
        $java=Join-Path $jdkRoot 'bin\java.exe';if(!(Test-Path -LiteralPath $java -PathType Leaf)){$java=Join-Path $jdkRoot 'jre\bin\java.exe'}
        $meta=Get-JavaMetadata $jdkRoot $java
        if(!$meta.architecture -and $tools.Count){$meta.architecture=Get-JavaMachine ([string]@($tools.Values)[0])}
        $meta|Add-Member NoteProperty tools ([pscustomobject]$tools);$candidates+=,$meta
    }
    $inventory=[pscustomobject]@{candidates=$candidates;plans=@();notes=@($notes.ToArray());policy='At most 128 homes, 3 compatible tool homes per PID/sample; shared sample budget = 2 * command timeout; no downloads or force attach.'}
    $inventory.plans+=,(New-JdkPlan $inventory $null)
    foreach($target in @($targets)){$inventory.plans+=,(New-JdkPlan $inventory $target)}
    return $inventory
}
