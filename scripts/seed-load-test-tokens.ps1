param(
    [int]$Users = 2000,
    [long]$FirstUserId = 100000,
    [string]$Output = "target/load-test/tokens.txt",
    [string]$RedisCli = "redis-cli",
    [string]$RedisHost = "127.0.0.1",
    [int]$RedisPort = 6379,
    [int]$RedisDatabase = 0,
    [string]$RedisPassword = ""
)

$ErrorActionPreference = "Stop"
$outputPath = [IO.Path]::GetFullPath((Join-Path (Get-Location) $Output))
$outputDirectory = Split-Path -Parent $outputPath
[IO.Directory]::CreateDirectory($outputDirectory) | Out-Null

$connection = @("-h", $RedisHost, "-p", [string]$RedisPort, "-n", [string]$RedisDatabase, "--raw")
if ($RedisPassword) {
    $connection += @("--no-auth-warning", "-a", $RedisPassword)
}

$tokens = [System.Collections.Generic.List[string]]::new($Users)
for ($index = 0; $index -lt $Users; $index++) {
    $token = [Guid]::NewGuid().ToString("N")
    $userId = $FirstUserId + $index
    $key = "login:token:$token"

    & $RedisCli @connection HSET $key id $userId nickName "load_user_$userId" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to seed Redis token for user $userId"
    }
    & $RedisCli @connection EXPIRE $key 86400 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to set token TTL for user $userId"
    }
    $tokens.Add($token)
}

[IO.File]::WriteAllLines($outputPath, $tokens, [Text.UTF8Encoding]::new($false))
Write-Host "Seeded $Users load-test tokens and wrote $outputPath"
