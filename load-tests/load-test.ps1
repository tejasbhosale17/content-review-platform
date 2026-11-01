<#
.SYNOPSIS
  Reproducible workload: N unique content/case pairs through the real HTTP API.
.DESCRIPTION
  1. Setup (not measured): creates N contents (half with an invalid license => RESTRICT, half valid => ALLOW).
  2. Measured: files one complaint per content with the given concurrency (HTTP latency, accepted/failed).
  3. Measured: polls every accepted case until it leaves PENDING_REVIEW (end-to-end latency from the moment the complaint
     request was sent, polling resolution about 100 ms), up to -DrainTimeoutSeconds. Whatever is still pending is the backlog.
  Passwords come from DEMO_*_PASSWORD or .env. Works in Windows PowerShell 5.1 and PowerShell 7.
.EXAMPLE
  .\load-tests\load-test.ps1 -Count 200 -Concurrency 8
#>
param(
  [int]$Count = 100,
  [int]$Concurrency = 4,
  [string]$CaseUrl = "http://localhost:8081",
  [string]$ContentUrl = "http://localhost:8083",
  [int]$DrainTimeoutSeconds = 120,
  [string]$OutFile = $null
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
foreach ($n in "DEMO_REPORTER_PASSWORD", "DEMO_CREATOR_PASSWORD") {
  if (-not (Test-Path "env:$n")) { throw "$n is not set" }
}

Add-Type -AssemblyName System.Net.Http
Add-Type -ReferencedAssemblies System.Net.Http -TypeDefinition @'
using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Net.Http;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

public class LoadResult {
    public long[] Ids; public double[] HttpMs; public int[] Status; public string[] CaseIds;
}

public static class Load {
    static string Basic(string user, string pw) { return Convert.ToBase64String(Encoding.UTF8.GetBytes(user + ":" + pw)); }

    static HttpRequestMessage Req(HttpMethod m, string url, string auth, string body, string key) {
        var r = new HttpRequestMessage(m, url);
        r.Headers.TryAddWithoutValidation("Authorization", "Basic " + auth);
        if (key != null) r.Headers.TryAddWithoutValidation("Idempotency-Key", key);
        if (body != null) r.Content = new StringContent(body, Encoding.UTF8, "application/json");
        return r;
    }

    public static string[] CreateContents(string url, string auth, int count, int concurrency) {
        var ids = new string[count];
        using (var http = new HttpClient { Timeout = TimeSpan.FromSeconds(60) })
        using (var gate = new SemaphoreSlim(concurrency)) {
            var tasks = new List<Task>();
            for (int i = 0; i < count; i++) {
                int n = i;
                gate.Wait();
                tasks.Add(Task.Run(async () => {
                    try {
                        string body = "{\"title\":\"load-" + n + "\",\"licenseValid\":" + (n % 2 == 0 ? "false" : "true") + "}";
                        var resp = await http.SendAsync(Req(HttpMethod.Post, url + "/api/contents", auth, body, null));
                        string text = await resp.Content.ReadAsStringAsync();
                        var m = Regex.Match(text, "\"contentId\"\\s*:\\s*\"([^\"]+)\"");
                        ids[n] = resp.IsSuccessStatusCode && m.Success ? m.Groups[1].Value : null;
                    } finally { gate.Release(); }
                }));
            }
            Task.WaitAll(tasks.ToArray());
        }
        return ids;
    }

    // Files one complaint per content id; records per-request latency and the send time (Stopwatch ticks) per case.
    public static void FileComplaints(string url, string auth, string[] contentIds, int concurrency,
                                      out double[] httpMs, out int[] status, out string[] caseIds, out long[] sentTicks, out double wallSeconds) {
        int n = contentIds.Length;
        var ms = new double[n]; var st = new int[n]; var cid = new string[n]; var sent = new long[n];
        var wall = Stopwatch.StartNew();
        using (var http = new HttpClient { Timeout = TimeSpan.FromSeconds(60) })
        using (var gate = new SemaphoreSlim(concurrency)) {
            var tasks = new List<Task>();
            for (int i = 0; i < n; i++) {
                int k = i;
                gate.Wait();
                tasks.Add(Task.Run(async () => {
                    try {
                        string body = "{\"contentId\":\"" + contentIds[k] + "\",\"reason\":\"load test\"}";
                        var req = Req(HttpMethod.Post, url + "/api/cases", auth, body, Guid.NewGuid().ToString());
                        long t0 = Stopwatch.GetTimestamp();
                        sent[k] = t0;
                        try {
                            var resp = await http.SendAsync(req);
                            string text = await resp.Content.ReadAsStringAsync();
                            ms[k] = (Stopwatch.GetTimestamp() - t0) * 1000.0 / Stopwatch.Frequency;
                            st[k] = (int)resp.StatusCode;
                            var m = Regex.Match(text, "\"caseId\"\\s*:\\s*\"([^\"]+)\"");
                            if (m.Success) cid[k] = m.Groups[1].Value;
                        } catch (Exception) {
                            ms[k] = (Stopwatch.GetTimestamp() - t0) * 1000.0 / Stopwatch.Frequency;
                            st[k] = 0;
                        }
                    } finally { gate.Release(); }
                }));
            }
            Task.WaitAll(tasks.ToArray());
        }
        wallSeconds = wall.Elapsed.TotalSeconds;
        httpMs = ms; status = st; caseIds = cid; sentTicks = sent;
    }

    // Polls every case until its status is not PENDING_REVIEW; returns end-to-end ms (or -1 if still pending at the deadline).
    public static double[] AwaitCompletion(string url, string auth, string[] caseIds, long[] sentTicks, int timeoutSeconds, out string[] finalStatus) {
        int n = caseIds.Length;
        var e2e = new double[n]; var fin = new string[n];
        for (int i = 0; i < n; i++) e2e[i] = -1;
        var deadline = Stopwatch.StartNew();
        using (var http = new HttpClient { Timeout = TimeSpan.FromSeconds(30) }) {
            var pending = new List<int>();
            for (int i = 0; i < n; i++) if (caseIds[i] != null) pending.Add(i);
            while (pending.Count > 0 && deadline.Elapsed.TotalSeconds < timeoutSeconds) {
                var still = new List<int>();
                var results = new ConcurrentDictionary<int, string>();
                Parallel.ForEach(pending, new ParallelOptions { MaxDegreeOfParallelism = 8 }, i => {
                    try {
                        var resp = http.SendAsync(Req(HttpMethod.Get, url + "/api/cases/" + caseIds[i], auth, null, null)).GetAwaiter().GetResult();
                        string text = resp.Content.ReadAsStringAsync().GetAwaiter().GetResult();
                        var m = Regex.Match(text, "\"status\"\\s*:\\s*\"([^\"]+)\"");
                        if (m.Success && m.Groups[1].Value != "PENDING_REVIEW") {
                            e2e[i] = (Stopwatch.GetTimestamp() - sentTicks[i]) * 1000.0 / Stopwatch.Frequency;
                            fin[i] = m.Groups[1].Value;
                        } else { results[i] = "p"; }
                    } catch (Exception) { results[i] = "p"; }
                });
                foreach (var i in pending) if (results.ContainsKey(i)) still.Add(i);
                pending = still;
                if (pending.Count > 0) Thread.Sleep(100);
            }
        }
        finalStatus = fin;
        return e2e;
    }
}
'@

function Pct($values, [double]$p) {
  if ($values.Count -eq 0) { return $null }
  $s = $values | Sort-Object
  $idx = [Math]::Min($s.Count - 1, [Math]::Ceiling($p * $s.Count) - 1)
  [Math]::Round([double]$s[[Math]::Max(0, $idx)], 1)
}

$creatorAuth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("creator:$env:DEMO_CREATOR_PASSWORD"))
$reporterAuth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("reporter:$env:DEMO_REPORTER_PASSWORD"))

Write-Host "Setup: creating $Count contents ..."
$contentIds = [Load]::CreateContents($ContentUrl, $creatorAuth, $Count, $Concurrency)
$created = @($contentIds | Where-Object { $_ }).Count
if ($created -ne $Count) { throw "Setup failed: only $created of $Count contents created" }

Write-Host "Measuring: filing $Count complaints with concurrency $Concurrency ..."
$httpMs = $null; $status = $null; $caseIds = $null; $sent = $null; $wall = 0.0
[Load]::FileComplaints($CaseUrl, $reporterAuth, $contentIds, $Concurrency, [ref]$httpMs, [ref]$status, [ref]$caseIds, [ref]$sent, [ref]$wall)
$accepted = @($status | Where-Object { $_ -eq 201 }).Count
$failed = $Count - $accepted

Write-Host "Measuring: waiting up to $DrainTimeoutSeconds s for the reviews to complete ..."
$finalStatus = $null
$e2e = [Load]::AwaitCompletion($CaseUrl, $reporterAuth, $caseIds, $sent, $DrainTimeoutSeconds, [ref]$finalStatus)
$done = @($e2e | Where-Object { $_ -ge 0 })
$backlog = $accepted - $done.Count
$accHttp = @(0..($Count - 1) | Where-Object { $status[$_] -eq 201 } | ForEach-Object { $httpMs[$_] })

$cs = Get-CimInstance Win32_ComputerSystem
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
$lines = @(
  "Dataset: $Count unique content/case pairs (half invalid license => RESTRICTED, half valid => ALLOWED)",
  "Concurrency (client): $Concurrency",
  "Host: $($cpu.Name.Trim()), $($cpu.NumberOfLogicalProcessors) logical CPUs, $([Math]::Round($cs.TotalPhysicalMemory / 1GB, 1)) GB RAM",
  "Accepted (201): $accepted   Failed: $failed   Completed: $($done.Count)   Remaining backlog: $backlog",
  "Complaint submission wall time: $([Math]::Round($wall, 2)) s  => $([Math]::Round($accepted / $wall, 1)) accepted requests/s",
  "HTTP latency ms (POST /api/cases, accepted only): p50=$(Pct $accHttp 0.5) p95=$(Pct $accHttp 0.95) p99=$(Pct $accHttp 0.99) max=$(Pct $accHttp 1.0)",
  "End-to-end ms (request sent -> status observed != PENDING_REVIEW, 100 ms poll): p50=$(Pct $done 0.5) p95=$(Pct $done 0.95) p99=$(Pct $done 0.99) max=$(Pct $done 1.0)",
  "Final statuses: " + ((@($finalStatus | Where-Object { $_ }) | Group-Object | ForEach-Object { "$($_.Name)=$($_.Count)" }) -join ", ")
)
$lines | ForEach-Object { Write-Host $_ }
if ($OutFile) { $lines | Set-Content -Path $OutFile -Encoding UTF8 }
if ($failed -gt 0 -or $backlog -gt 0) { exit 1 }
