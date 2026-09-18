<#
.SYNOPSIS
    One-command installer for the Kimi Mobile / Kimi Code web daemon on Windows.

.DESCRIPTION
    Sets up a machine as a Kimi Code server that the Kimi Mobile clients
    (Android / iOS / macOS / desktop) can connect to over REST + WebSocket.

    Two scenarios are handled:
      A) An existing Kimi Code installation is detected (kimi binary present
         and config.toml already declares providers). The installer never
         modifies config.toml in this case; it only sets up the daemon layer.
      B) A fresh machine. The official Kimi Code installer is run first,
         then an interactive menu helps import an API key.

    The script is idempotent: re-running it only fills gaps. It never resets
    the bearer token, never duplicates the Scheduled Task, and never touches
    config.toml for existing installations.

    Supports -Uninstall to remove the Scheduled Task and stop the daemon.
    Kimi Code itself and config.toml are left intact.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\install.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\install.ps1 -Uninstall
#>

#Requires -Version 5.1

[CmdletBinding()]
param(
    [switch]$Uninstall,
    [int]$Port = 0
)

# ---------------------------------------------------------------------------
# Constants and paths
# ---------------------------------------------------------------------------

$script:DefaultPort   = 58627
$script:TaskName      = 'KimiCodeWebDaemon'
$script:KimiHome      = if ($env:KIMI_CODE_HOME) { $env:KIMI_CODE_HOME } else { Join-Path $env:USERPROFILE '.kimi-code' }
$script:ConfigFile    = Join-Path $script:KimiHome 'config.toml'
$script:TokenFile     = Join-Path $script:KimiHome 'server.token'
$script:ConnectCard   = Join-Path $script:KimiHome 'connect-card.txt'
$script:ServerDir     = Join-Path $script:KimiHome 'server'
$script:StateFile     = Join-Path $script:ServerDir 'kimi-web-daemon.json'
$script:LauncherCmd   = Join-Path $script:ServerDir 'kimi-web-launcher.cmd'
$script:LauncherVbs   = Join-Path $script:ServerDir 'kimi-web-launcher.vbs'
$script:LogDir        = Join-Path $script:KimiHome 'logs'
$script:InstallerUrl  = 'https://code.kimi.com/kimi-code/install.ps1'

# Older .NET defaults may negotiate TLS 1.0, which the download host rejects.
try {
    [System.Net.ServicePointManager]::SecurityProtocol = `
        [System.Net.ServicePointManager]::SecurityProtocol -bor [System.Net.SecurityProtocolType]::Tls12
} catch { }

# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------

function Write-Info([string]$Message) { Write-Host "[*] $Message" -ForegroundColor Cyan }
function Write-Ok([string]$Message)   { Write-Host "[+] $Message" -ForegroundColor Green }
function Write-Warn([string]$Message) { Write-Host "[!] $Message" -ForegroundColor Yellow }
function Write-Err([string]$Message)  { Write-Host "[-] $Message" -ForegroundColor Red }

# ---------------------------------------------------------------------------
# Discovery helpers
# ---------------------------------------------------------------------------

function Resolve-KimiExe {
    $cmd = Get-Command kimi -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source) { return $cmd.Source }
    $candidate = Join-Path $script:KimiHome 'bin\kimi.exe'
    if (Test-Path $candidate) { return $candidate }
    return $null
}

function Test-ConfigHasProvider {
    if (-not (Test-Path $script:ConfigFile)) { return $false }
    $text = Get-Content $script:ConfigFile -Raw -ErrorAction SilentlyContinue
    if (-not $text) { return $false }
    return ($text -match '\[providers\.')
}

function Test-PortFree([int]$PortNumber) {
    $listener = $null
    try {
        $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $PortNumber)
        $listener.Start()
        return $true
    } catch {
        return $false
    } finally {
        if ($listener -ne $null) { $listener.Stop() }
    }
}

# HTTP-probe a URL and return the numeric status code, or 0 when the
# connection itself failed. Never use netstat for this kind of decision:
# a listening socket tells you nothing about what is actually serving it.
function Get-HttpStatus([string]$Url, [string]$Token) {
    try {
        $headers = @{}
        if ($Token) { $headers['Authorization'] = "Bearer $Token" }
        $response = Invoke-WebRequest -Uri $Url -Method Get -Headers $headers `
            -TimeoutSec 5 -UseBasicParsing -ErrorAction Stop
        return [int]$response.StatusCode
    } catch {
        $webResponse = $_.Exception.Response
        if ($webResponse -ne $null) {
            return [int]$webResponse.StatusCode
        }
        return 0
    }
}

function Get-HealthzStatus([int]$PortNumber, [string]$Token) {
    return Get-HttpStatus -Url "http://127.0.0.1:$PortNumber/api/v1/healthz" -Token $Token
}

# /api/v1/healthz is intentionally unauthenticated; /api/v1/meta requires the
# bearer token, so it is the right endpoint for token validation checks.
function Get-MetaStatus([int]$PortNumber, [string]$Token) {
    return Get-HttpStatus -Url "http://127.0.0.1:$PortNumber/api/v1/meta" -Token $Token
}

function Read-BearerToken {
    if (-not (Test-Path $script:TokenFile)) { return $null }
    $token = (Get-Content $script:TokenFile -Raw -ErrorAction SilentlyContinue)
    if (-not $token) { return $null }
    $token = $token.Trim()
    if ($token.Length -eq 0) { return $null }
    return $token
}

function Get-LanIPv4Addresses {
    $addresses = @()
    try {
        $addresses = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
            Where-Object {
                $_.IPAddress -notlike '127.*' -and
                $_.IPAddress -notlike '169.254.*' -and
                $_.InterfaceAlias -notlike '*Loopback*'
            } |
            Select-Object -ExpandProperty IPAddress -Unique
    } catch {
        # Fallback for systems without the NetTCPIP module.
        $addresses = [System.Net.Dns]::GetHostAddresses('') |
            Where-Object { $_.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork } |
            ForEach-Object { $_.IPAddressToString } |
            Where-Object { $_ -notlike '127.*' -and $_ -notlike '169.254.*' }
    }
    return @($addresses)
}

function Get-TailscaleInfo {
    $info = @{ Available = $false; IPv4 = $null; DnsName = $null }
    if (-not (Get-Command tailscale -ErrorAction SilentlyContinue)) { return $info }
    $info.Available = $true
    try {
        $ipOutput = (& tailscale ip -4 2>$null | Select-Object -First 1)
        if ($ipOutput) { $info.IPv4 = $ipOutput.Trim() }
    } catch { }
    try {
        $statusJson = (& tailscale status --json 2>$null | Out-String)
        if ($statusJson) {
            $status = $statusJson | ConvertFrom-Json
            if ($status.Self -and $status.Self.DNSName) {
                $info.DnsName = $status.Self.DNSName.TrimEnd('.')
            }
        }
    } catch { }
    return $info
}

function Test-IsAdministrator {
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object System.Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([System.Security.Principal.WindowsBuiltinRole]::Administrator)
}

function Read-SavedState {
    if (-not (Test-Path $script:StateFile)) { return $null }
    try {
        return (Get-Content $script:StateFile -Raw | ConvertFrom-Json)
    } catch {
        return $null
    }
}

function Save-State([int]$PortNumber) {
    if (-not (Test-Path $script:ServerDir)) {
        New-Item -ItemType Directory -Path $script:ServerDir -Force | Out-Null
    }
    $state = @{
        port      = $PortNumber
        updatedAt = (Get-Date).ToString('s')
    }
    ($state | ConvertTo-Json) | Set-Content -Path $script:StateFile -Encoding UTF8
}

function Get-LauncherPassword {
    # Recover the web password baked into an existing launcher so re-runs
    # keep it instead of prompting again (idempotency).
    if (-not (Test-Path $script:LauncherCmd)) { return $null }
    $text = Get-Content $script:LauncherCmd -Raw -ErrorAction SilentlyContinue
    if (-not $text) { return $null }
    $match = [regex]::Match($text, 'set "KIMI_CODE_PASSWORD=([^"]*)"')
    if ($match.Success -and $match.Groups[1].Value.Length -gt 0) {
        return $match.Groups[1].Value
    }
    return $null
}

# ---------------------------------------------------------------------------
# Uninstall
# ---------------------------------------------------------------------------

function Invoke-Uninstall {
    Write-Info 'Uninstalling the Kimi Code web daemon layer...'
    Write-Info 'Kimi Code itself and config.toml will NOT be touched.'

    $task = Get-ScheduledTask -TaskName $script:TaskName -ErrorAction SilentlyContinue
    if ($task) {
        Unregister-ScheduledTask -TaskName $script:TaskName -Confirm:$false
        Write-Ok "Removed Scheduled Task '$script:TaskName'."
    } else {
        Write-Info "No Scheduled Task named '$script:TaskName' found."
    }

    $state = Read-SavedState
    $portToCheck = 0
    if ($state -and $state.port) { $portToCheck = [int]$state.port }
    if ($portToCheck -le 0) {
        # Fall back to the port recorded inside the launcher script.
        if (Test-Path $script:LauncherCmd) {
            $text = Get-Content $script:LauncherCmd -Raw -ErrorAction SilentlyContinue
            $match = [regex]::Match($text, '--port\s+(\d+)')
            if ($match.Success) { $portToCheck = [int]$match.Groups[1].Value }
        }
    }
    if ($portToCheck -le 0) { $portToCheck = $script:DefaultPort }

    if ((Get-HealthzStatus -PortNumber $portToCheck -Token $null) -eq 200) {
        $stopped = $false
        try {
            $connections = Get-NetTCPConnection -LocalPort $portToCheck -State Listen -ErrorAction Stop
            foreach ($connection in $connections) {
                $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
                if ($process -and $process.ProcessName -match 'kimi') {
                    Stop-Process -Id $process.Id -Force -ErrorAction Stop
                    $stopped = $true
                    Write-Ok "Stopped daemon process '$($process.ProcessName)' (PID $($process.Id))."
                }
            }
        } catch {
            Write-Warn "Could not stop the daemon automatically: $($_.Exception.Message)"
        }
        if (-not $stopped) {
            Write-Warn "A healthy Kimi Code web server is still responding on port $portToCheck."
            Write-Warn 'Stop it manually (Ctrl+C in its terminal, or end the kimi process).'
        }
    } else {
        Write-Info "No daemon is responding on port $portToCheck."
    }

    Write-Ok 'Uninstall complete. Launcher scripts and connect card were left in place for reference:'
    Write-Info "  $script:ServerDir"
    exit 0
}

if ($Uninstall) {
    Invoke-Uninstall
}

# ---------------------------------------------------------------------------
# Banner
# ---------------------------------------------------------------------------

Write-Host ''
Write-Host 'Kimi Mobile - Windows server setup' -ForegroundColor White
Write-Host '==================================' -ForegroundColor White
Write-Host "Kimi Code home: $script:KimiHome"
Write-Host ''

# ---------------------------------------------------------------------------
# Step 1: detect an existing Kimi Code installation
# ---------------------------------------------------------------------------

$kimiExe = Resolve-KimiExe
$hasProvider = Test-ConfigHasProvider
$isGroupA = ($kimiExe -ne $null) -and $hasProvider

if ($isGroupA) {
    Write-Ok "Existing Kimi Code installation detected: $kimiExe"
    Write-Ok 'config.toml already declares providers. This script will NOT modify config.toml.'
} else {
    if ($kimiExe) {
        Write-Info "Kimi Code binary found ($kimiExe) but no model provider is configured yet."
    } else {
        Write-Info 'No Kimi Code installation found. A fresh install will be performed.'
    }
}

# ---------------------------------------------------------------------------
# Step 2 (Group B): install Kimi Code and configure credentials
# ---------------------------------------------------------------------------

if (-not $isGroupA) {

    if (-not $kimiExe) {
        Write-Info "Running the official Kimi Code installer ($script:InstallerUrl)..."
        if ($env:KIMI_VERSION) {
            Write-Info "KIMI_VERSION is set to '$env:KIMI_VERSION'; the official installer will honor it."
        }
        try {
            $installerScript = Invoke-RestMethod -Uri $script:InstallerUrl -ErrorAction Stop
            Invoke-Expression $installerScript
        } catch {
            Write-Err "The official installer failed: $($_.Exception.Message)"
            Write-Err 'Install Kimi Code manually (see https://www.kimi.com/code/docs/) and re-run this script.'
            exit 1
        }

        # The installer may have updated PATH only in the registry; refresh it.
        $env:Path = [System.Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + `
                    [System.Environment]::GetEnvironmentVariable('Path', 'User')
        $kimiExe = Resolve-KimiExe
        if (-not $kimiExe) {
            Write-Err 'Kimi Code still cannot be found after installation.'
            Write-Err 'Open a new terminal (so PATH refreshes) and re-run this script.'
            exit 1
        }
        Write-Ok "Kimi Code installed: $kimiExe"
    }

    if (-not $hasProvider) {
        Write-Host ''
        Write-Host 'API key setup' -ForegroundColor White
        Write-Host '-------------'
        Write-Host 'The Kimi Mobile app cannot chat until Kimi Code has a model configured.'
        Write-Host '  1) kimi login            - OAuth device-code flow (recommended; no plaintext key)'
        Write-Host '  2) Paste an API key      - written into config.toml'
        Write-Host '  3) Skip                  - configure later yourself'
        $choice = Read-Host 'Choose [1/2/3]'

        if ($choice -eq '1') {
            Write-Info 'Starting kimi login. Follow the URL/code prompt in this window...'
            & $kimiExe login
            if ($LASTEXITCODE -eq 0) {
                Write-Ok 'OAuth login completed.'
            } else {
                Write-Warn "kimi login exited with code $LASTEXITCODE. You can retry later by running: kimi login"
            }
        } elseif ($choice -eq '2') {
            $secureKey = Read-Host 'Paste the API key (input is hidden)' -AsSecureString
            $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
            try {
                $apiKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
            } finally {
                [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
            }
            if (-not $apiKey) {
                Write-Warn 'Empty API key; skipping.'
            } else {
                $baseUrl = Read-Host 'Base URL [https://api.moonshot.ai/v1]'
                if (-not $baseUrl) { $baseUrl = 'https://api.moonshot.ai/v1' }
                $modelId = Read-Host 'Model id [kimi-for-coding]'
                if (-not $modelId) { $modelId = 'kimi-for-coding' }

                $existingConfig = ''
                if (Test-Path $script:ConfigFile) {
                    $existingConfig = Get-Content $script:ConfigFile -Raw -ErrorAction SilentlyContinue
                    if (-not $existingConfig) { $existingConfig = '' }
                }

                $providerId = 'kimi'
                if ($existingConfig -match '\[providers\.kimi\]') { $providerId = 'kimi-platform' }

                $tables = "`r`n[providers.$providerId]`r`n"
                $tables += "type = `"kimi`"`r`n"
                $tables += "base_url = `"$baseUrl`"`r`n"
                $tables += "api_key = `"$apiKey`"`r`n"
                $tables += "`r`n[models.`"$providerId/$modelId`"]`r`n"
                $tables += "provider = `"$providerId`"`r`n"
                $tables += "model = `"$modelId`"`r`n"

                # Top-level keys must precede the first TOML table, so
                # default_model goes at the top of the file; the new provider
                # and model tables go at the end.
                $newConfig = $existingConfig.TrimEnd()
                if ($newConfig -notmatch '(?m)^\s*default_model\s*=') {
                    $newConfig = "default_model = `"$providerId/$modelId`"`r`n" + $newConfig
                }
                $newConfig += $tables

                if (-not (Test-Path $script:KimiHome)) {
                    New-Item -ItemType Directory -Path $script:KimiHome -Force | Out-Null
                }
                # Write UTF-8 without a BOM.
                [System.IO.File]::WriteAllText($script:ConfigFile, $newConfig, (New-Object System.Text.UTF8Encoding($false)))
                Write-Ok "Provider '$providerId' with model '$modelId' written to $script:ConfigFile."
                Write-Warn 'config.toml now contains an API key. It lives in your user profile,'
                Write-Warn 'which only your account can read - keep it that way and never share the file.'
            }
        } else {
            Write-Warn 'Skipped credential setup. The app cannot chat until a model is configured.'
            Write-Warn 'Configure one later with: kimi login   (or /provider inside the TUI)'
        }
    }
}

if (-not $kimiExe) {
    Write-Err 'Kimi Code binary not found; cannot continue.'
    exit 1
}

# ---------------------------------------------------------------------------
# Step 3: choose the daemon port
# ---------------------------------------------------------------------------

$state = Read-SavedState
$port = $script:DefaultPort
if ($Port -gt 0) {
    $port = $Port
} elseif ($state -and $state.port) {
    $port = [int]$state.port
}

$daemonAlreadyHealthy = $false
if (Test-PortFree -PortNumber $port) {
    Write-Info "Port $port is free."
} else {
    Write-Info "Port $port is occupied; probing what is serving it..."
    if ((Get-HealthzStatus -PortNumber $port -Token $null) -eq 200) {
        Write-Ok "A healthy Kimi Code web server is already running on port $port; adopting it."
        $daemonAlreadyHealthy = $true
    } else {
        Write-Warn "Port $port is occupied by something that is not a healthy Kimi Code web server."
        while ($true) {
            $answer = Read-Host "Enter another port to use [$($port + 1)]"
            if (-not $answer) { $answer = [string]($port + 1) }
            $candidate = 0
            if ([int]::TryParse($answer, [ref]$candidate) -and $candidate -ge 1 -and $candidate -le 65535) {
                if (Test-PortFree -PortNumber $candidate) {
                    $port = $candidate
                    break
                }
                Write-Warn "Port $candidate is also occupied."
            } else {
                Write-Warn 'Please enter a valid port number (1-65535).'
            }
        }
        Write-Info "Using port $port."
    }
}
Save-State -PortNumber $port

# ---------------------------------------------------------------------------
# Step 4: bearer token
# ---------------------------------------------------------------------------

$token = Read-BearerToken
if ($token) {
    Write-Ok "Reusing existing bearer token from $script:TokenFile (never rotated by this script)."
} else {
    Write-Info 'No bearer token yet; it will be generated when the daemon first starts.'
}

# ---------------------------------------------------------------------------
# Step 5: optional web password + launcher scripts
# ---------------------------------------------------------------------------

$webPassword = Get-LauncherPassword
if ($webPassword) {
    Write-Ok 'Reusing the web password already baked into the launcher script.'
} else {
    Write-Host ''
    Write-Host 'Optional web password (KIMI_CODE_PASSWORD)' -ForegroundColor White
    Write-Host '  Recommended when binding beyond loopback: browsers must supply this'
    Write-Host '  password in addition to the bearer token.'
    $webPassword = Read-Host 'Set a password (press Enter to skip)'
}

if (-not (Test-Path $script:ServerDir)) {
    New-Item -ItemType Directory -Path $script:ServerDir -Force | Out-Null
}
if (-not (Test-Path $script:LogDir)) {
    New-Item -ItemType Directory -Path $script:LogDir -Force | Out-Null
}

# Hostnames the daemon should accept in the Host header (DNS-rebinding guard).
$allowedHosts = @($env:COMPUTERNAME)
$tsInfo = Get-TailscaleInfo
if ($tsInfo.Available -and $tsInfo.DnsName) { $allowedHosts += $tsInfo.DnsName }
$allowedHostArg = ($allowedHosts | Where-Object { $_ }) -join ','

# The password is baked into the launcher itself. Do NOT rely on user-level
# environment variables: Scheduled Tasks and freshly spawned shells routinely
# run without them, and the daemon would silently start unauthenticated.
$cmdLines = @()
$cmdLines += '@echo off'
$cmdLines += 'rem Generated by install.ps1 - Kimi Code web daemon launcher.'
$cmdLines += "set `"KIMI_CODE_HOME=$script:KimiHome`""
if ($webPassword) {
    $cmdLines += "set `"KIMI_CODE_PASSWORD=$webPassword`""
}
$daemonLog = Join-Path $script:LogDir 'web-daemon.log'
$webArgs = "web --port $port --host=0.0.0.0 --no-open"
if ($allowedHostArg) { $webArgs += " --allowed-host `"$allowedHostArg`"" }
$cmdLines += "`"$kimiExe`" $webArgs >> `"$daemonLog`" 2>&1"
$cmdLines -join "`r`n" | Set-Content -Path $script:LauncherCmd -Encoding ASCII
Write-Ok "Launcher written: $script:LauncherCmd"

# wscript runs the .cmd with window style 0 (fully hidden).
$vbsLines = @()
$vbsLines += 'Set launcherShell = CreateObject("WScript.Shell")'
$vbsLines += 'launcherShell.Run """' + $script:LauncherCmd + '""", 0, False'
$vbsLines -join "`r`n" | Set-Content -Path $script:LauncherVbs -Encoding ASCII
Write-Ok "Hidden wrapper written: $script:LauncherVbs"

# ---------------------------------------------------------------------------
# Step 6: start the daemon now (unless one is already healthy)
# ---------------------------------------------------------------------------

if ($daemonAlreadyHealthy) {
    Write-Info 'Daemon is already running; no new instance started.'
} else {
    Write-Info 'Starting the daemon (hidden)...'
    Start-Process -FilePath 'wscript.exe' -ArgumentList "`"$script:LauncherVbs`"" -WindowStyle Hidden

    $deadline = (Get-Date).AddSeconds(60)
    $healthy = $false
    while ((Get-Date) -lt $deadline) {
        if ((Get-HealthzStatus -PortNumber $port -Token $null) -eq 200) { $healthy = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $healthy) {
        Write-Err "The daemon did not become healthy on port $port within 60 seconds."
        Write-Err "Check the log: $daemonLog"
        exit 1
    }
    Write-Ok "Daemon is up on port $port."
}

# The token file is generated by the daemon on first start; wait for it.
if (-not $token) {
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        $token = Read-BearerToken
        if ($token) { break }
        Start-Sleep -Seconds 1
    }
    if (-not $token) {
        Write-Err "No bearer token appeared at $script:TokenFile."
        Write-Err 'Check the daemon log and re-run this script.'
        exit 1
    }
    Write-Ok 'Bearer token generated by the daemon.'
}

# ---------------------------------------------------------------------------
# Step 7: autostart via Scheduled Task (at logon)
# ---------------------------------------------------------------------------

$existingTask = Get-ScheduledTask -TaskName $script:TaskName -ErrorAction SilentlyContinue
if ($existingTask) {
    Write-Ok "Scheduled Task '$script:TaskName' already exists; leaving it as is."
} else {
    $taskCreated = $false

    # Preferred method: schtasks.exe resolves the current user itself, without
    # the CIM UserId/SID mapping that fails in non-interactive contexts
    # (e.g. SSH sessions throw HRESULT 0x80070534 from Register-ScheduledTask).
    # Embedded quotes in /tr are backslash-escaped, the PowerShell 5.1 way of
    # passing quoted arguments to native commands.
    try {
        $runLine = 'wscript.exe \"' + $script:LauncherVbs + '\"'
        $null = & schtasks.exe /create /tn $script:TaskName /sc onlogon /rl LIMITED /f /tr $runLine 2>&1
        if ($LASTEXITCODE -eq 0) { $taskCreated = $true }
    } catch { }

    if (-not $taskCreated) {
        Write-Info 'schtasks.exe failed; falling back to Register-ScheduledTask...'
        try {
            $action = New-ScheduledTaskAction -Execute 'wscript.exe' -Argument "`"$script:LauncherVbs`""
            $trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
            $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" `
                -LogonType Interactive -RunLevel Limited
            $settings = New-ScheduledTaskSettingsSet -Hidden -AllowStartIfOnBatteries `
                -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero)
            Register-ScheduledTask -TaskName $script:TaskName -Action $action -Trigger $trigger `
                -Principal $principal -Settings $settings -ErrorAction Stop `
                -Description 'Starts the Kimi Code web daemon (kimi web) hidden at user logon.' | Out-Null
            $taskCreated = $true
        } catch {
            Write-Info "Register-ScheduledTask failed: $($_.Exception.Message)"
        }
    }

    # Report success only when the task is verifiably present.
    if ($taskCreated) {
        $verifiedTask = Get-ScheduledTask -TaskName $script:TaskName -ErrorAction SilentlyContinue
        if (-not $verifiedTask) { $taskCreated = $false }
    }

    if ($taskCreated) {
        Write-Ok "Scheduled Task '$script:TaskName' created (runs at your logon)."
    } else {
        Write-Warn "Could not create Scheduled Task '$script:TaskName' (see messages above)."
        Write-Warn 'Autostart is NOT configured. To add it manually, run:'
        Write-Warn "  schtasks /create /tn `"$script:TaskName`" /sc onlogon /f /tr `"wscript.exe \`"$script:LauncherVbs\`"`""
        Write-Warn "Or start the daemon manually for this session only: $script:LauncherVbs"
    }
}

# ---------------------------------------------------------------------------
# Step 8: firewall rule (optional, needs admin)
# ---------------------------------------------------------------------------

$ruleName = "Kimi Code Web $port"
$ruleExists = $false
try {
    $showOutput = netsh advfirewall firewall show rule name="$ruleName" 2>$null
    if ($LASTEXITCODE -eq 0 -and $showOutput -match 'Rule Name') { $ruleExists = $true }
} catch { }

if ($ruleExists) {
    Write-Ok "Firewall rule '$ruleName' already exists."
} else {
    $answer = Read-Host "Add a Windows Firewall inbound rule '$ruleName' for TCP port $port? [Y/n]"
    if ($answer -eq '' -or $answer -match '^[Yy]') {
        if (Test-IsAdministrator) {
            netsh advfirewall firewall add rule name="$ruleName" dir=in action=allow protocol=TCP localport=$port | Out-Null
            if ($LASTEXITCODE -eq 0) {
                Write-Ok "Firewall rule '$ruleName' added."
            } else {
                Write-Warn 'netsh reported an error while adding the rule; continuing without it.'
            }
        } else {
            Write-Warn 'Administrator rights are required to add firewall rules; continuing without one.'
            Write-Warn 'To add it later, run this in an elevated prompt:'
            Write-Warn "  netsh advfirewall firewall add rule name=`"$ruleName`" dir=in action=allow protocol=TCP localport=$port"
        }
    } else {
        Write-Info 'Skipped firewall rule. Only machines on networks Windows already trusts will reach the daemon.'
    }
}

# ---------------------------------------------------------------------------
# Step 9: self-check (all three must pass)
# ---------------------------------------------------------------------------

Write-Host ''
Write-Info 'Running self-check...'
$selfCheckOk = $true

$statusNoAuth = Get-HealthzStatus -PortNumber $port -Token $null
if ($statusNoAuth -eq 200) {
    Write-Ok '  healthz without auth -> 200'
} else {
    Write-Err "  healthz without auth -> $statusNoAuth (expected 200)"
    $selfCheckOk = $false
}

$wrongToken = 'wrong-token-' + [Guid]::NewGuid().ToString('N')
$statusWrong = Get-MetaStatus -PortNumber $port -Token $wrongToken
if ($statusWrong -eq 401) {
    Write-Ok '  meta with a wrong token -> 401'
} else {
    Write-Err "  meta with a wrong token -> $statusWrong (expected 401)"
    $selfCheckOk = $false
}

$statusRight = Get-MetaStatus -PortNumber $port -Token $token
if ($statusRight -eq 200) {
    Write-Ok '  meta with the right token -> 200'
} else {
    Write-Err "  meta with the right token -> $statusRight (expected 200)"
    $selfCheckOk = $false
}

if (-not $selfCheckOk) {
    Write-Err 'Self-check failed. The daemon is not in a trustworthy state; aborting before printing credentials.'
    exit 1
}
Write-Ok 'Self-check passed.'

# ---------------------------------------------------------------------------
# Step 10: connection URLs + connect card
# ---------------------------------------------------------------------------

$lanAddresses = Get-LanIPv4Addresses
$urls = @()
if ($tsInfo.Available -and $tsInfo.IPv4) { $urls += "http://$($tsInfo.IPv4):$port" }
foreach ($addr in $lanAddresses) { $urls += "http://${addr}:$port" }
if ($urls.Count -eq 0) { $urls += "http://127.0.0.1:$port" }
$primaryUrl = $urls[0]

$encodedName  = [System.Uri]::EscapeDataString($env:COMPUTERNAME)
$encodedUrl   = [System.Uri]::EscapeDataString($primaryUrl)
$encodedToken = [System.Uri]::EscapeDataString($token)
$deeplink = "kimi-mobile://connect?v=1&name=$encodedName&url=$encodedUrl&token=$encodedToken"

$jsonPayload = @{
    v     = 1
    name  = $env:COMPUTERNAME
    url   = $primaryUrl
    token = $token
} | ConvertTo-Json -Compress

$cardLines = @()
$cardLines += 'Kimi Mobile - Connect Card'
$cardLines += '=========================='
$cardLines += ''
$cardLines += "Host : $env:COMPUTERNAME"
$cardLines += "Port : $port"
$cardLines += ''
$cardLines += 'URLs (first reachable one wins; Tailscale preferred):'
foreach ($u in $urls) { $cardLines += "  $u" }
$cardLines += ''
$cardLines += "Token: $token"
if ($webPassword) {
    $cardLines += "Web password (browser UI only): $webPassword"
}
$cardLines += ''
$cardLines += 'Deeplink (open on your phone, or paste into the app):'
$cardLines += $deeplink
$cardLines += ''
$cardLines += 'JSON equivalent:'
$cardLines += $jsonPayload
$cardLines += ''
$cardLines += 'SECURITY: expose this server on your LAN or Tailnet only.'
$cardLines += 'Do NOT forward this port to the public internet.'
$cardText = $cardLines -join "`r`n"

$cardText | Set-Content -Path $script:ConnectCard -Encoding UTF8

Write-Host ''
Write-Host $cardText
Write-Host ''
Write-Ok "Connect card saved to $script:ConnectCard"

Write-Host ''
Write-Warn 'SECURITY: this daemon is meant for your LAN or Tailnet only.'
Write-Warn 'Do NOT expose the port to the public internet - the bearer token is the only barrier.'
if ($tsInfo.Available) {
    Write-Ok 'Tailscale detected; prefer the Tailscale URL above - it stays inside your tailnet.'
} else {
    Write-Info 'Tailscale not detected. Install it for easy, encrypted remote access outside your LAN.'
}

Write-Host ''
Write-Ok 'All done. Re-run this script any time; it only fills gaps.'
Write-Info "Autostart : Scheduled Task '$script:TaskName' (runs at logon)"
Write-Info "Logs      : $daemonLog"
Write-Info "Uninstall : powershell -ExecutionPolicy Bypass -File install.ps1 -Uninstall"
exit 0
