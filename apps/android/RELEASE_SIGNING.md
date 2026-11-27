# Android direct APK signing

OpenTrack360 uses direct APK distribution. The release package is `me.srishanth.opentrack360`; this project does not have a Play upload key or Play app-signing identity.

## Local signing identity

A dedicated RSA 3072-bit release key was created on 2026-11-27. Its self-signed certificate is valid from 2026-11-27 through 2054-04-14. The public SHA-256 fingerprint is:

`22:32:AD:A1:CA:CB:94:55:50:16:AB:83:68:D8:DF:48:DF:10:36:33:A9:E3:A1:63:88:2F:64:3E:D0:44:32:24`

The Vercel owner needs this exact package name and fingerprint in `/.well-known/assetlinks.json` before Android can verify production App Links. Do not use the debug certificate for the public association.

On this Windows account, the private PKCS#12 file and its separately DPAPI-protected password are kept under `%LOCALAPPDATA%\OpenTrack360\release-signing\`. Both files are outside the repository. The directory and files grant access only to the current Windows user. The DPAPI password file can be decrypted only in this Windows user profile; it is a local build credential, not a portable backup.

Build a signed release APK from `apps/android` with:

```powershell
./tools/build-release.ps1
```

To run tests and produce the APK in one invocation:

```powershell
./tools/build-release.ps1 -Tasks ':app:testDebugUnitTest,:app:assembleRelease'
```

The helper reads the password through Windows DPAPI, exposes it only to the Gradle process environment, then clears it. Gradle does not read a password from source control. Verify a release artifact with Android SDK Build Tools:

```powershell
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs .\app\build\outputs\apk\release\app-release.apk
Get-FileHash .\app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
```

The verified build from this release checkpoint is `app/build/outputs/apk/release/app-release.apk` (70,954,779 bytes; SHA-256 `8A88A7A7C05845B575F200C030CE4C2BE2FEB4E646A30289D80CDE8337D68289`). `apksigner verify --print-certs` confirmed the package signer and certificate above; `zipalign -c 4` passed.

## Backup and restore

Keep the original key and password recoverable for every future APK update. Losing the key means existing direct-install users cannot receive an in-place update signed with the same identity.

Create a portable encrypted copy on a separate secure volume or owner-controlled vault. From `apps/android`, run:

```powershell
./tools/backup-release-signing.ps1 -Destination 'X:\OpenTrack360\opentrack360-release-backup.p12'
```

The script prompts for a new backup password without echoing it and creates a password-encrypted PKCS#12 copy. Save that backup password separately in the owner password manager. Keep the backup file and its password in separate secure locations. Do not put either in Git, a public issue, chat, build log, screenshot, or an unencrypted shared folder.

To restore on a new Windows account, install the JDK/Android SDK and use the backup PKCS#12 with a new current-user-only local DPAPI credential file. Do not copy the original DPAPI file as the only recovery method. After any key migration, verify the APK certificate still has the fingerprint above before distributing it.
