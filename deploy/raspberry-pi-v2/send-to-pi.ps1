param([string]$PiTarget='qnm@192.168.60.87')
$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$archive=Join-Path ([IO.Path]::GetTempPath()) ('smartpool-v2-'+[guid]::NewGuid().ToString('N')+'.tar.gz')
try {
    & tar -czf $archive -C $repoRoot --exclude=.env* --exclude=backups backend deploy/raspberry-pi-v2 .dockerignore
    if($LASTEXITCODE -ne 0){throw 'Archive creation failed'}
    & scp $archive "${PiTarget}:smartpool-v2-deploy.tar.gz"
    if($LASTEXITCODE -ne 0){throw 'Upload failed'}
    $remote='set -e; mkdir -p "$HOME/smartpool-v2"; tar --no-same-owner --no-same-permissions --no-overwrite-dir -xzf "$HOME/smartpool-v2-deploy.tar.gz" -C "$HOME/smartpool-v2"; cd "$HOME/smartpool-v2/deploy/raspberry-pi-v2"; exec bash -l < /dev/tty'
    $encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remote))
    & ssh -t $PiTarget "printf %s $encoded | base64 -d | bash -s"
    if($LASTEXITCODE -ne 0){throw 'Remote extraction/session failed'}
} finally {
    if(Test-Path -LiteralPath $archive){Remove-Item -LiteralPath $archive}
}
