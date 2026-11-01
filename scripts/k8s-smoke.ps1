# Port-forwards the three services from the kind cluster and runs the smoke test.
param([string]$Cluster = 'content-review')
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$ns = 'content-review'
kubectl config use-context "kind-$Cluster" | Out-Null

$forwards = @(
    @{ Svc = 'case-service';    Local = 18081; Remote = 8081 },
    @{ Svc = 'review-service';  Local = 18082; Remote = 8082 },
    @{ Svc = 'content-service'; Local = 18083; Remote = 8083 }
) | ForEach-Object {
    Start-Process kubectl -ArgumentList "-n $ns port-forward svc/$($_.Svc) $($_.Local):$($_.Remote)" -WindowStyle Hidden -PassThru
}
try {
    Start-Sleep -Seconds 5
    & "$root\scripts\smoke-test.ps1" -CaseUrl http://localhost:18081 -ReviewUrl http://localhost:18082 -ContentUrl http://localhost:18083 -MultiReplicaReview
    $code = $LASTEXITCODE
} finally {
    $forwards | ForEach-Object { Stop-Process -Id $_.Id -ErrorAction SilentlyContinue }
}
exit $code
