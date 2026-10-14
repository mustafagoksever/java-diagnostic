# Controlled asynchronous adapter: this fixture never sends network traffic.
$global:pingFixtureClock=[Diagnostics.Stopwatch]::StartNew()
$global:pingFixtureStats=[pscustomobject]@{active=0;maximum=0;created=0;disposed=0;cancelled=0;starts=(New-Object Collections.ArrayList)}
function New-FixturePing {
    $global:pingFixtureStats.created++
    $adapter=[pscustomobject]@{finished=$false;disposed=$false}
    $adapter|Add-Member ScriptMethod SendPingAsync {
        param($address,$timeout)
        $ip=$address.ToString()
        [void]$global:pingFixtureStats.starts.Add([pscustomobject]@{address=$ip;at=$global:pingFixtureClock.ElapsedMilliseconds})
        if($global:pingFixtureMode -eq 'failures' -and $ip.EndsWith('.1')){throw 'FIXTURE_START_FAILURE'}
        $global:pingFixtureStats.active++
        $global:pingFixtureStats.maximum=[Math]::Max($global:pingFixtureStats.maximum,$global:pingFixtureStats.active)
        $task=[pscustomobject]@{owner=$this;address=$ip;due=($global:pingFixtureClock.ElapsedMilliseconds+100)}
        $task|Add-Member ScriptProperty IsCompleted {
            if($global:pingFixtureMode -eq 'cancel' -and $global:pingFixtureStats.active -ge 4){[IO.File]::WriteAllText((Join-Path $global:pingFixtureRoot 'cancel.txt'),'cancel');return $false}
            return $global:pingFixtureClock.ElapsedMilliseconds -ge $this.due
        }
        $task|Add-Member ScriptMethod GetAwaiter {return $this}
        $task|Add-Member ScriptMethod GetResult {
            if($global:pingFixtureClock.ElapsedMilliseconds -lt $this.due){throw 'FIXTURE_EARLY_BLOCKING_READ'}
            $this.owner.finished=$true;$global:pingFixtureStats.active--
            if($global:pingFixtureMode -eq 'failures' -and $this.address.EndsWith('.2')){throw 'FIXTURE_ASYNC_FAILURE'}
            $status=if($this.address.EndsWith('.1')){[Net.NetworkInformation.IPStatus]::Success}elseif($this.address.EndsWith('.2')){[Net.NetworkInformation.IPStatus]::DestinationHostUnreachable}else{[Net.NetworkInformation.IPStatus]::TimedOut}
            return [pscustomobject]@{Status=$status;Address=if($status -eq [Net.NetworkInformation.IPStatus]::Success){[Net.IPAddress]::Parse($this.address)}else{$null};RoundtripTime=7}
        }
        return $task
    }
    $adapter|Add-Member ScriptMethod SendAsyncCancel {$global:pingFixtureStats.cancelled++}
    $adapter|Add-Member ScriptMethod Dispose {
        if(!$this.disposed){$this.disposed=$true;$global:pingFixtureStats.disposed++}
        if(!$this.finished){$this.finished=$true;if($global:pingFixtureStats.active -gt 0){$global:pingFixtureStats.active--}}
    }
    return $adapter
}
