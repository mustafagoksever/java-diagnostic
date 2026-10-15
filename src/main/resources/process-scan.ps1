$errors = New-Object System.Collections.Generic.List[string]
$source = 'CIM'
function Launch-Identity([string]$line) {
    $tokens=@([regex]::Matches($line,'(?:[^\s"]+|"[^"]*")+') | ForEach-Object {$_.Value.Replace('"','')})
    $main=$null;$app=$null
    for($i=1;$i -lt $tokens.Count;$i++) {
        $token=$tokens[$i]
        if($token -eq '-jar' -and $i+1 -lt $tokens.Count){$main=[IO.Path]::GetFileName($tokens[$i+1]);$app=$main;break}
        if($token -in @('-cp','-classpath','--class-path','--module-path','-p')){$i++;continue}
        if($token.StartsWith('-')){continue}
        if($token -match '^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*$'){$main=$token;$app=$token}
        break
    }
    # Equinox launcher JAR names repeat across applications; an explicitly
    # supplied OSGi application ID is more useful than the launcher filename.
    for($i=1;$i+1 -lt $tokens.Count;$i++) {
        if($tokens[$i] -eq '-application' -and $tokens[$i+1] -match '^[A-Za-z0-9_.-]+$'){$app=$tokens[$i+1];break}
    }
    return [pscustomobject]@{mainClass=$main;applicationName=$app}
}
try { $all = @(Get-CimInstance Win32_Process) }
catch {
    $errors.Add('CIM: ' + $_.Exception.Message)
    $source = 'WMI'
    $all = @(Get-WmiObject Win32_Process)
}
$denied = 0
$found = New-Object System.Collections.Generic.List[object]
foreach ($p in $all) {
    $jvmLibraryPath=$null
    $isJava = $p.Name -match '^(java|javaw|javaws|j9)\.exe$' -or $additionalNames -contains $p.Name
    $discovery = $source + ':name'
    if (!$isJava -or $p.Name -notmatch '^(java|javaw|javaws|j9)\.exe$') {
        try {
            $native = Get-Process -Id $p.ProcessId -ErrorAction Stop
            foreach ($module in $native.Modules) {
                if ($module.ModuleName -ieq 'jvm.dll' -or $module.ModuleName -ieq 'j9vm29.dll') {
                    $isJava = $true; $jvmLibraryPath=$module.FileName; $discovery = $source + ':module'; break
                }
            }
        } catch { $denied++ }
    }
    if (!$isJava) { continue }
    $owner = $null; $ownerError = $null; $startTime = $null; $startTicks = $null
    try {
        if ($source -eq 'CIM') { $o = Invoke-CimMethod -InputObject $p -MethodName GetOwner }
        else { $o = $p.GetOwner() }
        if ($o.ReturnValue -eq 0) { $owner = $o.Domain + '\' + $o.User }
        else { $ownerError = 'GetOwner return code ' + $o.ReturnValue }
    } catch { $ownerError = $_.Exception.Message }
    try {
        if ($source -eq 'CIM') { $date = $p.CreationDate }
        else { $date = $p.ConvertToDateTime($p.CreationDate) }
        $startTime = $date.ToUniversalTime().ToString('o')
        $startTicks = $date.ToUniversalTime().Ticks.ToString()
    } catch { $errors.Add('CreationDate PID ' + $p.ProcessId + ': ' + $_.Exception.Message) }
    $version = $null
    # Return only the classification, never the raw command line (which may contain secrets).
    $collectorProcess = $false
    $launch = [string]$p.CommandLine
    $launchIdentity=Launch-Identity $launch
    if ($launch -match '(?:^|\s)-Ddiagnostic\.collector=true(?=\s|$)' -or $launch -match '(?:^|\s|")tr\.diagnostic\.Main(?=\s|"|$)') {
        $collectorProcess = $true
    } elseif ($launch -match '(?:^|\s)-jar\s+(?:"([^"]+)"|(\S+))') {
        $launchedJar = $(if ($matches[1]) { $matches[1] } else { $matches[2] })
        $collectorProcess = [IO.Path]::GetFileName($launchedJar) -match '^java-diagnostic-collector(?:-[0-9][0-9A-Za-z.-]*)?\.jar$'
    }
    if ($p.ExecutablePath -and $p.Name -match '^(java|javaw)\.exe$') {
        try { $version = [Diagnostics.FileVersionInfo]::GetVersionInfo($p.ExecutablePath).ProductVersion }
        catch { $errors.Add('FileVersion PID ' + $p.ProcessId + ': ' + $_.Exception.Message) }
    }
    $found.Add([pscustomobject]@{
        pid = [long]$p.ProcessId; processName = $p.Name; owner = $owner; ownerError = $ownerError
        sessionId = [int]$p.SessionId; startTime = $startTime; startTicks = $startTicks
        executablePath = $p.ExecutablePath; jvmLibraryPath=$jvmLibraryPath; javaVersion = $version
        mainClass = $launchIdentity.mainClass; applicationName = $launchIdentity.applicationName
        versionSource = $(if ($version) { 'executable-product-version (best effort)' } else { 'unknown' })
        discoverySource = $discovery
        collectorProcess = $collectorProcess
    })
}
if ($denied -gt 0) { $errors.Add('Module inspection unavailable for ' + $denied + ' processes; inaccessible renamed/embedded JVMs may be missing. Configure additionalProcessNames or retry elevated.') }
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
[pscustomobject]@{
    processes = @($found.ToArray()); errors = @($errors.ToArray())
    elevated = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    collectorIdentity = $identity.Name
} | ConvertTo-Json -Depth 6 -Compress
