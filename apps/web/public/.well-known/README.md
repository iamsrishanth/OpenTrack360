# Android App Links association

`assetlinks.json` associates `track.srishanth.me` with the direct-distribution Android package `me.srishanth.opentrack360` using the release certificate fingerprint verified by the Android team. Keep this as one Digital Asset Links statement. Do not add a debug certificate or unrelated package.

Digital Asset Links statements are domain-wide; the JSON file does not express path filters. The Android release manifest currently opts into verified links for `/s/*`, `/p/*`, and the exact `/i` invite route. Its intent filters exclude account administration (`/admin/*`) and the API (`/api/*`), which remain browser-only. Review any future manifest path change with the Android release owner.
