#Requires -Version 7.4
[CmdletBinding()]
param(
    [string]$RabbitImage = 'rabbitmq:3.13-management',
    [int]$BrokerTimeoutSec = 60
)

# Runs the opt-in Go real-broker tests against a disposable RabbitMQ
# container. It never falls back to the developer's local RabbitMQ endpoint.
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path -Parent $PSScriptRoot
$serviceRoot = Join-Path $workspaceRoot 'services/chat-realtime'
$containerId = $null
$containerOwned = $false
$previousEndpoint = [Environment]::GetEnvironmentVariable('CHAT_REALTIME_TEST_AMQP_URL', 'Process')

try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker CLI is required' }
    if (-not (Get-Command go -ErrorAction SilentlyContinue)) { throw 'Go CLI is required' }

    $verificationId = [Guid]::NewGuid().ToString('N').Substring(0, 12)
    $containerName = "bluealbum-chat-realtime-verify-$verificationId"
    $username = "bluealbum_verify_$verificationId"
    $passwordBytes = [byte[]]::new(24)
    [Security.Cryptography.RandomNumberGenerator]::Fill($passwordBytes)
    $password = [Convert]::ToHexString($passwordBytes).ToLowerInvariant()

    $dockerArguments = @(
        'run', '--detach', '--name', $containerName,
        '--env', "RABBITMQ_DEFAULT_USER=$username",
        '--env', "RABBITMQ_DEFAULT_PASS=$password",
        '--publish', '127.0.0.1::5672',
        $RabbitImage
    )
    $containerOutput = @(& docker @dockerArguments 2>&1)
    $dockerRunExitCode = $LASTEXITCODE
    $containerId = $containerOutput |
        ForEach-Object { [string]$_ } |
        Where-Object { $_ -match '^[0-9a-f]{12,64}$' } |
        Select-Object -Last 1
    if ($dockerRunExitCode -ne 0 -or [string]::IsNullOrWhiteSpace([string]$containerId)) {
        throw 'Could not create disposable RabbitMQ container'
    }
    $containerOwned = $true

    $portOutput = (& docker port $containerId '5672/tcp' 2>&1 | Select-Object -First 1)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace([string]$portOutput)) {
        throw 'Could not determine the disposable RabbitMQ port'
    }
    $brokerPort = [int](([string]$portOutput).Trim().Split(':')[-1])
    $readyBy = [DateTime]::UtcNow.AddSeconds($BrokerTimeoutSec)
    $brokerReachable = $false
    $brokerReady = $false
    while ([DateTime]::UtcNow -lt $readyBy) {
        $running = [string](& docker inspect --format '{{.State.Running}}' $containerId 2>$null)
        $inspectExitCode = $LASTEXITCODE
        if ($inspectExitCode -ne 0 -or $running.Trim() -ne 'true') {
            $state = [string](& docker inspect --format '{{.State.Status}} exit={{.State.ExitCode}} error={{.State.Error}}' $containerId 2>$null)
            throw "Disposable RabbitMQ container exited before opening its published port ($($state.Trim()))"
        }
        $tcpClient = [Net.Sockets.TcpClient]::new()
        try {
            $connect = $tcpClient.ConnectAsync('127.0.0.1', $brokerPort)
            if ($connect.Wait(500) -and $tcpClient.Connected) {
                $brokerReachable = $true
            }
        }
        catch {
            # The broker is still starting; retry until the bounded deadline.
        }
        finally {
            $tcpClient.Dispose()
        }
        if ($brokerReachable) {
            # Run diagnostics as RabbitMQ's service user. Running this as root
            # can create an Erlang cookie that the broker user cannot read.
            & docker exec --user rabbitmq $containerId rabbitmq-diagnostics -q check_running *> $null
            if ($LASTEXITCODE -eq 0) {
                $brokerReady = $true
                break
            }
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $brokerReady) { throw "RabbitMQ did not report ready within $BrokerTimeoutSec seconds" }

    $brokerEndpoint = 'amqp://{0}:{1}@127.0.0.1:{2}/%2f' -f $username, $password, $brokerPort
    $env:CHAT_REALTIME_TEST_AMQP_URL = $brokerEndpoint
    Push-Location $serviceRoot
    try {
        & go test ./... -run '^TestDualRealtimeReal(Broker|Processes)$' -count=1 -v
        if ($LASTEXITCODE -ne 0) { throw "Go real-broker verification failed with exit code $LASTEXITCODE" }
    }
    finally {
        Pop-Location
    }
}
finally {
    if ($null -eq $previousEndpoint) {
        Remove-Item Env:CHAT_REALTIME_TEST_AMQP_URL -ErrorAction SilentlyContinue
    }
    else {
        $env:CHAT_REALTIME_TEST_AMQP_URL = $previousEndpoint
    }
    if ($containerOwned) {
        & docker rm --force --volumes $containerId *> $null
        if ($LASTEXITCODE -ne 0) { throw "Could not remove disposable RabbitMQ container $containerId" }
    }
}
