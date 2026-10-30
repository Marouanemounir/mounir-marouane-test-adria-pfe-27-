param(
    [string]$BaseUrl = 'http://localhost:8081'
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

function Assert-Balance {
    param([string]$AccountId, [double]$Expected)
    $result = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/accounts/$AccountId/balance"
    if ([double]$result.balance -ne $Expected) {
        throw "Expected balance $Expected, received $($result.balance)"
    }
    Write-Host "OK balance = $Expected EUR"
}

function Assert-HttpStatus {
    param([scriptblock]$Action, [int]$Expected)
    $actualStatus = $null
    try {
        & $Action | Out-Null
    } catch {
        if ($null -eq $_.Exception.Response) {
            throw
        }
        $actualStatus = [int]$_.Exception.Response.StatusCode
    }
    if ($actualStatus -ne $Expected) {
        throw "Expected HTTP $Expected, received '$actualStatus' (empty means the request succeeded)"
    }
    Write-Host "OK HTTP $Expected"
}

$health = Invoke-RestMethod -Method Get -Uri "$BaseUrl/actuator/health"
if ($health.status -ne 'UP') { throw 'Account Service health is not UP' }
Write-Host 'OK health UP'

$body = @{
    customerReference = "TEST-LOCAL-$([guid]::NewGuid().ToString('N'))"
    status = 'ACTIVE'
    currency = 'EUR'
    balance = 5000
} | ConvertTo-Json
$account = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/accounts" -ContentType 'application/json' -Body $body
$accountId = ([guid]$account.id).ToString()
Write-Host "Created account UUID: $accountId"

$account = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/accounts/$accountId"
if ($account.id -ne $accountId -or $account.currency -ne 'EUR' -or $account.status -ne 'ACTIVE') {
    throw 'Unexpected account response'
}
Assert-Balance -AccountId $accountId -Expected 5000

Invoke-RestMethod -Method Patch -Uri "$BaseUrl/api/accounts/$accountId/debit" `
    -ContentType 'application/json' -Body '{"amount":250}' | Out-Null
Assert-Balance -AccountId $accountId -Expected 4750

Invoke-RestMethod -Method Patch -Uri "$BaseUrl/api/accounts/$accountId/credit" `
    -ContentType 'application/json' -Body '{"amount":500}' | Out-Null
Assert-Balance -AccountId $accountId -Expected 5250

$blocked = Invoke-RestMethod -Method Patch -Uri "$BaseUrl/api/accounts/$accountId/status?status=BLOCKED"
if ($blocked.status -ne 'BLOCKED') { throw 'Account was not blocked' }
Assert-HttpStatus -Expected 409 -Action {
    Invoke-RestMethod -Method Patch -Uri "$BaseUrl/api/accounts/$accountId/debit" `
        -ContentType 'application/json' -Body '{"amount":250}'
}
Assert-Balance -AccountId $accountId -Expected 5250

$missingId = [guid]::NewGuid().ToString()
Assert-HttpStatus -Expected 404 -Action {
    Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/accounts/$missingId"
}

$console = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/h2-console/"
if ($console.StatusCode -ne 200) { throw 'H2 Console is not accessible' }
Write-Host 'OK H2 Console HTTP 200'
Write-Host 'All Account Service REST checks passed.'
