# ELocate WhatsApp Bot - Full Flow Test Script
# Driver email: sumaleshka@gmail.com
# Run this AFTER starting the bot: mvn spring-boot:run

$BOT_URL     = "http://localhost:8081"
$PHONE       = "919876543210"
$EMAIL       = "kasumalesh@gmail.com"
$SECRET      = "elocate-internal-secret-change-in-prod"
$REQUEST_NUM = "EL-2024-00145"

function Send-Text($text) {
    $body = @{
        entry = @(@{
            changes = @(@{
                value = @{
                    messages = @(@{
                        from = $PHONE
                        type = "text"
                        text = @{ body = $text }
                    })
                }
            })
        })
    } | ConvertTo-Json -Depth 10
    try {
        Invoke-RestMethod -Uri "$BOT_URL/api/whatsapp/webhook" -Method POST -ContentType "application/json" -Body $body | Out-Null
        Write-Host "  [OK] Sent text: $text" -ForegroundColor Green
    } catch {
        Write-Host "  [ERR] $($_.Exception.Message)" -ForegroundColor Red
    }
}

function Send-Button($buttonId, $buttonTitle) {
    $body = @{
        entry = @(@{
            changes = @(@{
                value = @{
                    messages = @(@{
                        from = $PHONE
                        type = "interactive"
                        interactive = @{
                            button_reply = @{
                                id    = $buttonId
                                title = $buttonTitle
                            }
                        }
                    })
                }
            })
        })
    } | ConvertTo-Json -Depth 10
    try {
        Invoke-RestMethod -Uri "$BOT_URL/api/whatsapp/webhook" -Method POST -ContentType "application/json" -Body $body | Out-Null
        Write-Host "  [OK] Sent button: $buttonId" -ForegroundColor Green
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

# Check bot is running
Step 0 "Checking bot is up at $BOT_URL"
try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $tcp.Connect("localhost", 8081)
    $tcp.Close()
    Write-Host "  [OK] Bot is running on port 8081" -ForegroundColor Green
} catch {
    Write-Host "  [ERR] Bot is NOT running on port 8081. Start it first: mvn spring-boot:run" -ForegroundColor Red
    exit 1
}

# STEP 1 - First message
Step 1 "Driver sends first message (triggers welcome + email prompt)"
Send-Text "hi"
Write-Host "  >> Check bot logs for: Welcome to ELocate Driver Bot"
Start-Sleep -Seconds 2

# STEP 2 - Send email
Step 2 "Driver sends email: $EMAIL"
Send-Text $EMAIL
Write-Host "  >> Check bot logs for: OTP sent / Email not registered"
Write-Host "  >> If found: check $EMAIL inbox for the OTP"
Start-Sleep -Seconds 3

# STEP 3 - Enter OTP
Step 3 "Enter OTP received at $EMAIL"
$otp = Read-Host "  Type the OTP from your email"
Send-Text $otp
Write-Host "  >> Check bot logs for: Verified! Welcome"
Start-Sleep -Seconds 2

# STEP 4 - Pending Pickups
Step 4 "Tap Pending Pickups button"
Send-Button "menu_pending" "Pending Pickups"
Write-Host "  >> Check bot logs for: list of pending pickups or No records found"
Start-Sleep -Seconds 3

# STEP 5 - Back to menu first, then Completed
Step 5 "Tap Back to Menu, then Completed Pickups"
Send-Button "list_back" "Back to Menu"
Start-Sleep -Seconds 2
Send-Button "menu_completed" "Completed"
Write-Host "  >> Check bot logs for: completed pickups list"
Start-Sleep -Seconds 3

# STEP 6 - My Profile
Step 6 "Tap Back to Menu, then My Profile"
Send-Button "list_back" "Back to Menu"
Start-Sleep -Seconds 2
Send-Button "menu_profile" "My Profile"
Write-Host "  >> Check bot logs for: driver name, vehicle, availability"
Start-Sleep -Seconds 3

# STEP 7 - Back to menu
Step 7 "Tap Back to Menu from profile"
Send-Button "profile_back" "Back to Menu"
Start-Sleep -Seconds 2

# STEP 8 - Simulate pickup assignment notification
Step 8 "Simulate intermediary assigning a pickup (proactive notification)"
$notifyBody = @{
    requestNumber   = $REQUEST_NUM
    driverPhone     = $PHONE
    driverName      = "Test Driver"
    deviceName      = "iPhone 14 Pro Max"
    pickupAddress   = "42, MG Road, Bengaluru - 560001"
    pickupDate      = "Apr 18, 2024"
    estimatedAmount = "Rs.12500"
    comments        = "Call before arriving"
} | ConvertTo-Json

try {
    Invoke-RestMethod -Uri "$BOT_URL/internal/notify/pickup-assigned" `
        -Method POST -ContentType "application/json" `
        -Headers @{ "X-Internal-Secret" = $SECRET } `
        -Body $notifyBody | Out-Null
    Write-Host "  [OK] Sent pickup-assigned notification for $REQUEST_NUM" -ForegroundColor Green
    Write-Host "  >> Check bot logs for: New Pickup Assigned message with 3 buttons"
} catch {
    Write-Host "  [ERR] $($_.Exception.Message)" -ForegroundColor Red
}
Start-Sleep -Seconds 2

# STEP 9 - Accept pickup
Step 9 "Driver taps Accept on the assigned pickup"
Send-Button "accept_pickup:$REQUEST_NUM" "Accept"
Write-Host "  >> Expects resolveToken call to elocate-server"
Write-Host "  >> Will show No valid token if elocate-server not running (expected in mock)"
Start-Sleep -Seconds 2

# STEP 10 - Reject flow
Step 10 "Driver taps Reject (triggers reason prompt)"
Send-Button "reject_pickup:$REQUEST_NUM" "Reject"
Write-Host "  >> Check bot logs for: Please type a reason"
Start-Sleep -Seconds 2

Step "10b" "Driver types rejection reason"
Send-Text "Customer was not available at the address"
Write-Host "  >> Check bot logs for: rejectPickup call to elocate-server"
Start-Sleep -Seconds 2

# Done
Write-Host ""
Write-Host "============================================" -ForegroundColor Green
Write-Host "  Test flow complete!" -ForegroundColor Green
Write-Host "  Check the bot console for [MOCK] replies" -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Green
