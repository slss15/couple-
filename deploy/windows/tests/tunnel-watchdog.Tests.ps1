<#
  Pester 5 tests for the cloudflared drop-out auto-restart (tunnel-watchdog.ps1 + couple-tunnelwatch service):
  the pure decision helper, the shared restart marker, the WinSW template and the install/setup/uninstall wiring.
    Invoke-Pester -Path deploy/windows/tests -Output Detailed
#>

BeforeAll {
    $script:root = Split-Path -Parent $PSScriptRoot
    . (Join-Path $script:root 'lib/common.ps1')
    $script:tmp = Join-Path ([IO.Path]::GetTempPath()) ("couple-tw-{0}" -f [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $script:tmp | Out-Null
    $script:now = [DateTime]::Parse('2026-11-02T12:00:00Z', [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AdjustToUniversal)
}

AfterAll {
    Remove-Item -LiteralPath $script:tmp -Recurse -Force -ErrorAction SilentlyContinue
}

Describe 'Get-TunnelWatchDecision' {
    It 'public OK resets the counter' {
        $d = Get-TunnelWatchDecision $true $true 2 '' $script:now
        $d.Failures | Should -Be 0
        $d.Action | Should -Be 'none'
    }

    It 'counts failures and restarts on the 3rd consecutive one' {
        $n = 0
        $actions = foreach ($i in 1..3) { $d = Get-TunnelWatchDecision $false $true $n '' $script:now; $n = $d.Failures; $d.Action }
        $actions | Should -Be @('none', 'none', 'restart')
        $n | Should -Be 3
    }

    It 'does not count public failures while the local health is down' {
        $d = Get-TunnelWatchDecision $false $false 2 '' $script:now
        $d.Action | Should -Be 'skip'
        $d.Failures | Should -Be 2
        # ... nor does a public OK during a local outage reset anything (still skip).
        (Get-TunnelWatchDecision $true $false 2 '' $script:now).Action | Should -Be 'skip'
    }

    It 'backs off within 5 minutes of the last restart' {
        $recent = $script:now.AddMinutes(-4).ToString('o')
        $d = Get-TunnelWatchDecision $false $true 5 $recent $script:now
        $d.Action | Should -Be 'backoff'
        $d.Failures | Should -Be 6
        $old = $script:now.AddMinutes(-5).ToString('o')
        (Get-TunnelWatchDecision $false $true 5 $old $script:now).Action | Should -Be 'restart'
    }

    It 'honours a custom limit and cooldown' {
        (Get-TunnelWatchDecision $false $true 0 '' $script:now 1 10).Action | Should -Be 'restart'
        (Get-TunnelWatchDecision $false $true 0 $script:now.AddMinutes(-9).ToString('o') $script:now 1 10).Action | Should -Be 'backoff'
    }

    It 'treats an unreadable marker as no previous restart' {
        (Get-TunnelWatchDecision $false $true 2 'garbage' $script:now).Action | Should -Be 'restart'
    }
}

Describe 'cloudflared restart marker' {
    It 'is empty when missing and round-trips a UTC timestamp' {
        $dir = Join-Path $script:tmp 'inst1'
        Get-CloudflaredRestartMarker $dir | Should -Be ''
        Set-CloudflaredRestartMarker $dir $script:now
        $v = Get-CloudflaredRestartMarker $dir
        $v | Should -Not -BeNullOrEmpty
        Test-RestartAllowed $v $script:now.AddMinutes(4) 5 | Should -BeFalse
        Test-RestartAllowed $v $script:now.AddMinutes(5) 5 | Should -BeTrue
    }
}

Describe 'couple-tunnelwatch wiring' {
    BeforeAll {
        $script:xmlIn = Join-Path $script:root 'service/couple-tunnelwatch.xml.in'
        $script:install = Get-Content -Raw (Join-Path $script:root 'install.ps1')
        $script:setup = Get-Content -Raw (Join-Path $script:root 'setup.ps1')
        $script:uninstall = Get-Content -Raw (Join-Path $script:root 'uninstall.ps1')
        $script:build = Get-Content -Raw (Join-Path $script:root 'build-package.sh')
        $script:wd = Get-Content -Raw (Join-Path $script:root 'watchdog.ps1')
        $script:tw = Get-Content -Raw (Join-Path $script:root 'tunnel-watchdog.ps1')
    }

    It 'WinSW template renders to valid XML that runs tunnel-watchdog.ps1 from the install dir' {
        $out = Join-Path $script:tmp 'couple-tunnelwatch.xml'
        Expand-Template $script:xmlIn $out @{ INSTALL_DIR = 'D:\couple' }
        [xml]$x = Get-Content -Raw $out
        $x.service.id | Should -Be 'couple-tunnelwatch'
        $x.service.arguments | Should -Match ([regex]::Escape('-File "D:\couple\watchdog\tunnel-watchdog.ps1" -InstallDir "D:\couple"'))
        $x.service.logpath | Should -Be 'D:\couple\logs'
        (Get-Content -Raw $out) | Should -Not -Match '@[A-Z_]+@'
    }

    It 'install.ps1 copies the script and registers/starts the service only next to couple-cloudflared' {
        $script:install | Should -Match "Join-Path \`$Pkg 'tunnel-watchdog.ps1'"
        $script:install | Should -Match "function Install-TunnelWatch"
        $script:install | Should -Match "Get-ServiceState 'couple-cloudflared'\) -eq 'Missing'"
        $script:install | Should -Match "Start-Service -Name 'couple-tunnelwatch'"
        $script:install | Should -Match '\[switch\]\$NoTunnelWatch'
        $script:install | Should -Match "'watchdog.ps1', 'tunnel-watchdog.ps1'"
    }

    It 'setup.ps1 passes -NoTunnelWatch through and reports the service state' {
        $script:setup | Should -Match '\[switch\]\$NoTunnelWatch'
        $script:setup | Should -Match "\`$installArgs\['NoTunnelWatch'\] = \`$true"
        $script:setup | Should -Match "Get-ServiceState 'couple-tunnelwatch'"
    }

    It 'uninstall.ps1 removes it first and the package ships it' {
        $script:uninstall | Should -Match "@\('couple-tunnelwatch', 'couple-cloudflared'"
        $script:build | Should -Match 'tunnel-watchdog\.ps1'
    }

    It 'watchdog.ps1 leaves the public probe to couple-tunnelwatch and shares the restart marker' {
        $script:wd | Should -Match "Confirm-ServiceRunning 'couple-tunnelwatch'"
        $script:wd | Should -Match 'Get-CloudflaredRestartMarker'
        $script:wd | Should -Match 'Set-CloudflaredRestartMarker'
        $script:tw | Should -Match 'Set-CloudflaredRestartMarker'
        $script:tw | Should -Match "logs\\tunnel-watchdog\.log"
    }
}

Describe 'tunnel-watchdog.ps1 -Once' {
    It 'runs one round without cloudflared installed, logs nothing fatal and exits 0' {
        $inst = Join-Path $script:tmp 'inst2'
        New-Item -ItemType Directory -Force -Path (Join-Path $inst 'config') | Out-Null
        $pwsh = (Get-Process -Id $PID).Path
        & $pwsh -NoProfile -File (Join-Path $script:root 'tunnel-watchdog.ps1') -InstallDir $inst -Once | Out-Null
        $LASTEXITCODE | Should -Be 0
        $log = Get-ChildItem -Path $inst -Recurse -Filter '*tunnel-watchdog.log' | Select-Object -First 1
        if ($log) { (Get-Content -Raw $log.FullName) | Should -Not -Match 'ERROR' }
    }
}
