# bidichan-android

An Android client for [bidichan](https://github.com/torkve/bidichan) — a
point-to-point encrypted tunnel disguised as HTTPS/WebSocket. The app hosts the
bidichan connect-side peer inside a foreground service that owns the system's
packet interface, and drives the channel kinds the core offers: a packet
interface, SOCKS5/HTTP proxies and TCP port forwards.

It is the Android counterpart of
[bidichan-ios](https://github.com/torkve/bidichan-ios) and speaks the identical
wire protocol, because both embed the same Go core.

## Architecture

```
 MainActivity (Compose)  ──startService──▶  TunnelService (foreground)
   profiles / connect                         hosts app/libs/bidichan.aar
   channel UI                                 (gomobile bidichan core)
                                              system packet interface ⇄ Go tun
                                                     │ TLS(uTLS)+WS+yamux
                                                     ▼   unmodified bidichan server
```

The Go networking core is reused verbatim through `gomobile bind`, so the wire
protocol is the same one the command-line client speaks. Two things are
Android-specific and worth knowing about:

- **The packet interface arrives as a descriptor.** The service builds it, then
  detaches the descriptor and hands it to the core, which owns and closes it.
  The core reads and writes it directly rather than copying every packet across
  the language boundary.
- **Our own socket is kept out of the tunnel.** Android routes an app's traffic
  into the active tunnel by default, which would send the transport through
  itself; the core calls back into the service before every dial — including the
  redials it makes when the network moves — so the socket is marked exempt.

## Staying connected

A phone changes networks constantly, so the tunnel is built to outlive the one
it started on. When the connection drops — Wi-Fi to cellular, a lift, a dead
spot — the app does **not** fall back to disconnected:

- The core resumes the same session over a fresh connection, replaying from byte
  counters both ends exchange, so open channels and the TCP connections running
  through them continue where they left off. The packet interface is the
  exception: packets are dropped while the link is away, and the connections
  inside the tunnel retransmit as they would on any real link.
- The notification says "Reconnecting…" for the duration, and the channels stay
  open.
- A network callback tells the core the moment the system switches interfaces,
  so the dead socket is replaced immediately rather than after a timeout.
- **Reconnect window** (per profile, default 90 s) is how long the network may
  be gone before the session is given up. Past it the service rebuilds the
  session and replays the packet channel and every channel the profile asked
  for, so the tunnel comes back on its own — only the connections inside it are
  lost.

This needs a server running a bidichan that supports resumption. Against an
older one the app still reconnects; it just cannot preserve the connections
inside the tunnel.

## Building

Everything is built on a GitHub Actions Ubuntu runner — no local Android
toolchain is required, and nothing needs a Mac. See `.github/workflows/build.yml`:
push to `main` for a signed APK as a build artifact, tag `v*` to attach it to a
release.

To build locally you need JDK 17, the Android SDK with an NDK, and Go:

```sh
# 1. The gomobile binding of the Go core (not committed — the build makes it).
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
cd vendor/bidichan-src
go get golang.org/x/mobile/bind@latest
gomobile bind -target=android -androidapi 26 \
    -javapkg=torkve.bidichan.go -ldflags="-s -w" \
    -o ../../app/libs/bidichan.aar ./mobile

# 2. The APK.
cd ../..
gradle assembleRelease        # or ./gradlew, see below
```

The Gradle wrapper is not committed. Generate it once if you want `./gradlew`:

```sh
gradle wrapper --gradle-version 9.7.0
```

## Signing

A release build is signed with the keystore CI materialises from secrets. Without
those secrets the build still succeeds and is signed with the debug key, so a
branch build is installable — but never publish that.

See [SETUP.md](SETUP.md) for creating the keystore and the repository secrets.

## Requirements

- Android 8.0 (API 26) or later.
- A server running bidichan. The app never ships a server address; every
  connection setting lives in a profile you enter yourself.
