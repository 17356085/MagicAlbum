Set-StrictMode -Version Latest

# This module is intentionally a small, API-only recovery fixture.  It keeps
# credentials in the caller's process and returns only durable, non-secret
# evidence.  The idempotency key is retained in the creation fixture because
# the restore readback uses it to prove a replay, but the readback never emits
# it.

$script:StageRestoreStatuses = @(
    'IDLE', 'PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'SUPERSEDED'
)

function Test-StageRestoreProperty {
    [CmdletBinding()]
    param(
        [AllowNull()]
        [object]$InputObject,
        [Parameter(Mandatory)]
        [string]$Name
    )

    if ($null -eq $InputObject) {
        return $false
    }
    return $null -ne $InputObject.PSObject.Properties[$Name]
}

function Get-StageRestoreRequiredProperty {
    [CmdletBinding()]
    param(
        [AllowNull()]
        [object]$InputObject,
        [Parameter(Mandatory)]
        [string]$Name
    )

    if (-not (Test-StageRestoreProperty -InputObject $InputObject -Name $Name)) {
        throw "Stage restore response is missing required field '$Name'."
    }
    return $InputObject.PSObject.Properties[$Name].Value
}

function ConvertTo-StageRestoreInt64 {
    [CmdletBinding()]
    param(
        [AllowNull()]
        [object]$Value,
        [Parameter(Mandatory)]
        [string]$FieldName,
        [switch]$AllowNull
    )

    if ($null -eq $Value) {
        if ($AllowNull) {
            return $null
        }
        throw "Stage restore response field '$FieldName' must be an integer."
    }
    if ($Value -is [bool]) {
        throw "Stage restore response field '$FieldName' must be an integer."
    }
    $text = [Convert]::ToString($Value, [Globalization.CultureInfo]::InvariantCulture)
    if ($text -notmatch '^-?[0-9]+$') {
        throw "Stage restore response field '$FieldName' must be an integer."
    }
    $parsed = [long]0
    if (-not [long]::TryParse(
            $text,
            [Globalization.NumberStyles]::Integer,
            [Globalization.CultureInfo]::InvariantCulture,
            [ref]$parsed)) {
        throw "Stage restore response field '$FieldName' is outside the supported integer range."
    }
    return $parsed
}

function ConvertTo-StageRestoreNullableString {
    [CmdletBinding()]
    param(
        [AllowNull()]
        [object]$Value,
        [Parameter(Mandatory)]
        [string]$FieldName
    )

    if ($null -eq $Value) {
        return $null
    }
    if ($Value -isnot [string]) {
        throw "Stage restore response field '$FieldName' must be a string or null."
    }
    return [string]$Value
}

function ConvertTo-StageRestoreRequiredString {
    [CmdletBinding()]
    param(
        [AllowNull()]
        [object]$Value,
        [Parameter(Mandatory)]
        [string]$FieldName
    )

    $text = ConvertTo-StageRestoreNullableString -Value $Value -FieldName $FieldName
    if ([string]::IsNullOrWhiteSpace($text)) {
        throw "Stage restore response field '$FieldName' must not be empty."
    }
    return $text
}

function ConvertTo-StageRestoreBoolean {
    [CmdletBinding()]
    param(
        [AllowNull()]
        [object]$Value,
        [Parameter(Mandatory)]
        [string]$FieldName
    )

    if ($Value -isnot [bool]) {
        throw "Stage restore response field '$FieldName' must be boolean."
    }
    return [bool]$Value
}

function ConvertTo-StageRestoreBaseUri {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$BaseUrl
    )

    if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
        throw 'Stage restore BaseUrl must not be empty.'
    }
    try {
        $uri = [Uri]$BaseUrl.Trim()
    } catch {
        throw 'Stage restore BaseUrl must be an HTTP or HTTPS URL.'
    }
    if ($uri.Scheme -notin @('http', 'https') -or
        [string]::IsNullOrWhiteSpace($uri.Host) -or
        -not [string]::IsNullOrEmpty($uri.UserInfo) -or
        -not [string]::IsNullOrEmpty($uri.Query) -or
        -not [string]::IsNullOrEmpty($uri.Fragment)) {
        throw 'Stage restore BaseUrl must be an HTTP or HTTPS URL without credentials, query, or fragment.'
    }
    return $uri
}

function New-StageRestoreRequestUri {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [Uri]$BaseUri,
        [Parameter(Mandatory)]
        [string]$Path
    )

    if ([string]::IsNullOrWhiteSpace($Path) -or -not $Path.StartsWith('/')) {
        throw 'Stage restore request paths must be absolute API paths.'
    }
    $root = $BaseUri.AbsoluteUri.TrimEnd('/')
    $basePath = $BaseUri.AbsolutePath.TrimEnd('/')
    $prefix = if ($basePath -eq '/api/v1') { '' } else { '/api/v1' }
    try {
        return [Uri]::new("$root$prefix$Path")
    } catch {
        throw 'Stage restore request URL could not be constructed.'
    }
}

function Invoke-StageRestoreRequest {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$BaseUrl,
        [Parameter(Mandatory)]
        [string]$Token,
        [Parameter(Mandatory)]
        [ValidateSet('GET', 'POST', 'PUT', 'PATCH')]
        [string]$Method,
        [Parameter(Mandatory)]
        [string]$Path,
        [AllowNull()]
        [object]$Body,
        [int[]]$ExpectedStatus = @(200),
        [AllowEmptyString()]
        [string]$IdempotencyKey
    )

    if ([string]::IsNullOrWhiteSpace($Token)) {
        throw 'Stage restore request token must not be empty.'
    }
    $baseUri = ConvertTo-StageRestoreBaseUri -BaseUrl $BaseUrl
    $requestUri = New-StageRestoreRequestUri -BaseUri $baseUri -Path $Path
    if ($null -eq $ExpectedStatus -or $ExpectedStatus.Count -eq 0) {
        throw 'Stage restore request must declare at least one expected HTTP status.'
    }
    if (-not [string]::IsNullOrWhiteSpace($IdempotencyKey) -and
        $IdempotencyKey -notmatch '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$') {
        throw 'Stage restore Idempotency-Key must be a UUIDv4.'
    }

    $client = [Net.Http.HttpClient]::new()
    $request = $null
    $response = $null
    try {
        $client.Timeout = [TimeSpan]::FromSeconds(30)
        $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), $requestUri)
        $authorization = $Token.Trim()
        if (-not $authorization.StartsWith('Bearer ', [StringComparison]::OrdinalIgnoreCase)) {
            $authorization = "Bearer $authorization"
        }
        $null = $request.Headers.TryAddWithoutValidation('Authorization', $authorization)
        if (-not [string]::IsNullOrWhiteSpace($IdempotencyKey)) {
            $null = $request.Headers.TryAddWithoutValidation('Idempotency-Key', $IdempotencyKey)
        }
        if ($null -ne $Body) {
            $jsonBody = ConvertTo-Json -InputObject $Body -Compress -Depth 20
            $request.Content = [Net.Http.StringContent]::new($jsonBody, [Text.Encoding]::UTF8, 'application/json')
        }
        try {
            $response = $client.Send($request)
        } catch {
            throw "Stage restore request $Method $Path failed before receiving a response."
        }
        $statusCode = [int]$response.StatusCode
        try {
            $bodyText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        } catch {
            throw "Stage restore request $Method $Path could not read the response body."
        }
        if ($statusCode -notin $ExpectedStatus) {
            $expectedText = ($ExpectedStatus -join ',')
            throw "Stage restore request $Method $Path returned HTTP $statusCode; expected $expectedText."
        }
        $json = $null
        if (-not [string]::IsNullOrWhiteSpace($bodyText)) {
            try {
                $json = $bodyText | ConvertFrom-Json -Depth 30
            } catch {
                throw "Stage restore request $Method $Path returned an invalid JSON response."
            }
        }
        return [pscustomobject]@{
            StatusCode = $statusCode
            Json       = $json
        }
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        if ($null -ne $request) { $request.Dispose() }
        $client.Dispose()
    }
}

function Assert-StageRestoreSummaryView {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [object]$Response,
        [Parameter(Mandatory)]
        [long]$ThreadId
    )

    $json = Get-StageRestoreRequiredProperty -InputObject $Response -Name 'Json'
    if ($null -eq $json) {
        throw 'Stage restore summary response must be a direct JSON object.'
    }
    $threadId = ConvertTo-StageRestoreInt64 -Value (Get-StageRestoreRequiredProperty $json 'threadId') -FieldName 'threadId'
    if ($threadId -ne $ThreadId) {
        throw "Stage restore summary response belongs to an unexpected thread."
    }
    $taskIdValue = Get-StageRestoreRequiredProperty $json 'taskId'
    $taskId = ConvertTo-StageRestoreNullableString -Value $taskIdValue -FieldName 'taskId'
    if ($null -ne $taskId -and [string]::IsNullOrWhiteSpace($taskId)) {
        throw 'Stage restore summary taskId must be a non-empty string or null.'
    }
    $contentVersion = ConvertTo-StageRestoreInt64 -Value (Get-StageRestoreRequiredProperty $json 'contentVersion') -FieldName 'contentVersion'
    if ($contentVersion -lt 0) { throw 'Stage restore contentVersion must be non-negative.' }
    $taskContentVersion = ConvertTo-StageRestoreInt64 -Value (Get-StageRestoreRequiredProperty $json 'taskContentVersion') -FieldName 'taskContentVersion' -AllowNull
    if ($null -ne $taskContentVersion -and $taskContentVersion -lt 0) { throw 'Stage restore taskContentVersion must be non-negative.' }
    $status = ConvertTo-StageRestoreRequiredString -Value (Get-StageRestoreRequiredProperty $json 'status') -FieldName 'status'
    if ($status -notin $script:StageRestoreStatuses) { throw "Stage restore response has unknown task status '$status'." }
    $summaryRaw = Get-StageRestoreRequiredProperty $json 'summary'
    if ($summaryRaw -isnot [string]) { throw "Stage restore response field 'summary' must be a string." }
    $summary = [string]$summaryRaw
    $canManage = ConvertTo-StageRestoreBoolean -Value (Get-StageRestoreRequiredProperty $json 'canManage') -FieldName 'canManage'
    $attempts = ConvertTo-StageRestoreInt64 -Value (Get-StageRestoreRequiredProperty $json 'attempts') -FieldName 'attempts'
    if ($attempts -lt 0) { throw 'Stage restore attempts must be non-negative.' }
    $errorCode = ConvertTo-StageRestoreNullableString -Value (Get-StageRestoreRequiredProperty $json 'errorCode') -FieldName 'errorCode'
    $provider = ConvertTo-StageRestoreNullableString -Value (Get-StageRestoreRequiredProperty $json 'provider') -FieldName 'provider'
    $model = ConvertTo-StageRestoreNullableString -Value (Get-StageRestoreRequiredProperty $json 'model') -FieldName 'model'
    $inputTokens = ConvertTo-StageRestoreInt64 -Value (Get-StageRestoreRequiredProperty $json 'inputTokens') -FieldName 'inputTokens' -AllowNull
    $outputTokens = ConvertTo-StageRestoreInt64 -Value (Get-StageRestoreRequiredProperty $json 'outputTokens') -FieldName 'outputTokens' -AllowNull
    if (($null -ne $inputTokens -and $inputTokens -lt 0) -or ($null -ne $outputTokens -and $outputTokens -lt 0)) {
        throw 'Stage restore token usage must be non-negative.'
    }
    $usageEstimated = ConvertTo-StageRestoreBoolean -Value (Get-StageRestoreRequiredProperty $json 'usageEstimated') -FieldName 'usageEstimated'
    $chargedTokens = ConvertTo-StageRestoreInt64 -Value (Get-StageRestoreRequiredProperty $json 'chargedTokens') -FieldName 'chargedTokens'
    if ($chargedTokens -lt 0) { throw 'Stage restore chargedTokens must be non-negative.' }
    $requestId = ConvertTo-StageRestoreNullableString -Value (Get-StageRestoreRequiredProperty $json 'requestId') -FieldName 'requestId'
    $updatedAtRaw = Get-StageRestoreRequiredProperty $json 'updatedAt'
    if ($updatedAtRaw -is [DateTimeOffset]) {
        $updatedAt = $updatedAtRaw.ToUniversalTime().ToString('o', [Globalization.CultureInfo]::InvariantCulture)
    } elseif ($updatedAtRaw -is [DateTime]) {
        $updatedAt = $updatedAtRaw.ToUniversalTime().ToString('o', [Globalization.CultureInfo]::InvariantCulture)
    } else {
        $updatedAt = ConvertTo-StageRestoreNullableString -Value $updatedAtRaw -FieldName 'updatedAt'
    }
    if ($null -ne $updatedAt) {
        $parsedTime = [DateTimeOffset]::MinValue
        if (-not [DateTimeOffset]::TryParse($updatedAt, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind, [ref]$parsedTime)) {
            throw 'Stage restore updatedAt must be an ISO timestamp or null.'
        }
    }
    return [pscustomobject]@{
        threadId            = $threadId
        taskId              = $taskId
        contentVersion      = $contentVersion
        taskContentVersion  = $taskContentVersion
        status              = $status
        summary             = $summary
        canManage           = $canManage
        attempts            = $attempts
        errorCode           = $errorCode
        provider            = $provider
        model               = $model
        inputTokens         = $inputTokens
        outputTokens        = $outputTokens
        usageEstimated      = $usageEstimated
        chargedTokens       = $chargedTokens
        requestId           = $requestId
        updatedAt           = $updatedAt
    }
}

function Get-StageRestorePageItems {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [object]$Response,
        [Parameter(Mandatory)]
        [string]$ResourceName
    )

    $json = Get-StageRestoreRequiredProperty -InputObject $Response -Name 'Json'
    if ($null -eq $json) { throw "Stage restore $ResourceName response must be a JSON object." }
    $items = $null
    if (Test-StageRestoreProperty -InputObject $json -Name 'items') {
        $items = $json.PSObject.Properties['items'].Value
    } else {
        throw "Stage restore $ResourceName response is missing its page items."
    }
    if ($null -eq $items -or $items -isnot [System.Array]) { throw "Stage restore $ResourceName page items must be an array." }
    return @($items)
}

function Get-StageRestoreSha256 {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$Text
    )

    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
        $hash = $sha.ComputeHash($bytes)
        return (($hash | ForEach-Object { $_.ToString('x2') }) -join '')
    } finally {
        $sha.Dispose()
    }
}

function Wait-StageRestoreSummaryCompletion {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$BaseUrl,
        [Parameter(Mandatory)]
        [string]$Token,
        [Parameter(Mandatory)]
        [long]$ThreadId,
        [Parameter(Mandatory)]
        [object]$InitialView,
        [int]$TimeoutSeconds = 90
    )

    $view = $InitialView
    if ($view.status -eq 'COMPLETED') {
        return $view
    }
    if ([string]::IsNullOrWhiteSpace([string]$view.taskId)) {
        throw "Stage restore summary task did not return a taskId before status '$($view.status)'."
    }
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $response = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $Token -Method 'GET' -Path "/ai/summary/tasks/$([Uri]::EscapeDataString([string]$view.taskId))" -ExpectedStatus @(200)
        $view = Assert-StageRestoreSummaryView -Response $response -ThreadId $ThreadId
        if ($view.taskId -ne $InitialView.taskId) { throw 'Stage restore summary task identity changed while polling.' }
        if ($view.status -eq 'COMPLETED') { return $view }
        if ($view.status -in @('FAILED', 'CANCELLED', 'SUPERSEDED')) {
            $code = if ([string]::IsNullOrWhiteSpace([string]$view.errorCode)) { '未知错误' } else { [string]$view.errorCode }
            throw "Stage restore summary task ended in $($view.status) ($code)."
        }
        Start-Sleep -Milliseconds 250
    }
    throw 'Stage restore summary task did not reach COMPLETED within 90 seconds.'
}

function Find-StageRestoreNotification {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [AllowNull()]
        [AllowEmptyCollection()]
        [object[]]$Items,
        [Parameter(Mandatory)]
        [long]$OtherUserId,
        [Parameter(Mandatory)]
        [long]$ThreadId
    )

    foreach ($item in $Items) {
        if ($null -eq $item) { continue }
        if (-not (Test-StageRestoreProperty -InputObject $item -Name 'id') -or
            -not (Test-StageRestoreProperty -InputObject $item -Name 'threadId') -or
            -not (Test-StageRestoreProperty -InputObject $item -Name 'type')) { continue }
        $idValue = $item.PSObject.Properties['id'].Value
        $notificationId = ConvertTo-StageRestoreInt64 -Value $idValue -FieldName 'notification.id'
        if ($notificationId -le 0) { continue }
        $threadValue = $item.PSObject.Properties['threadId'].Value
        if ($null -eq $threadValue) { continue }
        $notificationThreadId = ConvertTo-StageRestoreInt64 -Value $threadValue -FieldName 'notification.threadId'
        if ($notificationThreadId -ne $ThreadId) { continue }
        $type = ConvertTo-StageRestoreRequiredString -Value $item.PSObject.Properties['type'].Value -FieldName 'notification.type'
        if ($type.ToLowerInvariant() -ne 'like') { continue }

        $targetId = $null
        $targetType = $null
        if (Test-StageRestoreProperty -InputObject $item -Name 'targetId') {
            $rawTargetId = $item.PSObject.Properties['targetId'].Value
            if ($null -ne $rawTargetId) { $targetId = ConvertTo-StageRestoreInt64 -Value $rawTargetId -FieldName 'notification.targetId' }
        }
        if (Test-StageRestoreProperty -InputObject $item -Name 'targetType') {
            $targetType = ConvertTo-StageRestoreNullableString -Value $item.PSObject.Properties['targetType'].Value -FieldName 'notification.targetType'
        }
        if ($targetId -ne $ThreadId -or $targetType -ne 'thread') { continue }
        if (-not (Test-StageRestoreProperty $item 'link') -or $item.link -ne "/threads/$ThreadId") { continue }
        return [pscustomobject]@{
            id       = $notificationId
            actorId  = $null
            threadId = $notificationThreadId
            type     = $type
            targetId = $targetId
            targetType = $targetType
            link = $item.link
        }
    }
    return $null
}

function Wait-StageRestoreNotification {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$BaseUrl,
        [Parameter(Mandatory)]
        [string]$Token,
        [Parameter(Mandatory)]
        [long]$OtherUserId,
        [Parameter(Mandatory)]
        [long]$ThreadId,
        [int]$TimeoutSeconds = 90
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $response = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $Token -Method 'GET' -Path '/notifications?page=1&size=100' -ExpectedStatus @(200)
        $found = Find-StageRestoreNotification -Items @(Get-StageRestorePageItems -Response $response -ResourceName 'notification') -OtherUserId $OtherUserId -ThreadId $ThreadId
        $json = $response.Json
        $source = $null
        if (Test-StageRestoreProperty -InputObject $json -Name 'source') { $source = ConvertTo-StageRestoreNullableString -Value $json.PSObject.Properties['source'].Value -FieldName 'notification.source' }
        if ($null -ne $found) {
            if ($null -ne $source -and $source.ToLowerInvariant() -eq 'fallback') {
                throw 'Stage restore notification readback used a fallback source.'
            }
            $found | Add-Member -NotePropertyName source -NotePropertyValue $source
            return $found
        }
        Start-Sleep -Milliseconds 250
    }
    throw 'Stage restore like notification was not available within 90 seconds.'
}

function Get-StageRestoreAuditEvidence {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$BaseUrl,
        [Parameter(Mandatory)]
        [string]$Token,
        [Parameter(Mandatory)]
        [long]$AccountId,
        [Parameter(Mandatory)]
        [string]$RunId,
        [Parameter(Mandatory)]
        [string]$Reason
    )

    $response = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $Token -Method 'GET' -Path '/governance/audit?page=1&size=50' -ExpectedStatus @(200)
    $matches = [System.Collections.Generic.List[object]]::new()
    foreach ($item in (Get-StageRestorePageItems -Response $response -ResourceName 'audit')) {
        if ($null -eq $item) { continue }
        if (-not (Test-StageRestoreProperty -InputObject $item -Name 'actorId') -or
            -not (Test-StageRestoreProperty -InputObject $item -Name 'reason')) { continue }
        $rawActorId = $item.PSObject.Properties['actorId'].Value
        if ($null -eq $rawActorId) { continue }
        try { $actorId = ConvertTo-StageRestoreInt64 -Value $rawActorId -FieldName 'audit.actorId' } catch { continue }
        if ($actorId -ne $AccountId) { continue }
        $rawReason = $item.PSObject.Properties['reason'].Value
        if ($null -eq $rawReason -or $rawReason -isnot [string]) { continue }
        $reasonValue = [string]$rawReason
        if ($reasonValue -ne $Reason -or (-not $reasonValue.Contains($RunId, [StringComparison]::Ordinal))) { continue }
        $id = ConvertTo-StageRestoreInt64 -Value (Get-StageRestoreRequiredProperty $item 'id') -FieldName 'audit.id'
        $action = ConvertTo-StageRestoreRequiredString -Value (Get-StageRestoreRequiredProperty $item 'action') -FieldName 'audit.action'
        $targetType = ConvertTo-StageRestoreRequiredString -Value (Get-StageRestoreRequiredProperty $item 'targetType') -FieldName 'audit.targetType'
        $targetId = ConvertTo-StageRestoreInt64 -Value (Get-StageRestoreRequiredProperty $item 'targetId') -FieldName 'audit.targetId' -AllowNull
        if ($null -eq $targetId) { throw "Stage restore audit entry $id is missing targetId." }
        $requestId = ConvertTo-StageRestoreNullableString -Value (Get-StageRestoreRequiredProperty $item 'requestId') -FieldName 'audit.requestId'
        if ([string]::IsNullOrWhiteSpace($requestId)) { throw "Stage restore audit entry $id is missing requestId." }
        $matches.Add([pscustomobject]@{
                id = $id; actorId = $actorId; action = $action; targetType = $targetType
                targetId = $targetId; reason = $reasonValue; requestId = $requestId
            })
    }
    $requiredActions = @('MODERATOR_SECTIONS_CHANGED', 'POSTING_RESTRICTION_CHANGED')
    foreach ($requiredAction in $requiredActions) {
        if (-not @($matches | Where-Object { $_.action -eq $requiredAction }).Count) {
            throw "Stage restore audit is missing action '$requiredAction' for run $RunId."
        }
    }
    return @($matches)
}

function New-StageRestoreFixture {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [string]$BaseUrl,
        [Parameter(Mandatory)] [string]$Token,
        [Parameter(Mandatory)] [long]$AccountId,
        [Parameter(Mandatory)] [long]$ThreadId,
        [Parameter(Mandatory)] [long]$SectionId,
        [Parameter(Mandatory)] [ValidatePattern('^[0-9A-Fa-f]{10}$')] [string]$RunId,
        [Parameter(Mandatory)] [string]$OtherToken,
        [Parameter(Mandatory)] [long]$OtherUserId
    )

    foreach ($pair in @(@('AccountId', $AccountId), @('ThreadId', $ThreadId), @('SectionId', $SectionId), @('OtherUserId', $OtherUserId))) {
        if ([long]$pair[1] -le 0) { throw "Stage restore $($pair[0]) must be positive." }
    }
    if ([string]::IsNullOrWhiteSpace($OtherToken)) { throw 'Stage restore OtherToken must not be empty.' }
    $reason = "stage-restore-$RunId"

    $meResponse = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $Token -Method 'GET' -Path '/governance/me' -ExpectedStatus @(200)
    $me = Get-StageRestoreRequiredProperty -InputObject $meResponse -Name 'Json'
    if ($null -eq $me -or -not (Test-StageRestoreProperty $me 'admin') -or $me.admin -isnot [bool] -or -not $me.admin) {
        throw 'Stage restore account token is not an administrator.'
    }

    $moderatorBody = [ordered]@{ sectionIds = @([long]$SectionId); reason = $reason }
    $moderatorResponse = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $Token -Method 'PUT' -Path "/governance/users/$AccountId/moderator-sections" -Body $moderatorBody -ExpectedStatus @(200)
    $moderatorJson = Get-StageRestoreRequiredProperty $moderatorResponse 'Json'
    if ($null -ne $moderatorJson -and (Test-StageRestoreProperty $moderatorJson 'id') -and (ConvertTo-StageRestoreInt64 $moderatorJson.id 'moderator.id') -ne $AccountId) {
        throw 'Stage restore moderator section response targeted the wrong account.'
    }
    if ($null -ne $moderatorJson -and (Test-StageRestoreProperty $moderatorJson 'moderatedSectionIds')) {
        $returnedSections = @($moderatorJson.moderatedSectionIds | ForEach-Object { ConvertTo-StageRestoreInt64 $_ 'moderator.moderatedSectionIds' })
        if ($SectionId -notin $returnedSections) { throw 'Stage restore moderator section response did not retain the requested section.' }
    }

    # The like is deliberately created before restricting the other account so
    # a posting restriction cannot accidentally turn the interaction into a
    # permission test instead of a notification fixture.
    $likeResponse = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $OtherToken -Method 'POST' -Path "/threads/$ThreadId/like" -ExpectedStatus @(200)
    $likeJson = Get-StageRestoreRequiredProperty $likeResponse 'Json'
    if ($null -eq $likeJson -or (ConvertTo-StageRestoreInt64 (Get-StageRestoreRequiredProperty $likeJson 'threadId') 'like.threadId') -ne $ThreadId -or -not (ConvertTo-StageRestoreBoolean (Get-StageRestoreRequiredProperty $likeJson 'liked') 'like.liked')) {
        throw 'Stage restore like response did not confirm the target thread was liked.'
    }
    $likeReadResponse = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $OtherToken -Method 'GET' -Path "/threads/$ThreadId/like" -ExpectedStatus @(200)
    $likeReadJson = Get-StageRestoreRequiredProperty $likeReadResponse 'Json'
    if ($null -eq $likeReadJson -or (ConvertTo-StageRestoreInt64 (Get-StageRestoreRequiredProperty $likeReadJson 'threadId') 'likeRead.threadId') -ne $ThreadId -or -not (ConvertTo-StageRestoreBoolean (Get-StageRestoreRequiredProperty $likeReadJson 'liked') 'likeRead.liked')) {
        throw 'Stage restore like readback did not confirm the target thread was liked.'
    }

    $restrictionBody = [ordered]@{ restricted = $true; until = $null; reason = $reason }
    $restrictionResponse = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $Token -Method 'PUT' -Path "/governance/users/$OtherUserId/posting-restriction" -Body $restrictionBody -ExpectedStatus @(200)
    $restrictionJson = Get-StageRestoreRequiredProperty $restrictionResponse 'Json'
    if ($null -ne $restrictionJson -and (Test-StageRestoreProperty $restrictionJson 'id') -and (ConvertTo-StageRestoreInt64 $restrictionJson.id 'restriction.id') -ne $OtherUserId) {
        throw 'Stage restore posting restriction response targeted the wrong account.'
    }
    if ($null -ne $restrictionJson -and (Test-StageRestoreProperty $restrictionJson 'postingRestricted') -and
        ($restrictionJson.postingRestricted -isnot [bool] -or -not $restrictionJson.postingRestricted)) {
        throw 'Stage restore posting restriction response did not enable the restriction.'
    }
    if ($null -ne $restrictionJson -and (Test-StageRestoreProperty $restrictionJson 'restrictedUntil') -and $null -ne $restrictionJson.restrictedUntil) {
        throw 'Stage restore posting restriction response unexpectedly has an expiry.'
    }

    $idempotencyKey = [Guid]::NewGuid().ToString('D')
    $startResponse = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $Token -Method 'POST' -Path ("/ai/summary/" + $ThreadId + '?force=false') -Body $null -ExpectedStatus @(200) -IdempotencyKey $idempotencyKey
    $startView = Assert-StageRestoreSummaryView -Response $startResponse -ThreadId $ThreadId
    $finalView = Wait-StageRestoreSummaryCompletion -BaseUrl $BaseUrl -Token $Token -ThreadId $ThreadId -InitialView $startView
    if ($finalView.status -ne 'COMPLETED' -or $finalView.provider -ne 'mock' -or [string]::IsNullOrWhiteSpace($finalView.taskId) -or [string]::IsNullOrWhiteSpace($finalView.summary)) {
        throw 'Stage restore summary did not complete with the mock provider and a non-empty task.'
    }
    if ($null -ne $finalView.taskContentVersion -and $finalView.taskContentVersion -ne $finalView.contentVersion) {
        throw 'Stage restore summary task version does not match the current content version.'
    }
    $summarySha = Get-StageRestoreSha256 -Text $finalView.summary
    $notification = Wait-StageRestoreNotification -BaseUrl $BaseUrl -Token $Token -OtherUserId $OtherUserId -ThreadId $ThreadId
    $audits = Get-StageRestoreAuditEvidence -BaseUrl $BaseUrl -Token $Token -AccountId $AccountId -RunId $RunId -Reason $reason

    # The creation fixture is local-only.  It carries the key solely so the
    # restore readback can replay the exact request; callers must not publish it.
    return [pscustomobject][ordered]@{
        schemaVersion       = 1
        runId               = $RunId
        reason              = $reason
        accountId           = $AccountId
        otherUserId         = $OtherUserId
        threadId            = $ThreadId
        sectionId           = $SectionId
        idempotencyKey      = $idempotencyKey
        taskId              = $finalView.taskId
        contentVersion      = $finalView.contentVersion
        taskContentVersion  = $finalView.taskContentVersion
        summarySHA256       = $summarySha
        attempts            = $finalView.attempts
        chargedTokens       = $finalView.chargedTokens
        usageEstimated      = $finalView.usageEstimated
        provider             = $finalView.provider
        like                = [pscustomobject][ordered]@{ threadId = $ThreadId; liked = $true; likeCount = (Get-StageRestoreRequiredProperty $likeReadJson 'likeCount') }
        notification        = $notification
        audit               = @($audits)
    }
}

function Assert-StageRestoreFixtureNoSecrets {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [object]$Fixture
    )

    foreach ($property in $Fixture.PSObject.Properties) {
        if ($property.Name -match '(?i)(password|(?:access|refresh)?token$)') {
            if ($property.Name -notin @('idempotencyKey')) {
                throw "Stage restore fixture contains a credential-like property '$($property.Name)'."
            }
        }
    }
}

function Assert-StageRestoreFixture {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [string]$BaseUrl,
        [Parameter(Mandatory)] [string]$Token,
        [Parameter(Mandatory)] [object]$Fixture,
        [Parameter(Mandatory)] [string]$OtherToken
    )

    if ($null -eq $Fixture) { throw 'Stage restore fixture must not be null.' }
    Assert-StageRestoreFixtureNoSecrets -Fixture $Fixture
    foreach ($name in @('runId', 'reason', 'accountId', 'otherUserId', 'threadId', 'sectionId', 'idempotencyKey', 'taskId', 'contentVersion', 'summarySHA256', 'attempts', 'chargedTokens', 'usageEstimated', 'provider', 'notification', 'audit')) {
        if (-not (Test-StageRestoreProperty -InputObject $Fixture -Name $name)) { throw "Stage restore fixture is missing '$name'." }
    }
    if ([string]$Fixture.idempotencyKey -notmatch '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$') { throw 'Stage restore fixture idempotency key is not UUIDv4.' }
    $accountId = ConvertTo-StageRestoreInt64 $Fixture.accountId 'fixture.accountId'
    $otherUserId = ConvertTo-StageRestoreInt64 $Fixture.otherUserId 'fixture.otherUserId'
    $threadId = ConvertTo-StageRestoreInt64 $Fixture.threadId 'fixture.threadId'
    $sectionId = ConvertTo-StageRestoreInt64 $Fixture.sectionId 'fixture.sectionId'
    $taskId = ConvertTo-StageRestoreRequiredString $Fixture.taskId 'fixture.taskId'
    $contentVersion = ConvertTo-StageRestoreInt64 $Fixture.contentVersion 'fixture.contentVersion'
    $attempts = ConvertTo-StageRestoreInt64 $Fixture.attempts 'fixture.attempts'
    $chargedTokens = ConvertTo-StageRestoreInt64 $Fixture.chargedTokens 'fixture.chargedTokens'
    $usageEstimated = ConvertTo-StageRestoreBoolean $Fixture.usageEstimated 'fixture.usageEstimated'
    $summarySha = ConvertTo-StageRestoreRequiredString $Fixture.summarySHA256 'fixture.summarySHA256'
    if ($summarySha -notmatch '^[0-9a-fA-F]{64}$') { throw 'Stage restore fixture summarySHA256 is invalid.' }
    $provider = ConvertTo-StageRestoreRequiredString $Fixture.provider 'fixture.provider'
    foreach ($id in @($accountId, $otherUserId, $threadId, $sectionId)) { if ($id -le 0) { throw 'Stage restore fixture identifiers must be positive.' } }
    if ([string]::IsNullOrWhiteSpace($OtherToken)) { throw 'Stage restore OtherToken must not be empty.' }

    $meResponse = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $Token -Method 'GET' -Path '/governance/me' -ExpectedStatus @(200)
    $me = Get-StageRestoreRequiredProperty $meResponse 'Json'
    if ($null -eq $me -or -not (Test-StageRestoreProperty $me 'admin') -or $me.admin -isnot [bool] -or -not $me.admin) { throw 'Restored account token did not retain administrator access.' }
    $sections = @((Get-StageRestoreRequiredProperty $me 'moderatedSectionIds') | ForEach-Object { ConvertTo-StageRestoreInt64 $_ 'governance.moderatedSectionIds' })
    if ($sectionId -notin $sections) { throw 'Restored account token lost the moderator section scope.' }

    $otherMeResponse = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $OtherToken -Method 'GET' -Path '/governance/me' -ExpectedStatus @(200)
    $otherMe = Get-StageRestoreRequiredProperty $otherMeResponse 'Json'
    if ($null -eq $otherMe -or -not (Test-StageRestoreProperty $otherMe 'admin') -or $otherMe.admin -isnot [bool] -or $otherMe.admin) { throw 'Restored other token unexpectedly has administrator access.' }
    if (-not (Test-StageRestoreProperty $otherMe 'postingRestricted') -or $otherMe.postingRestricted -isnot [bool] -or -not $otherMe.postingRestricted) { throw 'Restored other token lost the posting restriction.' }
    if (-not (Test-StageRestoreProperty $otherMe 'restrictedUntil') -or $null -ne $otherMe.restrictedUntil) { throw 'Restored posting restriction has an unexpected expiry.' }

    $blockedBody = [ordered]@{
        sectionId = $sectionId
        title = "stage-restore-blocked-$($Fixture.runId)"
        content = "stage restore permission check $($Fixture.runId)"
        tags = @()
    }
    $blocked = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $OtherToken -Method 'POST' -Path '/threads' -Body $blockedBody -ExpectedStatus @(403)
    if ($blocked.StatusCode -ne 403) { throw 'Posting restriction did not reject a new thread with HTTP 403.' }
    $likeReadback = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $OtherToken -Method 'GET' -Path "/threads/$threadId/like" -ExpectedStatus @(200)
    $likeReadbackJson = Get-StageRestoreRequiredProperty $likeReadback 'Json'
    if ($null -eq $likeReadbackJson -or
        (ConvertTo-StageRestoreInt64 (Get-StageRestoreRequiredProperty $likeReadbackJson 'threadId') 'restoredLike.threadId') -ne $threadId -or
        -not (ConvertTo-StageRestoreBoolean (Get-StageRestoreRequiredProperty $likeReadbackJson 'liked') 'restoredLike.liked')) {
        throw 'Restored other token lost the original thread like.'
    }

    $summaryResponse = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $Token -Method 'GET' -Path "/ai/summary/$threadId" -ExpectedStatus @(200)
    $summaryView = Assert-StageRestoreSummaryView -Response $summaryResponse -ThreadId $threadId
    if ($summaryView.status -ne 'COMPLETED' -or $summaryView.provider -ne $provider -or $summaryView.provider -ne 'mock' -or $summaryView.taskId -ne $taskId -or $summaryView.contentVersion -ne $contentVersion -or $summaryView.attempts -ne $attempts -or $summaryView.chargedTokens -ne $chargedTokens -or $summaryView.usageEstimated -ne $usageEstimated -or (Get-StageRestoreSha256 -Text $summaryView.summary) -ne $summarySha) {
        throw 'Restored current summary does not match the creation fixture.'
    }
    if ($null -ne $summaryView.taskContentVersion -and $summaryView.taskContentVersion -ne $contentVersion) { throw 'Restored current summary task version is inconsistent.' }
    $taskResponse = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $Token -Method 'GET' -Path "/ai/summary/tasks/$([Uri]::EscapeDataString($taskId))" -ExpectedStatus @(200)
    $taskView = Assert-StageRestoreSummaryView -Response $taskResponse -ThreadId $threadId
    if ($taskView.status -ne 'COMPLETED' -or $taskView.taskId -ne $taskId -or $taskView.contentVersion -ne $contentVersion -or $taskView.attempts -ne $attempts -or $taskView.chargedTokens -ne $chargedTokens -or $taskView.usageEstimated -ne $usageEstimated -or (Get-StageRestoreSha256 -Text $taskView.summary) -ne $summarySha) {
        throw 'Restored summary task does not match the creation fixture.'
    }

    $replayResponse = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $Token -Method 'POST' -Path ("/ai/summary/" + $threadId + '?force=false') -Body $null -ExpectedStatus @(200) -IdempotencyKey ([string]$Fixture.idempotencyKey)
    $replayView = Assert-StageRestoreSummaryView -Response $replayResponse -ThreadId $threadId
    if ($replayView.status -ne 'COMPLETED' -or $replayView.taskId -ne $taskId -or $replayView.contentVersion -ne $contentVersion -or $replayView.attempts -ne $attempts -or $replayView.chargedTokens -ne $chargedTokens) {
        throw 'Summary idempotency replay did not return the original task without another attempt.'
    }

    $notificationResponse = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $Token -Method 'GET' -Path '/notifications?page=1&size=100' -ExpectedStatus @(200)
    $notificationPage = Get-StageRestoreRequiredProperty $notificationResponse 'Json'
    $notification = Find-StageRestoreNotification -Items @(Get-StageRestorePageItems -Response $notificationResponse -ResourceName 'notification') -OtherUserId $otherUserId -ThreadId $threadId
    if ($null -eq $notification) { throw 'Restored like notification is missing or belongs to another thread.' }
    if ((Test-StageRestoreProperty -InputObject $notificationPage -Name 'source') -and $null -ne $notificationPage.source -and ([string]$notificationPage.source).ToLowerInvariant() -eq 'fallback') { throw 'Restored like notification used a fallback source.' }
    if ($notification.id -ne (ConvertTo-StageRestoreInt64 $Fixture.notification.id 'fixture.notification.id')) { throw 'Restored like notification id changed.' }
    $otherNotifications = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $OtherToken -Method 'GET' -Path '/notifications?page=1&size=100' -ExpectedStatus @(200)
    foreach ($otherItem in (Get-StageRestorePageItems $otherNotifications 'notification')) {
        if ($null -ne $otherItem -and (Test-StageRestoreProperty $otherItem 'id') -and $otherItem.id -eq $notification.id) {
            throw 'Restored notification leaked into the other account.'
        }
    }
    $restoredLike = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $OtherToken -Method 'GET' -Path "/threads/$threadId/like" -ExpectedStatus @(200)
    $like = $restoredLike.Json
    if ($like.threadId -ne $threadId -or $like.liked -isnot [bool] -or -not $like.liked -or $like.likeCount -ne $Fixture.like.likeCount) {
        throw 'Restored like did not preserve its account, thread or count.'
    }

    $auditResponse = Invoke-StageRestoreRequest -BaseUrl $BaseUrl -Token $Token -Method 'GET' -Path '/governance/audit?page=1&size=50' -ExpectedStatus @(200)
    $auditItems = Get-StageRestorePageItems -Response $auditResponse -ResourceName 'audit'
    foreach ($requiredAction in @('MODERATOR_SECTIONS_CHANGED', 'POSTING_RESTRICTION_CHANGED')) {
        if (-not @($Fixture.audit | Where-Object { $_.action -eq $requiredAction }).Count) {
            throw "Restore fixture has no evidence for audit action $requiredAction."
        }
    }
    $fixtureAuditIds = @($Fixture.audit | ForEach-Object { ConvertTo-StageRestoreInt64 $_.id 'fixture.audit.id' })
    foreach ($auditId in $fixtureAuditIds) {
        $match = @($auditItems | Where-Object {
                if ($null -eq $_ -or -not (Test-StageRestoreProperty -InputObject $_ -Name 'id')) { return $false }
                try { return (ConvertTo-StageRestoreInt64 $_.id 'audit.id') -eq $auditId } catch { return $false }
            })
        if ($match.Count -ne 1) { throw "Restored audit entry $auditId is missing." }
        $entry = $match[0]
        $actor = ConvertTo-StageRestoreInt64 (Get-StageRestoreRequiredProperty $entry 'actorId') 'audit.actorId'
        $auditReason = ConvertTo-StageRestoreRequiredString (Get-StageRestoreRequiredProperty $entry 'reason') 'audit.reason'
        $requestId = ConvertTo-StageRestoreRequiredString (Get-StageRestoreRequiredProperty $entry 'requestId') 'audit.requestId'
        $expectedAudit = @($Fixture.audit | Where-Object { $_.id -eq $auditId })[0]
        if ($actor -ne $accountId -or $auditReason -ne $expectedAudit.reason -or $requestId -ne $expectedAudit.requestId -or
            $entry.action -ne $expectedAudit.action -or $entry.targetType -ne $expectedAudit.targetType -or $entry.targetId -ne $expectedAudit.targetId) {
            throw "Restored audit entry $auditId is inconsistent."
        }
    }

    return [pscustomobject][ordered]@{
        passed               = $true
        provider             = 'mock'
        permissionsVerified  = $true
        summaryVerified      = $true
        notificationVerified = $true
        notificationIsolationVerified = $true
        auditVerified        = $true
        idempotencyVerified  = $true
        accountId            = $accountId
        otherUserId          = $otherUserId
        threadId             = $threadId
        sectionId            = $sectionId
        taskId               = $taskId
        contentVersion       = $contentVersion
        summarySHA256        = $summarySha.ToLowerInvariant()
        attempts             = $attempts
        chargedTokens        = $chargedTokens
        usageEstimated       = $usageEstimated
        notificationId       = $notification.id
        notificationActorId  = $notification.actorId
        notificationThreadId = $notification.threadId
        notificationLink     = $notification.link
        auditIds             = $fixtureAuditIds
        likeVerified         = $true
    }
}

Export-ModuleMember -Function @(
    'New-StageRestoreFixture',
    'Assert-StageRestoreFixture'
)
