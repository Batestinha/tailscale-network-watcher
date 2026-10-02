# Tailscale Network Watcher

Native Android replacement for the disabled Termux/rish watcher. The app keeps two Android
network callbacks registered in a low-priority `specialUse` foreground service and otherwise
sleeps. It does not poll, schedule jobs or alarms, run a resident shell, or acquire a wake lock.

The listener is disabled by default. Once enabled in the activity, it establishes the first
validated non-VPN network as a baseline. A different best network must remain validated for three
seconds before one Tailscale restart is requested. Shizuku work is retained for at most five
minutes and is resumed only by binder/permission/network events.

During a restart the app verifies Tailscale's exported connect receiver, creates a non-daemon
Shizuku UserService, checks `always_on_vpn_app`, and invokes fixed argument-array `am` commands.
The UserService is removed immediately after its one AIDL transaction. After any force-stop
attempt, a persisted marker remains until a VPN callback identifies a replacement network handle.
One direct reconnect is attempted
after 15 seconds, followed by one final 15-second wait and a warning; there is no retry loop.

## scrcpy Manager provisioning contract

Version 2.2.0 exposes two broadcasts protected by `android.permission.DUMP`:

- `dev.local.tailscalenetwatch.action.STATUS`
- `dev.local.tailscalenetwatch.action.ENABLE`

ADB shell/root receives protocol-versioned JSON in the ordered-broadcast result
data. `ENABLE` persists the enabled state and starts the foreground watcher;
ordinary Android applications cannot invoke either endpoint.

## Build

The project uses Gradle 8.11.1, Android Gradle Plugin 8.8.0, Java 17, compile/target SDK 36,
min SDK 29 (Android 10), and Shizuku API/provider 13.1.5. Android 10 and 11 use a passive
multi-network callback with deterministic best-network selection; Android 12 and newer use the
platform best-matching callback.

```sh
./gradlew test assembleRelease
```

The release APK is `app/build/outputs/apk/release/app-release.apk` and is signed with the preserved
local `netwatch` key when `.signing/netwatch.keystore` and `.signing/netwatch.properties` are
present. Copy `.signing/netwatch.properties.example` to the ignored `netwatch.properties` file and
provide the local key credentials. CI can instead use `NETWATCH_STORE_FILE`,
`NETWATCH_STORE_PASSWORD`, `NETWATCH_KEY_ALIAS`, and `NETWATCH_KEY_PASSWORD`. Without signing
credentials, Gradle produces an unsigned release APK and uses the standard debug key for debug
builds. Package/version: `dev.local.tailscalenetwatch`, code 6, name 2.2.0.

## Device acceptance

Keep the listener disabled until all device-specific checks pass:

1. Exercise Wi-Fi/cellular/USB/Ethernet handovers and verify one VPN process replacement per
   stable transition.
2. Stop Shizuku, hand over, and start Shizuku inside and outside the five-minute window.
3. Reboot and verify baseline establishment does not cycle Tailscale.
4. Confirm no app wake lock, scheduled job/alarm, Termux/rish monitor, or lingering UserService.
5. Complete a 30-minute idle CPU/power soak.

The existing `.termux/boot/tailscale-network-watch` rollback entry remains an immediate `exit 0`.
