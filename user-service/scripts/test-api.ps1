param(
    [string]$BaseUrl = "http://localhost:8080",
    # Docker container used to verify Redis caching and to clear rate-limit buckets before the run.
    [string]$RedisContainer = "user-service-redis",
    [switch]$SkipRedisChecks
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd("/")
Add-Type -AssemblyName System.Net.Http

$script:HttpClient = New-Object System.Net.Http.HttpClient
$script:Results = New-Object System.Collections.Generic.List[object]

function Invoke-ApiRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body,
        [string]$BearerToken
    )

    $request = New-Object System.Net.Http.HttpRequestMessage(
        [System.Net.Http.HttpMethod]::new($Method),
        "$BaseUrl$Path"
    )
    $request.Headers.Accept.ParseAdd("application/json")

    if ($BearerToken) {
        $request.Headers.Authorization =
            New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $BearerToken)
    }

    if ($PSBoundParameters.ContainsKey("Body")) {
        $json = ConvertTo-Json -InputObject $Body -Compress
        $request.Content = New-Object System.Net.Http.StringContent(
            $json,
            [System.Text.Encoding]::UTF8,
            "application/json"
        )
    }

    try {
        $response = $script:HttpClient.SendAsync($request).GetAwaiter().GetResult()
        try {
            $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $parsed = $null
            if ($content) {
                try {
                    $parsed = ConvertFrom-Json -InputObject $content -ErrorAction Stop
                }
                catch {
                    $parsed = $null
                }
            }

            $headers = @{}
            foreach ($header in $response.Headers) { $headers[$header.Key] = $header.Value -join "," }
            foreach ($header in $response.Content.Headers) { $headers[$header.Key] = $header.Value -join "," }

            return [pscustomobject]@{
                StatusCode = [int]$response.StatusCode
                ContentType = [string]$response.Content.Headers.ContentType
                Headers = $headers
                Body = $content
                Json = $parsed
            }
        }
        finally {
            $response.Dispose()
        }
    }
    finally {
        $request.Dispose()
    }
}

function Assert-Status {
    param(
        [string]$Name,
        [object]$Response,
        [int]$ExpectedStatus
    )

    $passed = $Response.StatusCode -eq $ExpectedStatus
    $detail = "expected HTTP $ExpectedStatus, received HTTP $($Response.StatusCode)"
    if (-not $passed -and $Response.Body) {
        $detail += ": $($Response.Body)"
    }

    $script:Results.Add([pscustomobject]@{
        Name = $Name
        Passed = $passed
        Detail = $detail
    })
}

function Assert-Condition {
    param(
        [string]$Name,
        [bool]$Condition,
        [string]$Detail
    )

    $script:Results.Add([pscustomobject]@{
        Name = $Name
        Passed = $Condition
        Detail = $Detail
    })
}

function Add-Skipped {
    param([string]$Name, [string]$Reason)

    $script:Results.Add([pscustomobject]@{
        Name = $Name
        Passed = $false
        Detail = "SKIPPED: $Reason"
    })
}

function Invoke-Check {
    param(
        [string]$Name,
        [string]$Method,
        [string]$Path,
        [int]$ExpectedStatus,
        [object]$Body,
        [string]$BearerToken
    )

    try {
        $requestParameters = @{
            Method = $Method
            Path = $Path
        }
        if ($PSBoundParameters.ContainsKey("Body")) {
            $requestParameters.Body = $Body
        }
        if ($BearerToken) {
            $requestParameters.BearerToken = $BearerToken
        }

        $response = Invoke-ApiRequest @requestParameters
        Assert-Status -Name $Name -Response $response -ExpectedStatus $ExpectedStatus
        return $response
    }
    catch {
        $script:Results.Add([pscustomobject]@{
            Name = $Name
            Passed = $false
            Detail = "Request failed: $($_.Exception.Message)"
        })
        return $null
    }
}

function Invoke-Redis {
    param([string[]]$Arguments)

    # Native stderr must not become a terminating error under Windows PowerShell 5.1.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & docker exec $RedisContainer redis-cli @Arguments 2>$null
        if ($LASTEXITCODE -ne 0) { return $null }
        return @($output | Where-Object { $_ })
    }
    catch {
        return $null
    }
    finally {
        $ErrorActionPreference = $previous
    }
}

$redisAvailable = $false
if (-not $SkipRedisChecks -and (Get-Command docker -ErrorAction SilentlyContinue)) {
    $redisAvailable = $null -ne (Invoke-Redis -Arguments @("PING"))
}

try {
    Write-Host "Testing user-service at $BaseUrl"

    if ($redisAvailable) {
        # Rate-limit buckets persist across runs (e.g. 5 registrations/hour/IP); start from a clean slate.
        $buckets = Invoke-Redis -Arguments @("--scan", "--pattern", "user-service:rl:*")
        if ($buckets) { Invoke-Redis -Arguments (@("DEL") + $buckets) | Out-Null }
        Write-Host "Cleared $(@($buckets).Count) rate-limit bucket(s) in container '$RedisContainer'."
    }
    else {
        Write-Host "Redis container '$RedisContainer' not reachable via docker; Redis checks will be skipped."
    }

    $health = Invoke-Check -Name "GET /actuator/health" -Method "GET" `
        -Path "/actuator/health" -ExpectedStatus 200
    if ($health -and $health.Json) {
        Assert-Condition -Name "Health status is UP" `
            -Condition ($health.Json.status -eq "UP") `
            -Detail "status=$($health.Json.status)"
    }
    else {
        Add-Skipped -Name "Health status is UP" -Reason "health response was not JSON"
    }

    $openApi = Invoke-Check -Name "GET /v3/api-docs" -Method "GET" `
        -Path "/v3/api-docs" -ExpectedStatus 200
    $requiredPaths = @(
        "/api/v1/auth/register",
        "/api/v1/auth/login",
        "/api/v1/auth/refresh",
        "/api/v1/auth/logout",
        "/api/v1/users/me",
        "/api/v1/users/{id}"
    )
    if ($openApi -and $openApi.Json -and $openApi.Json.paths) {
        foreach ($path in $requiredPaths) {
            Assert-Condition -Name "OpenAPI documents $path" `
                -Condition ($null -ne $openApi.Json.paths.PSObject.Properties[$path]) `
                -Detail "path present in generated OpenAPI document"
        }
    }
    else {
        Add-Skipped -Name "OpenAPI route coverage" -Reason "OpenAPI document was unavailable or invalid"
    }

    $swagger = Invoke-Check -Name "GET /swagger-ui/index.html" -Method "GET" `
        -Path "/swagger-ui/index.html" -ExpectedStatus 200
    if ($swagger) {
        Assert-Condition -Name "Swagger UI returns HTML" `
            -Condition ($swagger.ContentType -like "text/html*") `
            -Detail "Content-Type=$($swagger.ContentType)"
    }

    $email = "api-smoke-$([Guid]::NewGuid().ToString('N'))@example.com"
    $password = "StrongPassword123!"
    Invoke-Check -Name "POST /api/v1/auth/register (invalid email and short password return 400)" `
        -Method "POST" -Path "/api/v1/auth/register" -ExpectedStatus 400 -Body @{
            email = "not-an-email"
            password = "short"
            firstName = "API"
            lastName = "SmokeTest"
        } | Out-Null

    $registrationBody = @{
        email = $email
        password = $password
        firstName = "API"
        lastName = "SmokeTest"
    }
    $register = Invoke-Check -Name "POST /api/v1/auth/register" -Method "POST" `
        -Path "/api/v1/auth/register" -ExpectedStatus 201 -Body $registrationBody

    Invoke-Check -Name "POST /api/v1/auth/register (duplicate email returns 409)" `
        -Method "POST" -Path "/api/v1/auth/register" -ExpectedStatus 409 `
        -Body $registrationBody | Out-Null

    $accessToken = $null
    $refreshToken = $null
    if ($register -and $register.Json) {
        $accessToken = $register.Json.accessToken
        $refreshToken = $register.Json.refreshToken
    }
    Assert-Condition -Name "Registration returns both tokens" `
        -Condition ([bool]$accessToken -and [bool]$refreshToken) `
        -Detail "accessToken and refreshToken are required"

    if ($accessToken) {
        $me = Invoke-Check -Name "GET /api/v1/users/me (authenticated)" `
            -Method "GET" -Path "/api/v1/users/me" -ExpectedStatus 200 `
            -BearerToken $accessToken
        if ($me -and $me.Json) {
            Assert-Condition -Name "Current-user response matches registration" `
                -Condition ($me.Json.email -eq $email) `
                -Detail "email=$($me.Json.email)"

            if ($redisAvailable -and $me.Json.id) {
                $cacheKey = "user-service:v1:users::$($me.Json.id)"
                $cached = Invoke-Redis -Arguments @("EXISTS", $cacheKey)
                Assert-Condition -Name "User profile is cached in Redis" `
                    -Condition ($cached -and $cached[0] -eq "1") `
                    -Detail "key $cacheKey exists"
                $ttl = Invoke-Redis -Arguments @("TTL", $cacheKey)
                Assert-Condition -Name "Cached profile has a TTL" `
                    -Condition ($ttl -and [int]$ttl[0] -gt 0) `
                    -Detail "TTL=$($ttl)s"
            }

            if ($me.Json.id) {
                $byId = Invoke-Check -Name "GET /api/v1/users/{id} (USER_READ)" `
                    -Method "GET" -Path "/api/v1/users/$($me.Json.id)" `
                    -ExpectedStatus 200 -BearerToken $accessToken
                if ($byId -and $byId.Json) {
                    Assert-Condition -Name "Get-by-ID returns the requested user" `
                        -Condition ($byId.Json.id -eq $me.Json.id) `
                        -Detail "id=$($byId.Json.id)"
                }
            }
            else {
                Add-Skipped -Name "GET /api/v1/users/{id}" -Reason "current-user response did not include an ID"
            }
        }

        Invoke-Check -Name "GET /api/v1/users/me (no token returns 401)" `
            -Method "GET" -Path "/api/v1/users/me" -ExpectedStatus 401 | Out-Null
    }
    else {
        Add-Skipped -Name "Authenticated user routes" -Reason "registration did not return an access token"
    }

    $login = Invoke-Check -Name "POST /api/v1/auth/login" -Method "POST" `
        -Path "/api/v1/auth/login" -ExpectedStatus 200 -Body @{
            email = $email
            password = $password
        }
    if ($login -and $login.Json) {
        Assert-Condition -Name "Login returns an access token" `
            -Condition ([bool]$login.Json.accessToken) `
            -Detail "accessToken is required"
        Assert-Condition -Name "Login response carries rate-limit headers" `
            -Condition ($login.Headers.ContainsKey("X-RateLimit-Limit") -and $login.Headers.ContainsKey("X-RateLimit-Remaining")) `
            -Detail "X-RateLimit-Limit=$($login.Headers['X-RateLimit-Limit']), X-RateLimit-Remaining=$($login.Headers['X-RateLimit-Remaining'])"
    }

    Invoke-Check -Name "POST /api/v1/auth/login (wrong password returns 401)" `
        -Method "POST" -Path "/api/v1/auth/login" -ExpectedStatus 401 -Body @{
            email = $email
            password = "WrongPassword123!"
        } | Out-Null

    # Brute force: failed logins per (account, IP) are capped at 5 per 15 minutes.
    $victim = "bruteforce-$([Guid]::NewGuid().ToString('N'))@example.com"
    $failuresBeforeBlock = 0
    $blocked = $null
    for ($attempt = 1; $attempt -le 6; $attempt++) {
        $response = Invoke-ApiRequest -Method "POST" -Path "/api/v1/auth/login" -Body @{
            email = $victim
            password = "WrongPassword123!"
        }
        if ($response.StatusCode -eq 401) { $failuresBeforeBlock++ }
        elseif ($response.StatusCode -eq 429) { $blocked = $response; break }
    }
    Assert-Condition -Name "Brute force: 5 failed logins return 401" `
        -Condition ($failuresBeforeBlock -eq 5) `
        -Detail "401 count before block=$failuresBeforeBlock"
    Assert-Condition -Name "Brute force: 6th attempt returns 429 with Retry-After" `
        -Condition ($null -ne $blocked -and $blocked.Headers.ContainsKey("Retry-After")) `
        -Detail $(if ($blocked) { "Retry-After=$($blocked.Headers['Retry-After'])s, body=$($blocked.Body)" } else { "no 429 received" })

    Invoke-Check -Name "Brute force on one account does not block another account from the same IP" `
        -Method "POST" -Path "/api/v1/auth/login" -ExpectedStatus 200 -Body @{
            email = $email
            password = $password
        } | Out-Null

    if ($refreshToken) {
        $refresh = Invoke-Check -Name "POST /api/v1/auth/refresh" -Method "POST" `
            -Path "/api/v1/auth/refresh" -ExpectedStatus 200 -Body @{
                refreshToken = $refreshToken
            }
        if ($refresh -and $refresh.Json) {
            $rotatedRefreshToken = $refresh.Json.refreshToken
            Assert-Condition -Name "Refresh rotates the refresh token" `
                -Condition ([bool]$rotatedRefreshToken -and $rotatedRefreshToken -ne $refreshToken) `
                -Detail "refresh response must contain a different refreshToken"

            Invoke-Check -Name "POST /api/v1/auth/refresh (old token rejected)" `
                -Method "POST" -Path "/api/v1/auth/refresh" -ExpectedStatus 401 -Body @{
                    refreshToken = $refreshToken
                } | Out-Null

            if ($rotatedRefreshToken) {
                $secondRefresh = Invoke-Check -Name "POST /api/v1/auth/refresh (rotated token works for next refresh)" `
                    -Method "POST" -Path "/api/v1/auth/refresh" -ExpectedStatus 200 -Body @{
                        refreshToken = $rotatedRefreshToken
                    }
                if ($secondRefresh -and $secondRefresh.Json -and $secondRefresh.Json.refreshToken) {
                    $rotatedRefreshToken = $secondRefresh.Json.refreshToken
                }

                $logout = Invoke-Check -Name "POST /api/v1/auth/logout" -Method "POST" `
                    -Path "/api/v1/auth/logout" -ExpectedStatus 204 -Body @{
                        refreshToken = $rotatedRefreshToken
                    }

                if ($logout -and $logout.StatusCode -eq 204) {
                    Invoke-Check -Name "POST /api/v1/auth/refresh (logged-out token rejected)" `
                        -Method "POST" -Path "/api/v1/auth/refresh" -ExpectedStatus 401 -Body @{
                            refreshToken = $rotatedRefreshToken
                        } | Out-Null
                }
            }
        }
    }
    else {
        Add-Skipped -Name "Refresh and logout routes" -Reason "registration did not return a refresh token"
    }
}
finally {
    $script:HttpClient.Dispose()
}

Write-Host ""
foreach ($result in $script:Results) {
    if ($result.Passed) {
        Write-Host "[PASS] $($result.Name) - $($result.Detail)" -ForegroundColor Green
    }
    else {
        Write-Host "[FAIL] $($result.Name) - $($result.Detail)" -ForegroundColor Red
    }
}

$passedCount = @($script:Results | Where-Object { $_.Passed }).Count
$failedCount = $script:Results.Count - $passedCount
Write-Host ""
Write-Host "Summary: $passedCount passed, $failedCount failed, $($script:Results.Count) total."

if ($failedCount -gt 0) {
    exit 1
}
