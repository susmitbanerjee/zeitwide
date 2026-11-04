# Stores a Grafana Cloud token in Secret Manager without it touching the screen, a file you
# keep, or the shell history. Run it through store-secret.cmd (double-click, or from a prompt).
#
#   store-secret.cmd otlp   OTLP ingest token -> zeitwide-<env>-otlp-headers (checked against the gateway first)
#   store-secret.cmd ci     Service account token -> zeitwide-<env>-grafana-token (checked against the stack first)
param(
  [ValidateSet("otlp", "ci")] [string] $Kind = "otlp",
  [string] $Environment = "dev",
  [string] $Project = "project-6eaae4e0-3164-4e31-bc4",
  [string] $Stack = "https://cleverhare3418.grafana.net",
  [string] $InstanceId = "1681269",
  [string] $OtlpEndpoint = "https://otlp-gateway-prod-ap-south-1.grafana.net/otlp"
)
$ErrorActionPreference = "Stop"

function Read-Token([string] $prompt) {
  $secure = Read-Host $prompt -AsSecureString
  $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try { return ([Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) + "").Trim() }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

function Test-Request([string] $uri, [string] $method, [hashtable] $headers, [string] $body) {
  try {
    $request = @{ UseBasicParsing = $true; Method = $method; Uri = $uri; Headers = $headers }
    if ($body) { $request.ContentType = "application/json"; $request.Body = $body }
    Invoke-WebRequest @request | Out-Null
    return $true
  } catch {
    Write-Host "Rejected: $($_.Exception.Message)" -ForegroundColor Red
    return $false
  }
}

function Save-Secret([string] $secret, [string] $value) {
  $file = Join-Path $env:TEMP ([IO.Path]::GetRandomFileName())
  try {
    [IO.File]::WriteAllText($file, $value)
    & gcloud.cmd secrets versions add $secret "--data-file=$file" --project $Project
    if ($LASTEXITCODE -ne 0) { throw "gcloud could not add a version to $secret" }
  } finally { Remove-Item $file -ErrorAction SilentlyContinue }
}

# A glc_ token is base64 JSON: org (o), token name (n), secret key (k), region (m.r). Shows all
# but the key, so a cut-short copy or the wrong token is obvious.
function Show-CloudToken([string] $token) {
  if (-not $token.StartsWith("glc_")) { Write-Host "Doesn't start with glc_: not a Cloud access policy token." -ForegroundColor Yellow; return $false }
  if ($token -match "\s") { Write-Host "Contains spaces or line breaks: the copy picked up more than the token." -ForegroundColor Yellow; return $false }
  try {
    $payload = $token.Substring(4)
    $payload = $payload.PadRight($payload.Length + (4 - $payload.Length % 4) % 4, "=")
    $note = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)) | ConvertFrom-Json
    Write-Host "Token '$($note.n)', org $($note.o), region $($note.m.r)"
    return $true
  } catch {
    Write-Host "The token doesn't decode: the copy was cut short. Select the whole token and copy again." -ForegroundColor Yellow
    return $false
  }
}

if ($Kind -eq "otlp") {
  $secret = "zeitwide-$Environment-otlp-headers"
  $token = Read-Token "Paste the OTLP token (glc_..., input hidden)"
  Write-Host "Token length: $($token.Length)"
  if (-not (Show-CloudToken $token)) { exit 1 }
  $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${InstanceId}:$token"))
  # A new token can take a minute or two to work everywhere.
  $ok = $false
  foreach ($attempt in 1..6) {
    Write-Host "Checking it against $OtlpEndpoint (try $attempt of 6) ..."
    if (Test-Request "$OtlpEndpoint/v1/traces" "Post" @{ Authorization = "Basic $basic" } '{"resourceSpans":[]}') { $ok = $true; break }
    if ($attempt -lt 6) { Start-Sleep -Seconds 20 }
  }
  $value = "Authorization=Basic%20$basic"
} else {
  $secret = "zeitwide-$Environment-grafana-token"
  $token = Read-Token "Paste the zeitwide-ci service account token (glsa_..., input hidden)"
  Write-Host "Token length: $($token.Length)"
  Write-Host "Checking it against $Stack ..."
  $ok = Test-Request "$Stack/api/folders?limit=1" "Get" @{ Authorization = "Bearer $token" } $null
  $value = $token
}

if (-not $ok) {
  Write-Host "Nothing stored. Copy the whole token again and rerun." -ForegroundColor Yellow
  exit 1
}
Save-Secret $secret $value
Write-Host "Stored in $secret." -ForegroundColor Green
Set-Clipboard -Value " "
