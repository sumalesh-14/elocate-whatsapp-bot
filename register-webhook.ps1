# Register Telegram webhook
# Usage:
#   .\register-webhook.ps1 -Url "https://a1b2c3d4.ngrok-free.app"
#   .\register-webhook.ps1 -Url "https://your-app.onrender.com"

param(
    [Parameter(Mandatory=$true)]
    [string]$Url
)

$TOKEN = "8139388893:AAH1HlXpvIOqXcnYr9e1yHgujMCsLxCy7bY"
$WEBHOOK_URL = "$Url/api/telegram/webhook"

Write-Host "Registering webhook: $WEBHOOK_URL" -ForegroundColor Cyan

$response = Invoke-RestMethod -Uri "https://api.telegram.org/bot$TOKEN/setWebhook?url=$WEBHOOK_URL" -Method GET

if ($response.ok) {
    Write-Host "[OK] Webhook registered successfully!" -ForegroundColor Green
    Write-Host "     $($response.description)"
} else {
    Write-Host "[ERR] Failed: $($response.description)" -ForegroundColor Red
}

# Also verify current webhook info
Write-Host ""
Write-Host "Current webhook info:" -ForegroundColor Cyan
$info = Invoke-RestMethod -Uri "https://api.telegram.org/bot$TOKEN/getWebhookInfo" -Method GET
Write-Host "  URL: $($info.result.url)"
Write-Host "  Pending updates: $($info.result.pending_update_count)"
if ($info.result.last_error_message) {
    Write-Host "  Last error: $($info.result.last_error_message)" -ForegroundColor Yellow
}
