param(
    [string[]] $ExtraArgs
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path "$ScriptDir\..").ProviderPath
$InstallDir = Join-Path $ProjectRoot "app\build\install\app"
$Executable = Join-Path $InstallDir "bin\app.bat"

if (-not (Test-Path $Executable)) {
    Push-Location $ProjectRoot
    ./gradlew :app:installDist | Out-Null
    Pop-Location
}

$dataDir = if ($env:DATA_DIR) { $env:DATA_DIR } else { Join-Path $ProjectRoot "app\data\chain" }
$apiBind = if ($env:API_BIND) { $env:API_BIND } else { "0.0.0.0" }
$apiPort = if ($env:API_PORT) { [int]$env:API_PORT } else { 8080 }
$rpcBind = if ($env:RPC_BIND) { $env:RPC_BIND } else { "0.0.0.0" }
$rpcPort = if ($env:RPC_PORT) { [int]$env:RPC_PORT } else { 9090 }
$p2pPort = if ($env:P2P_PORT) { [int]$env:P2P_PORT } else { 9000 }
$apiToken = if ($env:API_TOKEN) { $env:API_TOKEN } else { $env:JAVA_CHAIN_API_TOKEN }
$rpcToken = if ($env:RPC_TOKEN) { $env:RPC_TOKEN } else { $env:JAVA_CHAIN_RPC_TOKEN }
$nodeId = if ($env:NODE_ID) { $env:NODE_ID } elseif ($env:JAVA_CHAIN_NODE_ID) { $env:JAVA_CHAIN_NODE_ID } else { $null }
$p2pPeersEnv = if ($env:P2P_PEERS) { $env:P2P_PEERS } elseif ($env:JAVA_CHAIN_P2P_PEERS) { $env:JAVA_CHAIN_P2P_PEERS } else { $null }

$cliArgs = New-Object System.Collections.Generic.List[string]
$cliArgs.Add("--data-dir=$dataDir") | Out-Null
$cliArgs.Add("--regen-genesis") | Out-Null
$cliArgs.Add("--keep-alive") | Out-Null

if ($env:RESET_CHAIN -and $env:RESET_CHAIN.ToLower() -eq 'true') {
    $cliArgs.Add("--reset-chain") | Out-Null
}

if (-not ($env:ENABLE_API -and $env:ENABLE_API.ToLower() -eq 'false')) {
    $cliArgs.Add("--enable-api") | Out-Null
    $cliArgs.Add("--api-bind=$apiBind") | Out-Null
    $cliArgs.Add("--api-port=$apiPort") | Out-Null
    if ($apiToken) {
        $cliArgs.Add("--api-token=$apiToken") | Out-Null
    }
}

if (-not ($env:ENABLE_RPC -and $env:ENABLE_RPC.ToLower() -eq 'false')) {
    $cliArgs.Add("--enable-rpc") | Out-Null
    $cliArgs.Add("--rpc-bind=$rpcBind") | Out-Null
    $cliArgs.Add("--rpc-port=$rpcPort") | Out-Null
    if ($rpcToken) {
        $cliArgs.Add("--rpc-token=$rpcToken") | Out-Null
    }
}

if ($env:ENABLE_P2P -and $env:ENABLE_P2P.ToLower() -eq 'false') {
    $cliArgs.Add("--no-p2p") | Out-Null
} else {
    $cliArgs.Add("--p2p-port=$p2pPort") | Out-Null
}


if ($nodeId) {
    $cliArgs.Add("--node-id=$nodeId") | Out-Null
}
if ($p2pPeersEnv) {
    foreach ($peer in $p2pPeersEnv.Split(',', [System.StringSplitOptions]::RemoveEmptyEntries)) {
        $trimmed = $peer.Trim()
        if ($trimmed) { $cliArgs.Add("--p2p-peer=$trimmed") | Out-Null }
    }
}
if ($env:RUN_DEMO -and $env:RUN_DEMO.ToLower() -eq 'false') {
    $cliArgs.Add("--no-demo") | Out-Null
}

if ($ExtraArgs) {
    $cliArgs.AddRange($ExtraArgs)
}

New-Item -ItemType Directory -Force -Path $dataDir | Out-Null

& $Executable $cliArgs
