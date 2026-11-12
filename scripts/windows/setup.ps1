# CivBot setup for Windows (safe to re-run). Start it with setup.cmd in the main folder.
#   1. checks for Java 21 (offers to install it with winget)
#   2. downloads Unciv (the game engine) at a known-good commit
#   3. wires the bot into Unciv's Gradle build and patches one crashy Unciv assertion
#   4. builds the bot
#   5. asks for your Discord bot token and writes .env
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is very slow with the progress bar on
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $Root
$UncivCommit = '0f2eb314a17213da0c0ce3b9d9c4693e2ed6ef96'   # keep in sync with setup.sh
$Interactive = -not $env:CI
$Utf8 = New-Object System.Text.UTF8Encoding($false)          # no BOM: Gradle, Kotlin and the bot all want plain UTF-8

function Fail($msg) { Write-Host ""; Write-Host $msg -ForegroundColor Red; exit 1 }
function Step($msg) { Write-Host ""; Write-Host "== $msg" -ForegroundColor Cyan }
# Windows PowerShell 5.1 turns a program's stderr output into errors when output is redirected;
# run programs with errors relaxed and check their exit codes instead.
function Native([scriptblock]$b) { $ErrorActionPreference = 'Continue'; & $b }
function Has($cmd) { [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }
function Refresh-Path {
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
}

function Get-JavaMajor {
    if (-not (Has 'java')) { return 0 }
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $out = (& java -version 2>&1 | Out-String)
    $ErrorActionPreference = $old
    if ($out -match 'version "(\d+)') { return [int]$Matches[1] }
    return 0
}

# --- 1. Java ---------------------------------------------------------------------------------
Step 'Checking Java'
$java = Get-JavaMajor
if ($java -lt 21) {
    if ($java -gt 0) { Write-Host "Java $java is installed, but CivBot needs Java 21 or newer." -ForegroundColor Yellow }
    else { Write-Host 'Java is not installed.' -ForegroundColor Yellow }
    if ($Interactive -and (Has 'winget')) {
        $a = Read-Host 'Install Java 21 now with winget? [Y/n]'
        if ($a -eq '' -or $a -match '^[Yy]') {
            Native { winget install --id EclipseAdoptium.Temurin.21.JDK -e --accept-source-agreements --accept-package-agreements }
            Refresh-Path
            # The installer sets JAVA_HOME; use it even if PATH hasn't caught up yet
            $jh = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
            if ($jh -and (Test-Path "$jh\bin\java.exe")) { $env:JAVA_HOME = $jh; $env:Path = "$jh\bin;" + $env:Path }
            $java = Get-JavaMajor
        }
    }
    if ($java -lt 21) {
        Fail ("Install a Java 21 JDK, then run setup.cmd again:`n" +
              "  winget install EclipseAdoptium.Temurin.21.JDK`n" +
              "  or download the .msi from https://adoptium.net (tick 'Set JAVA_HOME' in the installer)")
    }
}
Write-Host "Java $java found."

# --- 2. Unciv --------------------------------------------------------------------------------
Step 'Getting Unciv (the game engine)'
$marker = Join-Path $Root 'unciv\.civbot-commit'
$current = ''
if (Test-Path 'unciv\.git') {
    $ErrorActionPreference = 'Continue'   # Windows PowerShell 5.1 turns git's stderr into errors
    $current = "$(& git -C unciv rev-parse HEAD 2>$null)".Trim()
    $ErrorActionPreference = 'Stop'
} elseif (Test-Path $marker) {
    $current = (Get-Content $marker -Raw).Trim()
}
if ($current -ne $UncivCommit) {
    if ((Has 'git') -and -not $env:CIVBOT_NO_GIT) {
        if (-not (Test-Path 'unciv\.git')) {
            if (Test-Path 'unciv') { Remove-Item -Recurse -Force 'unciv' }
            Native { git init -q unciv; git -C unciv remote add origin https://github.com/yairm210/Unciv.git }
        }
        # Keep Unciv's files byte-for-byte as upstream (gradlew must keep its line endings)
        Native { git -C unciv config core.autocrlf false }
        Write-Host 'Downloading Unciv with git (about 100 MB)...'
        Native { git -C unciv fetch -q --depth 1 origin $UncivCommit }
        if ($LASTEXITCODE -ne 0) { Fail 'Downloading Unciv failed. Check your internet connection and try again.' }
        Native { git -C unciv checkout -q -f FETCH_HEAD }
        if ($LASTEXITCODE -ne 0) { Fail 'Checking out Unciv failed.' }
    } else {
        # No git needed: download the same commit as a zip
        Write-Host 'Downloading Unciv (about 150 MB, a few minutes)...'
        $tmp = Join-Path $Root 'unciv-download'
        if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
        New-Item -ItemType Directory $tmp | Out-Null
        $zip = Join-Path $tmp 'unciv.zip'
        Invoke-WebRequest -UseBasicParsing "https://codeload.github.com/yairm210/Unciv/zip/$UncivCommit" -OutFile $zip
        Write-Host 'Unpacking...'
        if (Has 'tar.exe') { Native { tar.exe -xf $zip -C $tmp }; if ($LASTEXITCODE -ne 0) { Fail 'Unpacking Unciv failed.' } }
        else { Expand-Archive $zip -DestinationPath $tmp }
        Remove-Item $zip
        $src = Get-ChildItem $tmp -Directory | Select-Object -First 1
        if (Test-Path 'unciv') { Remove-Item -Recurse -Force 'unciv' }
        Move-Item $src.FullName (Join-Path $Root 'unciv')
        Remove-Item -Recurse -Force $tmp
        [IO.File]::WriteAllText($marker, $UncivCommit, $Utf8)
    }
}
Write-Host 'Unciv is ready.'

# --- 3. Wire in the bot and patch -------------------------------------------------------------
$settings = Join-Path $Root 'unciv\settings.gradle.kts'
$text = [IO.File]::ReadAllText($settings)
if ($text -notmatch 'civbot') {
    $text += "`n// --- added by civbot setup ---`ninclude("":civbot"")`nproject("":civbot"").projectDir = file(""../bot"")`n"
    [IO.File]::WriteAllText($settings, $text, $Utf8)
}
# Turn Unciv's "City within distance" developer assertion (it can crash AI turns) into a skipped move
$sua = Join-Path $Root 'unciv\core\src\com\unciv\logic\automation\unit\SpecificUnitAutomation.kt'
$code = [IO.File]::ReadAllText($sua)
$patched = $code.Replace('throw Exception("City within distance")', 'return // civbot patch: was throw Exception("City within distance")')
if ($patched -ne $code) { [IO.File]::WriteAllText($sua, $patched, $Utf8) }

# --- 4. Build -------------------------------------------------------------------------------
Step 'Building (the first build downloads Gradle and libraries: a few minutes)'
Native { & (Join-Path $Root 'unciv\gradlew.bat') -p unciv :civbot:installDist --console=plain -q }
if ($LASTEXITCODE -ne 0) { Fail 'The build failed. See the messages above, and docs/TROUBLESHOOTING.md.' }
Write-Host 'Build finished.'

# --- 5. Token ---------------------------------------------------------------------------------
$envFile = Join-Path $Root '.env'
$hasToken = (Test-Path $envFile) -and ((Get-Content $envFile -Raw) -match '(?m)^\s*DISCORD_TOKEN\s*=\s*\S')
if (-not $hasToken -and $Interactive) {
    Step 'Discord bot token'
    Write-Host 'Paste your bot token (Discord Developer Portal > your app > Bot > Reset Token).'
    Write-Host 'Right-click pastes. It stays hidden while you type; press Enter when done (or just Enter to skip).'
    $secure = Read-Host 'Token' -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    $token = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr).Trim()
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    if ($token) {
        $template = [IO.File]::ReadAllText((Join-Path $Root '.env.example'))
        $out = [regex]::Replace($template, '(?m)^DISCORD_TOKEN=.*$', "DISCORD_TOKEN=$token")
        [IO.File]::WriteAllText($envFile, $out, $Utf8)
        $hasToken = $true
        Write-Host 'Saved to .env'
    }
}

Write-Host ''
Write-Host 'Done!' -ForegroundColor Green
if (-not $hasToken) { Write-Host 'Next: run setup.cmd again and paste your bot token, or copy .env.example to .env and put it there.' }
Write-Host 'Start the bot:           double-click run.cmd'
Write-Host 'Run it in the background: scripts\windows\install-service.cmd   (starts when you log in)'
Write-Host 'Test without Discord:    run.cmd --selftest'
