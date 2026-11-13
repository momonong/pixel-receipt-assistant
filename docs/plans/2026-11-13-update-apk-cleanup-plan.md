# Installed APK cache cleanup implementation plan

> Execute inline with superpowers:executing-plans; use tests for the deletion boundary and one final independent review.

**Goal:** After a successful update, remove only the installed APK cache and preserve all receipts, photos, drafts, ledger and login data. Restore the product display name to `PixelReceipt`, without a Chinese suffix.

**Architecture:** An unexported `MY_PACKAGE_REPLACED` receiver and a foreground fallback check the installed package identity locally. A shared lock covers the cache identity check/deletion and download commit, preventing cleanup from deleting a newly downloaded update. No network, schema, provider or dependency changes are needed for cleanup.

**Tech stack:** Existing Kotlin/coroutines, Android PackageManager/BroadcastReceiver and JUnit/Robolectric. Minimum API remains 26.

**Spec:** The user requested automatic APK cleanup only after successful installation and explicitly required all receipt/photo/ledger data to remain intact; their follow-up fixes the display spelling to `PixelReceipt`.

## Constraints and review focus

- Delete only `cacheDir/app-updates/update.apk`; never recursively clear cache or touch filesDir, noBackupFilesDir, databases or Downloads.
- Require the same expected package, nonempty matching signing certificates, and a cached version already installed or superseded. Keep newer, foreign, unreadable and symlinked files.
- Run filesystem/package inspection off the UI thread; finish asynchronous broadcasts. Failed cleanup is deferred for a later foreground retry.
- Serialize deletion with download rename. Preserve a new transfer and partial-file cleanup already implemented by the downloader.
- Do not equate opening the Android installer, cancelling it or returning from settings with installation success.

## Steps

- [x] Add failing tests for installed/obsolete cache deletion, retention of newer or foreign APKs, symlinks, and byte-for-byte preservation of other app data.
- [x] Implement the scoped cleanup helper, Android identity adapter, broadcast trigger and foreground fallback; pin download commit to the same lock.
- [x] Test the lifecycle and download overlap, then run the complete `testDebugUnitTest lintDebug assembleDebug` command: 519 passed, lint and build successful.
- [x] Restore `PixelReceipt` casing and prepare a 0.7.2/code16 candidate with compatible backend APK admission: 42 API tests passed.
- [x] Independently review the final diff: no blocking findings. Signed candidate and delivery status are recorded separately in private release evidence; merge, push and publication require this version's authorization.
