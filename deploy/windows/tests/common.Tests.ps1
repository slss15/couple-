<#
  Pester 5 tests for the pure helpers in lib/common.ps1 (doctor, watchdog, anti-downgrade, tunnel ports) and static
  checks of every .ps1 file. Runs on Windows PowerShell 5.1 and PowerShell 7 (also on Linux):
    Invoke-Pester -Path deploy/windows/tests -Output Detailed
  Not shipped in the zip. The older assertion-style tests stay in test-common.ps1.
#>

BeforeAll {
    $script:root = Split-Path -Parent $PSScriptRoot
    . (Join-Path $script:root 'lib/common.ps1')
    . (Join-Path $script:root 'backup.ps1')   # dot-source only: the entry-point guard keeps main from running
    $script:tmp = Join-Path ([IO.Path]::GetTempPath()) ("couple-pester-{0}" -f [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $script:tmp | Out-Null
}

AfterAll {
    Remove-Item -LiteralPath $script:tmp -Recurse -Force -ErrorAction SilentlyContinue
}

Describe 'Script files' {
    BeforeDiscovery {
        $dir = Split-Path -Parent $PSScriptRoot
        $script:files = @(Get-ChildItem -Path $dir -Recurse -Filter *.ps1 | ForEach-Object { @{ Path = $_.FullName; Name = $_.Name } })
    }

    It '<Name> parses without errors' -TestCases $script:files {
        param($Path)
        $tokens = $null; $errs = $null
        [void][Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errs)
        @($errs).Count | Should -Be 0
    }

    It '<Name> has no smart quote that PowerShell would treat as a string delimiter' -TestCases $script:files {
        param($Path)
        # PowerShell treats U+201C/U+201D like " and U+2018/U+2019 like ': inside a string of the same kind they
        # silently end it. Allowed: curly double quotes in single-quoted strings and in comments.
        $tokens = $null; $errs = $null
        [void][Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errs)
        $dq = [string][char]0x201C + [char]0x201D + [char]0x201E
        $sq = [string][char]0x2018 + [char]0x2019 + [char]0x201A + [char]0x201B
        $bad = @($tokens | Where-Object {
                $k = [string]$_.Kind
                if ($k -eq 'Comment') { return $false }
                if ($k -eq 'StringLiteral' -or $k -eq 'HereStringLiteral') { return ($_.Text.IndexOfAny($sq.ToCharArray()) -ge 0) }
                return ($_.Text.IndexOfAny(($dq + $sq).ToCharArray()) -ge 0)
            } | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Text)" })
        $bad | Should -BeNullOrEmpty
    }

    It '<Name> with non-ASCII text is UTF-8 with BOM (Windows PowerShell 5.1 reads it as ANSI otherwise)' -TestCases $script:files {
        param($Path)
        $bytes = [IO.File]::ReadAllBytes($Path)
        $nonAscii = @($bytes | Where-Object { $_ -gt 127 }).Count -gt 0
        if ($nonAscii) {
            ($bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) | Should -BeTrue
        }
    }

    It 'lib/common.ps1 stays ASCII-only' {
        $bytes = [IO.File]::ReadAllBytes((Join-Path $script:root 'lib/common.ps1'))
        @($bytes | Where-Object { $_ -gt 127 }).Count | Should -Be 0
    }
}

Describe 'Doctor helpers' {
    It 'exit code is 1 only when a FAIL exists' {
        Get-DoctorExitCode @((New-DoctorResult 'a' 'PASS' '' ''), (New-DoctorResult 'b' 'WARN' '' '')) | Should -Be 0
        Get-DoctorExitCode @((New-DoctorResult 'a' 'PASS' '' ''), (New-DoctorResult 'b' 'FAIL' '' '')) | Should -Be 1
        Get-DoctorExitCode @() | Should -Be 0
    }

    It 'folds newlines and caps the detail' {
        $r = New-DoctorResult 'x' 'WARN' ("line1`r`n  line2`n" + ('y' * 300)) 'h'
        $r.Detail | Should -Not -Match "`n"
        $r.Detail.Length | Should -Be 160
        $r.Detail | Should -BeLike 'line1 line2 y*...'
    }

    It 'thresholds: <Value> -> <Expected>' -TestCases @(
        @{ Value = 1.0; Expected = 'FAIL' }, @{ Value = 1.99; Expected = 'FAIL' }, @{ Value = 2.0; Expected = 'WARN' },
        @{ Value = 4.9; Expected = 'WARN' }, @{ Value = 5; Expected = 'PASS' }, @{ Value = 100; Expected = 'PASS' }
    ) {
        param($Value, $Expected)
        Get-ThresholdStatus $Value 2 5 | Should -Be $Expected
    }

    It 'OS build <Build> -> <Expected>' -TestCases @(
        @{ Build = 7601; Expected = 'FAIL' }, @{ Build = 9600; Expected = 'WARN' }, @{ Build = 14393; Expected = 'PASS' },
        @{ Build = 17763; Expected = 'PASS' }, @{ Build = 26100; Expected = 'PASS' }
    ) {
        param($Build, $Expected)
        Get-OsBuildStatus $Build | Should -Be $Expected
    }

    It 'clock skew from an HTTP Date header' {
        $now = [DateTime]::SpecifyKind([DateTime]'2026-10-25T00:01:30', 'Utc')
        Get-ClockSkew 'Sun, 25 Oct 2026 00:00:00 GMT' $now | Should -Be 90
        Get-ClockSkew 'Sun, 25 Oct 2026 00:02:00 GMT' $now | Should -Be -30
        Get-ClockSkew 'garbage' $now | Should -BeNullOrEmpty
        Get-ClockSkew '' $now | Should -BeNullOrEmpty
    }

    It 'parses netstat -ano listeners (English and localized output)' {
        $lines = @(
            'Active Connections', '',
            '  Proto  Local Address          Foreign Address        State           PID',
            '  TCP    0.0.0.0:135            0.0.0.0:0              LISTENING       1012',
            '  TCP    127.0.0.1:8080         0.0.0.0:0              LISTENING       4242',
            '  TCP    127.0.0.1:50000        127.0.0.1:8080         ESTABLISHED     77',
            ('  TCP    [::]:3306              [::]:0                 ABH' + [char]0x00D6 + 'REN         3306'),
            '  UDP    0.0.0.0:500            *:*                                    900'
        )
        $l = @(ConvertFrom-NetstatListen $lines)
        $l.Count | Should -Be 3
        ($l | Where-Object { $_.Port -eq 8080 }).ProcessId | Should -Be 4242
        ($l | Where-Object { $_.Port -eq 3306 }).Address | Should -Be '[::]'
        @($l | Where-Object { $_.ProcessId -eq 77 }).Count | Should -Be 0
    }

    It 'WinHTTP settings blob: direct and proxy' {
        ConvertFrom-WinHttpSetting ([byte[]]@(0x28, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0)) | Should -Be ''
        $p = [Text.Encoding]::ASCII.GetBytes('proxy.corp:8080')
        $blob = [byte[]](@(0x28, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, $p.Length, 0, 0, 0) + $p + @(0, 0, 0, 0))
        ConvertFrom-WinHttpSetting $blob | Should -Be 'proxy.corp:8080'
        ConvertFrom-WinHttpSetting $null | Should -Be ''
    }

    It 'port verdicts' {
        Get-PortStatus $false '' '' @('couple-backend') $false | Should -Be 'PASS'
        Get-PortStatus $true 'couple-backend' 'java.exe' @('couple-backend') $false | Should -Be 'PASS'
        Get-PortStatus $true '' 'nginx.exe' @('couple-backend') $false | Should -Be 'FAIL'
        Get-PortStatus $true 'W3SVC' 'svchost.exe' @('couple-backend') $false | Should -Be 'FAIL'
        Get-PortStatus $true 'MySQL80' 'mysqld.exe' @() $true | Should -Be 'PASS'
        Get-PortStatus $true '' 'mysqld' @() $true | Should -Be 'PASS'
        Get-PortStatus $true '' 'mysqld.exe' @() $false | Should -Be 'FAIL'
    }

    It 'hides proxy credentials' {
        Hide-UrlCredential 'http://user:p%40ss@proxy:3128' | Should -Be 'http://***@proxy:3128'
        Hide-UrlCredential 'proxy:3128' | Should -Be 'proxy:3128'
        Hide-UrlCredential 'u:p@proxy:3128' | Should -Be '***@proxy:3128'
        Hide-UrlCredential '' | Should -Be ''
    }

    It 'table pads CJK by display width' {
        Get-DisplayWidth 'abc' | Should -Be 3
        Get-DisplayWidth ([string][char]0x4E2D + [char]0x6587) | Should -Be 4
        $lines = Format-DoctorTable @((New-DoctorResult ([string][char]0x5185 + [char]0x5B58) 'PASS' '2 GB' ''), (New-DoctorResult 'C:' 'FAIL' 'x' 'fix it')) @('S', 'Item', 'Detail', 'Hint')
        $lines.Count | Should -Be 4
        $lines[2] | Should -BeLike 'PASS | * | 2 GB*| -'
        $lines[3] | Should -BeLike 'FAIL | C:   | x * | fix it'
        # Every row puts the 2nd separator at the same display column.
        $cols = $lines | Where-Object { $_ -like '*|*|*' } | ForEach-Object { $i = $_.IndexOf('|', $_.IndexOf('|') + 1); Get-DisplayWidth $_.Substring(0, $i) }
        @($cols | Select-Object -Unique).Count | Should -Be 1
    }
}

Describe 'Tunnel config ports' {
    It 'reads hostname, metrics and loopback ports from the shipped template' {
        $yml = Join-Path $script:tmp 'config.yml'
        Expand-Template (Join-Path $script:root 'cloudflared/config.yml.in') $yml @{
            TUNNEL_ID = '6f1c0000-1111-2222-3333-444455556666'; HOSTNAME = 'couple.example.com'; CREDENTIALS_FILE = 'C:\couple\x.json'
            BACKEND_PORT = '8080'; MQTT_WS_PORT = '8083'; HOOK_PORT = '9000'
        }
        $i = Get-TunnelConfigInfo $yml
        $i.Hostname | Should -Be 'couple.example.com'
        $i.Metrics | Should -Be '127.0.0.1:20241'
        $i.CredentialsFile | Should -Be 'C:\couple\x.json'
        $i.Ports | Should -Be @(9000, 8083, 8080)
        [IO.File]::ReadAllText($yml) | Should -Not -Match '@[A-Z_]+@'
    }

    It 'expected ports follow PORT / MQTT_WS_PORT / HOOK_PORT' {
        $e = Get-ExpectedTunnelPort @{ PORT = '18080'; MQTT_WS_PORT = '18083' }
        $e.Backend | Should -Be 18080; $e.MqttWs | Should -Be 18083; $e.Hook | Should -Be 9000
        $d = Get-ExpectedTunnelPort @{ PORT = ''; MQTT_WS_PORT = 'junk' }
        $d.Backend | Should -Be 8080; $d.MqttWs | Should -Be 8083
        Test-TunnelPortsMatch @(9000, 8083, 8080) $d | Should -BeTrue
        Test-TunnelPortsMatch @(9000, 8083, 8080) $e | Should -BeFalse
        Test-TunnelPortsMatch @(9000, 8083) $d | Should -BeFalse
        Test-TunnelPortsMatch @(9000, 8083, 8080, 3000) $d | Should -BeTrue   # extra hand-added ingress is fine
    }
}

Describe 'Anti-downgrade' {
    It 'semver compare <A> vs <B> = <Expected>' -TestCases @(
        @{ A = '0.2.0'; B = '0.10.0'; Expected = -1 }, @{ A = '1.0.0'; B = '0.99.99'; Expected = 1 },
        @{ A = 'v1.2.3'; B = '1.2.3'; Expected = 0 }, @{ A = '1.0.0-rc.1'; B = '1.0.0'; Expected = -1 },
        @{ A = '1.0.0-alpha'; B = '1.0.0-alpha.1'; Expected = -1 }, @{ A = '1.0.0-alpha.1'; B = '1.0.0-alpha.beta'; Expected = -1 },
        @{ A = '1.0.0-beta.2'; B = '1.0.0-beta.11'; Expected = -1 }, @{ A = '1.2.3+b5'; B = '1.2.3'; Expected = 0 }
    ) {
        param($A, $B, $Expected)
        Compare-SemVer $A $B | Should -Be $Expected
    }

    It 'non-semver names are incomparable' {
        Compare-SemVer 'edge-abc1234' '1.0.0' | Should -BeNullOrEmpty
    }

    It 'relation <New>/<NewEpoch> over <Cur>/<CurEpoch> = <Expected>' -TestCases @(
        @{ New = '0.2.0'; NewEpoch = 0; Cur = '0.3.0'; CurEpoch = 0; Expected = 'downgrade' },
        @{ New = '0.3.1'; NewEpoch = 1; Cur = '0.3.0'; CurEpoch = 999; Expected = 'upgrade' },
        @{ New = 'edge-a'; NewEpoch = 0; Cur = 'edge-a'; CurEpoch = 0; Expected = 'same' },
        @{ New = 'edge-old'; NewEpoch = 100; Cur = 'edge-new'; CurEpoch = 200; Expected = 'downgrade' },
        @{ New = 'edge-new'; NewEpoch = 200; Cur = 'edge-old'; CurEpoch = 100; Expected = 'upgrade' },
        @{ New = '0.3.0'; NewEpoch = 100; Cur = 'edge-new'; CurEpoch = 200; Expected = 'downgrade' },
        @{ New = 'edge-x'; NewEpoch = 0; Cur = 'edge-y'; CurEpoch = 200; Expected = 'unknown' },
        @{ New = '1.0.0'; NewEpoch = 0; Cur = ''; CurEpoch = 0; Expected = 'unknown' }
    ) {
        param($New, $NewEpoch, $Cur, $CurEpoch, $Expected)
        Get-VersionRelation $New $NewEpoch $Cur $CurEpoch | Should -Be $Expected
    }

    It 'reads COMMIT_EPOCH from BUILD_INFO' {
        $d = Join-Path $script:tmp 'rel'
        New-Item -ItemType Directory -Path $d -Force | Out-Null
        Get-BuildEpoch $d | Should -Be 0
        Write-Utf8NoBom (Join-Path $d 'BUILD_INFO') "VERSION=x`nCOMMIT_EPOCH=1760000000`n"
        Get-BuildEpoch $d | Should -Be 1760000000
        Write-Utf8NoBom (Join-Path $d 'BUILD_INFO') "COMMIT_EPOCH=abc`n"
        Get-BuildEpoch $d | Should -Be 0
    }

    It 'refreshes updater.json: new current, sha256 cleared, everything else kept' {
        $json = '{"current":{"server":"0.2.0"},"currentSha256":"' + ('a' * 64) + '","bad":{"server":["' + ('b' * 64) + '"]},"deploys":{"server":{"version":"0.2.0","state":"success","updatedAt":5}},"lastCheck":1760000000000,"channel":"edge"}'
        $out = ConvertFrom-Json (Update-UpdaterStateJson $json '0.3.0')
        $out.current.server | Should -Be '0.3.0'
        $out.currentSha256 | Should -BeNullOrEmpty
        @($out.bad.server).Count | Should -Be 1
        $out.deploys.server.state | Should -Be 'success'
        $out.lastCheck | Should -Be 1760000000000
        (Update-UpdaterStateJson $json '0.3.0') | Should -Match '"bad":\{"server":\["b{64}"\]\}'
    }

    It 'creates a minimal state from empty or broken JSON' {
        (ConvertFrom-Json (Update-UpdaterStateJson '' 'v1')).current.server | Should -Be 'v1'
        (ConvertFrom-Json (Update-UpdaterStateJson '{broken' 'v1')).current.server | Should -Be 'v1'
    }
}

Describe 'Watchdog helpers' {
    It 'sums a Prometheus metric over label sets' {
        $text = @(
            '# HELP cloudflared_tunnel_ha_connections Number of active ha connections',
            '# TYPE cloudflared_tunnel_ha_connections gauge',
            'cloudflared_tunnel_ha_connections 4',
            'cloudflared_tunnel_ha_connections_total 99',
            'cloudflared_tunnel_server_locations{connection_id="0",edge_location="hkg01"} 1'
        ) -join "`n"
        ConvertFrom-PrometheusMetric $text 'cloudflared_tunnel_ha_connections' | Should -Be 4
        ConvertFrom-PrometheusMetric $text 'cloudflared_tunnel_server_locations' | Should -Be 1
        ConvertFrom-PrometheusMetric "x{a=`"1`"} 1.5e0`nx{a=`"2`"} 2`r`n" 'x' | Should -Be 3.5
        ConvertFrom-PrometheusMetric 'cloudflared_tunnel_ha_connections 0' 'cloudflared_tunnel_ha_connections' | Should -Be 0
        ConvertFrom-PrometheusMetric $text 'missing_metric' | Should -BeNullOrEmpty
        ConvertFrom-PrometheusMetric '' 'x' | Should -BeNullOrEmpty
    }

    It 'consecutive failure counter' {
        $s = @{}
        Step-FailCounter $s 'k' $false | Should -Be 1
        Step-FailCounter $s 'k' $false | Should -Be 2
        Step-FailCounter $s 'k' $true | Should -Be 0
        Step-FailCounter $s 'k' $false | Should -Be 1
        $s2 = @{ k = '3' }
        Step-FailCounter $s2 'k' $false | Should -Be 4
    }

    It 'restart cooldown' {
        $now = [DateTime]::SpecifyKind([DateTime]'2026-10-25T12:00:00', 'Utc')
        Test-RestartAllowed '' $now 5 | Should -BeTrue
        Test-RestartAllowed '2026-10-25T11:56:00.0000000Z' $now 5 | Should -BeFalse
        Test-RestartAllowed '2026-10-25T11:55:00.0000000Z' $now 5 | Should -BeTrue
        Test-RestartAllowed 'not a date' $now 5 | Should -BeTrue
    }

    It 'rotates logs keeping N files' {
        $log = Join-Path $script:tmp 'w.log'
        for ($i = 1; $i -le 4; $i++) {
            [IO.File]::WriteAllText($log, ('x' * 20) + $i)
            Invoke-LogRotation $log 10 2 | Should -BeTrue
        }
        Test-Path $log | Should -BeFalse
        [IO.File]::ReadAllText("$log.1") | Should -BeLike '*4'
        [IO.File]::ReadAllText("$log.2") | Should -BeLike '*3'
        Test-Path "$log.3" | Should -BeFalse
        [IO.File]::WriteAllText($log, 'small')
        Invoke-LogRotation $log 10 2 | Should -BeFalse
        Invoke-LogRotation (Join-Path $script:tmp 'none.log') 10 2 | Should -BeFalse
    }

    It 'scheduled task XML: SYSTEM, every 2 min, no overlap, escaped arguments' {
        [xml]$x = New-WatchdogTaskXml 'C:\Windows\powershell.exe' '-File "C:\couple\watchdog\watchdog.ps1" -InstallDir "C:\a&b"' '2026-01-01T00:00:00' 2
        $ns = New-Object Xml.XmlNamespaceManager $x.NameTable
        $ns.AddNamespace('t', 'http://schemas.microsoft.com/windows/2004/02/mit/task')
        $x.SelectSingleNode('//t:Principal/t:UserId', $ns).InnerText | Should -Be 'S-1-5-18'
        $x.SelectSingleNode('//t:TimeTrigger/t:Repetition/t:Interval', $ns).InnerText | Should -Be 'PT2M'
        $x.SelectSingleNode('//t:MultipleInstancesPolicy', $ns).InnerText | Should -Be 'IgnoreNew'
        $x.SelectSingleNode('//t:Exec/t:Arguments', $ns).InnerText | Should -Be '-File "C:\couple\watchdog\watchdog.ps1" -InstallDir "C:\a&b"'
        $x.SelectSingleNode('//t:DisallowStartIfOnBatteries', $ns).InnerText | Should -Be 'false'
    }
}

Describe 'Backup helpers' {
    It 'parses jdbc url database name' {
        Get-DatabaseNameFromJdbcUrl 'jdbc:mysql://127.0.0.1:3306/couple?useSSL=false&allowPublicKeyRetrieval=true' | Should -Be 'couple'
        Get-DatabaseNameFromJdbcUrl 'jdbc:mysql://db.local:3307/couple' | Should -Be 'couple'
        Get-DatabaseNameFromJdbcUrl '' | Should -Be 'couple'
        Get-DatabaseNameFromJdbcUrl 'garbage' | Should -Be 'couple'
    }

    It 'parses jdbc url host/port' {
        $hp = Get-HostPortFromJdbcUrl 'jdbc:mysql://127.0.0.1:3306/couple?x=1'
        $hp.Host | Should -Be '127.0.0.1'; $hp.Port | Should -Be '3306'
        $hp2 = Get-HostPortFromJdbcUrl 'jdbc:mysql://db.local/couple'
        $hp2.Host | Should -Be 'db.local'; $hp2.Port | Should -Be '3306'
        $hp3 = Get-HostPortFromJdbcUrl ''
        $hp3.Host | Should -Be '127.0.0.1'; $hp3.Port | Should -Be '3306'
    }

    It 'retention: keeps the newest N dumps, keeps everything when keep < 1' {
        $f = @(1..4 | ForEach-Object {
            [pscustomobject]@{ Name = "couple-2026-01-0$_-020000.sql.gz"; LastWriteTime = [DateTime]"2026-01-0$_" }
        })
        # returned newest-first (deletion order is irrelevant; the sort is newest-first by contract)
        @(Get-BackupFilesToDelete $f 2).Name | Should -Be @('couple-2026-01-02-020000.sql.gz', 'couple-2026-01-01-020000.sql.gz')
        Get-BackupFilesToDelete $f 4 | Should -Be @()
        Get-BackupFilesToDelete $f 9 | Should -Be @()
        Get-BackupFilesToDelete $f 0 | Should -Be @()
        Get-BackupFilesToDelete @() 5 | Should -Be @()
    }

    It 'retention tiebreaks by name' {
        $f = @(
            [pscustomobject]@{ Name = 'b.sql.gz'; LastWriteTime = [DateTime]'2026-01-01' },
            [pscustomobject]@{ Name = 'a.sql.gz'; LastWriteTime = [DateTime]'2026-01-01' }
        )
        @(Get-BackupFilesToDelete $f 1).Name | Should -Be 'a.sql.gz'
    }

    It 'scheduled task XML: daily calendar trigger, SYSTEM, escaped arguments' {
        [xml]$x = New-BackupTaskXml 'C:\Windows\powershell.exe' '-File "C:\couple\scripts\backup.ps1" -InstallDir "C:\a&b"' '2026-01-01T02:30:00'
        $ns = New-Object Xml.XmlNamespaceManager $x.NameTable
        $ns.AddNamespace('t', 'http://schemas.microsoft.com/windows/2004/02/mit/task')
        $x.SelectSingleNode('//t:Principal/t:UserId', $ns).InnerText | Should -Be 'S-1-5-18'
        $x.SelectSingleNode('//t:CalendarTrigger/t:StartBoundary', $ns).InnerText | Should -Be '2026-01-01T02:30:00'
        $x.SelectSingleNode('//t:CalendarTrigger/t:ScheduleByDay/t:DaysInterval', $ns).InnerText | Should -Be '1'
        $x.SelectSingleNode('//t:MultipleInstancesPolicy', $ns).InnerText | Should -Be 'IgnoreNew'
        $x.SelectSingleNode('//t:StartWhenAvailable', $ns).InnerText | Should -Be 'true'
        $x.SelectSingleNode('//t:Exec/t:Arguments', $ns).InnerText | Should -Be '-File "C:\couple\scripts\backup.ps1" -InstallDir "C:\a&b"'
    }

    It 'gzip round-trips' {
        $src = Join-Path $script:tmp 'gzip-src.txt'
        $dst = Join-Path $script:tmp 'gzip-dst.gz'
        [IO.File]::WriteAllText($src, ("你好 couple " * 1000))
        Compress-GzipFile $src $dst
        (Get-Item $dst).Length | Should -BeGreaterThan 0
        (Get-Item $dst).Length | Should -BeLessThan ([IO.FileInfo]::new($src).Length)
        Add-Type -AssemblyName System.IO.Compression
        $in = [IO.File]::OpenRead($dst)
        try {
            $gz = New-Object IO.Compression.GZipStream($in, [IO.Compression.CompressionMode]::Decompress)
            try { $round = (New-Object IO.StreamReader($gz, [Text.Encoding]::UTF8)).ReadToEnd() } finally { $gz.Dispose() }
        } finally { $in.Dispose() }
        $round | Should -Be (("你好 couple " * 1000))
    }
}
