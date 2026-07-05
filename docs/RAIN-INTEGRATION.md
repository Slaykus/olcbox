# Rain integration (olcRTC × Remnawave) — roadmap

Goal: make olcbox connect through Rain's orchestrator-managed nodes with per-user
Remnawave accounting. Proven end-to-end already via a CLI reference client
(see the orchestrator repo `client/reference-cli/`): the ONLY thing olcbox lacks
is an **inner VLESS layer** — olcbox gives `olcrtc cnc -> local SOCKS`, and we must
run a per-user VLESS through that SOCKS so the node's Xray inbound accounts traffic.

## Data path we are building

```
device traffic
 -> (Android VpnService/TUN via hev-socks5-tunnel | desktop proxy/PAC)
   -> xray/sing-box  VLESS-out (per-user UUID)          # NEW: identity + accounting
        outbound detour / dialerProxy ->
      olcrtc cnc SOCKS5 (already produced by olcbox)     # WebRTC tunnel
        -> Jitsi room (Rain node) -> srv -> node VLESS inbound 127.0.0.1:<port>
          -> internet (exits at node IP)
```

Today olcbox wires `TUN/proxy -> olcrtc SOCKS` directly. We insert the VLESS
engine in the middle: `TUN/proxy -> xray SOCKS -> (dialer) -> olcrtc SOCKS`.

## Seam in the code

- `sharedUI/.../data/model/LocationConfig.kt` — one Location = one room on one
  provider. Fields: `id` (room, e.g. `https://<server>/<room>`), `key`, `provider`
  (`jitsi`), `transport` (`datachannel`). **Add optional Rain fields**: `vlessUuid`,
  `vlessPort` (empty for normal olcbox use).
- `sharedUI/.../vpn/desktop/OlcRtcCommand.kt` + Android VPN manager — where the
  olcrtc SOCKS is created and TUN/proxy is pointed at it. This is where we start
  the VLESS engine and re-point TUN/proxy to it.
- olcrtc itself is built as a gomobile AAR (`sharedUI/olcrtc-bin`, from
  `OLCRTC_REPO/mobile`). The VLESS engine is added the same way (second lib).

## Rain subscription -> Locations

Rain's `sub.md` line:
`olcrtc://jitsi/datachannel?room=<r>&servers=<csv>&key=<hex>&vless=<uuid>&port=<n>`
maps to **one Location per server** (same room+key+vless+port, `id =
https://<server>/<room>`). Multiple servers/lines = the failover set olcbox already
selects across. Subscription delivery for MVP: **fetch by HTTPS URL** (Rain will
expose the orchestrator `serve` endpoint behind TLS); parse + `#refresh` interval.

## VLESS engine choice (decide at M2)

- Option A: **sing-box Libbox** (official gomobile lib; outbound `detour` routes a
  VLESS outbound through a local `socks` outbound). Cleanest mobile embedding.
- Option B: **xray-core** (AndroidLibXrayLite, as v2rayNG). This is exactly what the
  CLI reference proved (`vless-out` + `sockopt.dialerProxy` -> socks outbound).
Both work; leaning A for mobile ergonomics. Bundle size: olcrtc(~31MB)+engine.

## Milestones (I push to main; you build+test on device)

- **M1**: map a Rain node's room into a Location; connect; olcrtc SOCKS up; tunnel
  reaches srv. No accounting yet. Proves app<->Rain-srv rendezvous.
- **M2**: embed the VLESS engine; insert into the data path; verify exit IP = node
  and Remnawave counts traffic per-user. (= the reference client, in-app.)
- **M3**: subscription fetch by URL + multi-Location failover + auto-refresh + pool
  rotation pickup.

## Your prerequisite (gate)

Build + run the UNMODIFIED fork on a device first: JDK 17, Android Studio + SDK +
NDK, Go 1.26 + gomobile (`gomobile init`), `openlibrecommunity/olcrtc` checked out
(or `OLCRTC_REPO`), then `./gradlew :androidApp:assembleDebug`. Once that connects,
the toolchain is proven and integration work can be built/tested.
