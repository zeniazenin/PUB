# Report endpoint

PUB's **Report content** form sends each report to an HTTPS endpoint you control. Google Play's AI-Generated Content policy requires that users can report offensive AI output to you without leaving the app. PUB has no server, so this folder contains a free endpoint you can run in your own Google account: a Google Apps Script web app that adds each report as a row in a Google Sheet and emails you about it.

## Deploy it (about 10 minutes)

1. Sign in to Google with the account that will own the reports. Use the LLC's account if you publish as LTTL LLC.
2. Create a Google Sheet named, for example, **PUB reports**.
3. In the sheet, choose **Extensions → Apps Script**. Replace the contents of `Code.gs` with [`Code.gs`](Code.gs) from this folder, and save.
4. Choose **Deploy → New deployment**, click the gear, and pick **Web app**. Set:
   - **Execute as:** Me
   - **Who has access:** Anyone. PUB has no Google sign-in. The script validates and size-limits every field, and caps email notifications at 20 a day.
5. Click **Deploy** and authorize the script. It asks for access to the spreadsheet and permission to send email as you.
6. Copy the **Web app URL**. It ends in `/exec`.

## Test it

In PowerShell, with your URL in place of `<url>`:

```powershell
Invoke-RestMethod -Method Post -Uri "<url>" -ContentType "application/json" -Body '{"kind":"pub-content-report","version":1,"subject":"app","reason":"other","details":"Endpoint test","pubVersion":"test"}'
```

It should print `ok : True`, a new row should appear in the **Reports** sheet, and you should receive an email.

## Connect PUB to it

Release builds won't build without an endpoint. Set the URL as the Gradle property `pub.reportEndpoint`, or as the environment variable `PUB_REPORT_ENDPOINT`. "Release builds" in the main README explains where these settings go.

## Changing the script

Edit the script, then choose **Deploy → Manage deployments**, edit the existing deployment and select **New version**. Editing the existing deployment keeps the URL. A **New deployment** gets a new URL, which would need a new PUB release.

To turn off emails, set `NOTIFY_BY_EMAIL = false`. Reports are still saved to the sheet.

## What is stored

Each report includes:

- the subject (build, research or app)
- the reason the user chose
- the details they wrote
- PUB's version
- the reported content, only when the user left **Include** checked: the app's name, description and code (up to 40,000 characters), or the research summary and sources

Reports contain no phone details, account, saved app data, files or photos. Keep the sheet private, and delete reports you no longer need. The privacy policy says reports are kept no longer than two years.
