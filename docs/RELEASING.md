# Releasing LocalCast

Release APKs are signed with the developer's own key. CI signs them when a `v*` tag is pushed and
publishes a GitHub Release with `LocalCast-<version>-arm64.apk`, `LocalCast-<version>-armv7.apk`
and `SHA256SUMS.txt`. Pushes to branches and pull requests are built without the release key
(the release build type falls back to the debug key there).

> [!WARNING]
> **Do not lose the key.** Android installs an update only if it is signed with the same key as
> the installed app. Without the keystore *and* its passwords you can never ship an update that
> installs over LocalCast already on people's devices: everyone would have to uninstall and lose
> the app's settings. Keep **two backup copies** of `localcast-release.jks` and both passwords in
> **different places** (for example a password manager and an encrypted USB drive kept elsewhere).
> Never commit the keystore or the passwords: `*.jks`, `*.keystore` and `keystore.properties` are
> git-ignored.

## 1. Create the key (once)

```bash
keytool -genkeypair -v -keystore localcast-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias localcast
```

`keytool` comes with the JDK. It asks for a keystore password, the certificate's name fields and
a key password (pressing Enter reuses the keystore password). Keep the file outside the repository.

## 2. Add the secrets to GitHub (once)

Encode the keystore as a single line of base64:

```bash
# Linux
base64 -w 0 localcast-release.jks > localcast-release.jks.b64
# macOS
base64 -i localcast-release.jks -o localcast-release.jks.b64
```

In the repository on GitHub: **Settings → Secrets and variables → Actions → New repository
secret**, add these four:

| Secret | Value |
|---|---|
| `LOCALCAST_KEYSTORE_BASE64` | contents of `localcast-release.jks.b64` |
| `LOCALCAST_KEYSTORE_PASSWORD` | keystore password |
| `LOCALCAST_KEY_ALIAS` | `localcast` (the `-alias` above) |
| `LOCALCAST_KEY_PASSWORD` | key password |

Delete `localcast-release.jks.b64` afterwards. The workflow decodes the keystore into
`$RUNNER_TEMP/release.jks`, passes the passwords to Gradle through environment variables and
deletes the file after the build. GitHub masks secret values in the log.

## 3. Release a version

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` and commit to `main`.
2. Tag the commit with the same version and push the tag:

   ```bash
   git tag v1.x.y && git push origin v1.x.y
   ```

The `Android` workflow then runs the unit tests, lint and the viewer e2e tests, builds the
release APKs with `-Plocalcast.requireReleaseSigning=true` (a missing key fails the build instead
of falling back to the debug key), checks that the tag matches `versionName`, that no APK is
signed with the debug key, writes `SHA256SUMS.txt` and creates the GitHub Release with generated
notes. Edit the notes on GitHub afterwards if needed.

## Signing locally (optional)

Copy `keystore.properties.example` to `keystore.properties` in the project root and fill it in, or
set the same four values as environment variables (`LOCALCAST_KEYSTORE_PATH`,
`LOCALCAST_KEYSTORE_PASSWORD`, `LOCALCAST_KEY_ALIAS`, `LOCALCAST_KEY_PASSWORD`; they take priority
over the file). Then:

```bash
./gradlew assembleRelease
```

Without either, the release build is signed with the debug key and Gradle prints
`LocalCast: release signing key not configured, APK is NOT signed for distribution`.

Check which key signed an APK:

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

Users can compare an APK with the release checksums: `sha256sum -c SHA256SUMS.txt` (Linux) or
`shasum -a 256 -c SHA256SUMS.txt` (macOS), run in the folder with the downloaded files.
