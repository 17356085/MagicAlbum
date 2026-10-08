#Requires -Version 7.0
[CmdletBinding()]
param([string]$BaseUrl = 'http://127.0.0.1:5173')

$ErrorActionPreference = 'Stop'
$blueAlbumRoot = Split-Path -Parent $PSScriptRoot
$blueAlbumRunDir = Join-Path $blueAlbumRoot 'test-artifacts/local-runtime'
$blueAlbumApi = "$($BaseUrl.TrimEnd('/'))/api/v1"
$checks = [System.Collections.Generic.List[object]]::new()

function Assert-Local([bool]$Condition, [string]$Name) {
    $checks.Add(@{ Name=$Name; Passed=$Condition })
    if (-not $Condition) { throw "Local smoke check failed: $Name" }
    Write-Host "PASS $Name"
}

function Send-Local([string]$Method, [string]$Path, $Body, [string]$Token, [int]$Expected = 200) {
    $headers = @{ 'X-Device-Id'=$script:blueAlbumDeviceId }
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    $options = @{ Uri="$blueAlbumApi$Path"; Method=$Method; Headers=$headers; TimeoutSec=35; SkipHttpErrorCheck=$true }
    if ($null -ne $Body) {
        $options['ContentType'] = 'application/json'
        $options['Body'] = ConvertTo-Json -InputObject $Body -Depth 12 -Compress
    }
    $response = Invoke-WebRequest @options
    Assert-Local ($response.StatusCode -eq $Expected) "$Method $Path returns $Expected (actual $($response.StatusCode))"
    if ($response.Content -and $response.Headers['Content-Type'] -like '*json*') {
        return ConvertFrom-Json -InputObject $response.Content
    }
    return $response.Content
}

try {
    foreach ($port in @(8080,8081,9000)) {
        $health = Invoke-RestMethod "http://127.0.0.1:$port/actuator/health" -TimeoutSec 10
        Assert-Local ($health.status -eq 'UP') "Service $port health UP"
    }
    $page = Invoke-WebRequest $BaseUrl -TimeoutSec 10
    Assert-Local ($page.StatusCode -eq 200 -and $page.Content.Contains('id="app"')) 'Frontend HTML loads'
    $script:blueAlbumDeviceId = "smoke-$([Guid]::NewGuid().ToString('N'))"
    $sections = Send-Local GET '/sections' $null ''
    $sectionList = if ($sections.items) { @($sections.items) } else { @($sections) }
    Assert-Local ($sectionList.Count -gt 0 -and $sectionList[0].id) 'Flyway seeded sections are available through frontend and gateway'
    $null = Send-Local GET '/threads?page=1&size=10' $null ''
    $null = Send-Local GET '/users/me' $null '' 401
    $null = Send-Local POST '/ai/chat/stream' @{ messages=@(@{role='user';content='hello'}) } '' 401
    $null = Send-Local POST '/users/register' @{} '' 400

    $suffix = [Guid]::NewGuid().ToString('N').Substring(0,12)
    $password = "Local-$([Guid]::NewGuid().ToString('N'))!"
    $accounts = @()
    foreach ($role in @('owner','reader')) {
        $phone = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
        $email = "${role}_${suffix}@example.test"
        $registered = Send-Local POST '/users/register' @{username="${role}_${suffix}";email=$email;phone=$phone;password=$password} '' 201
        $login = Send-Local POST '/auth/login' @{email=$email;password=$password} ''
        Assert-Local ($login.accessToken -and $login.user.id -eq $registered.id) "$role password login returns matching authenticated user"
        $accounts += @{ Email=$email; Phone=$phone; Password=$password; UserId=$registered.id; Token=$login.accessToken }
    }
    $owner = $accounts[0]
    $reader = $accounts[1]
    $accountForTests = @{ Email=$owner.Email; Phone=$owner.Phone; Password=$owner.Password; UserId=$owner.UserId }
    [IO.File]::WriteAllText((Join-Path $blueAlbumRunDir 'test-account.json'), (ConvertTo-Json $accountForTests), (New-Object Text.UTF8Encoding $false))
    $profile = Send-Local GET '/users/me' $null $owner.Token
    Assert-Local ($null -ne $profile) 'Authenticated profile loads'
    $thread = Send-Local POST '/threads' @{sectionId=$sectionList[0].id;title="本地试运行 ${suffix}";content='# 本地试运行验证';tags=@('local-smoke')} $owner.Token 201
    Assert-Local ($thread.id -and $thread.authorId -eq $owner.UserId) 'Thread persisted with correct ownership'
    $detail = Send-Local GET "/threads/$($thread.id)" $null ''
    Assert-Local ($detail.title -eq $thread.title -and $detail.content -eq '# 本地试运行验证') 'Public thread detail reads persisted content'
    $null = Send-Local PATCH "/threads/$($thread.id)" @{title='forbidden update';content='blocked'} $reader.Token 403
    $post = Send-Local POST "/threads/$($thread.id)/posts" @{contentMd='本地试运行评论'} $reader.Token 201
    Assert-Local ($post.id -and $post.authorId -eq $reader.UserId) 'Comment persisted with correct author'
    $notifications = Send-Local GET '/notifications?unread=true' $null $owner.Token
    $notification = @($notifications.items | Where-Object { $_.type -eq 'reply' }) | Select-Object -First 1
    Assert-Local ($null -ne $notification) 'Comment creates cross-service reply notification'
    Assert-Local ($notification.threadId -eq $thread.id -and $notification.targetId -eq $post.id -and $notification.targetType -eq 'post' -and $notification.link -eq "/threads/$($thread.id)#post-$($post.id)") 'Reply notification preserves its persisted target and safe local link'
    $null = Send-Local PATCH "/notifications/$($notification.id)/read" $null $reader.Token 404
    $read = Send-Local PATCH "/notifications/$($notification.id)/read" $null $owner.Token
    Assert-Local ($read.read) 'Only notification owner can mark it read'

    $qr = Send-Local POST '/auth/qr' @{} '' 201
    $qrStatus = Send-Local POST '/auth/qr/status' @{qrId=$qr.qrId;pollToken=$qr.pollToken} ''
    Assert-Local ($qrStatus.status -eq 'PENDING' -and -not $qrStatus.accessToken) 'QR polling returns status without JWT'
    $null = Send-Local POST '/auth/qr/scan' @{qrId=$qr.qrId} $owner.Token
    $null = Send-Local POST '/auth/qr/confirm' @{qrId=$qr.qrId} $owner.Token
    $qrLogin = Send-Local POST '/auth/qr/exchange' @{qrId=$qr.qrId;pollToken=$qr.pollToken} ''
    Assert-Local ($qrLogin.accessToken -and $qrLogin.user.id -eq $owner.UserId) 'QR one-time exchange returns authenticated owner'
    $null = Send-Local POST '/auth/qr/exchange' @{qrId=$qr.qrId;pollToken=$qr.pollToken} '' 409

    $stream = Send-Local POST '/ai/chat/stream' @{messages=@(@{role='user';content='你好，请做一个简短的本地试运行回复'});maxOutputTokens=64} $owner.Token
    Assert-Local ($stream -match 'data:' -and $stream -match '\[DONE\]') 'Authenticated AI mock SSE stream completes'
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    $summary = $null
    do {
        $summaryResponse = Invoke-WebRequest -Uri "$blueAlbumApi/ai/summary/$($thread.id)" -Headers @{Authorization="Bearer $($owner.Token)"} -TimeoutSec 10 -SkipHttpErrorCheck
        if ($summaryResponse.StatusCode -ne 200) { throw "Summary status lookup returned HTTP $($summaryResponse.StatusCode)." }
        $summary = ConvertFrom-Json -InputObject $summaryResponse.Content
        if ($summary.status -in @('COMPLETED','FAILED')) { break }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    Assert-Local ($summary.status -eq 'COMPLETED' -and $summary.summary) "RabbitMQ summary task completes and can be read back (status $($summary.status))"

    $imagePath = Join-Path $blueAlbumRunDir 'smoke-image.png'
    Add-Type -AssemblyName System.Drawing
    $bitmap = [System.Drawing.Bitmap]::new(2, 2)
    try {
        $bitmap.SetPixel(0, 0, [System.Drawing.Color]::Red)
        $bitmap.SetPixel(1, 0, [System.Drawing.Color]::Blue)
        $bitmap.SetPixel(0, 1, [System.Drawing.Color]::Green)
        $bitmap.SetPixel(1, 1, [System.Drawing.Color]::White)
        $bitmap.Save($imagePath, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $bitmap.Dispose()
    }
    # PowerShell -Form sends application/octet-stream; the browser sends image/png.
    $uploadClient = [Net.Http.HttpClient]::new()
    $multipart = [Net.Http.MultipartFormDataContent]::new()
    try {
        $uploadClient.Timeout = [TimeSpan]::FromSeconds(20)
        $uploadClient.DefaultRequestHeaders.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $owner.Token)
        $imagePart = [Net.Http.ByteArrayContent]::new([IO.File]::ReadAllBytes($imagePath))
        $imagePart.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new('image/png')
        $multipart.Add($imagePart, 'file', 'smoke-image.png')
        $uploadResponse = $uploadClient.PostAsync("$blueAlbumApi/uploads/images", $multipart).GetAwaiter().GetResult()
        $uploadBody = $uploadResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        Assert-Local ([int]$uploadResponse.StatusCode -eq 200) "Image multipart upload succeeds through frontend and gateway (actual $([int]$uploadResponse.StatusCode))"
        $image = $uploadBody | ConvertFrom-Json
    } finally {
        $multipart.Dispose()
        $uploadClient.Dispose()
    }
    $download = Invoke-WebRequest -Uri "$($BaseUrl.TrimEnd('/'))$($image.url)" -SkipHttpErrorCheck -TimeoutSec 20
    Assert-Local ($download.StatusCode -eq 200 -and $download.Headers['Content-Type'] -like 'image/*') 'Uploaded image is served as image bytes, not SPA HTML'

    [IO.File]::WriteAllText((Join-Path $blueAlbumRunDir 'smoke-data.json'), (ConvertTo-Json @{ThreadId=$thread.id;PostId=$post.id;OwnerId=$owner.UserId;ReaderId=$reader.UserId;ImageUrl=$image.url}), (New-Object Text.UTF8Encoding $false))
} finally {
    New-Item -ItemType Directory -Path $blueAlbumRunDir -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $blueAlbumRunDir 'smoke-checks.json'), (ConvertTo-Json -InputObject $checks.ToArray() -Depth 5), (New-Object Text.UTF8Encoding $false))
}
Write-Host "All $($checks.Count) local smoke checks passed. Account and evidence are in $blueAlbumRunDir."
