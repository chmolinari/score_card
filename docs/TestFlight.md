# Distributing ScoreCard with TestFlight

A practical, ScoreCard-specific guide to shipping beta builds through TestFlight.
Testers install through the TestFlight app — **no cable and no Developer Mode**
required on their device (unlike a direct Xcode/`devicectl` install).

> Last reviewed: 2026-06-06 against Apple's App Store Connect / CloudKit
> documentation. The App Store Connect web UI changes often; treat exact button
> names as approximate.
>
> The ScoreCard-specific facts below (bundle id, team, version, entitlements,
> privacy strings, and the still-absent `ITSAppUsesNonExemptEncryption`) were
> re-verified against this repository on 2026-07-26 and were all still correct.
> The Apple-side procedure has not been re-checked since the date above.

See the [Appendix: Acronyms and abbreviations](#appendix-acronyms-and-abbreviations)
at the end for any term in square-bracket form.

---

## App facts (for reference)

| Item | Value |
|---|---|
| Bundle identifier | `com.christianmolinari.ScoreCardApp` |
| Development team | `2QQ6K7P9C2` |
| iCloud / CloudKit container | `iCloud.com.christianmolinari.ScoreCardApp` |
| Deployment target | iOS 17.0, universal (iPhone + iPad) |
| Current version / build | `1.0` (`1`) |
| Signing | Automatic, capabilities: CloudKit, iCloud Documents, Push (APNs) |

## The one hard prerequisite

A paid **Apple Developer Program** membership (about US$99/year). The Account
Holder must also accept the Paid/Free Apps agreement in App Store Connect before
any app record can be created.

---

## Already in place (verified in this repo)

These need no action — they were confirmed during a project audit:

- Valid 1024×1024, no-alpha **AppIcon** in `ScoreCard/Assets.xcassets/AppIcon.appiconset`.
- **CloudKit + iCloud Documents** entitlements in `ScoreCard/ScoreCard.entitlements`,
  consistent with `NSUbiquitousContainers` in `ScoreCard/Info.plist`.
- Required privacy string `NSLocationWhenInUseUsageDescription` in `Info.plist`
  (the only sensitive API used is `LocationManager`).
- **Automatic signing** on team `2QQ6K7P9C2` for the correct bundle id, Release
  configuration included.
- Clean `GENERATE_INFOPLIST_FILE` + explicit `Info.plist` merge (no duplicate keys).

---

## Step 0 — Fix these first (ScoreCard-specific)

### A. Declare export compliance (one line)

`ITSAppUsesNonExemptEncryption` is currently **absent**, so every TestFlight build
stalls in App Store Connect asking the encryption question until answered by hand.
ScoreCard uses only Apple's HTTPS/CloudKit (exempt under the standard exemption),
so the correct, honest answer is "no". Add to `ScoreCard/Info.plist`:

```xml
<key>ITSAppUsesNonExemptEncryption</key>
<false/>
```

This makes the prompt disappear for all future builds.

### B. Deploy the CloudKit schema to Production (the #1 sync gotcha)

CloudKit has two **isolated** environments per container:

- **Development** — used by Xcode debug builds. Record types/fields are created
  automatically (just-in-time) the first time SwiftData saves them.
- **Production** — used by **TestFlight and the App Store**. It **rejects** any
  record type/field that has not been explicitly deployed.

The classic symptom: sync works perfectly when you run from Xcode, but a TestFlight
tester sees the app work while **nothing syncs** — because Production has no schema.

Before the first TestFlight build:

1. **Populate the Development schema completely.** Either exercise every model in a
   debug run (create a game with participants, score entries, seats, plus players
   and teams), or — more robust — call
   `NSPersistentCloudKitContainer.initializeCloudKitSchema(options:)` once, guarded
   by `#if DEBUG`, so every optional field materialises.
2. **Deploy.** CloudKit Console (`https://icloud.developer.apple.com/`) → CloudKit
   Database → select container `iCloud.com.christianmolinari.ScoreCardApp` →
   **Deploy Schema Changes** → review the diff → **Deploy**. (Deploying copies
   record types, fields, and indexes — **not** any records.)
3. **Re-deploy after every model change.** Any new `@Model` type, attribute, or
   relationship in `ScoreCardSchema.models` exists only in Development until you
   deploy again. Production accepts **additive** changes only — never rename or
   remove a field already in Production (this is exactly why every attribute has a
   default and every relationship is optional).

> **A note on `aps-environment = development`** in the entitlements: this is
> *informational only* and does **not** block upload. Xcode's automatic signing
> injects the correct value at archive time, and TestFlight builds use the
> **production** APNs gateway regardless. Leave automatic signing to manage it;
> do not hardcode `production`.

---

## Step 1 — One-time account setup

1. **App ID.** Developer portal → Certificates, Identifiers & Profiles →
   Identifiers. Confirm `com.christianmolinari.ScoreCardApp` exists with **iCloud**
   (CloudKit + the container) and **Push Notifications** capabilities enabled.
2. **App record.** App Store Connect → Apps → **＋ → New App**. Set platform iOS,
   app name, primary language, the registered **Bundle ID**, and an **SKU**
   [Stock Keeping Unit — a private tracking string you invent, e.g.
   `SCORECARD-001`; never shown to users].

## Step 2 — Archive and upload

1. In Xcode pick **Any iOS Device (arm64)** (not a simulator) → **Product ▸ Archive**.
2. In the Organizer: select the archive → **Distribute App** → **App Store Connect**
   → **Upload** → keep **Automatically manage signing** → **Upload**.
   - *CLI alternative:* `xcodebuild -exportArchive …` to produce a signed `.ipa`,
     then upload with **Transporter** or
     `xcrun altool --upload-app -f App.ipa -t ios --apiKey <KEY_ID> --apiIssuer <ISSUER_ID>`
     (App Store Connect API key auth). Note: `notarytool` is for Developer-ID/Mac
     notarisation, **not** App Store/TestFlight uploads.
3. **Wait for processing.** Builds are not instant (minutes to ~an hour); an email
   arrives when the build appears in the **TestFlight** tab.

> **Build numbers must be unique per version.** `1.0 (1)` is fine for the first
> upload. For every later upload, bump `CURRENT_PROJECT_VERSION` (Debug *and*
> Release) — e.g. via Xcode's General tab or `agvtool next-version -all` — or the
> upload is rejected as a duplicate.

## Step 3 — Add testers

| | **Internal** | **External** |
|---|---|---|
| Who | People on your App Store Connect team (with a role) | Anyone, by email or public link |
| Limit | 100 | 10,000 |
| Review? | **None** — installable right after processing | **Beta App Review** on the *first* build (~a day) |
| Test Information needed? | No | Yes (beta description + feedback email) |

- **Internal:** TestFlight → Internal Testing → ＋ group → **Invite Testers** (only
  selectable from existing team users). Optional **Automatic distribution** pushes
  every new processed build immediately. Best for your own fast smoke test.
- **External:** create an external group → **Add Builds** (the first build to any
  external group triggers Beta App Review) → invite via **Public Link**
  (`testflight.apple.com/join/…`, optionally filtered by device/OS and capped) or by
  email / CSV [Comma-Separated Values] import. You must create at least one internal
  group before external testing is available.

## Step 4 — How testers install

Each tester installs the free **TestFlight** app, opens your invite link (or email
invite → "View in TestFlight"), then **Accept → Install**. They can submit feedback
and screenshots in-app (surfaced in App Store Connect). Each build is testable for
**90 days**, then expires — ship a fresh build to keep testers going.

---

## Quick checklist

1. ☐ Add `ITSAppUsesNonExemptEncryption = false` to `Info.plist`.
2. ☐ Confirm the App ID has **iCloud** + **Push Notifications** capabilities.
3. ☐ **Deploy the CloudKit schema to Production** (and re-deploy after model changes).
4. ☐ Create the App Store Connect app record (with an SKU).
5. ☐ Archive → upload → wait for processing.
6. ☐ Add internal testers (instant) and/or external testers (first build reviewed).
7. ☐ Bump the build number for every subsequent upload.

## Common pitfalls

- **Schema never promoted** — sync works in debug but TestFlight testers see no data.
  Always deploy the schema to Production first.
- **Forgetting to re-deploy after a model change** — new data silently fails to sync.
- **Reusing a build number** — the upload is rejected; always bump it.
- **Testing sync on the Simulator** — simulators do not deliver push notifications,
  so cross-device sync looks broken. Verify on real hardware, signed into iCloud.
- **External testing before internal** — you must create an internal group first.

## References

- [TestFlight overview](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview/)
- [Add internal testers](https://developer.apple.com/help/app-store-connect/test-a-beta-version/add-internal-testers/)
- [Invite external testers](https://developer.apple.com/help/app-store-connect/test-a-beta-version/invite-external-testers/)
- [Upload builds](https://developer.apple.com/help/app-store-connect/manage-builds/upload-builds/)
- [Overview of export compliance](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance/)
- [Deploying an iCloud Container's Schema](https://developer.apple.com/documentation/cloudkit/deploying-an-icloud-container-s-schema)
- [`initializeCloudKitSchema`](https://developer.apple.com/documentation/coredata/nspersistentcloudkitcontainer/3343548-initializecloudkitschema)
- [`aps-environment` entitlement](https://developer.apple.com/documentation/bundleresources/entitlements/aps-environment)

---

## Appendix: Acronyms and abbreviations

| Term | Meaning |
|---|---|
| API | Application Programming Interface |
| APNs | Apple Push Notification service |
| arm64 | 64-bit ARM processor architecture |
| CLI | Command-Line Interface |
| CSV | Comma-Separated Values |
| ID | Identifier |
| iOS | iPhone/iPad operating system |
| IPA | iOS App Store Package (the `.ipa` app archive) |
| SKU | Stock Keeping Unit |
| TLS | Transport Layer Security (the encryption behind HTTPS) |
| UI | User Interface |
