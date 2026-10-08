param(
    [string]$BaseUrl = "http://127.0.0.1:8081",
    [Parameter(Mandatory = $true)][string]$AdminToken,
    [string]$Phone = "18800000001",
    [string]$RedisCli = "redis-cli",
    [string]$RedisDistro = "",
    [string]$RedisHost = "127.0.0.1",
    [int]$RedisPort = 6379,
    [int]$RedisDatabase = 0,
    [string]$RedisPassword = ""
)

$ErrorActionPreference = "Stop"

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        throw "Regression assertion failed: $Message"
    }
}

function Invoke-Redis {
    param([string[]]$Command)
    $connection = @("-h", $RedisHost, "-p", [string]$RedisPort, "-n", [string]$RedisDatabase, "--raw")
    if ($RedisPassword) {
        $connection += @("--no-auth-warning", "-a", $RedisPassword)
    }
    if ($RedisDistro) {
        # --exec bypasses the WSL shell so Stream IDs such as ">" stay literal.
        $output = & wsl.exe -d $RedisDistro --exec $RedisCli @connection @Command
    }
    else {
        $output = & $RedisCli @connection @Command
    }
    if ($LASTEXITCODE -ne 0) {
        throw "redis-cli failed: $($Command[0])"
    }
    return $output
}

function Invoke-JsonApi {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body,
        [hashtable]$Headers = @{}
    )
    $arguments = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $Headers
    }
    if ($null -ne $Body) {
        $arguments.ContentType = "application/json"
        $arguments.Body = $Body | ConvertTo-Json -Depth 8
    }
    return Invoke-RestMethod @arguments
}

Write-Host "[1/7] Checking application health"
$health = Invoke-RestMethod -Method Get -Uri "$BaseUrl/actuator/health"
Assert-True ($null -ne $health.status) "health endpoint did not return a status"

Write-Host "[2/7] Logging in through the verification-code flow"
$codeResult = Invoke-JsonApi -Method Post -Path "/user/code?phone=$Phone" -Body $null
Assert-True ($codeResult.success -eq $true) "verification code request failed"
$verificationCode = [string](Invoke-Redis -Command @("GET", "login:code:$Phone"))
Assert-True (-not [string]::IsNullOrWhiteSpace($verificationCode)) "verification code was not written to Redis"
$login = Invoke-JsonApi -Method Post -Path "/user/login" -Body @{
    phone = $Phone
    code = $verificationCode.Trim()
}
Assert-True ($login.success -eq $true) "login failed"
$userToken = [string]$login.data
$userHeaders = @{ authorization = $userToken }
$adminHeaders = @{ "X-Admin-Token" = $AdminToken }

Write-Host "[3/7] Creating a seckill voucher"
$now = Get-Date
$voucher = Invoke-JsonApi -Method Post -Path "/voucher/seckill" -Headers $adminHeaders -Body @{
    title = "regression-$($now.ToString('yyyyMMddHHmmss'))"
    stock = 2
    beginTime = $now.AddMinutes(-1).ToString("yyyy-MM-ddTHH:mm:ss")
    endTime = $now.AddMinutes(30).ToString("yyyy-MM-ddTHH:mm:ss")
}
Assert-True ($voucher.success -eq $true) "voucher creation failed"
$voucherId = [long]$voucher.data

Write-Host "[4/7] Reserving inventory and rejecting a duplicate request"
$order = Invoke-JsonApi -Method Post -Path "/voucher-order/seckill/$voucherId" -Headers $userHeaders -Body $null
Assert-True ($order.success -eq $true) "seckill reservation failed"
$duplicate = Invoke-JsonApi -Method Post -Path "/voucher-order/seckill/$voucherId" -Headers $userHeaders -Body $null
Assert-True ($duplicate.success -eq $false) "duplicate seckill request was not rejected"

Write-Host "[5/7] Waiting for Redis Stream consumption and database commit"
$orderStatus = $null
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    $orderStatus = Invoke-JsonApi -Method Get -Path "/voucher-order/seckill/$voucherId/status" -Headers $userHeaders -Body $null
    if ($orderStatus.data.status -eq "SUCCESS") {
        break
    }
    Start-Sleep -Milliseconds 200
}
Assert-True ($orderStatus.data.status -eq "SUCCESS") "order was not committed within 6 seconds"

Write-Host "[6/7] Exercising dead-letter quarantine"
$suffix = [Guid]::NewGuid().ToString("N")
$sourceStream = "regression:source:$suffix"
$deadStream = "regression:dead:$suffix"
$stockKey = "regression:stock:$suffix"
$orderedKey = "regression:ordered:$suffix"
$retryKey = "regression:retry:$suffix"
$group = "regression-group"
$consumer = "regression-consumer"
$testUserId = "900001"
$testVoucherId = "900001"
$testOrderId = "900001"

# dead-letter.lua 只接收 3 个 KEYS：sourceStream / deadLetterStream / retryKey
try {
    Invoke-Redis -Command @("SET", $stockKey, "0") | Out-Null
    Invoke-Redis -Command @("SADD", $orderedKey, $testUserId) | Out-Null
    Invoke-Redis -Command @("SET", $retryKey, "4") | Out-Null
    Invoke-Redis -Command @("XGROUP", "CREATE", $sourceStream, $group, "0", "MKSTREAM") | Out-Null
    $sourceId = [string](Invoke-Redis -Command @(
        "XADD", $sourceStream, "*",
        "userId", $testUserId, "voucherId", $testVoucherId, "id", $testOrderId))
    Invoke-Redis -Command @(
        "XREADGROUP", "GROUP", $group, $consumer, "COUNT", "1",
        "STREAMS", $sourceStream, ">") | Out-Null

    $deadLetterResult = [string](Invoke-Redis -Command @(
        "--eval", "src/main/resources/dead-letter.lua",
        $sourceStream, $deadStream, $retryKey, ",",
        $group, $sourceId.Trim(), $testUserId, $testVoucherId, $testOrderId, "retry_exhausted"))
    Assert-True ($deadLetterResult.Trim() -eq "1") "dead-letter quarantine script failed"

    $pendingAfter = [string](Invoke-Redis -Command @(
        "XPENDING", $sourceStream, $group, "-", "+", "10"))
    Assert-True ($pendingAfter.Trim() -eq "") "quarantine did not ACK the source message"

    # 死信记录必须被创建，且带 failureReason
    $deadRecord = [string](Invoke-Redis -Command @("XRANGE", $deadStream, "-", "+", "COUNT", "1"))
    Assert-True (-not [string]::IsNullOrWhiteSpace($deadRecord)) "dead-letter record was not created"
    Assert-True ($deadRecord -match "retry_exhausted") "dead-letter record missing failureReason"

    # 关键安全语义：绝不回补库存、绝不释放一人一单资格
    Assert-True (([string](Invoke-Redis -Command @("GET", $stockKey))).Trim() -eq "0") "quarantine changed reserved inventory"
    Assert-True (([string](Invoke-Redis -Command @("SISMEMBER", $orderedKey, $testUserId))).Trim() -eq "1") "quarantine released eligibility"
    # 重试计数被清除
    Assert-True (([string](Invoke-Redis -Command @("EXISTS", $retryKey))).Trim() -eq "0") "quarantine did not clear retry counter"

    # 重复隔离必须幂等：已 ACK 的消息再次隔离返回 0，且不产生第二条死信
    $duplicate = [string](Invoke-Redis -Command @(
        "--eval", "src/main/resources/dead-letter.lua",
        $sourceStream, $deadStream, $retryKey, ",",
        $group, $sourceId.Trim(), $testUserId, $testVoucherId, $testOrderId, "retry_exhausted"))
    Assert-True ($duplicate.Trim() -eq "0") "duplicate quarantine should be a no-op"
    Assert-True (([string](Invoke-Redis -Command @("XLEN", $deadStream))).Trim() -eq "1") "duplicate quarantine created a second dead letter"
    Assert-True (([string](Invoke-Redis -Command @("GET", $stockKey))).Trim() -eq "0") "duplicate quarantine changed inventory"
    Assert-True (([string](Invoke-Redis -Command @("SISMEMBER", $orderedKey, $testUserId))).Trim() -eq "1") "duplicate quarantine released eligibility"
}
finally {
    Invoke-Redis -Command @("DEL", $sourceStream, $deadStream, $stockKey, $orderedKey, $retryKey) | Out-Null
}

Write-Host "[7/7] Triggering reconciliation and validating metrics"
$reconcile = Invoke-JsonApi -Method Post -Path "/admin/reconcile/trigger" -Headers $adminHeaders -Body $null
Assert-True ($reconcile.success -eq $true) "manual reconciliation failed"
$metrics = Invoke-JsonApi -Method Get -Path "/metrics/seckill" -Body $null
Assert-True ([double]$metrics.runtime_metrics.total_requests -ge 2) "request metric was not collected"
Assert-True ([double]$metrics.runtime_metrics.reserve_success -ge 1) "reservation metric was not collected"
Assert-True ([double]$metrics.runtime_metrics.order_success -ge 1) "database commit metric was not collected"

Write-Host "Regression passed: login, voucher creation, reservation, duplicate rejection, asynchronous commit, dead-letter quarantine, reconciliation and runtime metrics."
