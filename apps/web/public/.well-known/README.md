# Android App Links association

`assetlinks.json` is intentionally an empty Digital Asset Links statement list until the Android team provides the SHA-256 fingerprint of the release signing certificate for `me.srishanth.opentrack360`.

After that fingerprint is available, replace the empty array with one `delegate_permission/common.handle_all_urls` statement whose Android target package is `me.srishanth.opentrack360` and whose `sha256_cert_fingerprints` contains the verified release signing fingerprint. Do not use a debug certificate fingerprint for the Play release. Until the file is updated and Android verifies `https://track.srishanth.me/.well-known/assetlinks.json`, share links continue to work in the browser but Android App Links are not verified.

The Android manifest should associate only the `/s/*` trip-share path. The pet recovery route (`/p/*`), account administration (`/admin/*`), and API (`/api/*`) remain browser-only.
