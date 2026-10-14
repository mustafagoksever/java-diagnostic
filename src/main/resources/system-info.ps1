$errors = New-Object System.Collections.Generic.List[string]
function Query([string]$class) {
    try { return @(Get-CimInstance -ClassName $class) }
    catch {
        $errors.Add($class + ' CIM: ' + $_.Exception.Message)
        try { return @(Get-WmiObject -Class $class) }
        catch { $errors.Add($class + ' WMI: ' + $_.Exception.Message); return @() }
    }
}
$os = @(Query 'Win32_OperatingSystem' | Select-Object Caption, Version, BuildNumber, OSArchitecture, LastBootUpTime, TotalVisibleMemorySize, FreePhysicalMemory)
$machine = @(Query 'Win32_ComputerSystem' | Select-Object Name, Manufacturer, Model, NumberOfLogicalProcessors, TotalPhysicalMemory)
$cpu = @(Query 'Win32_Processor' | Select-Object Name, NumberOfCores, NumberOfLogicalProcessors, LoadPercentage)
$cpuTotal = @(Query 'Win32_PerfFormattedData_PerfOS_Processor' | Where-Object { $_.Name -eq '_Total' } | Select-Object Name, PercentProcessorTime)
$memory = @(Query 'Win32_PerfFormattedData_PerfOS_Memory' | Select-Object AvailableMBytes, PercentCommittedBytesInUse)
$perf = @(Query 'Win32_PerfFormattedData_PerfProc_Process' | Where-Object { $targetPids -contains [long]$_.IDProcess } | Select-Object IDProcess, Name, PercentProcessorTime, WorkingSet, WorkingSetPrivate, PrivateBytes, ThreadCount, HandleCount)
$disks = @(Query 'Win32_LogicalDisk' | Where-Object { $_.DriveType -eq 3 } | Select-Object DeviceID, VolumeName, Size, FreeSpace)
[pscustomobject]@{
    sampledAtUtc = [DateTime]::UtcNow.ToString('o'); operatingSystem = $os; computer = $machine
    cpu = $cpu; cpuTotal = $cpuTotal; memory = $memory; processes = $perf; disks = $disks
    units = 'OS memory: KiB; process memory: bytes; process CPU PercentProcessorTime may exceed 100 across logical CPUs; CPU LoadPercentage is a WMI snapshot.'
    errors = @($errors.ToArray())
} | ConvertTo-Json -Depth 6 -Compress
