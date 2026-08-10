# Setup

What CI needs to produce a **release-signed** APK instead of a debug-signed one.
Four secrets, one keystore, about five minutes. Nothing here needs a Mac or an
Android toolchain.

Until they are set the build still succeeds — it signs with the debug key and
prints a warning on the run. That is fine for testing and must never be
published.

## 1. Create the signing key

Android installs only signed APKs, and an installed app can be upgraded in place
only by a build signed with the **same** key. Generate it once and keep it
safe: lose it and every user has to uninstall and reinstall to move on.

```sh
keytool -genkeypair -v \
    -keystore release.jks \
    -alias bidichan \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storetype pkcs12
```

`keytool` ships with any JDK. It asks for a keystore password and a few name
fields; the name fields are cosmetic and never shown to users. With `-storetype
pkcs12` the key password is the same as the keystore password — answer the key
password prompt with the same value, or press return to accept it.

Keep `release.jks` somewhere durable and out of the repository — it is already
covered by `.gitignore`, but a password manager or an encrypted backup is the
right home for it.

## 2. Set the four secrets

The workflow reads exactly these names:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | the keystore file, base64 with no line breaks |
| `KEYSTORE_PASSWORD` | the keystore password from step 1 |
| `KEY_ALIAS` | `bidichan`, unless you chose another `-alias` |
| `KEY_PASSWORD` | the key password (same as the keystore password with `pkcs12`) |

### With the `gh` CLI

Needs [`gh`](https://cli.github.com) on `PATH` and authenticated (`gh auth login`,
or `GH_TOKEN` set) with a token that has **Secrets: write** on the repository —
a token that can only watch CI runs will get a 403 on the secrets endpoint.

From the directory holding `release.jks`, in the repository checkout:

```sh
gh secret set KEYSTORE_BASE64   --repo torkve/bidichan-android < <(base64 -w0 release.jks)
gh secret set KEYSTORE_PASSWORD --repo torkve/bidichan-android
gh secret set KEY_ALIAS         --repo torkve/bidichan-android --body bidichan
gh secret set KEY_PASSWORD      --repo torkve/bidichan-android
```

The two without `--body` prompt for the value, so it stays out of your shell
history. Setting secrets needs a token with **Secrets: write** on the
repository — the one used for watching CI runs does not have it.

### Or through the web UI

**Settings ▸ Secrets and variables ▸ Actions ▸ New repository secret**, once per
row above. For `KEYSTORE_BASE64` paste the output of:

```sh
base64 -w0 release.jks        # GNU/Linux
base64 release.jks | tr -d '\n'   # macOS
```

`-w0` matters: a base64 value with line breaks decodes to a corrupt keystore and
the build fails at signing.

## 3. Check it took effect

Re-run the workflow (**Actions ▸ build ▸ Run workflow**) and look at the
**"Say how the build will be signed"** step. It prints one of:

- `Release key: signing with the keystore from KEYSTORE_BASE64.` — done.
- a warning that the APK is debug-signed — `KEYSTORE_BASE64` is unset or empty.

To confirm on the artifact itself:

```sh
# from the Android SDK build-tools
apksigner verify --print-certs app-release.apk
```

The certificate subject should be the name you entered in step 1, not
`CN=Android Debug`.

## 4. Build it

- Push to `main` → the APK appears as a build artifact on the run.
- Tag `v*` → the same APK is attached to a GitHub release.
- Or **Actions ▸ build ▸ Run workflow**.

Download it, move it to the device, and install (the device has to allow
installing from that source).

## 5. Keeping the core in step

The core is a git submodule at `vendor/bidichan-src`, pinned to a commit. CI
builds the binding from that exact commit, so a change to the core reaches the
app only once the pin moves:

```sh
cd vendor/bidichan-src
git fetch origin && git checkout <sha>
cd ../..
git add vendor/bidichan-src && git commit -m "bump bidichan core"
```

## 6. First run on the device

The system asks the user to allow this app to handle the device's packets the
first time a profile connects. That prompt belongs to the platform, not the app,
and the answer is remembered.

A profile needs at least the server address, the hostname to present, and the
pre-shared key — the same values the server was configured with. The key is held
under a key in the platform keystore, separately from the rest of the profile.
