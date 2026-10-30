param(
    [string]$DiscoveryUrl = 'http://localhost:8761',
    [string]$ConfigUrl = 'http://localhost:8888',
    [string]$GatewayUrl = 'http://localhost:8080',
    [string]$AccountUrl = 'http://localhost:8081',
    [string]$TransactionUrl = 'http://localhost:8082'
)
$ErrorActionPreference = 'Stop'

function Assert-Check {
    param([bool]$Condition, [string]$Label)
    if (-not $Condition) { throw $Label }
    Write-Host "PASS $Label" -ForegroundColor Green
}

try {
    foreach ($url in @($DiscoveryUrl, $ConfigUrl, $AccountUrl, $TransactionUrl, $GatewayUrl)) {
        $health = Invoke-RestMethod "$url/actuator/health" -TimeoutSec 10
        Assert-Check ($health.status -eq 'UP') "$url health UP"
    }
    $dashboard = Invoke-WebRequest -UseBasicParsing "$DiscoveryUrl/" -TimeoutSec 10
    Assert-Check ($dashboard.StatusCode -eq 200) 'Eureka dashboard HTTP 200'

    $config = Invoke-RestMethod "$ConfigUrl/transaction-service/default" -TimeoutSec 10
    $shared = @($config.propertySources | Where-Object {
        $_.source.PSObject.Properties.Name -contains 'bank.transaction.max-amount'
    })
    Assert-Check ($shared.Count -gt 0 -and $shared[0].source.'bank.transaction.max-amount' -eq 1000) 'Config Server shared maximum = 1000'

    # Allow registry propagation after startup; no retries of banking writes.
    $deadline = (Get-Date).AddSeconds(45)
    do {
        $registry = Invoke-RestMethod "$DiscoveryUrl/eureka/apps" -Headers @{ Accept = 'application/json' } -TimeoutSec 5
        $names = @($registry.applications.application | Where-Object {
            @($_.instance | Where-Object { $_.status -eq 'UP' }).Count -gt 0
        } | ForEach-Object { $_.name })
        $registered = ($names -contains 'ACCOUNT-SERVICE') -and ($names -contains 'TRANSACTION-SERVICE') -and ($names -contains 'GATEWAY-SERVICE')
        if (-not $registered) { Start-Sleep -Seconds 1 }
    } until ($registered -or (Get-Date) -gt $deadline)
    foreach ($name in @('ACCOUNT-SERVICE', 'TRANSACTION-SERVICE', 'GATEWAY-SERVICE')) {
        Assert-Check ($names -contains $name) "$name registered UP in Eureka"
    }

    $response = Invoke-WebRequest -UseBasicParsing "$GatewayUrl/api/accounts" -TimeoutSec 10
    Assert-Check ($response.StatusCode -eq 200) 'Gateway GET /api/accounts HTTP 200'
    $response = Invoke-WebRequest -UseBasicParsing "$GatewayUrl/api/transactions" -TimeoutSec 10
    Assert-Check ($response.StatusCode -eq 200) 'Gateway GET /api/transactions HTTP 200'
    Write-Host 'PASS Cloud services smoke checks completed.' -ForegroundColor Green
    exit 0
} catch {
    Write-Host "FAIL $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
