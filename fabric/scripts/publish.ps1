param(
    [string]$Token = $env:CF_API_TOKEN,
    [string]$ProjectId = "1759097",
    [string]$ReleaseType = "beta"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Token)) {
    throw "Set CF_API_TOKEN to your CurseForge upload token (authors.curseforge.com -> API Tokens)."
}

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root

try {
    $props = Get-Content "gradle.properties"
    $version = ($props | Where-Object { $_ -match '^mod_version=' }) -replace '^mod_version=', ''
    if ([string]::IsNullOrWhiteSpace($version)) {
        throw "mod_version is missing from gradle.properties"
    }

    $jar = Join-Path $root "build\libs\upshift-$version.jar"
    Write-Host "Building Upshift $version..."
    & cmd /c "gradlew.bat --no-daemon build"
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $jar)) {
        throw "Build failed or $jar is missing."
    }

    $changelog = "Upshift $version"
    $changelogFile = Join-Path $root "CHANGELOG.md"
    if (Test-Path $changelogFile) {
        $lines = Get-Content $changelogFile
        $start = ($lines | Select-String -Pattern "^##\s+$([regex]::Escape($version))" | Select-Object -First 1).LineNumber
        if ($start) {
            $section = @()
            for ($i = $start; $i -lt $lines.Count; $i++) {
                if ($i -gt $start -and $lines[$i] -match '^##\s') { break }
                $section += $lines[$i]
            }
            $changelog = ($section -join "`n").Trim()
        }
    }

    $metadata = [ordered]@{
        changelog        = $changelog
        changelogType    = "markdown"
        displayName      = "Upshift $version"
        releaseType      = $ReleaseType
        gameVersionNames = @("Client", "Fabric", "26.2")
    }

    $metaPath = Join-Path $env:TEMP "upshift-cf-metadata-$version.json"
    $json = $metadata | ConvertTo-Json -Depth 5
    [System.IO.File]::WriteAllText($metaPath, $json, (New-Object System.Text.UTF8Encoding($false)))

    Write-Host "Uploading $jar to project $ProjectId..."
    $response = & curl.exe -sS -X POST "https://minecraft.curseforge.com/api/projects/$ProjectId/upload-file" `
        -H "X-Api-Token: $Token" `
        -F "metadata=<$metaPath;type=application/json" `
        -F "file=@$jar;type=application/java-archive" `
        -w "`nHTTP_STATUS=%{http_code}"

    Write-Host $response
    $responseText = ($response -join "`n")
    if ($responseText -notmatch "HTTP_STATUS=200") {
        throw "CurseForge upload failed."
    }
}
finally {
    Pop-Location
}
