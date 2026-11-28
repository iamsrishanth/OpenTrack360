param(
    [Parameter(Mandatory = $true)]
    [string]$Destination
)

$ErrorActionPreference = 'Stop'
$credentialDirectory = Join-Path $env:LOCALAPPDATA 'OpenTrack360\release-signing'
$keystorePath = Join-Path $credentialDirectory 'opentrack360-release.p12'
$protectedPasswordPath = Join-Path $credentialDirectory 'release-password.dpapi'
$keytoolPath = 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
$destinationPath = [System.IO.Path]::GetFullPath($Destination)
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$repositoryPrefix = $repositoryRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar

if (-not (Test-Path -LiteralPath $keystorePath) -or -not (Test-Path -LiteralPath $protectedPasswordPath)) {
    throw 'The local OpenTrack360 release signing identity is not installed for this Windows user.'
}
if (-not (Test-Path -LiteralPath $keytoolPath)) {
    throw 'Android Studio keytool is unavailable. Install Android Studio or update this script with the JDK keytool path.'
}
if ([System.IO.Path]::GetExtension($destinationPath) -notin @('.p12', '.pfx')) {
    throw 'Destination must end in .p12 or .pfx.'
}
if (Test-Path -LiteralPath $destinationPath) {
    throw 'Destination already exists; choose a new backup path so an existing backup is not overwritten.'
}
if ($destinationPath.Equals($repositoryRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
    $destinationPath.StartsWith($repositoryPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Keep backup files outside the Git repository.'
}
$destinationDirectory = Split-Path -Parent $destinationPath
if (-not (Test-Path -LiteralPath $destinationDirectory -PathType Container)) {
    throw 'The destination folder must already exist.'
}

function Convert-SecureStringToPlainText([System.Security.SecureString]$Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {
        [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

$backupPasswordSecure = Read-Host 'Enter a new password for the portable encrypted backup' -AsSecureString
$backupPasswordConfirmSecure = Read-Host 'Re-enter the new backup password' -AsSecureString
$backupPassword = Convert-SecureStringToPlainText $backupPasswordSecure
$backupPasswordConfirm = Convert-SecureStringToPlainText $backupPasswordConfirmSecure
if ($backupPassword -cne $backupPasswordConfirm) {
    $backupPassword = $null
    $backupPasswordConfirm = $null
    $backupPasswordSecure.Dispose()
    $backupPasswordConfirmSecure.Dispose()
    throw 'The entered backup passwords did not match.'
}
$backupPasswordConfirm = $null
$backupPasswordConfirmSecure.Dispose()

$releasePasswordSecure = ConvertTo-SecureString -String (Get-Content -Raw -LiteralPath $protectedPasswordPath)
$releasePasswordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($releasePasswordSecure)
$previousSourcePassword = [Environment]::GetEnvironmentVariable('OPENTRACK360_BACKUP_SOURCE_PASSWORD', 'Process')
$previousDestinationPassword = [Environment]::GetEnvironmentVariable('OPENTRACK360_BACKUP_DEST_PASSWORD', 'Process')
$backupCompleted = $false
try {
    $releasePassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($releasePasswordPointer)
    $env:OPENTRACK360_BACKUP_SOURCE_PASSWORD = $releasePassword
    $env:OPENTRACK360_BACKUP_DEST_PASSWORD = $backupPassword
    & $keytoolPath -importkeystore `
        -srckeystore $keystorePath -srcstoretype PKCS12 `
        -srcstorepass:env OPENTRACK360_BACKUP_SOURCE_PASSWORD `
        -srcalias opentrack360-release -srckeypass:env OPENTRACK360_BACKUP_SOURCE_PASSWORD `
        -destkeystore $destinationPath -deststoretype PKCS12 `
        -deststorepass:env OPENTRACK360_BACKUP_DEST_PASSWORD `
        -destalias opentrack360-release -destkeypass:env OPENTRACK360_BACKUP_DEST_PASSWORD `
        -noprompt
    if ($LASTEXITCODE -ne 0) {
        throw 'The encrypted portable signing backup could not be created.'
    }
    $backupCompleted = $true
    Write-Output "Encrypted portable backup created at $destinationPath"
    Write-Output 'Store the backup password separately in the owner password manager; do not commit either item.'
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($releasePasswordPointer)
    $releasePassword = $null
    $backupPassword = $null
    $releasePasswordSecure.Dispose()
    $backupPasswordSecure.Dispose()
    [Environment]::SetEnvironmentVariable('OPENTRACK360_BACKUP_SOURCE_PASSWORD', $previousSourcePassword, 'Process')
    [Environment]::SetEnvironmentVariable('OPENTRACK360_BACKUP_DEST_PASSWORD', $previousDestinationPassword, 'Process')
    if (-not $backupCompleted -and (Test-Path -LiteralPath $destinationPath)) {
        Remove-Item -LiteralPath $destinationPath -Force -ErrorAction SilentlyContinue
    }
}
