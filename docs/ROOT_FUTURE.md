# VOID // APPS: root mode plan (2.0)

Updated: 2026-10-11. Status: **planned, not implemented.** The app is Shizuku-only (shell uid
2000) today and stays fully usable that way. Root is an opt-in layer on top.

The reference phone is rooted with KernelSU and runs Sui. Every phone result in `HANDOFF.md` so
far was measured as **uid 2000**. With Shizuku or Sui running as **uid 0**, some results (A4
uid scope, A5, A6 DND, A7) can differ, so every phone report states the uid shown on the home
screen.

PULSE // BATTERY 1.1.0 already shipped a root mode on the same phone, so several pieces below
are ports of tested code rather than new work: access detection, the `rootAvailable` gate, and
full app data backup and restore (mount namespace, ownership, SELinux labels).

## Rules for the root track

- Root is detected from the real uid of the privileged service (`0`), not from an installed
  root manager. Shell mode stays the default and must keep working on its own.
- Same engineering rules as the rest of the app: quote everything, read every write back,
  refuse protected packages, preview and snapshot before anything destructive, phone probe
  before a control is shown.
- No root feature may be required for a shell feature to work.
- Every root control is labelled as such in the UI, and a control that was not phone-tested
  is labelled as untested.

## Scope

VOID // APPS manages apps. Root is used to make app management work where the shell user is
refused, not to turn the app into a general network or system tool. These ideas from the
owner's older modules are **out of scope** and will not be built here: traffic redirection (NAT,
port forwarding, DMZ, transparent proxy), traffic logging, bandwidth throttling, a raw iptables
console, and a "wipe every firewall rule" action.

## Plan by section

### 0. Foundation
- [ ] Access detection ported from PULSE: provider (Sui or Shizuku), uid, `rootAvailable`.
- [ ] Mode badge on the home screen and in Self-check (`uid 2000` or `uid 0`).
- [ ] Re-run Self-check as uid 0 and record any parser differences.

### A2 Apps
- [ ] Disable for system packages where HyperOS rejects `pm disable-user` as shell: retry as
  uid 0, read back with `pm list packages -d`.
- [ ] Clear data with a real read-back: size of the app's data directory before and after,
  instead of *unverifiable*.

### A3 Snapshots
- [ ] Optional app data archive inside a snapshot (DataVault from PULSE).
- [ ] Undo restores data together with the package state, with per-package read-back.

### Debloat
- [ ] Presets fall back to the uid 0 disable path when the shell path is refused, and the
  review screen says which path will be used.

### A4 Permissions and AppOps
- [ ] Retest uid-scope AppOps writes as uid 0 (CAMERA, CALL_PHONE were silently kept as shell).
  Enable the uid-scope buttons only if the read-back matches.
- [ ] `MIUIOP(n)` modes stay read-only.

### A5 Autostart
- [ ] Fix the open shorthand receiver item first (no root needed).
- [ ] Component disable for system apps where the shell is refused, as uid 0, with read-back.

### A6 Notifications
- [ ] DND access through the secure setting as uid 0, read back from
  `enabled_notification_policy_access_packages`. Ship only if it holds on HyperOS.
- [ ] Notification listener access the same way, where `cmd notification` is ignored.

### A7 Network
- [ ] Per-app block by uid with iptables / ip6tables (`-m owner --uid-owner`) in the app's own
  chain, used when the Chain 3 read-back fails. Read back with `iptables -S` on that chain.
- [ ] Optional re-apply after reboot (opt-in, untested label until a reboot test passes).
- [ ] Separate controls for Wi-Fi and mobile data per app.
- [ ] Remove only the app's own chain on uninstall or reset, never other rules.

### A8 Install and clean
- [ ] Per-app internal cache clean (`cache`, `code_cache`), size before and after.
- [ ] Leftover data of uninstalled apps: list folders whose package is gone, preview with sizes,
  archive first, then delete only what the user selected.
- [ ] ANR and tombstone file cleanup, with the size freed.

### Extract
- [ ] Optional data archive next to the extracted APKs, so an app can be moved with its data.
- [ ] Keystore-bound secrets cannot be moved; say so on screen (same limit as PULSE).

### Console
- [ ] Show which uid a command runs as. Read-only quick commands stay read-only.

## Order

1. Foundation (no behaviour change).
2. A6 DND and A4 uid-scope retests: small, closes open items.
3. Snapshots with data, ported from PULSE.
4. A2 and Debloat uid 0 fallbacks.
5. A8 clean additions.
6. A7 iptables fallback last, because it needs the most phone testing.

Release as **2.0.0 (versionCode 200)** when every shipped control has a phone test.
