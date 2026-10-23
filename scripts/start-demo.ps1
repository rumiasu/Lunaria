$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath (Join-Path $projectRoot 'backend')
if (-not (Test-Path -LiteralPath 'build\libs\lunaria-dev.jar')) {
    & .\gradlew.bat test bootJar
    if ($LASTEXITCODE -ne 0) { throw '构建失败，请查看测试输出' }
}
& java -jar build\libs\lunaria-dev.jar --spring.profiles.active=demo
