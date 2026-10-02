# appdist

Command-line tool that builds an Android release APK and sends it to testers
with [Firebase App Distribution](https://firebase.google.com/docs/app-distribution),
optionally announcing it in Slack.

It powers the [App Distribution for Firebase](https://github.com/jayrajsinhthakurinbox-code/app-distribution-plugin)
plugin for Android Studio, which bundles it, but it also works on its own
from a terminal or CI.

## Usage

Run from the root of an Android project:

```bash
appdist release                      # build the release APK and record its details
appdist release --build-type debug   # build the debug APK instead
appdist release --apk my.apk         # use an existing APK instead of building
appdist distribute --testers-file testers.txt --release-notes-file notes.txt
```

`testers.txt` holds one email per line (or comma-separated).

What it takes care of:

- **Finds your app module**: the one applying `com.android.application`
  (`app` preferred), and builds only that module.
- **Reads version and package from the built APK** with the SDK's `aapt2`,
  however your build declares them, and finds the APK Gradle actually
  produced (flavors, custom names, up-to-date builds).
- **Detects unsigned builds** and says so. Debug builds use the debug key,
  and whether an APK is a debug build is read from the APK itself.
- **Finds the Firebase app** for your package in `google-services.json`
  (module root or `src/<variant>/`).
- **Firebase CLI**: uses an installed `firebase`, or `appdist firebase --yes`
  downloads the standalone binary into `~/.app-distribution/bin` (no Node.js
  or admin rights).

Details of the last release are written to
`<app module>/build/app-distribution/release.json`.

### Slack (optional)

Set an [Incoming Webhook](https://api.slack.com/messaging/webhooks) URL and
each distribution is announced, with release notes formatted as bullet lists:

```bash
export APPDIST_SLACK_WEBHOOK_URL=https://hooks.slack.com/services/…
appdist slack test
```

### Signing

`appdist release` runs `:<module>:assembleRelease`, so the release build must
be signed by your Gradle config, or by the standard
`android.injected.signing.*` properties (what Android Studio's
*Generate Signed APK* wizard uses), e.g. as `ORG_GRADLE_PROJECT_…`
environment variables.

## Platform support

Developed and tested on macOS. Linux should work with the `firebase` CLI
already installed, but isn't tested. Windows isn't supported yet (the build
runs `./gradlew`, and the automatic Firebase CLI download is the macOS
binary).

## Build

Requires JDK 21 (Gradle downloads it if missing).

```bash
./gradlew test installDist
build/install/appdist/bin/appdist --help
```

## License

[MIT](LICENSE)
