# Runs CivBot in the background on Windows: a Task Scheduler task that starts when you log in
# and restarts the bot if it crashes. Use install-service.cmd next to this file.
#   install-service.cmd           install and start now
#   install-service.cmd -Remove   stop and uninstall
param([switch]$Remove, [switch]$Run)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$TaskName = 'CivBot'
$bin = Join-Path $Root 'bot\build\install\civbot\bin\civbot.bat'

function Stop-Bot {
    Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
        Where-Object { $_.CommandLine -like '*civbot*' } |
        ForEach-Object { Invoke-CimMethod -InputObject $_ -MethodName Terminate | Out-Null }
}

if ($Run) {
    # What the task runs: keep the bot going, restarting it 10 s after a crash.
    $jh = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
    if (-not $env:JAVA_HOME -and $jh) { $env:JAVA_HOME = $jh }
    while ($true) {
        $p = Start-Process -FilePath $bin -WorkingDirectory (Join-Path $Root 'unciv\android\assets') -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput (Join-Path $Root 'civbot.log') -RedirectStandardError (Join-Path $Root 'civbot-error.log')
        if ($p.ExitCode -eq 0) { break }
        Start-Sleep -Seconds 10
    }
    exit 0
}

if ($Remove) {
    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
        Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    }
    Stop-Bot
    Write-Host 'CivBot background task removed.'
    exit 0
}

if (-not (Test-Path $bin)) { Write-Host 'Build first: run setup.cmd.' -ForegroundColor Red; exit 1 }
$envFile = Join-Path $Root '.env'
if (-not ((Test-Path $envFile) -and ((Get-Content $envFile -Raw) -match '(?m)^\s*DISCORD_TOKEN\s*=\s*\S'))) {
    Write-Host 'Add your bot token first: run setup.cmd again, or put DISCORD_TOKEN=... in .env' -ForegroundColor Red; exit 1
}

$me = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -WorkingDirectory $Root `
    -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Run"
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $me
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId $me -LogonType Interactive -RunLevel Limited
Stop-Bot   # in case run.cmd is already running it: two copies would answer every click twice
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal `
    -Description 'CivBot: Unciv in Discord' -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName

Write-Host 'CivBot is running in the background and starts again whenever you log in.' -ForegroundColor Green
Write-Host "Logs:   $Root\civbot.log  (errors in civbot-error.log)"
Write-Host 'Stop:   scripts\windows\install-service.cmd -Remove'
