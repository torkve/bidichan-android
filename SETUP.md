# Setup

One-time steps to make CI produce a signed, installable APK on its own. Nothing
here needs a Mac, and nothing needs an Android toolchain on your machine.

## 1. Create a signing keystore

Android will only install an APK that is signed, and an app can only be upgraded
in place by a build signed with the *same* key. Generate one and keep it safe —
losing it means every user has to uninstall and reinstall.

```sh
keytool -genkeypair -v \
    -keystore release.jks \
    -alias bidichan \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storetype pkcs12
```

Then base64 it for the secret:

```sh
base64 -w0 release.jks
```

## 2. Repository secrets

Add these under **Settings ▸ Secrets and variables ▸ Actions**:

| Secret | What it is |
| --- | --- |
| `KEYSTORE_BASE64` | the base64 from step 1 |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | the alias (`bidichan` above) |
| `KEY_PASSWORD` | the key password |

Without them the workflow still builds, but signs with the debug key. That is
fine for trying a branch build and must never be published.

## 3. Build

- Push to `main` → the APK appears as a build artifact on the run.
- Tag `v*` → the same APK is attached to a GitHub release.
- Or run the workflow by hand from the Actions tab.

Download the artifact, transfer it to the device, and install it (the device has
to allow installing from that source).

## 4. Keeping the Go core in step

The core is a git submodule at `vendor/bidichan-src`, pinned to a commit. CI
builds the binding from that exact commit, so a change to the core only reaches
the app once the pin moves:

```sh
cd vendor/bidichan-src
git fetch origin && git checkout <sha>
cd ../..
git add vendor/bidichan-src && git commit -m "bump bidichan core"
```

## 5. First run on the device

The system asks the user to allow this app to handle the device's packets the
first time a profile connects. That prompt is the platform's, not the app's, and
consent is remembered afterwards.

A profile needs, at minimum, the server address, the hostname to present, and
the pre-shared key — the same values the server side was configured with. The
key is stored under a key held in the platform keystore, separately from the
rest of the profile.
