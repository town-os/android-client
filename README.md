# Town OS Android client

Connects an Android phone to a Town OS network over WireGuard, and — the part
that actually takes care — makes the box's DNS work once it is connected.

**Source:** <https://github.com/town-os/android-client>

## Quick start

```bash
make deps      # host packages, Gradle, and the Android SDK (all repo-local)
make debug     # build the APK
make install   # adb install to a connected device
```

`make deps` is idempotent and installs everything into the checkout
(`.android-sdk/`, `.gradle-dist/`) rather than a system path, so two checkouts
never fight over one SDK. It works on Arch, Fedora, and Debian/Ubuntu.

`make help` lists every target. `make test` runs lint first and stops on the
first failure — there is no point testing code that does not lint.

## Building on ARM (Apple Silicon, Raspberry Pi)

This works out of the box, but it is worth knowing why, because the failure mode
is otherwise baffling.

`aapt2` — the Android resource compiler — is the **only** native binary in an
Android build (Gradle, Kotlin, D8/R8 and apksigner are pure Java). Google ships
it for **x86_64 Linux only**; there is no linux-arm64 build on Google Maven. So
on ARM it must run under **FEX** emulation.

FEX has one hard requirement: a **4K page size**, because x86 software assumes 4K
mmap granularity. That is fine on most ARM hosts — but Apple Silicon supports
both page sizes, and Fedora Asahi's default `kernel-16k` uses 16K pages, where
FEX aborts instantly with `<jemalloc>: Unsupported system page size`.

`make/emulation.sh` detects this and picks the right route automatically:

| Host | Route |
| --- | --- |
| x86_64 | native |
| aarch64, 4K pages | FEX via `binfmt_misc` — transparent |
| aarch64, 16K pages | wrapped in **`muvm`**, which boots a 4K-page microVM to host FEX |

The 16K path is verified working on an M1 under Asahi: x86_64 `aapt2` reports
`Android Asset Packaging Tool (aapt) 2.19` from inside muvm, exit codes
propagate, and the full APK builds. No kernel change is needed.

Two muvm quirks the Makefile handles, both of which look like something else:

- **`RUST_LOG` must be set** or muvm prints *nothing* from the guest — a build
  appears to hang, then silently "succeed".
- **The `--` separator is mandatory.** muvm has its own `-p` (port-forward), so
  Gradle's `-p <project dir>` gets eaten and muvm dies with
  ``Failed to start `passt` / invalid digit found in string`` — which looks like
  a networking bug and is not one.

`make check-aapt2` verifies the whole chain and explains itself if it cannot.

## Installing on a phone

```bash
make install    # builds the debug APK and adb-installs it
make logs       # logcat, filtered to just this app
make devices    # what adb can see
make uninstall
```

On the phone: Settings → About → tap **Build number** seven times, then
Developer options → **USB debugging**. Plug in over USB and accept the
"Allow USB debugging?" prompt.

There is no emulator path. The app opens a `VpnService` and its whole purpose is
DNS behaviour on a real network, so an emulator would not tell you anything you
want to know.

**ARM hosts need a native `adb`.** Google ships platform-tools for x86_64 Linux
only, so the SDK's `adb` is an x86_64 binary — and because binfmt hands it to
FEX, it *appears* to work while actually running under emulation. adb starts a
long-lived background server and talks to USB, so that is slow and fragile.
`make deps` installs the distro's native adb (`android-tools`), and `make
install` refuses to use the emulated one.

## What the app does

1. **Log in** to a box (`POST /account/authenticate`, port 5309, plain HTTP).
   Enrolling a device is an admin-only operation in Town OS, so this needs admin
   credentials — there is no device-enrollment or invite flow today.
2. **Join a network** (`POST /networks/peers/add`). The keypair is generated
   *on the phone* and only the public half is sent. If you let the box generate
   it, the box returns your **private key** in the HTTP response, in the clear.
3. **Bring up the tunnel** with the WireGuard Go backend, using the wg-quick
   config the box returns.
4. **Wire DNS** so Town OS names resolve. See below — this is the whole point.

## How DNS actually works here, and what the app does about it

Two facts about rolodex (the box's resolver) drive the entire design.

**It answers by source IP.** rolodex is split-horizon. A query arriving from an
overlay address (Town OS derives every WireGuard subnet from `10.64.0.0/10`)
gets that network's *scoped* view: the network's own TLD, with answers pointing
at overlay addresses. A query from anywhere else gets the *global* view, with
answers pointing at the box's LAN address. An overlay source that is not joined
to a scope is `REFUSED` outright.

So our DNS queries **must leave through the tunnel**. If they go out over Wi-Fi
instead, we get LAN addresses we cannot route to — or nothing at all.

**It only listens where it is bound.** On a real box
(`install/scripts/rolodex-config.sh`) rolodex binds `127.0.0.2`, `::1`, and every
global-scope address on the **default-route interface** — e.g. `192.168.122.50`
on the QEMU dev VM. The WireGuard interface is not the default route, so the
overlay `.1` address that the peer config's `DNS =` line points at is not
necessarily bound.

Those two pull in opposite directions, and reconciling them is what
`TunnelConfigBuilder` exists to do:

- The resolver address is **configurable** (`dnsServerOverride`), defaulting to
  the `DNS =` line the box handed us. Point it at whatever address rolodex is
  actually bound on.
- **Whatever address you choose, the app routes it into the tunnel** — it adds a
  host route (`/32`, or `/128` for v6) for every DNS server to the peer's
  `AllowedIPs`. For the overlay `.1` that is a no-op; it already sits inside the
  overlay `/24`. For the box's LAN address it is the whole ballgame: the query
  goes through the tunnel, arrives with our overlay source IP, and rolodex
  applies the scoped view.

The network's TLD is installed as a **search domain**, so a bare `gitea` resolves
as well as the full `gitea.default.<tld>`.

### The thing that will bite you: Android Private DNS

If Android's Private DNS is set to a **provider hostname** (strict DoT), *every*
lookup goes to that provider and the DNS servers a VPN installs are ignored
entirely. Town OS names exist only in the box's resolver, so they come back
NXDOMAIN — with a perfectly healthy tunnel. It is the app's most confusing
failure mode, and no app can override the setting; only the user can.

So the app makes it loud:

- **A non-dismissible in-app alert**, styled as an error, naming the offending
  provider, with a button that opens the Private DNS settings screen directly.
- **A system notification** while the tunnel is up and Private DNS is strict —
  because the failure is actually hit in a *browser*, not while looking at this
  app. Tapping it goes to the same setting.
- **Live monitoring** via a `LinkProperties` network callback, not a one-shot
  check at connect: the user can flip the setting while connected (we are asking
  them to), and the warning clears itself the moment they do.

Only strict mode warns. **Automatic (opportunistic) is fine** and must not warn —
it only upgrades the *current network's own* resolvers to DoT and falls back to
plaintext to those same resolvers, so queries still reach the box. The
distinguishing signal is subtle (opportunistic reports `isPrivateDnsActive=true`
with a **null** `privateDnsServerName`), so `PrivateDnsCheck.classify` is pinned
by tests in both directions — a false warning is nearly as bad as a missing one.

## Certificates: the CA, and the pins in DNS

Every package is served over HTTPS by the box's shared `:443` ingress, with a
leaf issued by the box's **own CA**. No Android trust store knows that CA.

The app handles this two ways:

- It fetches the CA from `GET /tls/ca.crt` (unauthenticated — a CA cert is public
  by definition) and adds it as an **extra** trust anchor alongside the system
  ones, so ordinary internet HTTPS keeps working. The SHA-256 is pinned on first
  contact and a later **change** is surfaced.
- It reads the **DANE TLSA pins the box publishes in DNS**. For a package at
  `<pkg>.<repo>.<tld>` on :443 the box writes:

  ```
  _443._tcp.<pkg>.<repo>.<tld>.  IN TLSA  3 1 1 <hex sha256 of the leaf SPKI>
  ```

  — usage 3 (DANE-EE), selector 1 (SubjectPublicKeyInfo), matching 1 (SHA-256).
  These are dual-homed exactly like the A records: a scoped copy for overlay
  peers and a global copy for the LAN, so they must be queried *through the
  tunnel* to match what you will actually connect to. `DaneVerifier` does the
  lookup and the comparison.

**Be honest about what DANE buys you here.** Town OS publishes no DNSSEC chain
for its private TLD, so a TLSA record is exactly as trustworthy as the resolver
that served it. Since that resolver is the box, reached over an authenticated
WireGuard tunnel, that is a defensible place to put trust — but it is trust in
the tunnel, not in DNSSEC. Fetching the CA is likewise trust-on-first-use.

## Known server-side gap

The box's peer config advertises `DNS = <overlay .1>`, but nothing in either
repo binds a DNS socket to that address: rolodex binds the default-route
interface, and rolodex's per-scope ingress-listener feature
(`AddScopeTldWithListener`) is never called from Town OS. Until that is wired up
server-side, set the resolver override to an address rolodex is actually bound
on (the box's LAN address); the app will still route it through the tunnel, so
split-horizon keeps working.

Verify with `dig @<overlay .1> <pkg>.<repo>.<tld>` from a connected peer.

## Layout

| Path | What |
| --- | --- |
| `vpn/TunnelConfigBuilder.kt` | The DNS wiring. The one file to read. |
| `vpn/TunnelController.kt` | WireGuard `GoBackend` up/down, key generation. |
| `dns/DaneVerifier.kt` | TLSA lookup + SPKI pin comparison. |
| `dns/PrivateDnsCheck.kt` | Detects strict Private DNS, which silently breaks everything. |
| `net/TownOsTrust.kt` | Box CA as an extra trust anchor. |
| `api/TownOsApi.kt` | Control-plane client (:5309). |

## License

GNU Affero General Public License v3.0 — see [LICENSE](./LICENSE). Same license
as Town OS itself.
