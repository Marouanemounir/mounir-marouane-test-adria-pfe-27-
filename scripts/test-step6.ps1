param(
    [string]$AccountUrl = 'http://localhost:8081',
    [string]$TransactionUrl = 'http://localhost:8082',
    [string]$AgentUrl = 'http://localhost:8083',
    [string]$McpToken = $env:MCP_AUTH_TOKEN,
    [string]$AgentToken = $env:AGENT_AUTH_TOKEN,
    [switch]$TestLlm
)

$ErrorActionPreference = 'Stop'
if (-not $McpToken) { throw 'Set MCP_AUTH_TOKEN before running this script' }
if (-not $AgentToken) { $AgentToken = $McpToken }

function Assert-Check([bool]$Condition, [string]$Label) {
    if (-not $Condition) { throw "FAIL $Label" }
    Write-Output "PASS $Label"
}

function Json-Post([string]$Url, [object]$Body, [hashtable]$Headers = @{}) {
    Invoke-RestMethod -Method Post -Uri $Url -Headers $Headers -ContentType 'application/json' `
        -Body (ConvertTo-Json -InputObject $Body -Depth 15 -Compress) -TimeoutSec 120
}

function Assert-Status([string]$Url, [object]$Body, [hashtable]$Headers, [int]$Expected) {
    $status = 0
    try { Json-Post $Url $Body $Headers | Out-Null; $status = 200 }
    catch {
        if ($null -eq $_.Exception.Response) { throw }
        $status = [int]$_.Exception.Response.StatusCode
        if ($Expected -eq 503) {
            $errorBody = $_.ErrorDetails.Message
            if (-not $errorBody) {
                $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                try { $errorBody = $reader.ReadToEnd() } finally { $reader.Dispose() }
            }
            Assert-Check ($errorBody -match 'configuration LLM') 'explicit missing LLM configuration'
        }
    }
    Assert-Check ($status -eq $Expected) "$Url returns HTTP $Expected"
}

function Mcp-Rpc([string]$Url, [hashtable]$Headers, [string]$Method, [object]$Parameters, [int]$Id) {
    $body = @{ jsonrpc = '2.0'; method = $Method; params = $Parameters }
    if ($Id -gt 0) { $body.id = $Id }
    $response = Invoke-WebRequest -UseBasicParsing -Uri "$Url/mcp" -Method Post -Headers $Headers `
        -ContentType 'application/json' -Body (ConvertTo-Json $body -Depth 15 -Compress) -TimeoutSec 30
    $content = $response.Content
    if ($content -is [byte[]]) { $content = [Text.Encoding]::UTF8.GetString($content) }
    $rpc = $null
    if ($content -match '^\s*\{') { $rpc = $content | ConvertFrom-Json }
    else {
        foreach ($line in ($content -split "`n")) {
            if ($line.StartsWith('data:')) { $rpc = $line.Substring(5).Trim() | ConvertFrom-Json }
        }
    }
    if ($rpc.error) { throw (ConvertTo-Json $rpc.error -Depth 10) }
    @{ Rpc = $rpc; Headers = $response.Headers }
}

function Connect-Mcp([string]$Url) {
    $headers = @{ Authorization = "Bearer $McpToken"; Accept = 'application/json, text/event-stream' }
    $init = Mcp-Rpc $Url $headers 'initialize' @{
        protocolVersion = '2025-03-26'; capabilities = @{}
        clientInfo = @{ name = 'adria-step6-smoke'; version = '1.0.0' }
    } 1
    if ($init.Headers['Mcp-Session-Id']) { $headers['Mcp-Session-Id'] = $init.Headers['Mcp-Session-Id'] }
    $headers['MCP-Protocol-Version'] = $init.Rpc.result.protocolVersion
    Mcp-Rpc $Url $headers 'notifications/initialized' @{} 0 | Out-Null
    return $headers
}

function Call-Tool([string]$Url, [hashtable]$Headers, [string]$Name, [hashtable]$Arguments) {
    $result = (Mcp-Rpc $Url $Headers 'tools/call' @{ name = $Name; arguments = $Arguments } 3).Rpc.result
    if ($result.isError) { throw (ConvertTo-Json $result -Depth 15) }
    return ($result.content[0].text | ConvertFrom-Json)
}

# Seed data through the existing banking REST API; no repository bypass.
$reference = "STEP6-$([guid]::NewGuid().ToString('N'))"
$account = Json-Post "$AccountUrl/api/accounts" @{ customerReference = $reference; currency = 'EUR'; balance = 5000 }
$transaction = Json-Post "$TransactionUrl/api/transactions" @{
    accountId = $account.id; amount = 250.25; currency = 'EUR'; merchantCategory = 'FOOD'; country = 'FR'
} @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
$expectedBalance = 4749.75

$accounts = Json-Post "$AccountUrl/graphql" @{ query = '{ accounts { id customerReference balance } }' }
Assert-Check (-not $accounts.errors -and ($accounts.data.accounts.id -contains $account.id)) 'GraphQL accounts'
$one = Json-Post "$AccountUrl/graphql" @{
    query = 'query($id: ID!) { accountById(id: $id) { id status currency balance } }'; variables = @{ id = $account.id }
}
Assert-Check (-not $one.errors -and $one.data.accountById.balance -eq $expectedBalance) 'GraphQL accountById balance'
$history = Json-Post "$TransactionUrl/graphql" @{
    query = 'query($id: ID!) { transactionsByAccount(accountId: $id) { id amount currency createdAt } }'
    variables = @{ id = $account.id }
}
Assert-Check (-not $history.errors -and $history.data.transactionsByAccount[0].amount -eq '250.25') 'GraphQL transactionsByAccount decimal string'
$oneTransaction = Json-Post "$TransactionUrl/graphql" @{
    query = 'query($id: ID!) { transactionById(id: $id) { id amount createdAt } }'; variables = @{ id = $transaction.id }
}
Assert-Check (-not $oneTransaction.errors -and $oneTransaction.data.transactionById.createdAt -match 'Z$') 'GraphQL transactionById ISO Instant'

foreach ($url in @($AccountUrl, $TransactionUrl)) {
    Assert-Status "$url/mcp" @{ jsonrpc='2.0'; id=1; method='tools/list' } @{} 401
    Assert-Status "$url/mcp" @{ jsonrpc='2.0'; id=1; method='tools/list' } @{ Authorization='Bearer invalid' } 401
}
$accountHeaders = Connect-Mcp $AccountUrl
$transactionHeaders = Connect-Mcp $TransactionUrl
try {
    $accountTools = (Mcp-Rpc $AccountUrl $accountHeaders 'tools/list' @{} 2).Rpc.result.tools.name
    $transactionTools = (Mcp-Rpc $TransactionUrl $transactionHeaders 'tools/list' @{} 2).Rpc.result.tools.name
    Assert-Check ($accountTools.Count -eq 3 -and $accountTools -contains 'getAccountBalance' -and
        $accountTools -contains 'getAccountDetails' -and $accountTools -contains 'getAccountsByCustomerReference') 'MCP account tools/list: exactly 3 read tools'
    Assert-Check ($transactionTools.Count -eq 2 -and $transactionTools -contains 'getTransactionDetails' -and
        $transactionTools -contains 'getTransactionsByAccount') 'MCP transaction tools/list: exactly 2 read tools'
    $balance = Call-Tool $AccountUrl $accountHeaders 'getAccountBalance' @{ accountId=$account.id }
    Assert-Check ($balance.balance -eq $expectedBalance -and $balance.currency -eq 'EUR') 'MCP getAccountBalance real balance'
    $details = Call-Tool $AccountUrl $accountHeaders 'getAccountDetails' @{ accountId=$account.id }
    Assert-Check ($details.customerReference -eq $reference) 'MCP getAccountDetails'
    $customerAccounts = Call-Tool $AccountUrl $accountHeaders 'getAccountsByCustomerReference' @{ reference=$reference }
    Assert-Check ($customerAccounts[0].id -eq $account.id) 'MCP getAccountsByCustomerReference'
    $transactionDetails = Call-Tool $TransactionUrl $transactionHeaders 'getTransactionDetails' @{ transactionId=$transaction.id }
    Assert-Check ($transactionDetails.id -eq $transaction.id) 'MCP getTransactionDetails'
    $mcpHistory = Call-Tool $TransactionUrl $transactionHeaders 'getTransactionsByAccount' @{ accountId=$account.id }
    Assert-Check ($mcpHistory[0].id -eq $transaction.id) 'MCP getTransactionsByAccount'
} finally {
    foreach ($connection in @(@{ Url=$AccountUrl; Headers=$accountHeaders }, @{ Url=$TransactionUrl; Headers=$transactionHeaders })) {
        Invoke-WebRequest -UseBasicParsing -Method Delete -Uri "$($connection.Url)/mcp" -Headers $connection.Headers -TimeoutSec 10 | Out-Null
    }
}

Assert-Status "$AgentUrl/api/chat" @{ message='Bonjour' } @{} 401
$question = @{ message="Quel est le solde du compte $($account.id) ? Indique le montant exact et la devise." }
if ($TestLlm) {
    $answer = Json-Post "$AgentUrl/api/chat" $question @{ Authorization="Bearer $AgentToken" }
    Assert-Check (@($answer.toolsUsed | Where-Object { $_ -match 'getAccount(Balance|Details)' }).Count -gt 0) 'LLM actually invoked account MCP'
    $normalizedAnswer = $answer.answer -replace '[\s\u00A0\u202F]', ''
    Assert-Check ($normalizedAnswer -match '4[.,]?749[.,]75' -and $answer.answer -match 'EUR|euro') 'LLM returned real balance and currency'
    Write-Output ($answer | ConvertTo-Json -Depth 5)
} else {
    Assert-Status "$AgentUrl/api/chat" $question @{ Authorization="Bearer $AgentToken" } 503
    Write-Output 'NOT RUN: real LLM tool selection (enable llm profile, configure provider, rerun with -TestLlm)'
}
Write-Output "Fixture accountId=$($account.id) transactionId=$($transaction.id) balance=$expectedBalance EUR"
