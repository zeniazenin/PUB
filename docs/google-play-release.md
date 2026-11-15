# Publishing PUB on Google Play

A step-by-step plan for listing PUB as a free app, under LTTL LLC or a personal account. It reflects Google Play's rules as of November 15, 2026. Check Play Console as you go, because it shows the current requirements.

## Where PUB stands today

Already in place:

- **Android 16.** PUB targets and compiles against API 36 (AGP 8.10.1). The CI workflow installs the API 36 SDK. It already draws edge-to-edge and uses `BackHandler`, which works with predictive back.
- **Release signing.** `bundleRelease` signs with your upload key, read from settings outside the repository. It refuses to build without the key or a report endpoint. A test bundle signed with a throwaway key built and verified on November 15, 2026. Its native libraries (CameraX and AndroidX graphics) are 16 KB-aligned.
- **In-app reporting.** **Report content** on the build result, **Report research** on the Research card and **Report app** in each saved app's long-press menu open a form that sends the report to your endpoint without leaving PUB. The endpoint is `tools/report-endpoint`.
- **Privacy policy.** A draft is in `docs/privacy-policy.md`, linked from Settings → Privacy policy and from Help & guide.
- **Open-source license.** PUB is released under the BSD Zero Clause License (0BSD, in `LICENSE`). It's the most permissive OSI-approved license: anyone may use, change and share the code for any purpose, without even having to credit you.
- The `release` build type is R8-minified and shrunk. The `benchmark` variant uses the same settings, so test the release build with it.
- `allowBackup=false` and a launcher icon.
- No ads, analytics or tracking SDKs, and no `AD_ID` permission.
- No permissions that need a Play declaration form, apart from the foreground service. Location is foreground-only, and there is no SMS, call log, all-files or package-visibility access.
- "Continue with ChatGPT", "Using ChatGPT plan" and a "Manage usage" link in Settings.

Left for you before the first upload:

| # | Task | Where |
|---|------|-------|
| 1 | Test on an Android 16 phone. Install the `benchmark` build and go through sign-in, a build, preview, save, permissions, back gestures and the report form. This hasn't been done on a phone yet. | Step 2 |
| 2 | Create your upload key and add the signing settings | Step 2 |
| 3 | Deploy the report endpoint and add its URL | `tools/report-endpoint/README.md` |
| 4 | Review the privacy policy draft, then push it or host it on the LLC website | Step 3 |

Should be settled before launch:

| # | Item | Why |
|---|------|-----|
| 5 | ChatGPT plan usage eligibility | OpenAI's docs describe the dynamic, no-client-ID sign-in flow PUB uses as being "for open-source and locally hosted apps", and send paid or remotely hosted apps to an interest form. PUB is free, runs on the phone and is open source under 0BSD, which fits. Consider confirming with OpenAI through the interest form. If OpenAI disables the client, the builder stops working for every user. |
| 6 | Package name `app.pub` | It becomes permanent on first upload. It appears to be free (no public Play listing), and Play tells you on upload if it's taken. If you'd rather tie it to the LLC (for example `com.lttl.pub`), change `applicationId` before the first upload. Only your own test installs lose their data. |

## Step 1: Choose the account type

| | LTTL LLC (organization) | Personal |
|---|---|---|
| Fee | $25 one-time | $25 one-time |
| Extra paperwork | D-U-N-S number for the LLC (free from Dun & Bradstreet, can take up to 30 days), business payments profile, official business phone, website | ID and address verification |
| Shown publicly on Google Play | Developer name, the LLC's legal name, **D-U-N-S number, legal address**, developer email and **developer phone** | Developer name, **your legal name and country**, developer email. Your full address appears only if you sell something. PUB is free, so it won't. |
| Before you can publish to production | Nothing extra | **A closed test with at least 12 testers opted in continuously for 14 days**, then a production-access application (Google usually answers within 7 days) |
| Liability | Sits with the LLC | Sits with you personally |
| Switching later | Organization can't change back to personal | Personal can move to organization |

**Recommendation: LTTL LLC.** It skips the 12-tester, 14-day gate, keeps liability with the LLC, and is the only choice you can't easily switch into later. The trade-off is that the LLC's address and a phone number become public. If the LLC is registered at your home, use a registered-agent or business address, and use it consistently in D&B and the payments profile.

### If you choose LTTL LLC

1. **Get or find the D-U-N-S number.** Search for "LTTL LLC" on Dun & Bradstreet's free D-U-N-S lookup or request page (dnb.com). Make sure the legal name, address and phone in D&B are current and match your state filing exactly. Google compares them.
2. **Prepare** a website for the LLC (one page that links to the privacy policy is enough), an official business phone number, a public support email (for example `support@<your domain>`), and the business bank account for the payments profile.
3. **Create the developer account** at play.google.com/console with a Google account you'll keep for the business, with 2-Step Verification turned on. Choose **An organization or business**. Create the payments profile as a business with the exact D&B name and address. Pay the fee and complete the phone, email and identity verification. Bank verification can take up to 5 days.
4. Add yourself (or others) as users with roles, rather than sharing the account's password.

### If you choose personal

1. Create the account at play.google.com/console and choose **Yourself**. Verify your legal name, address, contact email and phone, then pay the fee.
2. Line up **12 or more testers** now (friends or family with Android phones and Google accounts). They need to stay opted in for 14 days in a row, and Google also looks at whether they actually use the app.

## Step 2: Prepare the app

- [x] **Target API 36**: done (AGP 8.10.1, SDK 36, CI updated).
- [x] **Report form for AI output**: done. You still need to deploy the endpoint (below).
- [x] **Privacy policy link** in Settings and Help & guide: done.
- [x] **Release signing**: configured. You still need to create the key (below).
- [ ] **Test on an Android 16 phone.** On Android 16, apps can't opt out of edge-to-edge, predictive back is on by default, and orientation and resizability locks are ignored on tablets and foldables.
- [x] **License file**: done (0BSD, see item 5 above).
- [ ] **Package name**: keep `app.pub` or change `applicationId` (item 6).
- [ ] **Version**: every upload needs a higher `versionCode` (currently 11). Consider bumping `versionName` to 1.0.0 for launch.

### Deploy the report endpoint

Follow `tools/report-endpoint/README.md`. It takes about 10 minutes and uses a Google Sheet in the account you publish with. Copy the web app URL, which ends in `/exec`.

### Create your upload key (once, and back it up)

From the project folder in PowerShell, using the JDK bundled in `.toolchain`:

```powershell
New-Item -ItemType Directory -Force "$HOME\keys"
```

```powershell
& ".\.toolchain\jdk-17.0.21.0.2+1\bin\keytool.exe" -genkeypair -v -keystore "$HOME\keys\pub-upload.jks" -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

Use the LLC (or your) name when it asks for the organization. Store the `.jks` file and both passwords in a password manager and in an offline backup. Never commit it (`*.jks` is already in `.gitignore`). With Play App Signing, Google holds the key that signs what users install. Yours only signs uploads, so if you lose it, Google can reset it.

Next, add the settings to `.toolchain\gradle-home\gradle.properties`. Create the file if it doesn't exist. It's Gradle's user home for `tools\dev.ps1`, and Git ignores it. Use forward slashes in the path, and put in your own Windows user folder, password and endpoint URL:

```properties
pub.upload.storeFile=C:/Users/<you>/keys/pub-upload.jks
pub.upload.storePassword=<your keystore password>
pub.reportEndpoint=https://script.google.com/macros/s/<id>/exec
```

If you host the privacy policy somewhere other than GitHub, also add `pub.privacyPolicyUrl=https://…`. The README's "Release builds" section lists every setting, including environment-variable equivalents. Then build:

```powershell
.\tools\dev.ps1 :core:test :app:lintRelease :app:bundleRelease
```

The bundle is written to `app\build\outputs\bundle\release\app-release.aab`. Install the matching `benchmark` build on your phone and run through every screen. R8 minification can break code that relies on reflection, such as Gson.

## Step 3: Review and publish the privacy policy

A draft is in [`privacy-policy.md`](privacy-policy.md). It names LTTL LLC as the publisher and points people to the developer email on the Play listing. Change both if you publish personally or want a direct email address. It also commits to keeping reports no longer than two years. Make sure every statement is true for you, and consider a lawyer's review.

PUB links to `https://github.com/zeniazenin/PUB/blob/main/docs/privacy-policy.md` by default, so the link works once you push. To host the policy on the LLC website instead, set `pub.privacyPolicyUrl`. Either way, use the same URL in Play Console. It must be publicly reachable and not a PDF. The draft covers:

- **What leaves the phone, and to whom.** When the user builds or edits an app, PUB sends OpenAI the app description, phone model, Android version, API level, screen size, font scale, locale, theme, hardware availability and PUB's capability list. Edits also send the app's code, and its saved request, research brief and checks. Research requests send only the description (and, for edits, the app's name, description and brief). This goes through the user's own ChatGPT account and is subject to OpenAI's privacy policy. Content reports, once added, go to you.
- **What stays on the phone.** Saved apps and their data, photos, private files, picked documents, location and sensor readings, Bluetooth and USB data, and the encrypted ChatGPT tokens. PUB has no servers, ads, analytics or tracking, and doesn't sell data.
- **Dictation.** It's handled by Android's speech service, which may process audio online. PUB receives only text.
- **Permissions**, each with what it's used for.
- **Deletion.** Deleting an app removes its data, Disconnect revokes the ChatGPT session, and uninstalling removes everything. Point to ChatGPT's settings for data held by OpenAI.
- **Children.** PUB isn't directed at children.
- **Security** (Keystore encryption, isolated runtime), how changes to the policy are announced, and the date it takes effect.

## Step 4: Create the app in Play Console

**Home → Create app**:

- App name: `PUB – Personal Utility Builder` (30 characters, the maximum)
- Default language: English (United States)
- App or game: **App**
- Free or paid: **Free**. This is permanent: a free app can never become paid.
- Accept the declarations (Developer Program Policies, US export laws).

## Step 5: Complete App content

These are under **Policy → App content**. Each must be complete before you can send anything for review.

| Section | Answer for PUB |
|---|---|
| Privacy policy | Your policy URL |
| Ads | No, the app doesn't contain ads |
| App access | All or some functionality is restricted. See the reviewer instructions below. |
| Content rating | Fill in the IARC questionnaire honestly (category: Utility/Productivity). There's no user-to-user communication or sharing, and the app generates content with AI. |
| Target audience | **18 and over** only. This keeps PUB out of the Families policy and matches the ChatGPT account requirement. |
| Data safety | See the draft below |
| Advertising ID | No |
| Government app / Financial features / Health | No / none / none |
| Foreground service permissions | **Data sync**: "When the user taps Generate app, PUB keeps the research, generation and checking requests running while the user switches apps or turns off the screen, and shows an ongoing notification with progress and Cancel." Attach a link to a short screen recording (an unlisted YouTube video works) showing Generate, leaving PUB, the notification, and returning to the result. |

### Reviewer instructions (App access)

Reviewers must be able to use the builder, which needs a ChatGPT account. Create a **dedicated ChatGPT account** for review, on a plan that works with PUB, with email and password sign-in and no multi-factor authentication. OpenAI may email a code when it sees a new device, and reviewers can't receive that email. If review fails with "couldn't access the app", reply in Play Console's policy inbox and offer a demo video. Draft:

> PUB builds small private apps on the phone from a text description, using the reviewer's ChatGPT account.
> 1. Open PUB. The Tip Calculator sample works without signing in.
> 2. Settings (top right) → Continue with ChatGPT, then sign in with the account below in the browser and return to PUB.
> 3. Tap + Create app, type "a timer for boiling eggs", then tap Generate app. A build takes a few minutes and can run in the background.
> 4. Open Preview app, then Save locally.
> Generated apps run in an isolated Android process with no internet access, and can only use PUB's native UI components and host-checked capabilities.
> Account: <email> / <password>

### Data safety draft

Data processed only on the phone doesn't count as collected. Camera, microphone, location, sensors, files and saved app data never leave the phone through PUB, so you don't declare them.

| Question | Answer |
|---|---|
| Does the app collect or share any of the required data types? | Yes |
| Is all data encrypted in transit? | Yes (HTTPS) |
| Can users request that data be deleted? | No server-side data is kept by you. Explain that deleting an app or uninstalling removes local data, and that OpenAI-held data is managed in ChatGPT settings. |
| Account creation | The app doesn't let users create an account. Sign-in uses an existing ChatGPT account. |
| **App activity → Other user-generated content** | Collected (app descriptions, edit requests, app code for edits). Purpose: App functionality. Required to use the builder. |
| **App info and performance → Other** | Collected (phone model, Android version, screen and font metrics, hardware availability). Purpose: App functionality. |
| **App activity → Other user-generated content**, content reports | Collected when the user sends a report (reason, details, and the app's name, description and code or the research summary and sources if included). Purpose: App functionality, plus Fraud prevention, security and compliance. Optional: only when the user reports. Not shared. Google stores the sheet for you as a service provider. |
| Shared? | The data goes to OpenAI when the user taps Generate. Google exempts transfers the user starts and expects, but **declaring it as shared with OpenAI is the safer choice** and avoids a mismatch finding. |

## Step 6: Store listing

Under **Grow users → Store presence → Main store listing**:

- **Short description** (80 characters max): `Describe a tool and PUB builds a private mini app that runs on your phone.`
- **Full description** (4,000 characters max). Lead with what it does, then:
  - private, on-device apps
  - research, planning and automatic checks before you see a build
  - camera, sensors and other hardware, only with your permission
  - files you choose
  - no ads or tracking
  - "Requires a ChatGPT account. Builds use your ChatGPT plan's usage."
  - "PUB is not affiliated with or endorsed by OpenAI."
- **Trademarks.** Say "works with your ChatGPT account" rather than anything implying a partnership. Keep OpenAI and ChatGPT logos out of the icon, feature graphic and screenshots.
- **App icon**: 512 × 512 PNG, 32-bit. Export it from `app/src/main/res/drawable-nodpi/pub_icon_art.png` (1254 × 1254).
- **Feature graphic**: 1024 × 500 PNG or JPEG, required.
- **Phone screenshots**: at least 2 and up to 8, 9:16. Use at least 4 at 1080 px or more to be eligible for Play's recommendations. Good choices: launcher, Create app, Research card, a preview, and Settings → Access.
- **Category** Productivity (or Tools), plus tags. Contact: the public support email and website.

## Step 7: Upload, test and release

1. **Internal testing** (Test and release → Testing → Internal testing): create a release and upload `app-release.aab`. Accept **Play App Signing** when prompted. Add yourself as a tester, install from the opt-in link, and check sign-in, a full build, preview, save, permissions and the report button. Also check the bundle's warnings, including 16 KB page-size support for CameraX's native library, in **App bundle explorer**.
2. **Personal account only: closed test.** Promote the build to a closed testing track, add your 12+ testers by email list, and keep them opted in for 14 days in a row. Then use **Dashboard → Apply for production** and answer the questions about your test, the app and production readiness.
3. **Production** (Test and release → Production): choose countries (start with the United States, or all), create a release from the tested bundle, consider a **staged rollout** (for example 20%), and send for review. First reviews usually take a few days and can take longer for new accounts. Turn on **Managed publishing** if you want to choose the exact launch moment after approval.
4. Fix any policy-inbox findings, resubmit, and roll out to 100% when you're satisfied.

## After launch

- Every update needs a higher `versionCode` and must keep targeting the current required API level. Expect API 37 to be required for new apps and updates around August 2027.
- Update the Data safety form and the privacy policy whenever what leaves the phone changes.
- Android developer verification: apps on Play were registered automatically. Check **Play Console → developer verification status** once.
- Read user reports and act on them. Google's AI policy expects reports to inform your moderation.

## Sources

- [Target API level requirements (Play Console Help)](https://support.google.com/googleplay/android-developer/answer/11926878) and [Meet Google Play's target API level requirement](https://developer.android.com/google/play/requirements/target-sdk)
- [Required information to create a Play Console developer account](https://support.google.com/googleplay/android-developer/answer/13628312)
- [App testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465)
- [Boosting trust and transparency in Google Play (D-U-N-S, About the developer)](https://android-developers.googleblog.com/2023/07/boosting-trust-and-transparency-in-google-play.html)
- [AI-Generated Content policy](https://support.google.com/googleplay/android-developer/answer/13985936) and [Understanding the AI-Generated Content policy](https://support.google.com/googleplay/android-developer/answer/14094294)
- [Device and Network Abuse policy (interpreted code)](https://support.google.com/googleplay/android-developer/answer/9888379)
- [Foreground service declaration](https://support.google.com/googleplay/android-developer/answer/16965181) and [foreground service types](https://developer.android.com/guide/components/fg-service-types)
- [Android developer verification rollout](https://developer.android.com/blog/posts/android-developer-verification-rolling-out-to-all-developers-on-play-console-and-android-developer-console)
- OpenAI: [ChatGPT plan usage overview](https://developers.openai.com/siwc/token-sharing-open-source), [Request a client ID](https://developers.openai.com/siwc/request-client-id), [UI/UX guidelines](https://developers.openai.com/siwc/ui-ux-guidelines)
