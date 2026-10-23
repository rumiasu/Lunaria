param(
    [string]$MySqlBin = '',
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $repoRoot 'backend'
if (-not $MySqlBin) { $MySqlBin = Split-Path -Parent (Get-Command mysqld.exe -ErrorAction Stop).Source }
$serverExe = Join-Path $MySqlBin 'mysqld.exe'
$clientExe = Join-Path $MySqlBin 'mysql.exe'
if (-not (Test-Path -LiteralPath $serverExe) -or -not (Test-Path -LiteralPath $clientExe)) { throw 'MySQL server and client binaries are required; no install or host service changes are performed.' }
# Fresh files and root credentials belong only to this disposable instance.
$buildRoot = [IO.Path]::GetFullPath((Join-Path $backendRoot 'build'))
$instanceRoot = [IO.Path]::GetFullPath((Join-Path $buildRoot ('mysql-it-' + [Guid]::NewGuid().ToString('N'))))
New-Item -ItemType Directory -Path $instanceRoot -Force | Out-Null
$dataDir = (Join-Path $instanceRoot 'data').Replace('\','/')
$initFile = Join-Path $instanceRoot 'init.sql'
$instancePassword = [Guid]::NewGuid().ToString('N')
$mysqlProcess = $null
$oldEnvironment = @{}
$envKeys = @('MYSQL_PWD','LUNARIA_MYSQL_TEST_ISOLATED','LUNARIA_MYSQL_TEST_URL','LUNARIA_MYSQL_TEST_USER','LUNARIA_MYSQL_TEST_PASSWORD','LUNARIA_MYSQL_TEST_DATADIR')
foreach ($envKey in $envKeys) { $oldEnvironment[$envKey] = [Environment]::GetEnvironmentVariable($envKey,'Process') }
try {
    Write-Host 'Initializing a disposable Lunaria MySQL instance (host configuration disabled).'
    & $serverExe --no-defaults --initialize-insecure "--datadir=$dataDir" --console *> (Join-Path $instanceRoot 'initialize.log')
    if ($LASTEXITCODE -ne 0) { throw "Temporary MySQL initialization failed; inspect $instanceRoot" }
    Set-Content -LiteralPath $initFile -Value "ALTER USER 'root'@'localhost' IDENTIFIED BY '$instancePassword';" -Encoding ascii
    $portProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    $portProbe.Start(); $instancePort = $portProbe.LocalEndpoint.Port; $portProbe.Stop()
    # --no-defaults is first: no host my.ini, existing datadir, business credential or service is read.
    $serverArgs = @('--no-defaults','--no-monitor','--console',"--datadir=`"$dataDir`"",'--bind-address=127.0.0.1',"--port=$instancePort",'--mysqlx=OFF','--local-infile=OFF','--secure-file-priv=NULL','--skip-log-bin',"--init-file=`"$($initFile.Replace('\','/'))`"")
    $mysqlProcess = Start-Process -FilePath $serverExe -ArgumentList $serverArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $instanceRoot 'server.stdout.log') -RedirectStandardError (Join-Path $instanceRoot 'server.stderr.log')
    $env:MYSQL_PWD = $instancePassword
    $clientArgs = @('--no-defaults','--no-login-paths','--protocol=TCP','--host=127.0.0.1',"--port=$instancePort",'--user=root','--ssl-mode=DISABLED','--get-server-public-key','--connect-timeout=1','--batch','--skip-column-names')
    $ready = $false
    for ($attempt=0; $attempt -lt 60; $attempt++) {
        if ($mysqlProcess.HasExited) { throw "Temporary MySQL stopped; inspect $instanceRoot" }
        $pollPreference = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        $lastProbe = & $clientExe @clientArgs -e 'SELECT 1' 2>&1 | Out-String
        $ErrorActionPreference = $pollPreference
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) { throw "Temporary MySQL did not become ready: $lastProbe" }
    Remove-Item -LiteralPath $initFile
    & $clientExe @clientArgs -e 'SELECT VERSION(),@@port,@@datadir,@@transaction_isolation'
    $env:LUNARIA_MYSQL_TEST_ISOLATED = 'true'
    # Fixed Shanghai offset does not require loading global MySQL time-zone tables.
    $env:LUNARIA_MYSQL_TEST_URL = "jdbc:mysql://127.0.0.1:$instancePort/?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true"
    $env:LUNARIA_MYSQL_TEST_USER = 'root'
    $env:LUNARIA_MYSQL_TEST_PASSWORD = $instancePassword
    $env:LUNARIA_MYSQL_TEST_DATADIR = $dataDir
    Push-Location $backendRoot
    try {
        $gradleArgs = @('--no-daemon','mysqlTest')
        if ($Offline) { $gradleArgs += '--offline' }
        & .\gradlew.bat @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw 'MySQL engine tests failed; see backend/build/reports/tests/mysqlTest.' }
    } finally { Pop-Location }
} finally {
    if ($mysqlProcess -and -not $mysqlProcess.HasExited) {
        # Stop only the process returned by this invocation; never a host MySQL service.
        $env:MYSQL_PWD = $instancePassword
        $null = & $clientExe @clientArgs -e 'SHUTDOWN' 2>$null
        if (-not $mysqlProcess.WaitForExit(10000)) { Stop-Process -Id $mysqlProcess.Id -Force; $mysqlProcess.WaitForExit() }
    }
    foreach ($envKey in $envKeys) { [Environment]::SetEnvironmentVariable($envKey,$oldEnvironment[$envKey],'Process') }
    # Verify the exact absolute target remains below this repository's generated build directory.
    if (-not $instanceRoot.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing cleanup outside backend/build.' }
    if (Test-Path -LiteralPath $instanceRoot) { Remove-Item -LiteralPath $instanceRoot -Recurse -Force }
}
