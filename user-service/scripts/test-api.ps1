param(
    [string]$BaseUrl = "http://localhost:8080"
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

            return [pscustomobject]@{
                StatusCode = [int]$response.StatusCode
                ContentType = [string]$response.Content.Headers.ContentType
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

try {
    Write-Host "Testing user-service at $BaseUrl"

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
    }

    Invoke-Check -Name "POST /api/v1/auth/login (wrong password returns 401)" `
        -Method "POST" -Path "/api/v1/auth/login" -ExpectedStatus 401 -Body @{
            email = $email
            password = "WrongPassword123!"
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
