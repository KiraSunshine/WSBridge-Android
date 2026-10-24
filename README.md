# WSBridge

IP-over-WebSocket relay for Android (VpnService). Russian version: [README.ru.md](README.ru.md)

## What it does

The app creates a virtual network interface (TUN) and carries the IPv4
traffic of the configured prefixes through it as a WebSocket stream:

```mermaid
flowchart LR
    subgraph DEV["Android · single process (VpnService + UI)"]
        direction LR
        APP(["Apps"]) -->|raw IPv4 packets| TUN[/"TUN fd (VpnService)"/]
        TUN --> JNI["JNI bridge"]
        JNI --> LW["lwIP stack<br/>NO_SYS=1 · single thread · IPv4 only"]
        LW -->|terminated TCP flows| WS["OkHttp WebSocket client"]
        WS -->|decoded IP packets| JNI
        JNI --> TUN
    end
    WS <-->|"wss:// (TLS) · HTTP 101 upgrade"| GW["Gateway endpoint"]
```

1. The system routes IPv4 traffic for the configured prefixes into the
   TUN fd (`149.154.0.0/16`, `91.108.0.0/16`, `91.105.192.0/24` — nothing
   else is touched).
2. The vendored lwIP stack (2.2.0, single-threaded, `NO_SYS=1`, IPv4-only)
   terminates TCP and re-emits each flow as WebSocket frames.
3. Frames travel over TLS (`wss://`) to an HTTP endpoint that upgrades
   (status 101) and relays the stream.
4. Responses are decoded back into IP packets and written to the TUN.

Unlike the iOS original there is no IPC layer: the service and the UI live
in one process, so the journal and the status are read directly. lwIP has
no UDP of its own, so DNS from the tunnel is forwarded by `DNSForwarder`
as plain UDP:53 to the selected server.

The 64-byte obfuscated init header is parsed on the fly. The client does
not embed `dc_idx` into it without a proxy secret, so the DC index is
derived from the destination address (built-in IP→DC table) and the
`dc_idx` bytes are rewritten in place (keystream XOR) to match the chosen
gateway host.

Additional modes (the "CF Worker" field in the menu):
- **pipe** — your own Cloudflare Worker: the raw TCP stream goes into WS,
  the worker connects to the destination itself;
- **relay** — a direct relay on your VPS: `host:port`, raw TCP, no CF/WS.

## Endpoint selection

Connections are established through an ordered cascade:

```mermaid
flowchart TD
    A(["new connection"]) --> B{"persistent health registry<br/>skip: ≥3 consecutive fails and no success in 24 h<br/>order: latest successes first"}
    B --> T1["tier 1 · direct IPs<br/>gateway address for this DC<br/>SNI/Host/cert stay domain-based"]
    T1 -->|fail| T2["tier 2 · CDN edge<br/>kws{dc}.{domain} · round-robin start"]
    T2 -->|fail| T3["tier 3 · native<br/>kws{dc}.*"]
    T3 -->|fail| X(["abort · client reconnects → new cascade"])
    T1 & T2 & T3 -->|first data received| OK(["session active"])
    OK -.->|"success: failCount = 0, lastSuccess = now"| R[("health registry<br/>SharedPreferences · survives restarts")]
    T1 & T2 & T3 -.->|"fail before first data: failCount++<br/>+ 10-min runtime ban"| R
    R -.->|next connect| B
```

- **Direct IPs** — gateway addresses proven to serve this DC (the gateway
  picks its certificate by SNI, so an IP is only used where the cert
  covers the host); TCP goes to the IP while SNI/Host/validation stay
  domain-based via a custom DNS resolver.
- **CDN edge** — `kws{dc}.{domain}` hosts, resolved and rotated
  round-robin; the prefix of live endpoints is preferred.
- **Native** — the `kws{dc}.*` gateway host as the final fallback; the
  `-1` host variant serves media clusters.

The per-endpoint health registry survives restarts: consecutive failures,
last failure and last success timestamps. Endpoints with ≥3 consecutive
failures and no success within 24 h are excluded until recovery; most
recent successes are tried first. A runtime cache additionally bans a
failed endpoint for 10 minutes inside the running session.

## Endpoint list updates

The endpoint list is hot-updatable without rebuilding the app:

1. The app fetches the list from the origin URL, falling back to two
   CDN mirrors (10 s timeout, cache bypass).
2. Each entry is validated by format and DNS resolution; a list where
   fewer than 3 entries resolve is discarded as stale.
3. Validated entries are merged into the persistent registry — the
   built-in list always stays first as an immutable fallback; known-live
   entries are never re-tested, dead ones get a DNS re-check, entries
   dead for 7 days and absent from the upstream list are evicted.
4. The registry feeds the cascade on the next tunnel start.
   Auto-refresh is rate-limited to once per 30 minutes and runs after
   tunnel start, not before it. A manual "Update fronts" menu item shows
   the result as a snackbar.

## DNS

DNS from inside the tunnel is intercepted and forwarded by `DNSForwarder`.
The transport is user-selectable:

- **DoT** (DNS-over-TLS, RFC 7858) — **default**. TLS directly to the
  server IP on port 853 with SNI; the hostname is never resolved, so this
  is the most reliable transport while inside the tunnel.
- **DoH** (DNS-over-HTTPS, RFC 8484) — HTTPS POST, masquerades as ordinary
  traffic, but requires the resolver hostname to be resolved first.
- **plain UDP:53** — fallback for servers that support neither.

Presets: system / Cloudflare / Google / Comss.one / Malw.link / GeoHide /
XBox DNS / Alt Malw.link, each with a provider site link. Custom servers
require a DoT hostname: an unencrypted resolver is intercepted by the
provider, which defeats the point of using a custom one. Changes take
effect on the next tunnel start.

## Install

Download the APK from [Releases](https://github.com/l1ratch/WSBridge-Android/releases);
the dev build of the latest `main` push lives in the `preview` prerelease.
All builds share one signing key and a growing `versionCode`, so updates
install in place. (The first switch away from an old debug build needs one
manual uninstall — those were signed with a random key.)

## Build

Version is two values in `gradle.properties`: `wsb.build` — the monotonic
build number that drives `versionCode`, and `wsb.version` — the marketing
semver (`0.3.0`) that drives `versionName`. Both CI workflows pass
`wsb.build` as `BUILD_NUMBER`, so preview and release builds share one
growing `versionCode` and install over each other. The release workflow
refuses to build when the pushed tag (`v0.3.0`) disagrees with `wsb.version`,
or when `wsb.build` would go below an already published preview — so bump
`wsb.build` on every push and set `wsb.version` deliberately on releases.

- Gradle wrapper; CI (GitHub Actions) builds a signed APK on every push
  to `main` and publishes it to the `preview` prerelease.
- Local: JDK 17+ (CI uses 21), Android SDK 36, NDK 27+ / CMake. The native
  part (lwIP 2.2.0 + JNI bridge, `app/src/main/cpp/`) builds via
  externalNativeBuild.

```sh
./gradlew testDebugUnitTest assembleRelease
```

## Differences from the iOS version

- `PacketTunnelProvider` → `VpnService` (TUN fd instead of NEPacketFlow);
- `URLSessionWebSocketTask` → OkHttp WebSocket; direct gateway IPs go
  through a custom DNS resolver (SNI/Host/certificate stay domain-based,
  TCP goes to the IP);
- no IPC layer (Darwin notifications, JournalServer): the service and the
  UI share one process, the journal is read directly;
- iOS delegates encrypted DNS to the system (`NEDNSOverTLSSettings` /
  `NEDNSOverHTTPSSettings`); Android implements both transports in-app —
  DoT as TLS with SNI plus DNS-over-TCP framing, DoH via OkHttp — because
  the system private DNS is not available to a `VpnService`. Hostname
  verification is off for DoT: the socket connects to an IP, so the
  certificate cannot match it; integrity comes from TLS and authenticity
  from the trusted IP list;
- network changes and long-session reminders: iOS watches `NWPathMonitor`
  in the tunnel extension and posts `UNUserNotificationCenter`
  notifications; Android uses `ConnectivityManager` callbacks owned by the
  service, with an in-app alert and a restart action;
- the DC index is taken from the destination address and `dc_idx` is
  rewritten in the init (the Android client leaves those bytes random
  without a proxy secret; iOS writes them always).

## License

MIT © 2026 l1ratch. Ported logic —
[Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) (MIT),
lwIP — BSD-like. Android port of
[WSBridge-iOS](https://github.com/l1ratch/WSBridge-iOS).
