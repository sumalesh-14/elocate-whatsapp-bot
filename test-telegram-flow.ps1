# ELocate Telegram Bot - Full Flow Test Script
# Driver email: kasumalesh@gmail.com
# Simulates Telegram webhook calls to test the bot end-to-end
#
# HOW TO USE:
#   Option A - Local:  Start bot (mvn spring-boot:run), then run this script
#   Option B - Render: Set $BOT_URL to your Render URL, run this script
#
# The script simulates Telegram sending updates to your webhook.
# Bot replies are visible in the Spring Boot logs as [TELEGRAM MOCK] until
# you set a real TELEGRAM_BOT_TOKEN.

param(
    [string]$BotUrl = "http://localhost:8081",
    [string]$InternalSecret = "elocate-internal-secret-change-in-prod",
    [long]$ChatId = 987654321
)

$EMAIL       = "kasumalesh@gmail.com"
$REQUEST_NUM = "RCY-2026-000042"

function Send-TelegramText($text) {
    $body = @{
        update_id = (Get-Random -Minimum 100000 -Maximum 999999)
        message   = @{
            message_id = (Get-Random -Minimum 1 -Maximum 9999)
            from       = @{
                id         = $ChatId
                first_name = "Sumalesh"
                last_name  = "KA"
                username   = "sumaleshka"
            }
            chat = @{ id = $ChatId }
            text = $text
        }
    } | ConvertTo-Json -Depth 10

    try {
        Invoke-RestMethod -Uri "$BotUrl/api/telegram/webhook" `
            -Method POST -ContentType "application/json" -Body $body | Out-Null
        Write-Host "  [OK] Sent text: $text" -ForegroundColor Green
    } catch {
        Write-Host "  [ERR] $($_.Exception.Message)" -ForegroundColor Red
    }
}

function Send-TelegramButton($callbackData, $callbackId) {
    if (-not $callbackId) { $callbackId = [string](Get-Random -Minimum 100000000 -Maximum 999999999) }
    $body = @{
        update_id      = (Get-Random -Minimum 100000 -Maximum 999999)
        callback_query = @{
            id      = $callbackId
            from    = @{
                id         = $ChatId
                first_name = "Sumalesh"
                username   = "sumaleshka"
            }
            message = @{
                message_id = (Get-Random -Minimum 1 -Maximum 9999)
                chat       = @{ id = $ChatId }
                text       = "previous message"
            }
            data = $callbackData
        }
    } | ConvertTo-Json -Depth 10

    try {
        Invoke-RestMethod -Uri "$BotUrl/api/telegram/webhook" `
            -Method POST -ContentType "application/json" -Body $body | Out-Null
        Write-Host "  [OK] Sent button: $callbackData" -ForegroundColor Green
    } catch {
        Write-Host "  [ERR] $($_.Exception.Message)" -ForegroundColor Red
    }
}

function Step($num, $msg) {
    Write-Host ""
    Write-Host "--------------------------------------------" -ForegroundColor Cyan
    Write-Host "  STEP $num : $msg" -ForegroundColor Yellow
    Write-Host "--------------------------------------------" -ForegroundColor Cyan
}

# Check bot is reachable
Step 0 "Checking bot is up at $BotUrl"
try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $uri = [System.Uri]$BotUrl
    $tcp.Connect($uri.Host, $uri.Port)
    $tcp.Close()
    Write-Host "  [OK] Bot is reachable at $BotUrl" -ForegroundColor Green
} catch {
    Write-Host "  [ERR] Cannot reach $BotUrl" -ForegroundColor Red
    Write-Host "  Start the bot first or check your Render URL" -ForegroundColor Yellow
    exit 1
}

# STEP 1 - First message
Step 1 "Driver sends first message (triggers welcome + email prompt)"
Send-TelegramText "/start"
Write-Host "  >> Bot should reply: Welcome to ELocate Driver Bot"
Start-Sleep -Seconds 2

# STEP 2 - Send email
Step 2 "Driver sends email: $EMAIL"
Send-TelegramText $EMAIL
Write-Host "  >> Bot should reply: OTP sent to k***@gmail.com"
Write-Host "  >> Check $EMAIL inbox for the OTP HTML email"
Start-Sleep -Seconds 4

# STEP 3 - Enter OTP
Step 3 "Enter OTP received at $EMAIL"
$otp = Read-Host "  Type the OTP from your email inbox"
if ($otp -eq "") {
    Write-Host "  [SKIP] No OTP entered, skipping remaining steps" -ForegroundColor Yellow
    exit 0
}
Send-TelegramText $otp
Write-Host "  >> Bot should reply: Verified! Welcome, Sumalesh"
Start-Sleep -Seconds 2

# STEP 4 - Tap Pending Pickups
Step 4 "Tap Pending Pickups button"
Send-TelegramButton "menu_pending"
Write-Host "  >> Bot should reply: list of pending pickups"
Start-Sleep -Seconds 3

# STEP 5 - Back to menu then Completed
Step 5 "Back to menu, then Completed Pickups"
Send-TelegramButton "list_back"
Start-Sleep -Seconds 2
Send-TelegramButton "menu_completed"
Write-Host "  >> Bot should reply: completed pickups list"
Start-Sleep -Seconds 3

# STEP 6 - Back to menu then Profile
Step 6 "Back to menu, then My Profile"
Send-TelegramButton "list_back"
Start-Sleep -Seconds 2
Send-TelegramButton "menu_profile"
Write-Host "  >> Bot should reply: driver name, vehicle, availability"
Start-Sleep -Seconds 3

# STEP 7 - Back to menu from profile
Step 7 "Back to menu from profile"
Send-TelegramButton "profile_back"
Start-Sleep -Seconds 2

# STEP 8 - Simulate pickup assignment notification
Step 8 "Simulate intermediary assigning a pickup (proactive notification)"
$notifyBody = @{
    requestNumber   = $REQUEST_NUM
    driverPhone     = [string]$ChatId
    driverName      = "Sumalesh KA"
    deviceName      = "iPhone 14 Pro Max"
    pickupAddress   = "42, MG Road, Bengaluru - 560001"
    pickupDate      = "Apr 18, 2024"
    estimatedAmount = "Rs.12500"
    comments        = "Call before arriving"
} | ConvertTo-Json

try {
    Invoke-RestMethod -Uri "$BotUrl/internal/notify/pickup-assigned" `
        -Method POST -ContentType "application/json" `
        -Headers @{ "X-Internal-Secret" = $InternalSecret } `
        -Body $notifyBody | Out-Null
    Write-Host "  [OK] Sent pickup-assigned notification for $REQUEST_NUM" -ForegroundColor Green
    Write-Host "  >> Bot should reply: New Pickup Assigned with 3 buttons"
} catch {
    Write-Host "  [ERR] $($_.Exception.Message)" -ForegroundColor Red
}
Start-Sleep -Seconds 2

# STEP 9 - Accept pickup
Step 9 "Driver taps Accept on the assigned pickup"
Send-TelegramButton "accept_pickup:$REQUEST_NUM"
Write-Host "  >> If elocate-server has the new controller deployed: marks PICKUP_COMPLETED + emails citizen"
Write-Host "  >> If not deployed yet: shows 'action link expired' message (expected)"
Start-Sleep -Seconds 3

# STEP 10 - Reject flow
Step 10 "Driver taps Reject (triggers reason prompt)"
Send-TelegramButton "reject_pickup:$REQUEST_NUM"
Write-Host "  >> Bot should reply: Please type a reason"
Start-Sleep -Seconds 2

Step "10b" "Driver types rejection reason"
Send-TelegramText "Customer was not available at the address"
Write-Host "  >> Bot should reply: Rejected, reason recorded"
Start-Sleep -Seconds 2

# Done
Write-Host ""
Write-Host "============================================" -ForegroundColor Green
Write-Host "  Telegram test flow complete!" -ForegroundColor Green
Write-Host ""
Write-Host "  If TELEGRAM_BOT_TOKEN is set (not mock):" -ForegroundColor White
Write-Host "  Check your Telegram app for real bot replies" -ForegroundColor White
Write-Host ""
Write-Host "  If still in mock mode:" -ForegroundColor White
Write-Host "  Check bot logs for [TELEGRAM MOCK] lines" -ForegroundColor White
Write-Host "============================================" -ForegroundColor Green
