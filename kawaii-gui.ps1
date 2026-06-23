# ~ Kawaii Plugin Manager (clickable GUI) ~
# Run via build-all.bat, or directly:
#   powershell -NoProfile -ExecutionPolicy Bypass -File kawaii-gui.ps1

$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Root) { $Root = (Get-Location).Path }

# If this folder was downloaded as a ZIP (folder name ends in "-main", no .git),
# the manager can convert it into a real git repo so pull/refresh work.
# *** Set this to YOUR repo URL if the guess below is wrong. ***
$Script:RepoUrl = 'https://github.com/ferisooo/Minecraft-Paper-Plugins.git'

# Auto-discover plugins by scanning $Root for direct subfolders that contain a
# pom.xml. That way 'git pull' adding a new plugin shows up after a click of
# 'Reload list' — no need to keep a hardcoded array in sync with the repo.
function Get-Plugins {
    Get-ChildItem -Path $Root -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'pom.xml') } |
        Sort-Object Name |
        ForEach-Object { $_.Name }
}
$Script:Plugins = @(Get-Plugins)

# ----- Color palette ------------------------------------------------------
$pinkBg      = [System.Drawing.Color]::FromArgb(255, 252, 232, 240)
$pinkBtn     = [System.Drawing.Color]::FromArgb(255, 255, 198, 220)
$pinkBtnHvr  = [System.Drawing.Color]::FromArgb(255, 255, 174, 201)
$pinkBorder  = [System.Drawing.Color]::FromArgb(255, 220, 130, 170)
$pinkText    = [System.Drawing.Color]::FromArgb(255, 196,  60, 122)

# ----- Tooling discovery --------------------------------------------------

# True if the Maven at $mvncmd is actually runnable. The Maven bundled in this
# repo is hollow - .gitignore excludes *.jar, so its boot/lib jars were never
# committed and it dies with "-classpath requires class path specification".
# A working Maven has a plexus-classworlds jar under its boot\ folder.
function Test-MvnWorks([string]$mvncmd) {
    if (-not $mvncmd) { return $false }
    $mvnHome = Split-Path (Split-Path $mvncmd -Parent) -Parent
    $boot = Join-Path $mvnHome 'boot'
    if (-not (Test-Path $boot)) { return $false }
    return @(Get-ChildItem -Path $boot -Filter 'plexus-classworlds*.jar' -ErrorAction SilentlyContinue).Count -gt 0
}

# Download a complete Apache Maven into tools\ on demand and return its mvn.cmd.
function Ensure-RealMvn {
    $ver  = '3.9.16'
    $dest = Join-Path $Root 'tools'
    $mvn  = Join-Path $dest "apache-maven-$ver\bin\mvn.cmd"
    if (Test-Path $mvn) { return $mvn }
    $url = "https://archive.apache.org/dist/maven/maven-3/$ver/binaries/apache-maven-$ver-bin.zip"
    $zip = Join-Path $dest 'maven.zip'
    try {
        if (-not (Test-Path $dest)) { New-Item -ItemType Directory -Path $dest | Out-Null }
        Append-Out "* Downloading Apache Maven $ver - one-time setup ..."
        Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
        Append-Out '* Unzipping Maven ...'
        Expand-Archive -Force -Path $zip -DestinationPath $dest
        Remove-Item $zip -ErrorAction SilentlyContinue
    } catch {
        Append-Out "! Maven download failed: $_"
        return $null
    }
    if (Test-Path $mvn) { return $mvn }
    return $null
}

function Find-Mvn {
    $cmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    # Use a bundled Maven ONLY if it actually has its jars (skip the hollow one).
    $bundled = Get-ChildItem -Path $Root -Recurse -Filter 'mvn.cmd' -ErrorAction SilentlyContinue |
               Where-Object { Test-MvnWorks $_.FullName } |
               Select-Object -First 1
    if ($bundled) { return $bundled.FullName }
    # Nothing usable found - fetch a real Maven into tools\.
    return (Ensure-RealMvn)
}

function Find-Git {
    $cmd = Get-Command git -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

# ----- Form ---------------------------------------------------------------
$form = New-Object System.Windows.Forms.Form
$form.Text = '~ Kawaii Plugin Manager ~'
$form.Size = New-Object System.Drawing.Size(820, 650)
$form.StartPosition = 'CenterScreen'
$form.BackColor = $pinkBg
$form.Font = New-Object System.Drawing.Font('Segoe UI', 10)
$form.MinimumSize = New-Object System.Drawing.Size(740, 540)

$banner = New-Object System.Windows.Forms.Label
$banner.Text = '(^_^) /     Kawaii Plugin Manager     \ (^_^)'
$banner.Font = New-Object System.Drawing.Font('Segoe UI', 14, [System.Drawing.FontStyle]::Bold)
$banner.ForeColor = $pinkText
$banner.AutoSize = $true
$banner.Location = New-Object System.Drawing.Point(20, 12)
$form.Controls.Add($banner)

$pluginsLbl = New-Object System.Windows.Forms.Label
$pluginsLbl.Text = 'Plugins ~'
$pluginsLbl.AutoSize = $true
$pluginsLbl.Location = New-Object System.Drawing.Point(20, 56)
$form.Controls.Add($pluginsLbl)

$list = New-Object System.Windows.Forms.ListBox
$list.Location = New-Object System.Drawing.Point(20, 80)
$list.Size = New-Object System.Drawing.Size(260, 260)
$list.Font = New-Object System.Drawing.Font('Consolas', 10)
$form.Controls.Add($list)

# Re-scan disk and repopulate the listbox. Preserves the current selection
# by name when possible; falls back to the first entry.
function Refresh-PluginList {
    $previous = $list.SelectedItem
    $Script:Plugins = @(Get-Plugins)
    $list.BeginUpdate()
    $list.Items.Clear()
    if ($Script:Plugins.Count -gt 0) {
        $list.Items.AddRange($Script:Plugins)
        $idx = if ($previous) { $Script:Plugins.IndexOf([string]$previous) } else { -1 }
        if ($idx -lt 0) { $idx = 0 }
        $list.SelectedIndex = $idx
    }
    $list.EndUpdate()
}
Refresh-PluginList

function New-KawaiiButton([string]$text, [int]$y, [scriptblock]$onClick) {
    $b = New-Object System.Windows.Forms.Button
    $b.Text = $text
    $b.Location = New-Object System.Drawing.Point(300, $y)
    $b.Size = New-Object System.Drawing.Size(220, 38)
    $b.BackColor = $pinkBtn
    $b.ForeColor = $pinkText
    $b.FlatStyle = 'Flat'
    $b.FlatAppearance.MouseOverBackColor = $pinkBtnHvr
    $b.FlatAppearance.BorderColor = $pinkBorder
    $b.Font = New-Object System.Drawing.Font('Segoe UI', 10, [System.Drawing.FontStyle]::Bold)
    $b.Add_Click($onClick)
    $form.Controls.Add($b)
    return $b
}

$out = New-Object System.Windows.Forms.TextBox
$out.Location = New-Object System.Drawing.Point(20, 360)
$out.Size = New-Object System.Drawing.Size(760, 200)
$out.Anchor = ([System.Windows.Forms.AnchorStyles]::Top -bor `
               [System.Windows.Forms.AnchorStyles]::Left -bor `
               [System.Windows.Forms.AnchorStyles]::Right -bor `
               [System.Windows.Forms.AnchorStyles]::Bottom)
$out.Multiline = $true
$out.ScrollBars = 'Vertical'
$out.ReadOnly = $true
$out.Font = New-Object System.Drawing.Font('Consolas', 9)
$out.BackColor = [System.Drawing.Color]::White
$form.Controls.Add($out)

$status = New-Object System.Windows.Forms.Label
$status.Text = 'ready~'
$status.AutoSize = $false
$status.Size = New-Object System.Drawing.Size(760, 22)
$status.Location = New-Object System.Drawing.Point(20, 565)
$status.Anchor = ([System.Windows.Forms.AnchorStyles]::Left -bor `
                  [System.Windows.Forms.AnchorStyles]::Right -bor `
                  [System.Windows.Forms.AnchorStyles]::Bottom)
$status.ForeColor = [System.Drawing.Color]::FromArgb(255, 130, 60, 100)
$form.Controls.Add($status)

# ----- Helpers ------------------------------------------------------------
$Script:Busy = $false
$Script:Buttons = @()

function Append-Out([string]$msg) {
    $out.AppendText($msg + "`r`n")
    $out.SelectionStart = $out.Text.Length
    $out.ScrollToCaret()
    [System.Windows.Forms.Application]::DoEvents()
}

function Set-Busy([bool]$busy, [string]$msg) {
    $Script:Busy = $busy
    foreach ($b in $Script:Buttons) { $b.Enabled = -not $busy }
    $list.Enabled = -not $busy
    if ($msg) { $status.Text = $msg }
    [System.Windows.Forms.Application]::DoEvents()
}

function Run-Cmd([string]$exe, [string[]]$argList, [string]$workdir) {
    Append-Out ""
    Append-Out ("> {0} {1}    (in {2})" -f $exe, ($argList -join ' '), $workdir)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $exe
    $psi.Arguments = ($argList | ForEach-Object {
        if ($_ -match '\s') { '"' + $_ + '"' } else { $_ }
    }) -join ' '
    $psi.WorkingDirectory = $workdir
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    try {
        $p = [System.Diagnostics.Process]::Start($psi)
    } catch {
        Append-Out ("! failed to start {0}: {1}" -f $exe, $_.Exception.Message)
        return -1
    }
    while (-not $p.HasExited) {
        if (-not $p.StandardOutput.EndOfStream) {
            Append-Out $p.StandardOutput.ReadLine()
        } else {
            Start-Sleep -Milliseconds 40
        }
        [System.Windows.Forms.Application]::DoEvents()
    }
    while (-not $p.StandardOutput.EndOfStream) { Append-Out $p.StandardOutput.ReadLine() }
    while (-not $p.StandardError.EndOfStream)  { Append-Out ('! ' + $p.StandardError.ReadLine()) }
    return $p.ExitCode
}

function Clear-Target([string]$dir) {
    # Maven's own 'clean' goal fails on Windows when target\ files are marked
    # read-only (a common side effect of copying out of OneDrive) or briefly
    # locked by AV/an editor. Do the wipe ourselves: strip read-only, then
    # delete with a few retries. We then run Maven WITHOUT 'clean'.
    $target = Join-Path $dir 'target'
    if (-not (Test-Path $target)) { return }
    for ($i = 0; $i -lt 4; $i++) {
        try {
            Get-ChildItem -LiteralPath $target -Recurse -Force -ErrorAction SilentlyContinue |
                ForEach-Object { $_.Attributes = 'Normal' }
            Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction Stop
            return
        } catch {
            Start-Sleep -Milliseconds 400
        }
    }
    Append-Out "  (note) couldn't fully delete target\ - building over it"
}

function Build-Plugin([string]$name) {
    $mvn = Find-Mvn
    if (-not $mvn) {
        Append-Out "! Maven not found. Install JDK 21+ and Maven, or drop apache-maven-X.Y.Z under the repo root."
        return $false
    }
    $dir = Join-Path $Root $name
    if (-not (Test-Path $dir)) { Append-Out "! folder '$name' does not exist"; return $false }

    Clear-Target $dir
    $code = Run-Cmd $mvn @('-B','-DskipTests','package') $dir
    if ($code -ne 0) { Append-Out "! build failed for $name (exit $code)"; return $false }

    $dist = Join-Path $Root 'dist'
    if (-not (Test-Path $dist)) { New-Item -ItemType Directory -Path $dist | Out-Null }
    Get-ChildItem (Join-Path $dir 'target') -Filter '*.jar' -ErrorAction SilentlyContinue | ForEach-Object {
        Copy-Item $_.FullName -Destination $dist -Force
        Append-Out ("  copied {0} -> dist\" -f $_.Name)
    }
    return $true
}

# Make sure $Root is a real git repo. If it isn't (downloaded ZIP), offer to
# convert it in place WITHOUT deleting any local files, then track origin/main.
function Ensure-GitRepo {
    if (-not (Find-Git)) { Append-Out '! git not found on PATH (install Git for Windows from https://git-scm.com/)'; return $false }
    if (Test-Path (Join-Path $Root '.git')) { return $true }

    $msg = "This folder isn't a git repo yet.`r`n`r`n" +
           "It looks like it was downloaded as a ZIP (no .git folder), so pull / refresh can't work.`r`n`r`n" +
           "Convert this folder into a repo that tracks:`r`n  $Script:RepoUrl`r`n`r`n" +
           "Your local files are KEPT - nothing is deleted.`r`n" +
           "(If that URL is wrong, edit `$Script:RepoUrl at the top of the script.)`r`n`r`nConvert now?"
    $r = [System.Windows.Forms.MessageBox]::Show($msg, 'Set up git repo', 'YesNo', 'Question')
    if ($r -ne [System.Windows.Forms.DialogResult]::Yes) { Append-Out 'cancelled git setup'; return $false }

    Append-Out '*~ setting up git repo ~*'
    if ((Run-Cmd 'git' @('init') $Root) -ne 0) { Append-Out '! git init failed'; return $false }
    if ((Run-Cmd 'git' @('remote','add','origin',$Script:RepoUrl) $Root) -ne 0) {
        # origin already exists - just point it at the right place
        Run-Cmd 'git' @('remote','set-url','origin',$Script:RepoUrl) $Root | Out-Null
    }
    Append-Out '  fetching origin (first time, may take a bit)...'
    if ((Run-Cmd 'git' @('fetch','origin') $Root) -ne 0) { Append-Out '! fetch failed - check the URL and your internet'; return $false }
    # Move the local branch onto origin/main WITHOUT touching working-tree files.
    if ((Run-Cmd 'git' @('reset','origin/main') $Root) -ne 0) { Append-Out '! could not align with origin/main (is the default branch named "main"?)'; return $false }
    Run-Cmd 'git' @('branch','-M','main') $Root | Out-Null
    Run-Cmd 'git' @('branch','--set-upstream-to=origin/main','main') $Root | Out-Null
    Append-Out '(^_^)/ repo is set up! tracking origin/main now'
    return $true
}

function Pull-Main {
    if (-not (Ensure-GitRepo)) { return }
    $code = Run-Cmd 'git' @('pull','origin','main') $Root
    if ($code -eq 0) {
        Append-Out "(^_^)/ up to date with origin/main"
        # A pull may have added or removed plugins on disk — re-scan automatically
        # so the list reflects what's actually buildable right now.
        Refresh-PluginList
        Append-Out ("plugin list refreshed: " + ($Script:Plugins -join ', '))
    } else {
        Append-Out "! git pull failed (exit $code)"
    }
}

function Refresh-Plugin([string]$name) {
    if (-not (Ensure-GitRepo)) { return }
    $r = [System.Windows.Forms.MessageBox]::Show(
        "Delete the local '$name' folder and restore it from origin/main?`r`nAny uncommitted changes there will be lost.",
        'Refresh plugin', 'YesNo', 'Warning')
    if ($r -ne [System.Windows.Forms.DialogResult]::Yes) { Append-Out 'cancelled'; return }

    $code = Run-Cmd 'git' @('fetch','origin','main') $Root
    if ($code -ne 0) { Append-Out '! fetch failed'; return }

    $dir = Join-Path $Root $name
    if (Test-Path $dir) {
        try { Remove-Item -Recurse -Force $dir }
        catch { Append-Out ("! could not delete {0}: {1}" -f $name, $_.Exception.Message); return }
    }
    $code = Run-Cmd 'git' @('checkout','origin/main','--',$name) $Root
    if ($code -eq 0) { Append-Out "(^_^)/ refreshed $name" }
    else             { Append-Out "! restore failed for $name (exit $code)" }
}

# ----- Buttons ------------------------------------------------------------
$Script:Buttons += New-KawaiiButton 'Reload plugin list'  80  {
    if ($Script:Busy) { return }
    Refresh-PluginList
    $status.Text = ("plugins: {0}" -f $Script:Plugins.Count)
    Append-Out ("reloaded list: " + ($Script:Plugins -join ', '))
}
$Script:Buttons += New-KawaiiButton 'Build selected'      124 {
    if ($Script:Busy) { return }
    $sel = $list.SelectedItem; if (-not $sel) { return }
    Set-Busy $true "building $sel..."
    [void](Build-Plugin $sel)
    Set-Busy $false 'ready~'
}
$Script:Buttons += New-KawaiiButton 'Build all'           168 {
    if ($Script:Busy) { return }
    Set-Busy $true 'building all...'
    $failed = @()
    # Re-scan so a plugin pulled mid-session (or one the user dropped into the
    # folder by hand) is picked up without needing 'Reload plugin list' first.
    Refresh-PluginList
    foreach ($p in $Script:Plugins) {
        Append-Out ""
        Append-Out ("--- {0} ---" -f $p)
        if (-not (Build-Plugin $p)) { $failed += $p }
    }
    Append-Out ""
    if ($failed.Count -gt 0) { Append-Out ("! failed: " + ($failed -join ', ')) }
    else                     { Append-Out "(^_^)/ all built" }
    Set-Busy $false 'ready~'
}
$Script:Buttons += New-KawaiiButton 'Pull origin/main'    212 {
    if ($Script:Busy) { return }
    Set-Busy $true 'pulling...'
    Pull-Main
    Set-Busy $false 'ready~'
}
$Script:Buttons += New-KawaiiButton 'Refresh selected'    256 {
    if ($Script:Busy) { return }
    $sel = $list.SelectedItem; if (-not $sel) { return }
    Set-Busy $true "refreshing $sel..."
    Refresh-Plugin $sel
    Refresh-PluginList
    Set-Busy $false 'ready~'
}
$Script:Buttons += New-KawaiiButton 'Open dist\ folder'   300 {
    $dist = Join-Path $Root 'dist'
    if (-not (Test-Path $dist)) { New-Item -ItemType Directory -Path $dist | Out-Null }
    Start-Process explorer.exe $dist
}

# ----- Boot diagnostics ---------------------------------------------------
Append-Out ("ROOT = {0}" -f $Root)
$javaCmd = Get-Command java -ErrorAction SilentlyContinue
if ($javaCmd) { Append-Out ("java = {0}" -f $javaCmd.Source) }
else          { Append-Out '! java not on PATH (install JDK 21+ from https://adoptium.net/)' }
$mvn = Find-Mvn
if ($mvn) { Append-Out ("mvn  = {0}" -f $mvn) }
else      { Append-Out '! mvn not found (install Maven, or drop apache-maven-X.Y.Z under repo root)' }
$git = Find-Git
if ($git) { Append-Out ("git  = {0}" -f $git) }
else      { Append-Out '! git not on PATH (install Git for Windows from https://git-scm.com/)' }
Append-Out ("plugins ({0}): {1}" -f $Script:Plugins.Count, ($Script:Plugins -join ', '))

[void]$form.ShowDialog()
