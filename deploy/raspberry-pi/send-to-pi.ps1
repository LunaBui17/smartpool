param(
    [string]$PiTarget = 'qnm@192.168.60.87'
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$archive = Join-Path ([System.IO.Path]::GetTempPath()) ('smartpool-' + [guid]::NewGuid().ToString('N') + '.tar.gz')
try {
    & tar -czf $archive -C $repoRoot --exclude=.env* --exclude=backups `
        backend deploy/raspberry-pi .dockerignore
    if ($LASTEXITCODE -ne 0) { throw 'Could not build deployment archive.' }
    & scp $archive "${PiTarget}:smartpool-deploy.tar.gz"
    if ($LASTEXITCODE -ne 0) { throw 'SCP failed. Check SSH authentication.' }
    Write-Host 'Files uploaded. Opening SSH; enter your password directly in the terminal.'
    & ssh -t $PiTarget 'mkdir -p ~/smartpool && tar -xzf ~/smartpool-deploy.tar.gz -C ~/smartpool && cd ~/smartpool/deploy/raspberry-pi && exec bash -l'
    if ($LASTEXITCODE -ne 0) { throw 'SSH session ended with an error.' }
} finally {
    if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive }
}
