<#
.SYNOPSIS
  End-to-end smoke test against running services (Compose, or kind via port-forward).
.DESCRIPTION
  Creates content with an invalid license, files a complaint, waits for the asynchronous RESTRICT decision,
  appeals, and waits for the round-2 outcome. Also checks access control and the metrics endpoints.
  Passwords are read from the DEMO_*_PASSWORD environment variables or from .env in the repository root.
  Works in Windows PowerShell 5.1 and PowerShell 7.
#>
param(
  [string]$CaseUrl = "http://localhost:8081",
  [string]$ContentUrl = "http://localhost:8083",
  [string]$ReviewUrl = "http://localhost:8082",
  [int]$TimeoutSeconds = 90,
  [switch]$MultiReplicaReview
)
$ErrorActionPreference = "Stop"

$envFile = Join-Path $PSScriptRoot "..\.env"
if (Test-Path $envFile) {
  foreach ($line in Get-Content $envFile) {
    if ($line -match '^\s*([A-Z_]+)\s*=\s*(.*)$' -and -not (Test-Path "env:$($Matches[1])")) {
      Set-Item "env:$($Matches[1])" $Matches[2]
    }
  }
}
foreach ($n in "DEMO_REPORTER_PASSWORD", "DEMO_CREATOR_PASSWORD", "DEMO_ADMIN_PASSWORD") {
  if (-not (Test-Path "env:$n")) { throw "$n is not set (export it or create .env from .env.example)" }
}

Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(30)

function Call([string]$method, [string]$url, [string]$user = $null, $body = $null, [hashtable]$headers = @{}) {
  $req = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::new($method), $url)
  if ($user) {
    $password = (Get-Item "env:DEMO_$($user.ToUpper())_PASSWORD").Value
    $token = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${user}:$password"))
    $req.Headers.TryAddWithoutValidation("Authorization", "Basic $token") | Out-Null
  }
  foreach ($k in $headers.Keys) { $req.Headers.TryAddWithoutValidation($k, [string]$headers[$k]) | Out-Null }
  if ($body) {
    $req.Content = New-Object System.Net.Http.StringContent (($body | ConvertTo-Json -Compress), [Text.Encoding]::UTF8, "application/json")
  }
  $resp = $client.SendAsync($req).GetAwaiter().GetResult()
  $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
  $json = $null
  if ($text -and $text.TrimStart().StartsWith("{")) { $json = $text | ConvertFrom-Json }
  [pscustomobject]@{ Status = [int]$resp.StatusCode; Text = $text; Body = $json }
}

$failures = 0
function Check([string]$name, [bool]$ok) {
  if ($ok) { Write-Host "PASS  $name" } else { Write-Host "FAIL  $name"; $script:failures++ }
}

function WaitWhile([string]$caseId, [string]$whileStatus) {
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  do {
    $c = (Call GET "$CaseUrl/api/cases/$caseId" "reporter").Body
    if ($c.status -ne $whileStatus) { return $c }
    Start-Sleep -Milliseconds 500
  } while ((Get-Date) -lt $deadline)
  return $c
}

# Access control
Check "unauthenticated request is rejected (401)" ((Call GET "$CaseUrl/api/cases/x").Status -eq 401)
Check "health is public" ((Call GET "$CaseUrl/actuator/health").Status -eq 200)

# Content (invalid license) -> complaint -> RESTRICT
$content = Call POST "$ContentUrl/api/contents" "creator" @{ title = "smoke-test"; licenseValid = $false }
Check "creator creates content (201)" ($content.Status -eq 201)
$contentId = $content.Body.contentId

$headers = @{ "Idempotency-Key" = [guid]::NewGuid().ToString(); "X-Correlation-Id" = "smoke-" + [guid]::NewGuid().ToString("N").Substring(0, 12) }
$case = Call POST "$CaseUrl/api/cases" "reporter" @{ contentId = $contentId; reason = "smoke test" } $headers
Check "reporter files complaint (201, PENDING_REVIEW)" ($case.Status -eq 201 -and $case.Body.status -eq "PENDING_REVIEW")
$caseId = $case.Body.caseId

$restricted = WaitWhile $caseId "PENDING_REVIEW"
Check "review-service restricts the content asynchronously ($($restricted.status))" ($restricted.status -eq "RESTRICTED")

Check "second complaint for the same content conflicts (409)" ((Call POST "$CaseUrl/api/cases" "reporter" @{ contentId = $contentId; reason = "again" }).Status -eq 409)
Check "reporter cannot appeal (403)" ((Call POST "$CaseUrl/api/cases/$caseId/appeals" "reporter" @{ statement = "not mine" }).Status -eq 403)

# Appeal -> round 2 (the demo policy decides round 2 from the license-note fixture, so RESTRICT is confirmed here)
$appeal = Call POST "$CaseUrl/api/cases/$caseId/appeals" "creator" @{ statement = "I hold the license" }
Check "creator appeals (201, APPEAL_PENDING)" ($appeal.Status -eq 201 -and $appeal.Body.caseStatus -eq "APPEAL_PENDING")
$final = WaitWhile $caseId "APPEAL_PENDING"
Check "appeal reaches a final status in round 2 ($($final.status))" ($final.status -ne "APPEAL_PENDING" -and $final.reviewRound -eq 2)
Check "a second appeal is rejected (409)" ((Call POST "$CaseUrl/api/cases/$caseId/appeals" "creator" @{ statement = "second try" }).Status -eq 409)

# Observability
$m1 = Call GET "$CaseUrl/actuator/prometheus" "admin"
Check "case-service exposes complaints.accepted to admin" ($m1.Status -eq 200 -and $m1.Text -match "complaints_accepted_total")
Check "case-service metrics are not public" ((Call GET "$CaseUrl/actuator/prometheus").Status -eq 401)
$m2 = Call GET "$ReviewUrl/actuator/prometheus" "admin"
if ($MultiReplicaReview) {
  # Counters are per pod and only exist on pods that handled a message, so only the endpoint is checked.
  Check "review-service metrics endpoint is available to admin" ($m2.Status -eq 200)
} else {
  Check "review-service exposes reviews.completed to admin" ($m2.Status -eq 200 -and $m2.Text -match "reviews_completed_total")
}

if ($failures -gt 0) { Write-Host "`n$failures check(s) failed"; exit 1 }
Write-Host "`nAll smoke checks passed"
