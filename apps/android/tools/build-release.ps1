param(
    [Parameter(Position = 0)]
    [string]$Tasks = ':app:assembleRelease'
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$credentialDirectory = Join-Path $env:LOCALAPPDATA 'OpenTrack360\release-signing'
$keystorePath = Join-Path $credentialDirectory 'opentrack360-release.p12'
$protectedPasswordPath = Join-Path $credentialDirectory 'release-password.dpapi'
$passwordEnvironmentNames = @(
    'OPENTRACK360_RELEASE_KEYSTORE',
    'OPENTRACK360_RELEASE_STORE_PASSWORD',
    'OPENTRACK360_RELEASE_KEY_ALIAS',
    'OPENTRACK360_RELEASE_KEY_PASSWORD'
)

if (-not (Test-Path -LiteralPath $keystorePath) -or -not (Test-Path -LiteralPath $protectedPasswordPath)) {
    throw 'The local OpenTrack360 release signing identity is not installed for this Windows user.'
}

$previousEnvironment = @{}
foreach ($name in $passwordEnvironmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

$securePassword = ConvertTo-SecureString -String (Get-Content -Raw -LiteralPath $protectedPasswordPath)
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    $env:OPENTRACK360_RELEASE_KEYSTORE = $keystorePath
    $env:OPENTRACK360_RELEASE_STORE_PASSWORD = $password
    $env:OPENTRACK360_RELEASE_KEY_ALIAS = 'opentrack360-release'
    $env:OPENTRACK360_RELEASE_KEY_PASSWORD = $password

    if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
        $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    }
    if (-not $env:JAVA_HOME) {
        $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
    }

    Push-Location $projectDirectory
    try {
        $taskArguments = $Tasks -split '\s*,\s*'
        & (Join-Path $projectDirectory 'gradlew.bat') --no-daemon @taskArguments
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    $password = $null
    $securePassword.Dispose()
    foreach ($name in $passwordEnvironmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
