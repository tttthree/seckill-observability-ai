param(
    [int]$Users = 2000,
    [long]$FirstUserId = 100000,
    [string]$Output = "target/load-test/tokens.txt",

    [string]$RedisCli = "redis-cli",
    [string]$RedisDistro = "",

    [string]$RedisHost = "127.0.0.1",
    [int]$RedisPort = 6379,
    [int]$RedisDatabase = 0,
    [string]$RedisPassword = ""
)

$ErrorActionPreference = "Stop"

# ============================================================
# 1. 准备输出目录
# ============================================================

$outputPath = [IO.Path]::GetFullPath(
    (Join-Path (Get-Location) $Output)
)

$outputDirectory = Split-Path -Parent $outputPath
[IO.Directory]::CreateDirectory($outputDirectory) | Out-Null

# Redis 批量命令临时文件
$commandFile = Join-Path $outputDirectory "redis-load-commands.txt"

Write-Host "========================================"
Write-Host " Load Test Token Seeder"
Write-Host "========================================"
Write-Host "Users          : $Users"
Write-Host "Redis DB       : $RedisDatabase"
Write-Host "Redis Host     : ${RedisHost}:${RedisPort}"

if ($RedisDistro) {
    Write-Host "Redis Runtime  : WSL / $RedisDistro"
}
else {
    Write-Host "Redis Runtime  : Windows"
}

Write-Host ""

# ============================================================
# 2. 一次性生成 Token + Redis 命令
# ============================================================

Write-Host "[1/3] Generating $Users tokens..."

$tokens = [System.Collections.Generic.List[string]]::new($Users)
$commands = [System.Collections.Generic.List[string]]::new($Users * 2)

for ($index = 0; $index -lt $Users; $index++) {

    $token = [Guid]::NewGuid().ToString("N")
    $userId = $FirstUserId + $index

    $key = "login:token:$token"
    $nickName = "load_user_$userId"

    # 保存给 JMeter 使用
    $tokens.Add($token)

    # 每个用户：
    # 1. 写入登录信息
    # 2. 设置 24 小时 TTL
    $commands.Add(
        "HSET $key id $userId nickName $nickName"
    )

    $commands.Add(
        "EXPIRE $key 86400"
    )
}

# Token 文件
[IO.File]::WriteAllLines(
    $outputPath,
    $tokens,
    [Text.UTF8Encoding]::new($false)
)

# Redis 命令临时文件
[IO.File]::WriteAllLines(
    $commandFile,
    $commands,
    [Text.UTF8Encoding]::new($false)
)

Write-Host "Generated $Users tokens."
Write-Host ""

# ============================================================
# 3. 构造 Redis 参数
# ============================================================

$connection = @(
    "-h", $RedisHost,
    "-p", [string]$RedisPort,
    "-n", [string]$RedisDatabase,
    "--raw"
)

if ($RedisPassword) {
    $connection += @(
        "--no-auth-warning",
        "-a", $RedisPassword
    )
}

# ============================================================
# 4. 单次启动 redis-cli，批量执行所有命令
# ============================================================

Write-Host "[2/3] Writing users to Redis..."

try {

    if ($RedisDistro) {

        # 这里只启动一次 WSL
        $null = Get-Content `
            -Path $commandFile `
            -Encoding UTF8 |
            & wsl.exe `
                -d $RedisDistro `
                --exec $RedisCli `
                @connection

    }
    else {

        # Windows 本地 redis-cli
        $null = Get-Content `
            -Path $commandFile `
            -Encoding UTF8 |
            & $RedisCli @connection
    }

    if ($LASTEXITCODE -ne 0) {
        throw "redis-cli exited with code $LASTEXITCODE"
    }

}
finally {

    # 临时命令文件用完就删
    Remove-Item `
        -Path $commandFile `
        -Force `
        -ErrorAction SilentlyContinue
}

Write-Host "Redis batch write completed."
Write-Host ""

# ============================================================
# 5. 校验 Token 文件
# ============================================================

Write-Host "[3/3] Verifying output..."

$actualCount = (
    Get-Content -Path $outputPath
).Count

if ($actualCount -ne $Users) {
    throw "Token count mismatch: expected=$Users actual=$actualCount"
}

Write-Host ""
Write-Host "========================================"
Write-Host " SUCCESS"
Write-Host "========================================"
Write-Host "Users seeded : $Users"
Write-Host "Token file   : $outputPath"
Write-Host "Redis DB     : $RedisDatabase"
Write-Host "========================================"
