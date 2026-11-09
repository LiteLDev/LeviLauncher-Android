# Changelogs and launcher news

LeviLauncher for Android uses two small JSON files as the source of truth. Both files are bundled into the APK, validated in CI, and intentionally stay in English.

## Prepare a launcher release

1. Replace the text in `resources/launcher/changelog.json` with everything that changed in the release. Each item requires `text` and may include a clickable `url`. Keep the existing keys and add as many sections or items as needed.
2. Run `python3 scripts/validate_release_content.py`.
3. Commit the release content, create the version tag, and push both the commit and tag:

```bash
git checkout main
git pull --ff-only
git add resources/launcher/changelog.json
git commit -m "chore: prepare v1.5.16"
git tag v1.5.16
git push origin main
git push origin v1.5.16
```

The version is written only in the Git tag. `app/build.gradle` turns the most recent `v...` tag into `BuildConfig.VERSION_NAME`, so the version is not duplicated in the changelog file. The Android release workflow also converts the same changelog JSON into the GitHub Release description.

The What’s new dialog is skipped on a clean installation. It appears once when Android reports that an existing installation was updated, and later updates are tracked with the APK version code.

## Publish launcher news

Add one new object to the beginning of `resources/launcher/news.json`. Use a permanent unique `id` and a UTC ISO-8601 timestamp. Set `important` to `true` for a highlighted card. Omit `url` or use an empty string when the card does not need a link.

```json
{
  "id": "2026-08-27-example",
  "title": "Example announcement",
  "summary": "The short text users will see in the inbox and notification.",
  "publishedAt": "2026-08-27T12:00:00Z",
  "category": "Announcement",
  "url": "https://github.com/LiteLDev/LeviLauncher-Android/releases",
  "important": false,
  "notify": true
}
```

Then publish it with normal Git commands:

```bash
python3 scripts/validate_release_content.py
git add resources/launcher/news.json
git commit -m "news: example announcement"
git push origin main
```

Confirm that the public feed opens before testing refresh in the app:

`https://raw.githubusercontent.com/LiteLDev/LeviLauncher-Android/main/resources/launcher/news.json`

If that URL returns `404: Not Found`, the `resources/launcher` files were not committed to `main`. Add and push them:

```bash
git add resources/launcher/news.json resources/launcher/changelog.json
git commit -m "chore: publish launcher release content"
git push origin main
```

The app checks the GitHub-hosted file when it opens and keeps an offline cache. The `publish-news.yml` workflow detects the newly added ID and sends one FCM topic message when `notify` is `true`. Editing an existing entry does not resend a notification. Add only one new news item per commit.