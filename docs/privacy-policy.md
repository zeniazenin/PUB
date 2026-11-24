# PUB privacy policy

Last updated: November 15, 2026

PUB (Personal Utility Builder) is an Android app published by LTTL LLC ("we", "us"). This policy explains what information PUB handles, where it goes, and the choices you have.

## In short

- PUB has no servers, no PUB accounts, no ads, no analytics and no tracking. We don't sell or rent any information.
- The apps you build stay on your phone, along with their data, files and photos.
- When you build or edit an app, PUB sends your request and some details about your phone to OpenAI, through your own ChatGPT account.
- If you report content, the report is sent to us.

## Information sent to OpenAI

PUB builds apps with your ChatGPT account. You sign in on OpenAI's own page in your browser, and PUB never sees your ChatGPT password. Signing in, refreshing the session, loading the list of models and disconnecting exchange account tokens with OpenAI.

When you tap **Generate app** or **Generate changes**, PUB sends OpenAI a series of requests:

- **Research:** your description of the app. For an edit, it also includes the app's name, description and saved research. OpenAI may run web searches for this request.
- **Screen planning, building and icon design:**
  - your description and PUB's building instructions
  - the research findings
  - details about your phone: manufacturer and model, Android version and API level, screen size, text size, language, light or dark theme, the app's style (your style guide's colours, corner shape and font, or PUB's default style), which hardware features are available, the capabilities you blocked for all apps, and the features PUB supports
  - for an edit: the app's current code and graphics definitions (not photo pixels), and its saved original request, research and behavior checks
- **Checks and repairs:** a screenshot of the new app's first screen, rendered privately with empty storage and no hardware access. It's sent with your request, the research and the app's code so the model can check and fix the result. When a check fails, the failing checks are sent with the repair request.

PUB never sends OpenAI:

- the data an app has saved
- an app's private files, or files you picked on your phone
- photos you captured
- audio recordings
- location coordinates
- sensor readings
- names of, or data from, Bluetooth, USB or NFC devices
- unique device identifiers

OpenAI handles these requests as part of your ChatGPT account, under OpenAI's own terms and privacy policy. Builds use your ChatGPT plan's usage.

## Information that stays on your phone

PUB stores the following privately on your phone:

- your saved apps, with their saved data, private files and photos
- each app's saved research and behavior checks
- prompt history and build drafts
- your settings and permission rules
- your ChatGPT sign-in tokens, encrypted with a key held in Android Keystore, and the account email shown in Settings

PUB turns off Android cloud backup and device-to-device transfer for its data.

- **Hardware.** Apps you build can use the camera, location, sensors, Bluetooth, NFC, USB, infrared, the flashlight and vibration only when you allow them. Readings go only to that app on your phone.
- **Microphone.** Microphone apps measure pitch on the phone, and nothing is recorded.
- **Dictation.** Speak your idea, and dictation inside apps, use your phone's speech service, which may process audio online under its own provider's policy. PUB receives only the recognised text.
- **Files.** Apps can use files elsewhere on your phone only after you pick them in Android's file picker. PUB has no permission to browse your storage.

Generated apps run in an isolated Android process with no internet access.

## Content reports

You can report a build, its research or a saved app from inside PUB. A report sends us:

- the reason you choose
- any details you write
- PUB's version
- the reported content, only if you leave the **Include** option checked. For an app, that's its name, description and code. For research, it's the research summary and source links.

Reports never include your phone details, your ChatGPT account, an app's saved data, files or photos.

We use reports to review content PUB produced and to improve its safeguards. Reports are stored in a Google spreadsheet in our Google account, where Google processes them for us. We keep a report for as long as we need it to review and act on it, and no longer than two years.

## Android permissions

| Permission | Used for |
|---|---|
| Internet | Signing in to ChatGPT, building apps and sending reports. Generated apps can't use the internet. |
| Notifications, foreground service (data sync), wake lock | Keeping a build running while you use other apps, and telling you when it's done |
| Microphone | Live pitch for apps you allow |
| Camera | Still photos for apps you allow |
| Approximate and precise location | Current position for apps you allow, only while PUB is open |
| Physical activity | Step sensors for apps you allow |
| Nearby devices (Bluetooth) | Connecting to devices you choose. Bluetooth scanning isn't used to find your location. |
| NFC, infrared, vibration, audio settings | The matching features in apps you allow |

You can block any capability for all apps or for one app in **Settings → Access**, and revoke Android permissions in Android's settings.

## Children

PUB is meant for adults and isn't directed at children. We don't knowingly collect information from children.

## Your choices

- **Delete an app** to remove its data, files, photos, research and checks from your phone. Files you picked elsewhere are never deleted; the app only loses access to them.
- **Disconnect** in Settings to revoke the ChatGPT session and clear its tokens from your phone.
- **Uninstall PUB** to remove all of its data from your phone.
- Manage information that OpenAI holds in your ChatGPT settings, or contact OpenAI.
- Reports contain no account or contact details, so to ask us to delete a report you sent, contact us with roughly when you sent it and what it was about.

## Security

PUB uses HTTPS for every request it sends. It encrypts your ChatGPT tokens with Android Keystore and runs generated apps in an isolated process. No method of storage or transmission is perfectly secure.

## Changes

We'll post changes on this page and update the date at the top. Significant changes will also appear in PUB's release notes on Google Play.

## Contact

LTTL LLC. Use the developer email address on PUB's Google Play listing, or open an issue at https://github.com/zeniazenin/PUB/issues.
