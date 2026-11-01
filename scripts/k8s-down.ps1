# Deletes the kind cluster (and with it all data).
param([string]$Cluster = 'content-review')
kind delete cluster --name $Cluster
