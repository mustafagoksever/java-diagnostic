# A fake serial adapter. Tests never open a hardware COM port.
function New-FakeSerial {
    $fake=[pscustomobject]@{IsOpen=$false;Handshake=0;DtrEnable=$false;RtsEnable=$false;ReadTimeout=0;WriteTimeout=0;Encoding=$null;Buffer='';PendingPage=$false;Writes=@()}
    $fake|Add-Member ScriptMethod Open {$this.IsOpen=$true}
    $fake|Add-Member ScriptMethod Close {$this.IsOpen=$false}
    $fake|Add-Member ScriptMethod Dispose {[IO.File]::WriteAllText((Join-Path $global:fixtureRoot 'disposed.txt'),'closed')}
    $fake|Add-Member ScriptMethod ReadExisting {$value=$this.Buffer;$this.Buffer='';return $value}
    $fake|Add-Member ScriptMethod Write {
        param($inputText)
        $this.Writes+=,[string]$inputText
        if($global:fixtureSilent){return}
        $idPrefix=if($global:fixtureWide){'ffff8000000000'}else{''}
        $row1=if($global:fixtureWide){'tOne '+$idPrefix+'42 10 PEND 0000000000000000 0 -'}else{'tOne main 42 10 PEND 0 0 0 0'}
        $row2=if($global:fixtureWide){'tShell0 '+$idPrefix+'43 10 READY 0000000000000000 0 -'}else{'tShell0 main 43 10 READY 0 0 0 0'}
        $row3=if($global:fixtureWide){'tProtected '+$idPrefix+'44 10 PEND 0000000000000000 0 -'}else{'tProtected main 44 10 PEND 0 0 0 0'}
        $cmd=$inputText.Trim()
        if($cmd -eq 'version'){
            if($global:fixtureTiMode -eq 'version-invalid'){$this.Buffer="version`r`nOther device version 1.0`r`n-> ";return}
            if($global:fixtureTiMode -eq 'version-unsupported'){$this.Buffer="version`r`nC interp: unknown symbol name 'VxWorks'.`r`n-> ";return}
            $this.Buffer="version`r`nVxWorks (fixture) version 6.9.4.6`r`nvalue = 0`r`n-> ";return
        }
        if($cmd -eq 'whoami' -and $global:fixtureTiMode -eq 'diagnostic-silent'){$this.Buffer="whoami`r`npartial username";return}
        if($cmd -eq 'time' -and $global:fixtureTiMode -eq 'time-silent'){$this.Buffer="time`r`npartial time output";return}
        if($cmd -eq 'time' -and $global:fixtureTiMode -eq 'time-unsupported'){$this.Buffer="time`r`nC interp: unknown symbol name 'time'.`r`n-> ";return}
        if($cmd -in @('time','moduleShow','checkStack 0','taskShow 0,2','devs','whoami','w') -or $cmd -match '^tw 0x[0-9a-f]+$'){
            if($global:fixtureTiMode -eq 'diagnostic-unsupported'){$this.Buffer=$cmd+"`r`nC interp: unknown symbol name.`r`n-> ";return}
            if($cmd -eq 'time'){$this.Buffer="time`r`nvalue = 1791988800 = 0x6acf9440`r`n-> ";return}
            if($cmd -eq 'moduleShow'){$this.Buffer="moduleShow`r`nMODULE NAME     MODULE ID  GROUP #  TEXT START  DATA START  BSS START`r`nfixtureApp.out  0x123400   2        0x100000    0x200000    0x300000`r`nvalue = 0`r`n-> ";return}
            $this.Buffer=$cmd+"`r`nFIXTURE_DIAGNOSTIC_OUTPUT`r`nvalue = 0`r`n-> ";return
        }
        if(!$cmd){
            if($this.PendingPage){$this.Buffer=$row2+"`r`n"+$row3+"`r`nvalue = 0`r`n-> ";$this.PendingPage=$false}else{$this.Buffer="`r`n-> "};return
        }
        if($cmd -eq 'i'){$this.PendingPage=$true;$this.Buffer="i`r`nNAME ENTRY TID PRI STATUS`r`n"+$row1+"`r`nType <CR> to continue, Q<CR> or q<CR> to stop: ";return}
        if($cmd -eq 'TatlYaz'){
            if($global:fixtureTiMode -eq 'tatl-oversize'){$this.Buffer="TatlYaz`r`nLARGE_BEGIN"+('x'*(1MB+1));return}
            if($global:fixtureTiMode -eq 'tatl-write-failed'){throw 'FIXTURE_TATLYAZ_WRITE_FAILED'}
            if($global:fixtureTiMode -eq 'tatl-silent'){$this.Buffer="TatlYaz`r`npartial custom output, no prompt";return}
            if($global:fixtureTiMode -eq 'tatl-unsupported'){$this.Buffer="TatlYaz`r`nC interp: unknown symbol name.`r`n-> ";return}
            $this.Buffer="TatlYaz`r`nFIXTURE_CUSTOM_OUTPUT`r`nvalue = -1`r`n-> ";return
        }
        if(!$global:fixtureNoIdentity -and $global:fixtureWide -and $cmd -eq 'ifconfig'){
            $this.Buffer="ifconfig`r`nlo0 Link type:Local loopback`r`n inet 127.0.0.1 mask 255.255.255.255`r`ngei0 Link type:Ethernet HWaddr 00:03:73:08:a1:e2`r`n inet 15.30.9.198 mask 255.0.0.0 broadcast 15.255.255.255`r`n inet6 unicast fe80::203:73ff:fe08:a1e2%gei0`r`n inet 999.0.0.1`r`nGateway: 15.30.9.1`r`nvalue = 0`r`n-> ";return
        }
        if(!$global:fixtureNoIdentity -and !$global:fixtureWide -and $cmd -eq 'ifShow'){
            $this.Buffer="ifShow`r`nlo (unit number 0):`r`n inet: 127.0.0.1`r`nmgi (unit number 0):`r`n inet: 15.30.9.195`r`n Broadcast address: 15.255.255.255`r`n Netmask 0xff000000 Subnetmask 0xff000000`r`n Ethernet address is 00:03:73:77:95:02`r`nvalue = 0`r`n-> ";return
        }
        if($cmd -eq 'inetstatShow'){$this.Buffer="inetstatShow`r`nvalue = 0`r`n-> ";return}
        if($cmd -eq 'netstat "-a -n"'){$this.Buffer=$cmd+"`r`nTCP 0 0 0.0.0.0.23 0.0.0.0.* LISTEN`r`nUDP 0 0 0.0.0.0.111 0.0.0.0.*`r`nvalue = 0`r`n-> ";return}
        if($cmd -match '^ti 0x[0-9a-f]+$'){
            if($global:fixtureTiMode -eq 'write-failed'){throw 'FIXTURE_SERIAL_WRITE_FAILED'}
            if($global:fixtureTiMode -eq 'silent'){$this.Buffer=$cmd+"`r`npartial task info, no prompt";return}
            if($global:fixtureTiMode -eq 'unconfirmed'){$this.Buffer=$cmd+"`r`n"+($row1 -replace ($idPrefix+'42'),($idPrefix+'99'))+"`r`nvalue = 0`r`n-> ";return}
            if($global:fixtureTiMode -eq 'unsupported'){$this.Buffer=$cmd+"`r`nC interp: unknown symbol name.`r`n-> ";return}
            if($cmd -match '44$'){$this.Buffer=$cmd+"`r`ntask not found`r`nvalue = -1 = 0xffffffff`r`n-> ";return}
            $row=if($cmd -match '42$'){$row1}else{$row2}
            $returnValue=if($global:fixtureTiMode -eq 'minus-one'){'-1'}elseif($global:fixtureTiMode -eq 'unsigned-ones'){'18446744073709551615'}else{'0'}
            $this.Buffer=$cmd+"`r`nNAME ENTRY TID PRI STATUS`r`n"+$row+"`r`nstack: base 0x8000 end 0x7000 size 4096 high 1024 margin 3072`r`noptions: 0x1e`r`nPC = 0x1000 SP = 0x7800`r`nvalue = "+$returnValue+"`r`n-> ";return
        }
        if($cmd -match '^tt 0x[0-9a-f]*42$'){
            if($global:fixtureTiMode -eq 'tt-silent'){$this.Buffer=$cmd+"`r`npartial trace, no prompt";return}
            $this.Buffer=$cmd+"`r`n0xffffffff80001000 taskEntry +0x10: waitForEvent ()`r`nvalue = 0`r`n-> ";return
        }
        if($cmd -match '^tt 0x[0-9a-f]*43$'){$this.Buffer=$cmd+"`r`ntt(): unable to self trace.`r`nvalue = 4294967295`r`n-> ";return}
        if($cmd -match '^tt 0x[0-9a-f]*44$'){$this.Buffer=$cmd+"`r`ntt(): unable to trace unbreakable task.`r`nvalue = 4294967295`r`n-> ";return}
        $this.Buffer=$cmd+"`r`nC interp: unknown symbol name.`r`n-> "
    }
    return $fake
}
