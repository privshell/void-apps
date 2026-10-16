# VOID // APPS: roadmap and remaining work

Updated: 2026-10-11. See `docs/HANDOFF.md` for the narrative and evidence, and `docs/ROOT_FUTURE.md` for the root mode plan.

## Product goal

A safe, reversible, local Android package manager built with Kotlin, Compose, and Shizuku UserService. It never pretends a shell command succeeded when the ROM rejected it. Shizuku is the only execution path today. A root mode is planned for 2.0 in `docs/ROOT_FUTURE.md`; Dhizuku is out of scope.

## Complete

- M0 core, Shizuku connection, UserService/AIDL, ExecBridge, console, CI.
- A1 inventory, protected guard, parsers, details, real-device counts.
- A2 single-package Suspend/Unsuspend, Disable/Enable, Force stop, Remove/Restore, Clear data, read-back.
- A3 snapshots, Restore/Undo, JSON Import/Export, Pins, batch operations. Acceptance test passed on the phone.
- Debloat track: knowledge base, SAFE-only presets, review screen, compact disclaimer, search in preset list and package list, cross-manager restore via `pm install-existing`, one snapshot per batch, sequential read-back. CI and phone acceptance passed.
- A4 permissions and AppOps. Phone-verified on the reference phone (see HANDOFF).
- A7 per-app network block (Chain 3) and background data (netpolicy). Phone-verified on user, system and protected apps (see HANDOFF).
- A8 installer (APK / APKS / XAPK, OBB, Inspect, extract) and cleaner (cache, empty folders, running apps). Phone-verified by the owner.
- 1.0.0 released: README, CHANGELOG, fastlane metadata, store images, F-Droid metadata draft, release guide, signed APK, tag `v1.0.0` on `main`.
- Old Cyber App Manager WebUI module removed from the tree and its old releases removed.
- `ci.yml` leftovers from the old project fixed.

## A5 autostart: partly done, one open item

Done and phone-verified:

- Read-only boot receiver audit from `dumpsys package` (BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, QUICKBOOT_POWERON, MY_PACKAGE_REPLACED), Receiver Resolver Table only.
- Background execution state (RUN_IN_BACKGROUND, RUN_ANY_IN_BACKGROUND) shown in the card; changes go through the A4 AppOps card.
- Component Disable/Enable round trip for a receiver whose class is outside the app package (`com.thirtytwo.steps/androidx.profileinstaller.ProfileInstallReceiver`): APPLIED both ways.

### Open: component control for shorthand receivers (`pkg/.Cls`)

Status: deferred by the owner.

- Symptom: `.BootReceiver` / `.StartOnBootListener` rows keep showing **Disable** while the repository reports "component is already disabled". The card and the repository disagree.
- Seen on three apps. On `com.eyalm.adns/.services.BootReceiver` the repository returned APPLIED for Disable, then "already disabled", while the row stayed on Disable: the device state is right, the card check is wrong.
- Cause (code review 2026-10-05): the card calls `isComponentDisabled(receiver.component, set)` in `AutostartAudit.kt` with the shorthand name, while `queryDisabledComponents` returns fully qualified `pkg/pkg.Cls` names. None of its comparisons build `pkg/pkg.Cls` from `pkg/.Cls`. Fix: compare `expandComponentName()` of both sides, plus a unit test for `pkg/.Cls` against `pkg/pkg.Cls`.
- Device state: `com.thirtytwo.steps/com.thirtytwo.steps.BootReceiver`, `ch.abertschi.adfree/ch.abertschi.adfree.StartOnBootListener` and `com.eyalm.adns/com.eyalm.adns.services.BootReceiver` were disabled during testing. They can be re-enabled from the console with `pm enable <pkg>/<full.Cls>`.
- Acceptance when fixed: Disable then Enable on `.BootReceiver` both APPLIED, and the row button flips each time.

## A6 notifications: partly done, one open item

Implemented from `privshell/void-pulse`, per-app only:

- Posting notifications (mute): POST_NOTIFICATIONS through the A4 permission path; phone-tested and working. Allow/Revoke read-back matches Android.
- Listener section: apps with no listener service correctly show the empty state. Listener allow/revoke remains available for apps that declare one.
- DND access: `cmd notification allow_dnd/disallow_dnd` returns exit 0 on HyperOS but the secure setting does not change. The UI correctly reports **NOT_APPLIED** and warns that Android silently ignored it.

### Open: DND access control on HyperOS

Status: deferred by the owner.

- Evidence: `cmd notification allow_dnd 'com.thirtytwo.steps'` returned exit 0, but `enabled_notification_policy_access_packages` remained unchanged (`before: not granted`, `after: not granted`).
- The app does not claim DND access was granted when the read-back disagrees.
- Next step: the uid 0 path in `docs/ROOT_FUTURE.md` (section A6). No automatic fallback is trusted until it is phone-tested.

## A7 network: done

Ported from `privshell/VOID-WALL`, per-app, no root. Block, unblock, background restrict and allow were all APPLIED on the phone, with read-back from `cmd connectivity get-package-networking-enabled` (output format confirmed: the card shows "read from connectivity"). A blocked browser really lost DNS. Shared uids show the warning, protected and system-uid apps show no buttons. Reboot persistence of Chain 3 rules is still worth a look later, but the owner accepted the section.

## A8 installer + cleaner: done

One home screen, **Install & clean**, with three tabs, plus an **Extract APK** card in app details.

- Install (from `privshell/pulse-install`): shell-side folder browser (default `/sdcard/Download`), APK / APKS / XAPK selection, Inspect (package, label, version, SDK, ABIs, launcher, permissions, SHA-256, installed version, downgrade warning) with a native AXML reader, options `-r` / `-g` / delete after install, several loose APKs as one split session, auto-install folder `/sdcard/pulse-install/auto` with move to `installed/`, local history. Streaming install: `pm install-create`, `sz=$(stat -c%s f) && cat f | pm install-write -S "$sz" <id> <split> -`, `pm install-commit`, abandon on write error. XAPK `manifest.json` `split_apks`, `universal.apk` first, OBB copy to `/sdcard/Android/obb/<pkg>/` with size check.
- Install read-back: package read from the APK manifest; `versionCode` / `lastUpdateTime` from `dumpsys package` before and after. APPLIED only when Android said `Success` **and** the installed version or update time changed.
- Extract: `pm path`, copy to `/sdcard/Download/pulse-extracted/<pkg>_<version>/`, size check per file; optional `.xapk` with generated `manifest.json` when `zip` exists, checked with `unzip -l`.
- Left out on purpose: APKM, VirusTotal and the AI assistant (both need INTERNET, which this app does not have).
- Clean (from `privshell/void-purge`): `pm trim-caches 999999999999` with free space on `/data` measured before and after; empty folders via `find <root> -depth -type d -empty`, removed with `rmdir` only and read back with `[ -e ]`; `/sdcard` itself and system roots are refused.
- Running apps (void-purge, fixed): the original stopped every third-party package in a background subshell with its output discarded, so it listed nothing and hid every failure. Now live processes come from `ps -A -o USER,PID,NAME` (fallback `ps -A`), grouped by package; `am force-stop --user 0 <pkg>` one by one in the foreground; `ps` read again after 1.2 s. Protected apps (this app, Shizuku, launcher, keyboard, dialer, SMS, WebView, static core list) cannot be selected.
- Left out of void-purge on purpose: orphaned files, log files, duplicate files and every root tool (the useful root ones are in the 2.0 plan).

## Then

| Step | Where | What |
|---|---|---|
| F-Droid | gitlab.com/fdroid/fdroiddata | merge request with `docs/fdroid/io.github.kreza6173pixel.voidapps.yml` |
| awesome-shizuku | github.com/timschneeb/awesome-shizuku | pull request with the entry in `docs/RELEASE.md` section 5 |
| Branch protection | GitHub settings | on `main`: block force push and deletion; no required reviews (single maintainer) |
| A5 shorthand receivers | this repository | fix described above, then a phone test |
| In-app About screen | this repository | not built yet; README covers it for 1.0 |
| Root mode 2.0 | see `docs/ROOT_FUTURE.md` | section-by-section plan |

## Gates

No INTERNET permission. Protected packages are never overridden. No destructive batch without preview and snapshot. No support claim without a device probe. No release claim while CI or device acceptance is red. Code and its strings/resources always land in one commit. Shell filters are measured on the phone before they are relied on. A write control is shown only where a phone test showed Android accepts it. ROM-specific support comes from issue reports with phone evidence. APKM stays out of scope. Root features follow the rules in `docs/ROOT_FUTURE.md`.
