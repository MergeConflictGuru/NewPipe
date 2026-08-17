param(
    [ValidateSet('Debug', 'Release', 'Continuous')]
    [string]$Configuration = 'Debug',

    [string]$DeviceIp = '192.168.1.101',

    [string]$PackageSuffix,

    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

function Read-LocalPropertiesValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter(Mandatory = $true)]
        [string]$Key
    )

    if (-not (Test-Path -LiteralPath $FilePath)) {
        throw "local.properties not found at $FilePath"
    }

    foreach ($line in Get-Content -LiteralPath $FilePath) {
        if ($line -match '^\s*[#!]') {
            continue
        }

        $parts = $line -split '=', 2
        if ($parts.Count -eq 2 -and $parts[0].Trim() -eq $Key) {
            # local.properties uses Java-properties escaping (for example,
            # C\:\\Users\\... for C:\Users\...).
            return $parts[1].Trim().Replace('\:', ':').Replace('\\', '\')
        }
    }

    throw "Key '$Key' not found in $FilePath"
}

function Write-Step($Message) {
    Write-Host ""
    Write-Host "== $Message ==" -ForegroundColor Cyan
}

function Invoke-AdbCaptured {
    param(
        [Parameter(ValueFromRemainingArguments = $true)]
        [string[]]$Arguments
    )

    # adb can write normal daemon startup messages to stderr. Capture native
    # output here so PowerShell does not treat that chatter as a terminating
    # error; callers decide success from the exit code and adb output.
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $adbPath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    [pscustomobject]@{
        Output = $output
        ExitCode = $exitCode
    }
}

function Get-WorkingBranch {
    $branchOutput = & git -C $projectDir rev-parse --abbrev-ref HEAD 2>$null
    if ($LASTEXITCODE -ne 0) {
        return ''
    }

    return ($branchOutput | Out-String).Trim()
}

function Get-ApplicationId {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BuildConfiguration,

        [string]$ExplicitPackageSuffix
    )

    $baseApplicationId = 'org.schabi.newpipe'
    if ($ExplicitPackageSuffix) {
        return "$baseApplicationId$ExplicitPackageSuffix"
    }

    if ($BuildConfiguration -eq 'Release') {
        return $baseApplicationId
    }

    $workingBranch = Get-WorkingBranch
    $normalizedWorkingBranch = $workingBranch -replace '^[^A-Za-z]+', ''
    $normalizedWorkingBranch = $normalizedWorkingBranch -replace '[^0-9A-Za-z]+', ''
    $variantSuffix = $BuildConfiguration.ToLowerInvariant()

    if ([string]::IsNullOrEmpty($normalizedWorkingBranch) -or $workingBranch -in @('master', 'dev')) {
        return "$baseApplicationId.$variantSuffix"
    }

    return "$baseApplicationId.$variantSuffix.$normalizedWorkingBranch"
}

function Invoke-AdbConnect {
    param([string]$Serial)

    # adb connect can exit 0 even when the connection failed, so inspect its
    # output as well as the process exit code.
    $result = Invoke-AdbCaptured connect $Serial
    if ($result.Output) {
        Write-Host $result.Output
    }

    $combinedOutput = ($result.Output | ForEach-Object { $_.ToString() }) -join "`n"
    return ($result.ExitCode -eq 0 -and $combinedOutput -notmatch 'error|cannot connect|failed|refused|unable|aborted')
}

function Read-TcpPort {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prompt,

        [int]$DefaultPort = 0
    )

    while ($true) {
        $promptWithDefault = if ($DefaultPort -gt 0) {
            "$Prompt (default $DefaultPort)"
        } else {
            $Prompt
        }
        $value = Read-Host $promptWithDefault

        if ([string]::IsNullOrWhiteSpace($value) -and $DefaultPort -gt 0) {
            return $DefaultPort
        }

        $port = 0
        if ([int]::TryParse($value, [ref]$port) -and $port -ge 1 -and $port -le 65535) {
            return $port
        }

        Write-Host 'Enter a port number from 1 to 65535.' -ForegroundColor Yellow
    }
}

function Find-AdbDeviceSerial {
    param(
        [Parameter(Mandatory = $true)]
        [int[]]$Ports
    )

    foreach ($port in $Ports) {
        $serial = "${deviceHost}:$port"
        Write-Host "Trying $serial ..." -ForegroundColor Cyan

        if (Invoke-AdbConnect -Serial $serial) {
            return $serial
        }
    }

    return $null
}

function Invoke-AdbPair {
    Write-Step 'Pairing Android device for wireless adb'
    Write-Host 'On the phone, open Developer options > Wireless debugging > Pair device with pairing code.' -ForegroundColor Yellow

    $pairingPort = Read-TcpPort -Prompt 'Enter the pairing port shown on the phone'
    $pairingCode = Read-Host 'Enter the pairing code shown on the phone'
    if ([string]::IsNullOrWhiteSpace($pairingCode)) {
        throw 'Pairing code was empty.'
    }

    $pairingSerial = "${deviceHost}:$pairingPort"
    $pairResult = Invoke-AdbCaptured pair $pairingSerial $pairingCode
    if ($pairResult.Output) {
        Write-Host $pairResult.Output
    }

    $combinedOutput = ($pairResult.Output | ForEach-Object { $_.ToString() }) -join "`n"
    if ($pairResult.ExitCode -ne 0 -or $combinedOutput -match 'error|failed|unable|refused') {
        throw "adb pair failed for $pairingSerial with exit code $($pairResult.ExitCode)"
    }

    Write-Host "Successfully paired with $pairingSerial" -ForegroundColor Green
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = Split-Path -Parent $scriptDir
$localPropertiesPath = Join-Path $projectDir 'local.properties'
$sdkDir = Read-LocalPropertiesValue -FilePath $localPropertiesPath -Key 'sdk.dir'
$adbPath = Join-Path $sdkDir 'platform-tools\adb.exe'
$gradleWrapper = Join-Path $projectDir 'gradlew.bat'
$variantLower = $Configuration.ToLowerInvariant()
$assembleTask = "assemble$Configuration"
$apkDir = Join-Path $projectDir "app\build\outputs\apk\$variantLower"
$applicationId = Get-ApplicationId -BuildConfiguration $Configuration -ExplicitPackageSuffix $PackageSuffix
$activityName = "$applicationId/.MainActivity"

# Accept either a host name/IP or host:port. Wireless adb normally uses 5555,
# while Android's developer-settings pairing flow may initially expose another
# port.
if ($DeviceIp -match '^(.+):(\d+)$') {
    $deviceHost = $Matches[1]
    $customPort = [int]$Matches[2]
} else {
    $deviceHost = $DeviceIp
    $customPort = $null
}

if (-not (Test-Path -LiteralPath $adbPath)) {
    throw "adb.exe not found at $adbPath. Check sdk.dir in $localPropertiesPath"
}

if (-not (Test-Path -LiteralPath $gradleWrapper)) {
    throw "gradlew.bat not found at $gradleWrapper"
}

if (-not $SkipBuild) {
    Write-Step "Building Android $Configuration APK"
    Push-Location $projectDir
    try {
        & $gradleWrapper $assembleTask
        $gradleExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    if ($gradleExitCode -ne 0) {
        throw "Gradle task $assembleTask failed with exit code $gradleExitCode"
    }
}

# Prefer the normal APK name, with an arm64 split as a fallback if ABI splits
# are enabled in the Android build later.
$apkCandidates = @(
    (Join-Path $apkDir "app-$variantLower.apk"),
    (Join-Path $apkDir "app-arm64-v8a-$variantLower.apk")
)
$apkPath = $apkCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1

if ($null -eq $apkPath) {
    throw "APK not found in $apkDir. Expected app-$variantLower.apk"
}

$maxDeployAttempts = 5
$deployRetryDelaySeconds = 5
$serial5555 = "${deviceHost}:5555"
$script:pairingCompleted = $false
$script:pairingConnectPort = $null

function Invoke-ConnectAndInstall {
    Write-Step "Connecting with adb from SDK"
    $adbServerResult = Invoke-AdbCaptured start-server
    if ($adbServerResult.Output) {
        Write-Host $adbServerResult.Output
    }
    if ($adbServerResult.ExitCode -ne 0) {
        Write-Host "Warning: adb start-server returned exit code $($adbServerResult.ExitCode); continuing to adb connect anyway." -ForegroundColor Yellow
    }

    $portsToTry = @(5555)
    if ($null -ne $customPort -and $customPort -ne 5555) {
        $portsToTry = @($customPort, 5555)
    }

    $deviceSerial = Find-AdbDeviceSerial -Ports $portsToTry

    if ($null -eq $deviceSerial) {
        if (-not $script:pairingCompleted) {
            Invoke-AdbPair
            $script:pairingCompleted = $true

            if ($null -ne $customPort) {
                $script:pairingConnectPort = $customPort
            } else {
                $script:pairingConnectPort = Read-TcpPort `
                    -Prompt "Enter the phone's Wireless debugging connect port (the IP address & Port value)" `
                    -DefaultPort 5555
            }
        }

        $portsToTry = @($script:pairingConnectPort)
        if ($script:pairingConnectPort -ne 5555) {
            $portsToTry += 5555
        }
        $deviceSerial = Find-AdbDeviceSerial -Ports $portsToTry
    }

    if ($null -eq $deviceSerial) {
        throw "adb connect failed - could not reach $deviceHost on any port (tried: $($portsToTry -join ', '))"
    }

    if ($deviceSerial -ne $serial5555) {
        Write-Host "Switching device to port 5555 for future runs..." -ForegroundColor Cyan
        $tcpipResult = Invoke-AdbCaptured '-s' $deviceSerial tcpip 5555
        if ($tcpipResult.Output) {
            Write-Host $tcpipResult.Output
        }
        if ($tcpipResult.ExitCode -ne 0) {
            Write-Host "Warning: adb tcpip 5555 failed with exit code $($tcpipResult.ExitCode), continuing on $deviceSerial" -ForegroundColor Yellow
        } else {
            Start-Sleep -Seconds 2
            if (Invoke-AdbConnect -Serial $serial5555) {
                $deviceSerial = $serial5555
                Write-Host "Now connected on $deviceSerial" -ForegroundColor Green
            } else {
                Write-Host "Warning: could not reconnect on 5555, continuing on $deviceSerial" -ForegroundColor Yellow
            }
        }
    }

    Write-Step "Installing APK"
    Write-Host "APK: $apkPath"
    $installResult = Invoke-AdbCaptured '-s' $deviceSerial install '-r' $apkPath
    if ($installResult.Output) {
        Write-Host $installResult.Output
    }
    if ($installResult.ExitCode -ne 0) {
        throw "adb install failed for $apkPath with exit code $($installResult.ExitCode)"
    }

    return $deviceSerial
}

$deviceSerial = $null
$lastDeployError = $null
for ($deployAttempt = 1; $deployAttempt -le $maxDeployAttempts; $deployAttempt++) {
    try {
        Write-Host "Deploy attempt $deployAttempt/$maxDeployAttempts" -ForegroundColor Cyan
        $deviceSerial = Invoke-ConnectAndInstall
        $lastDeployError = $null
        break
    } catch {
        $lastDeployError = $_
        Write-Host "Connect/install attempt $deployAttempt/$maxDeployAttempts failed: $($_.Exception.Message)" -ForegroundColor Yellow

        if ($deployAttempt -lt $maxDeployAttempts) {
            Write-Host "Retrying whole connect/install block in $deployRetryDelaySeconds seconds..." -ForegroundColor Yellow
            Start-Sleep -Seconds $deployRetryDelaySeconds
        }
    }
}

if ($null -eq $deviceSerial) {
    throw "connect/install failed after $maxDeployAttempts attempts. Last error: $($lastDeployError.Exception.Message)"
}

Write-Step "Restarting app"
$forceStopResult = Invoke-AdbCaptured '-s' $deviceSerial shell am force-stop $applicationId
if ($forceStopResult.Output) {
    Write-Host $forceStopResult.Output
}
$startResult = Invoke-AdbCaptured '-s' $deviceSerial shell am start '-n' $activityName
if ($startResult.Output) {
    Write-Host $startResult.Output
}
if ($startResult.ExitCode -ne 0) {
    throw "adb shell am start failed for $activityName with exit code $($startResult.ExitCode)"
}

Write-Host "Deployed $Configuration build to $deviceSerial ($applicationId)" -ForegroundColor Green
