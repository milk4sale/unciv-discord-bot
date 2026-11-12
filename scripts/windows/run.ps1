# Starts CivBot on Windows. Use run.cmd in the main folder.
#   run.cmd              start the bot (keep the window open while people play)
#   run.cmd --selftest   play an offline test game, no Discord needed
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$bin = Join-Path $Root 'bot\build\install\civbot\bin\civbot.bat'
if (-not (Test-Path $bin)) { Write-Host 'Not built yet: run setup.cmd first.' -ForegroundColor Red; exit 1 }
$jh = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
if (-not $env:JAVA_HOME -and $jh) { $env:JAVA_HOME = $jh }
# Unciv loads its rules with relative paths, so run from its assets folder.
# (The bot finds this folder's .env and data from there, so no path is passed: civbot.bat
# would split a folder name that contains spaces.)
Set-Location (Join-Path $Root 'unciv\android\assets')
$ErrorActionPreference = 'Continue'   # the bot logs to stderr; don't treat that as a script error
& $bin @args
exit $LASTEXITCODE
