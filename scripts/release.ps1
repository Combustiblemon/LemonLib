<#
.SYNOPSIS
    Releases the mod: bumps every version file, rolls CHANGELOG.md "Unreleased" into the new
    version, builds the zip, commits, tags, pushes the release branch and creates a GitHub release.

.USAGE
    release.bat v0.1.2            full release
    release.bat v0.1.2 -DryRun    edit + build only; nothing committed, pushed or released

.NOTES
    Runs only on a clean, up-to-date release branch and never switches branches (branches are the
    developer's). Needs git and the GitHub CLI (`gh`, authenticated). PowerShell 5.1 compatible.
    The same script serves Covenant Remnants and LemonLib; only the config block differs.
#>
param(
    [Parameter(Position = 0)]
    [string]$Version,
    [switch]$DryRun
)

# ---------------------------------------------------------------------------------------------
# Config (the only per-repo part)
# ---------------------------------------------------------------------------------------------

$ModName = 'LemonLib'                      # release asset label
$VersionFileName = 'lemonlib.version'          # Version Checker file (major/minor/patch block)
$ZipName = 'LemonLib.zip'                   # output of `gradlew packageMod`
$ReleaseBranch = 'main'                     # the only branch releases are made from

$ErrorActionPreference = 'Stop'

function Fail([string]$message) {
    Write-Host ""
    Write-Host "RELEASE ABORTED: $message" -ForegroundColor Red
    exit 1
}

function Step([string]$message) {
    Write-Host ""
    Write-Host "== $message" -ForegroundColor Cyan
}

function Invoke-Native([string]$exe, [string[]]$arguments) {
    # PowerShell 5.1 wraps every stderr line of a native command in an ErrorRecord when stderr is
    # redirected, and with $ErrorActionPreference = 'Stop' that throws even on exit code 0 (git
    # fetch / push report progress on stderr). Run with 'Continue' and flatten to plain strings;
    # callers decide on $LASTEXITCODE.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $exe @arguments 2>&1 | ForEach-Object { "$_" }
    } finally {
        $ErrorActionPreference = $previous
    }
    return @($output)
}

function Run-Git([string[]]$arguments) {
    $output = Invoke-Native 'git' $arguments
    if ($LASTEXITCODE -ne 0) {
        Fail ("git " + ($arguments -join ' ') + " failed:`n" + ($output -join "`n"))
    }
    return $output
}

function Read-Text([string]$path) {
    return [IO.File]::ReadAllText($path)
}

function Write-Text([string]$path, [string]$text) {
    # UTF-8 without BOM; the text keeps whatever line endings it already had.
    [IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

# ---------------------------------------------------------------------------------------------
# Arguments
# ---------------------------------------------------------------------------------------------

if (-not $Version -or $Version -notmatch '^v?(\d+)\.(\d+)\.(\d+)$') {
    Write-Host "Usage: release.bat vX.Y.Z [-DryRun]"
    Write-Host "       e.g. release.bat v0.1.2"
    exit 1
}
$major = [int]$Matches[1]; $minor = [int]$Matches[2]; $patch = [int]$Matches[3]
$plain = "$major.$minor.$patch"
$tag = "v$plain"

$root = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $root

$modInfo = Join-Path $root 'mod_info.json'
$versionFile = Join-Path $root $VersionFileName
$gradleKts = Join-Path $root 'build.gradle.kts'
$gradleProps = Join-Path $root 'gradle.properties'
$changelog = Join-Path $root 'CHANGELOG.md'
$zip = Join-Path $root $ZipName
$notesDir = Join-Path $root 'build'
$notesFile = Join-Path $notesDir "release-notes-$tag.md"
$versionFiles = @('mod_info.json', $VersionFileName, 'build.gradle.kts', 'gradle.properties', 'CHANGELOG.md')
$assetArg = "$zip#$ModName $tag"
$resumeRelease = "  gh release create $tag `"$assetArg`" --title $tag --notes-file `"$notesFile`""

# ---------------------------------------------------------------------------------------------
# Pre-flight (nothing is modified before this block passes)
# ---------------------------------------------------------------------------------------------

Step "Pre-flight for $ModName $tag"

$branch = (Run-Git @('rev-parse', '--abbrev-ref', 'HEAD') | Select-Object -First 1).Trim()
if ($branch -ne $ReleaseBranch) {
    Fail "current branch is '$branch'. Releases are made from '$ReleaseBranch' only. Switch branches yourself and rerun."
}

$dirty = Run-Git @('status', '--porcelain')
if ($dirty) {
    Fail ("working tree is not clean. Commit or stash first:`n" + ($dirty -join "`n"))
}

Run-Git @('fetch', 'origin', $ReleaseBranch) | Out-Null
$behind = [int]((Run-Git @('rev-list', '--count', "HEAD..origin/$ReleaseBranch") | Select-Object -First 1).Trim())
if ($behind -gt 0) {
    Fail "local $ReleaseBranch is $behind commit(s) behind origin/$ReleaseBranch. Pull first."
}

# Resolve gh: PATH first, then the standard install folders. A terminal opened before the
# install (an IDE terminal in particular) still carries the old PATH, so PATH alone is not enough.
$gh = $null
$ghCommand = Get-Command gh -ErrorAction SilentlyContinue
if ($ghCommand) { $gh = $ghCommand.Source }
if (-not $gh) {
    foreach ($candidate in @("$env:ProgramFiles\GitHub CLI\gh.exe", "${env:ProgramFiles(x86)}\GitHub CLI\gh.exe", "$env:LOCALAPPDATA\Programs\GitHub CLI\gh.exe")) {
        if (Test-Path $candidate) { $gh = $candidate; break }
    }
}
if (-not $gh) {
    Fail "GitHub CLI 'gh' not found. Install once with:  winget install --id GitHub.cli  then run:  gh auth login"
}
Write-Host "gh: $gh"
$null = Invoke-Native $gh @('auth', 'status')
if ($LASTEXITCODE -ne 0) {
    Fail "GitHub CLI is not authenticated. Run:  & `"$gh`" auth login"
}

$localTag = Invoke-Native 'git' @('tag', '--list', $tag)
if ($localTag) { Fail "tag $tag already exists locally." }
$remoteTag = Invoke-Native 'git' @('ls-remote', '--tags', 'origin', "refs/tags/$tag")
if ($remoteTag) { Fail "tag $tag already exists on origin." }

foreach ($required in @($modInfo, $versionFile, $gradleKts, $changelog)) {
    if (-not (Test-Path $required)) { Fail "missing file: $required" }
}

$modInfoText = Read-Text $modInfo
if ($modInfoText -notmatch '"version":\s*"(\d+)\.(\d+)\.(\d+)"') {
    Fail "could not read the current version from mod_info.json"
}
$current = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3])
$new = @($major, $minor, $patch)
$isNewer = $false
for ($i = 0; $i -lt 3; $i++) {
    if ($new[$i] -gt $current[$i]) { $isNewer = $true; break }
    if ($new[$i] -lt $current[$i]) { break }
}
if (-not $isNewer) {
    Fail ("$tag is not newer than the current version v" + ($current -join '.'))
}

$changelogText = Read-Text $changelog
$newline = if ($changelogText.Contains("`r`n")) { "`r`n" } else { "`n" }
$unreleasedPattern = '(?s)(## Unreleased[ \t]*' + [regex]::Escape($newline) + ')(.*?)(?=' + [regex]::Escape($newline) + '## |\z)'
$match = [regex]::Match($changelogText, $unreleasedPattern)
if (-not $match.Success) {
    Fail "CHANGELOG.md has no '## Unreleased' section, so there is nothing to release. Add one above the last version with the changes since then."
}
$notes = $match.Groups[2].Value.Trim("`r", "`n")
if ($notes -notmatch '(?m)^- ') {
    Fail "the '## Unreleased' section of CHANGELOG.md has no bullets. Write the changelog first."
}

Write-Host "branch $ReleaseBranch, clean, up to date; gh authenticated; tag $tag free; v$($current -join '.') -> $tag" -ForegroundColor Green

# ---------------------------------------------------------------------------------------------
# Edits
# ---------------------------------------------------------------------------------------------

Step "Bumping version files"

$modInfoText = [regex]::Replace($modInfoText, '"version":\s*"[^"]*"', ('"version": "' + $plain + '"'), 1)
Write-Text $modInfo $modInfoText

$versionText = Read-Text $versionFile
$versionText = [regex]::Replace($versionText, '"major":\s*\d+', ('"major":' + $major), 1)
$versionText = [regex]::Replace($versionText, '"minor":\s*\d+', ('"minor":' + $minor), 1)
$versionText = [regex]::Replace($versionText, '"patch":\s*\d+', ('"patch":' + $patch), 1)
Write-Text $versionFile $versionText

$ktsText = Read-Text $gradleKts
$ktsText = [regex]::Replace($ktsText, '(?m)^version = "[^"]*"', ('version = "' + $plain + '"'), 1)
Write-Text $gradleKts $ktsText

if (Test-Path $gradleProps) {
    $propsText = Read-Text $gradleProps
    $propsText = [regex]::Replace($propsText, '(?m)^project\.version=.*$', ('project.version=' + $plain), 1)
    Write-Text $gradleProps $propsText
}

Step "Rolling CHANGELOG.md: Unreleased -> $tag"

$today = Get-Date -Format 'yyyy-MM-dd'
$emDash = [string][char]0x2014
$heading = "## $tag $emDash $today"
# The "## Unreleased" heading is consumed: the next change adds it back (developer 2026-10-12).
$replacement = $heading + $newline + $newline + $notes + $newline
$changelogText = $changelogText.Substring(0, $match.Index) + $replacement + $changelogText.Substring($match.Index + $match.Length)
Write-Text $changelog $changelogText

if (-not (Test-Path $notesDir)) { New-Item -ItemType Directory -Path $notesDir | Out-Null }
Write-Text $notesFile ($notes + "`n")
Write-Host "release notes saved to $notesFile"

# ---------------------------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------------------------

Step "Building the release zip (gradlew packageMod)"

& (Join-Path $root 'gradlew.bat') packageMod
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $zip)) {
    Write-Host "build failed; reverting the version bump" -ForegroundColor Yellow
    $null = Invoke-Native 'git' (@('checkout', '--') + $versionFiles)
    Fail "gradlew packageMod failed. Nothing was committed."
}
Write-Host ("built " + $zip + " (" + [math]::Round((Get-Item $zip).Length / 1MB, 1) + " MB)") -ForegroundColor Green

if ($DryRun) {
    Step "Dry run: stopping before commit"
    Invoke-Native 'git' @('--no-pager', 'diff', '--stat') | Write-Host
    Write-Host ""
    Write-Host "Would commit the files above, tag $tag, push $ReleaseBranch + tag, and create the GitHub release with:"
    Write-Host "---- $notesFile ----"
    Write-Host $notes
    Write-Host "--------------------"
    Write-Host "Undo the local edits with:  git checkout -- $($versionFiles -join ' ')" -ForegroundColor Yellow
    exit 0
}

# ---------------------------------------------------------------------------------------------
# Publish (no rollback past this point; failures print the resume command)
# ---------------------------------------------------------------------------------------------

Step "Committing and tagging"
Run-Git (@('add', '--') + $versionFiles) | Out-Null
Run-Git @('commit', '-m', "Release $tag") | Out-Null
Run-Git @('tag', '-a', $tag, '-m', $tag) | Out-Null

Step "Pushing $ReleaseBranch and $tag"
$pushOut = Invoke-Native 'git' @('push', 'origin', $ReleaseBranch)
if ($LASTEXITCODE -ne 0) {
    Fail ("push of $ReleaseBranch failed:`n" + ($pushOut -join "`n") + "`nCommit and tag exist locally. Resume with:`n  git push origin $ReleaseBranch`n  git push origin $tag`n$resumeRelease")
}
$pushOut = Invoke-Native 'git' @('push', 'origin', $tag)
if ($LASTEXITCODE -ne 0) {
    Fail ("push of tag failed:`n" + ($pushOut -join "`n") + "`nResume with:`n  git push origin $tag`n$resumeRelease")
}

Step "Creating the GitHub release"
$ghOut = Invoke-Native $gh @('release', 'create', $tag, $assetArg, '--title', $tag, '--notes-file', $notesFile)
if ($LASTEXITCODE -ne 0) {
    Fail ("gh release create failed:`n" + ($ghOut -join "`n") + "`nCommit, tag and push are done. Resume with:`n$resumeRelease")
}

$url = (Invoke-Native $gh @('release', 'view', $tag, '--json', 'url', '-q', '.url') | Select-Object -First 1)
Write-Host ""
Write-Host "Released $ModName $tag" -ForegroundColor Green
Write-Host $url
exit 0
