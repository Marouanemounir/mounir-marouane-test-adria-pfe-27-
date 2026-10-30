param(
    [string]$AccountUrl = 'http://localhost:8081',
    [string]$TransactionUrl = 'http://localhost:8082'
)

$ErrorActionPreference = 'Stop'
$AccountUrl = $AccountUrl.TrimEnd('/')
$TransactionUrl = $TransactionUrl.TrimEnd('/')

function Assert-Check {
    param([bool]$Condition, [string]$Label)
    if (-not $Condition) { throw $Label }
    Write-Host "PASS $Label" -ForegroundColor Green
}

function Invoke-Json {
    param([string]$Method, [string]$Uri, [object]$Body = $null,
          [hashtable]$Headers = @{}, [int]$ExpectedStatus = 200)
    $parameters = @{ Method = $Method; Uri = $Uri; Headers = $Headers; UseBasicParsing = $true; TimeoutSec = 15 }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = ConvertTo-Json -InputObject $Body -Depth 5
    }
    $response = Invoke-WebRequest @parameters
    Assert-Check ($response.StatusCode -eq $ExpectedStatus) "$Method $Uri HTTP $ExpectedStatus"
    if ($response.Content) {
        $json = $response.Content
        # Windows PowerShell 5.1 returns byte[] for Actuator's vendor JSON media type.
        if ($json -is [byte[]]) { $json = [System.Text.Encoding]::UTF8.GetString($json) }
        return ($json | ConvertFrom-Json)
    }
}

function Assert-Balance {
    param([string]$Id, [decimal]$Expected)
    $balance = Invoke-Json Get "$AccountUrl/api/accounts/$Id/balance"
    Assert-Check ([decimal]$balance.balance -eq $Expected) "balance $Id = $Expected EUR"
}

function Assert-Rejected {
    param([string]$Uri, [object]$Body, [int]$ExpectedStatus)
    $actual = $null
    try {
        Invoke-WebRequest -UseBasicParsing -Method Post -Uri $Uri -ContentType 'application/json' -TimeoutSec 15 `
            -Headers @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() } `
            -Body (ConvertTo-Json -InputObject $Body) | Out-Null
    } catch {
        if ($null -eq $_.Exception.Response) { throw }
        $actual = [int]$_.Exception.Response.StatusCode
    }
    Assert-Check ($actual -eq $ExpectedStatus) "rejected with HTTP $ExpectedStatus (received $actual)"
}

try {
    foreach ($url in @($AccountUrl, $TransactionUrl)) {
        $health = Invoke-Json Get "$url/actuator/health"
        Assert-Check ($health.status -eq 'UP') "$url available"
    }
    $reference = "TEST-STEP3-$([guid]::NewGuid().ToString('N'))"
    $source = Invoke-Json Post "$AccountUrl/api/accounts" @{
        customerReference = "$reference-SOURCE"; currency = 'EUR'; balance = 5000
    } -ExpectedStatus 201
    $destination = Invoke-Json Post "$AccountUrl/api/accounts" @{
        customerReference = "$reference-DESTINATION"; currency = 'EUR'; balance = 1000
    } -ExpectedStatus 201
    Assert-Check (([guid]$source.id) -ne [guid]::Empty) 'source created with UUID'
    Assert-Check (([guid]$destination.id) -ne [guid]::Empty) 'destination created with UUID'
    Assert-Balance $source.id 5000
    Assert-Balance $destination.id 1000

    $purchase = @{ accountId = $source.id; amount = 250; currency = 'EUR'; merchantCategory = 'FOOD'; country = 'FR' }
    $purchaseHeaders = @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
    $transaction = Invoke-Json Post "$TransactionUrl/api/transactions" $purchase $purchaseHeaders 201
    Assert-Check ([decimal]$transaction.amount -eq 250) 'purchase of 250 EUR recorded'
    Assert-Balance $source.id 4750
    $replay = Invoke-Json Post "$TransactionUrl/api/transactions" $purchase $purchaseHeaders 201
    Assert-Check ($replay.id -eq $transaction.id) 'same key returns same transaction UUID'
    Assert-Balance $source.id 4750

    $transfer = @{ sourceAccountId = $source.id; destinationAccountId = $destination.id; amount = 300; currency = 'EUR' }
    $transferHeaders = @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
    $result = Invoke-Json Post "$TransactionUrl/api/transactions/transfers" $transfer $transferHeaders 201
    Assert-Check ([decimal]$result.sourceBalance -eq 4450 -and [decimal]$result.destinationBalance -eq 1300) 'atomic transfer receipt'
    Assert-Balance $source.id 4450
    Assert-Balance $destination.id 1300
    $replayedTransfer = Invoke-Json Post "$TransactionUrl/api/transactions/transfers" $transfer $transferHeaders 201
    Assert-Check ($replayedTransfer.transferId -eq $result.transferId) 'transfer replay is idempotent'
    Assert-Balance $source.id 4450
    Assert-Balance $destination.id 1300

    $history = @(Invoke-Json Get "$TransactionUrl/api/transactions/account/$($source.id)")
    Assert-Check ($history.Count -eq 2) 'source history contains purchase and transfer debit'
    $purchase.amount = 1500
    Assert-Rejected "$TransactionUrl/api/transactions" $purchase 400
    Assert-Balance $source.id 4450

    $blocked = Invoke-Json Patch "$AccountUrl/api/accounts/$($source.id)/status?status=BLOCKED"
    Assert-Check ($blocked.status -eq 'BLOCKED') 'source blocked'
    $purchase.amount = 10
    Assert-Rejected "$TransactionUrl/api/transactions" $purchase 409
    Assert-Balance $source.id 4450
    Assert-Balance $destination.id 1300
    Write-Host 'PASS All Transaction Service checks passed.' -ForegroundColor Green
    exit 0
} catch {
    Write-Host "FAIL $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
