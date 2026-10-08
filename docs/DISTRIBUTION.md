# Share Intentional with another Android phone

Run from the project folder on your development computer. Recipients do not need Android Studio, Java, or a cable.

## One command, same Wi-Fi

```sh
./scripts/share-android.sh
```

The script runs session tests, builds a signed debug APK, creates a mobile download page, and prints page and direct-download links. Send the page link. Keep the terminal and computer running until downloads finish; Ctrl+C stops sharing. Only the page, APK, and checksum file are served.

Building needs the JDK/Android SDK setup from the README, plus Python 3.9+. On Windows use `python scripts/share_android.py`. No Python packages are required.

## Internet link, including mobile data

Install [cloudflared](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/) once, then run:

```sh
./scripts/share-android.sh --public
```

The script starts a [Cloudflare Quick Tunnel](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/) and prints a temporary HTTPS link. No Cloudflare account or domain is needed. Anyone with the complete link can download; there is no recipient login. The link changes on each run and stops working when the command stops. The downloaded app keeps working afterward. Cloudflare handles download traffic, not the app’s session journal.

If startup fails, inspect the reported `tunnel.log`. A pre-existing cloudflared configuration can interfere with Quick Tunnels; consult the provider documentation. The script does not change cloudflared configuration or install software automatically.

## Share an APK you already built

```sh
./scripts/share-android.sh --apk androidApp/build/outputs/apk/debug/androidApp-debug.apk --public
```

`--apk` skips building and tests. Supply a signed, previously verified APK. The script checks archive integrity and required entries; it does not validate the signing certificate or prove that the APK installs.

## Prepare files for a permanent link

```sh
./scripts/share-android.sh --prepare
```

This creates a uniquely named folder in `dist/` containing `index.html`, `intentional.apk`, and `SHA256SUMS`. Upload all three together to a static host that permits APK downloads, then share its page URL. This command does not publish files or create a public URL. It also works with `--apk`.

## On the receiving phone

1. Open the page and tap **Download for Android**.
2. Open the APK, allow installation from that browser if Android asks, and confirm installation. A link cannot silently install an Android app.
3. Open Intentional, read the automatic collection notice, and continue to try the 75-second demo.
4. For Instagram and YouTube interruptions, follow **Set up opening check-ins**. Accessibility must be enabled on each phone. Some devices require **App info → menu → Allow restricted settings** first; wording varies.
5. Study data is collected automatically after the one-time notice. Participants do not export or send files. Researchers must configure Firebase and deploy the current rules before distribution, then use the [collection and aggregation tools](STUDY_DATA.md) to download each installation’s latest CSV.

This distributes a debug prototype for testing, not a Play Store release. Use the same development machine/signing key for updates. A different key cannot replace an existing installation; uninstalling deletes its local journal and can lose unsynced changes. Have researchers confirm receipt of the latest study data before a deliberate uninstall. Never put signing keys in the download bundle. For a longer-lived study, use a dedicated release key and increment Android’s version code for releases.

## Troubleshooting

To remove the prototype, users can tap **Uninstall Intentional** at the bottom of any app screen and continue to Android’s uninstall confirmation. Canceling does not erase data. Alternatively, long-press the Intentional icon → App info → Uninstall. Removing Intentional stops check-ins and future uploads and deletes its local journal; received cloud data is not erased. Instagram and YouTube remain installed.

- Campus/guest Wi-Fi may isolate devices; use `--public` or a network without client isolation.
- If the selected IP belongs to a VPN, use `--host 192.168.x.x` with your computer’s Wi-Fi address.
- If port 8765 is busy, use `--port 8766` or `--port 0` to select an available port.
- Failed builds stop before sharing. Old APKs are not silently substituted. Fix the Gradle error or explicitly use `--apk` with a working build.
- If Gradle reports a missing Java 17 compiler, rerun with internet access: the project now provisions a full JDK 17 automatically. `java -version` alone does not prove the compiler is installed. If your network blocks provisioning, install a complete JDK 17 (on Ubuntu: `sudo apt install openjdk-17-jdk`) and rerun. The app and shared test module both request that toolchain.
- Socket-restricted environments cannot run Gradle, the server, or tunnels. Run this script in your normal computer terminal.

Helper tests: `python3 -m unittest discover -s scripts/tests -v`. They exercise checksums, APK validation, download responses, and restricted routing without network sockets. Real installation still needs the [device checklist](DEVICE_TESTS.md).
