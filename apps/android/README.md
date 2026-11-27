# OpenTrack360 Android

Native Android client built with Kotlin and Jetpack Compose. The application ID is `me.srishanth.opentrack360`, minimum supported Android is API 29, and the app targets API 36.

The app talks to the Vercel API at `https://track.srishanth.me/api/v1`. It does not embed Supabase or other backend credentials. Build with `gradlew.bat :app:assembleDebug`; the APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Direct APK release signing, local key protection, certificate verification, and backup steps are documented in [RELEASE_SIGNING.md](RELEASE_SIGNING.md). A signed build uses `tools/build-release.ps1`; release keys and passwords are stored outside the repository.

Circle and guest maps use MapLibre with the OpenFreeMap `liberty` style, including the provider attribution. Map tiles are loaded from `https://tiles.openfreemap.org/styles/liberty`; tile requests can reveal the map area currently being viewed to OpenFreeMap. The public tile service is free for commercial use but does not provide an SLA.
