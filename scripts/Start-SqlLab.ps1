#requires -Version 5.1
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$settings = Join-Path $project '.local'
New-Item -ItemType Directory -Path $settings -Force | Out-Null
$adminFile = Join-Path $settings 'sql-lab-admin'
$clientFile = Join-Path $settings 'sql-lab.env'

# 재실행해도 기존 로컬 암호를 유지합니다. 암호 값은 터미널에 출력하지 않습니다.
if (-not (Test-Path -LiteralPath $adminFile)) {
    [IO.File]::WriteAllText($adminFile, [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'))
}
if (-not (Test-Path -LiteralPath $clientFile)) {
    $secret = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
    [IO.File]::WriteAllText($clientFile, "SQL_LAB_PASSWORD=$secret" + [Environment]::NewLine)
}

$composeFile = Join-Path $project 'compose.sql-lab.yaml'
docker compose --env-file $clientFile -f $composeFile up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'SQL Lab did not start.' }
