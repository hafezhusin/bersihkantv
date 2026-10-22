# CPU Stats

Optional standalone Android TV module card, maintained by [lionlev21](https://github.com/lionlev21).
Build with JDK 17 and Android SDK 34: `./gradlew testReleaseUnitTest lintRelease assembleRelease`.
The small gradlew scripts reuse the repository’s pinned Gradle wrapper with this folder as the project;
no wrapper JAR is copied into the add-on and no launcher source is included. Keep this folder inside
the repository when building. Install the APK you build with ADB or your preferred installer.
No binaries or prebuilt APKs are committed.

The launcher discovers the static module card and asks whether to show it. Opening the card shows
live readings in this app. Module spec 1 has static captions: it cannot show live counters on Home.
No launcher changes are required. English and Hebrew UI, standard locale direction for other languages.
Reads refresh every ten seconds only while this screen is resumed; no background service survives leaving it.

## Permissions

Shizuku permission (`moe.shizuku.manager.permission.API_V23`) is requested only by pressing Authorize.
The Shizuku provider is guarded by `android.permission.INTERACT_ACROSS_USERS_FULL`; this app does
not request that system permission. No INTERNET, storage, usage-access or accessibility permission.
No telemetry, ads, network requests or arbitrary commands. Setup guide opens in your external browser.

Install Shizuku yourself from its official project, start it, opt in and authorize this app separately.
The option is off by default. On unrooted devices restart Shizuku after each TV reboot. The narrow
non-daemon user service reads only aggregate /proc/stat counters. No arbitrary paths or shell interface.
CPU remains hidden until two valid samples; permission denial, service loss or invalid counters hide it.
All CPU controls are hidden when Shizuku is absent. An installation does not reuse launcher permissions.
Uninstalling the add-on stops its reader. Revoke permission in Shizuku to remove persistent authorization.

## Validation

Real Android TV 12 hardware; the owner's model and configuration are withheld for privacy.
8 JVM tests, lintRelease and release build passed. On real hardware verified module-card recognition, hidden authorization controls with Shizuku absent, and no worker after leaving. The new app package has not yet been authorized against a running Shizuku server; privileged permission/IPC testing remains pending, so this contribution is a draft.
