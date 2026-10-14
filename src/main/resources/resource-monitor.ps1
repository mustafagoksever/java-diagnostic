$issues = New-Object System.Collections.Generic.List[string]
function Query([string]$class) {
    try { return @(Get-CimInstance -ClassName $class) }
    catch {
        try { return @(Get-WmiObject -Class $class) }
        catch { $message = $class + ': ' + $_.Exception.Message; if (!$issues.Contains($message)) { $issues.Add($message) }; return @() }
    }
}
$machine = @(Query 'Win32_ComputerSystem' | Select-Object -First 1)
$logical = $null
if ($machine.Count -gt 0) { $logical = $machine[0].NumberOfLogicalProcessors }
$samples = New-Object System.Collections.Generic.List[object]
$watch = [Diagnostics.Stopwatch]::StartNew()
do {
    $os = @(Query 'Win32_OperatingSystem' | Select-Object TotalVisibleMemorySize, FreePhysicalMemory)
    $cpu = @(Query 'Win32_PerfFormattedData_PerfOS_Processor' | Where-Object { $_.Name -eq '_Total' } | Select-Object Name, PercentProcessorTime)
    $validPids = @()
    foreach ($p in @(Query 'Win32_Process' | Where-Object { $expectedStarts.ContainsKey([string]$_.ProcessId) })) {
        try {
            if ($p.CreationDate -is [DateTime]) { $date = $p.CreationDate } else { $date = $p.ConvertToDateTime($p.CreationDate) }
            if ($date.ToUniversalTime().Ticks.ToString() -eq $expectedStarts[[string]$p.ProcessId]) { $validPids += [long]$p.ProcessId }
        } catch { $message = 'PID identity ' + $p.ProcessId + ': ' + $_.Exception.Message; if (!$issues.Contains($message)) { $issues.Add($message) } }
    }
    $processes = @(Query 'Win32_PerfFormattedData_PerfProc_Process' | Where-Object { $validPids -contains [long]$_.IDProcess } | Select-Object IDProcess, Name, PercentProcessorTime, WorkingSet, WorkingSetPrivate, PrivateBytes)
    $disk = @(Query 'Win32_PerfFormattedData_PerfDisk_PhysicalDisk' | Where-Object { $_.Name -eq '_Total' } | Select-Object Name, DiskReadBytesPersec, DiskWriteBytesPersec, CurrentDiskQueueLength)
    $samples.Add([pscustomobject]@{
        sampledAtUtc = [DateTime]::UtcNow.ToString('o'); elapsedSeconds = [math]::Round($watch.Elapsed.TotalSeconds, 3)
        cpuTotal = $cpu; operatingSystem = $os; processes = $processes; diskIo = $disk
    })
    $remaining = $monitorDurationSeconds - $watch.Elapsed.TotalSeconds
    if ($remaining -gt 0) { Start-Sleep -Milliseconds ([int]([math]::Min($sampleIntervalSeconds, $remaining) * 1000)) }
} while ($watch.Elapsed.TotalSeconds -lt $monitorDurationSeconds)
[pscustomobject]@{
    requestedDurationSeconds = $monitorDurationSeconds; actualDurationSeconds = [math]::Round($watch.Elapsed.TotalSeconds, 3)
    intervalSeconds = $sampleIntervalSeconds; logicalProcessors = $logical
    samples = @($samples.ToArray()); errors = @($issues.ToArray())
} | ConvertTo-Json -Depth 8 -Compress
