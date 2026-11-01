# Builds the images, creates a kind cluster, loads the images and deploys everything.
# Requires docker, kind, kubectl and a populated .env (copy .env.example).
param([string]$Cluster = 'content-review', [int]$TimeoutSeconds = 600)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$ns = 'content-review'

function Invoke-Native { param([scriptblock]$Cmd) & $Cmd; if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $Cmd" } }

$envMap = @{}
Get-Content "$root\.env" | ForEach-Object {
    if ($_ -match '^\s*([A-Z0-9_]+)=(.*)$') { $envMap[$Matches[1]] = $Matches[2] }
}
$required = 'MYSQL_ROOT_PASSWORD','CASE_DB_USER','CASE_DB_PASSWORD','REVIEW_DB_USER','REVIEW_DB_PASSWORD',
            'CONTENT_DB_USER','CONTENT_DB_PASSWORD','DEMO_REPORTER_PASSWORD','DEMO_CREATOR_PASSWORD','DEMO_ADMIN_PASSWORD'
foreach ($k in $required) { if (-not $envMap[$k]) { throw "Missing $k in .env" } }

$services = 'case-service','review-service','content-service'
$ports = @{ 'case-service' = '8081'; 'review-service' = '8082'; 'content-service' = '8083' }
foreach ($s in $services) {
    Invoke-Native { docker build -f infra/docker/Dockerfile --build-arg SERVICE=$s --build-arg PORT=$($ports[$s]) -t "content-review/${s}:local" . }
}

$existing = kind get clusters 2>$null
if ($existing -notcontains $Cluster) { Invoke-Native { kind create cluster --name $Cluster --wait 120s } }
Invoke-Native { kubectl config use-context "kind-$Cluster" }

foreach ($s in $services) { Invoke-Native { kind load docker-image "content-review/${s}:local" --name $Cluster } }

Invoke-Native { kubectl apply -f k8s/base/00-namespace-config.yaml }

# Secrets and scripts are created from local files so nothing sensitive is committed.
$secretArgs = $required | ForEach-Object { "--from-literal=$_=$($envMap[$_])" }
kubectl -n $ns create secret generic app-secrets @secretArgs --dry-run=client -o yaml | kubectl apply -f -
if ($LASTEXITCODE -ne 0) { throw 'secret apply failed' }
kubectl -n $ns create configmap mysql-init --from-file=init-databases.sh=infra/mysql/init-databases.sh --dry-run=client -o yaml | kubectl apply -f -
kubectl -n $ns create configmap kafka-init-script --from-file=create-topics.sh=infra/kafka/create-topics.sh --dry-run=client -o yaml | kubectl apply -f -

Invoke-Native { kubectl apply -f k8s/base/10-mysql.yaml -f k8s/base/20-kafka.yaml }
Invoke-Native { kubectl -n $ns rollout status statefulset/mysql --timeout="${TimeoutSeconds}s" }
Invoke-Native { kubectl -n $ns rollout status statefulset/kafka --timeout="${TimeoutSeconds}s" }
Invoke-Native { kubectl -n $ns wait --for=condition=complete job/kafka-init --timeout="${TimeoutSeconds}s" }

Invoke-Native { kubectl apply -f k8s/base/40-content-service.yaml }
Invoke-Native { kubectl -n $ns rollout status deployment/content-service --timeout="${TimeoutSeconds}s" }
Invoke-Native { kubectl apply -f k8s/base/50-review-service.yaml -f k8s/base/60-case-service.yaml }
foreach ($d in 'review-service','case-service') {
    Invoke-Native { kubectl -n $ns rollout status "deployment/$d" --timeout="${TimeoutSeconds}s" }
}
kubectl -n $ns get pods
Write-Host 'Deployed. Run scripts\k8s-smoke.ps1 to verify.'
