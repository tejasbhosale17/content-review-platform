<#
.SYNOPSIS
  Recovery checks against the running Docker Compose stack (docker compose up -d --build).
.DESCRIPTION
  Scenario A: review-service is stopped, N complaints are accepted, the service is restarted -> all must complete.
  Scenario B: Kafka is stopped, N complaints are accepted (events wait in the outbox), Kafka is restarted -> all must complete.
  Each case must reach a final status exactly once (no case stays PENDING_REVIEW, none is lost).
#>
param(
  [int]$Count = 10,
  [string]$CaseUrl = "http://localhost:8081",
  [string]$ContentUrl = "http://localhost:8083",
  [int]$TimeoutSeconds = 180
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

foreach ($line in Get-Content "$root\.env") {
  if ($line -match '^\s*([A-Z_]+)\s*=\s*(.*)$' -and -not (Test-Path "env:$($Matches[1])")) { Set-Item "env:$($Matches[1])" $Matches[2] }
}
function Auth($u, $p) { @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${u}:$p")) } }
$creator = Auth "creator" $env:DEMO_CREATOR_PASSWORD
$reporter = Auth "reporter" $env:DEMO_REPORTER_PASSWORD

function Compose { param([string[]]$a) $old = $ErrorActionPreference; $ErrorActionPreference = "Continue"; & docker compose @a 2>&1 | Out-Null; $code = $LASTEXITCODE; $ErrorActionPreference = $old; if ($code -ne 0) { throw "docker compose $a failed" } }

function File-Complaints([int]$n) {
  $ids = @()
  for ($i = 0; $i -lt $n; $i++) {
    $c = Invoke-RestMethod -Method Post -Uri "$ContentUrl/api/contents" -Headers $creator -ContentType "application/json" `
      -Body (@{ title = "recovery-$i"; licenseValid = ($i % 2 -eq 1) } | ConvertTo-Json -Compress)
    $h = $reporter.Clone(); $h["Idempotency-Key"] = [guid]::NewGuid().ToString()
    $case = Invoke-RestMethod -Method Post -Uri "$CaseUrl/api/cases" -Headers $h -ContentType "application/json" `
      -Body (@{ contentId = $c.contentId; reason = "recovery" } | ConvertTo-Json -Compress)
    $ids += $case.caseId
  }
  return $ids
}

function Count-Pending($ids) {
  @($ids | Where-Object { (Invoke-RestMethod -Uri "$CaseUrl/api/cases/$_" -Headers $reporter).status -eq "PENDING_REVIEW" }).Count
}

function Wait-AllDone($ids) {
  $sw = [Diagnostics.Stopwatch]::StartNew()
  do { $p = Count-Pending $ids; if ($p -eq 0) { return $sw.Elapsed.TotalSeconds }; Start-Sleep -Seconds 1 } while ($sw.Elapsed.TotalSeconds -lt $TimeoutSeconds)
  return -1
}

function Wait-Healthy([string]$url) {
  $sw = [Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt 120) {
    try { if ((Invoke-WebRequest -UseBasicParsing -Uri "$url/actuator/health/readiness").StatusCode -eq 200) { return } } catch { }
    Start-Sleep -Seconds 2
  }
  throw "$url did not become ready"
}

$failures = 0
function Check([string]$name, [bool]$ok) { if ($ok) { Write-Host "PASS  $name" } else { Write-Host "FAIL  $name"; $script:failures++ } }

Write-Host "Scenario A: review-service down while $Count complaints arrive"
Compose @("stop", "review-service")
$ids = File-Complaints $Count
Start-Sleep -Seconds 5
Check "all $Count cases are still PENDING_REVIEW while the worker is down" ((Count-Pending $ids) -eq $Count)
Compose @("start", "review-service")
$t = Wait-AllDone $ids
Check "all $Count cases complete after the worker restarts ($([Math]::Round($t,1)) s)" ($t -ge 0)

Write-Host "Scenario B: Kafka down while $Count complaints arrive (events wait in the outbox)"
Compose @("stop", "kafka")
$ids = File-Complaints $Count
Check "complaints are accepted while Kafka is down" ($ids.Count -eq $Count)
Compose @("start", "kafka")
Wait-Healthy $CaseUrl
$t = Wait-AllDone $ids
Check "all $Count cases complete after Kafka recovers ($([Math]::Round($t,1)) s)" ($t -ge 0)

if ($failures -gt 0) { Write-Host "`n$failures check(s) failed"; exit 1 }
Write-Host "`nAll recovery checks passed"
