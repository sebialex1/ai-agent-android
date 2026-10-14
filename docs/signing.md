# Signing the APK so new builds update in place

Android only installs an APK over an existing app when both are signed with the same key. Debug
builds use a debug key that is generated per machine, so every CI run produced an APK that could not
update the previous one (and uninstalling deletes the app's data). The workflow now signs with a key
you own.

## One-time setup

1. Create a keystore (keep the file and passwords somewhere safe, outside the repo; losing it means
   one more uninstall for every future key change):

   ```bash
   keytool -genkeypair -v -keystore agent-release.jks -alias agent \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

   Pick a password when asked. Use the same password for the key (press Enter at the key-password
   prompt) to keep it simple.

2. Encode it as one line of base64:

   ```bash
   base64 -w0 agent-release.jks > agent-release.jks.b64     # Linux
   base64 -i agent-release.jks | tr -d '\n' > agent-release.jks.b64   # macOS
   ```

3. In GitHub open the repo, Settings > Secrets and variables > Actions > New repository secret, and add:

   | Secret | Value |
   | --- | --- |
   | `KEYSTORE_BASE64` | contents of `agent-release.jks.b64` |
   | `KEYSTORE_PASSWORD` | the keystore password |
   | `KEY_ALIAS` | `agent` (the `-alias` above) |
   | `KEY_PASSWORD` | the key password (same as the keystore password if you pressed Enter) |

   With the GitHub CLI: `gh secret set KEYSTORE_BASE64 < agent-release.jks.b64`, then
   `gh secret set KEYSTORE_PASSWORD`, `gh secret set KEY_ALIAS`, `gh secret set KEY_PASSWORD`.

4. Run the "Build APK" workflow. The log prints the signing certificate (SHA-256); it must be the same
   on every run. The APK is attached to the run as the `agent-apk` artifact and to the release.

Never commit the keystore (`*.jks` and `*.keystore` are in `.gitignore`).

## What the build does

- `versionCode` is the workflow run number, so every build is newer than the previous one and Android
  accepts it as an update. `versionName` is `0.1.<run>` (or the pushed tag).
- Without the secrets (forks, local builds) the release build is signed with the debug key and the
  workflow prints a warning. Locally you can pass the same values as environment variables or Gradle
  properties: `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `VERSION_CODE`.

## The one last uninstall

If the app on your phone was installed from a build signed with a different key (any earlier debug
APK), the first build signed with your new key cannot install over it. Before that single uninstall:

1. Settings > Backup & restore: choose a folder (for example Documents/Agent), optionally set a
   passphrase so API keys are included (encrypted), and tap Back up now.
2. Uninstall, install the new signed APK, open the app and choose "Restore" (or Settings > Backup &
   restore > Choose folder > Restore).

Every build after that installs as an update and keeps all data.
