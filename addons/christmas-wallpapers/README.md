# Gustus Wallpaper

Sets Home's wallpaper from a picture on the TV. Open it from More: the nine newest pictures on the TV
(downloads from a browser such as Sofa, File Manager) and a bundled Christmas tree fill a grid. The focused
picture fills the screen behind it, and OK puts it on Home. "✓ On Home now" marks the current one.
No network, no ads, no accounts.

The picture is scaled to cover the screen and staged with CleanYourTV's theme provider, which checks it before
replacing `wallpaper.jpg`, so a broken file never leaves Home without a wallpaper. Nothing else on Home changes.

## Permissions

- `READ_EXTERNAL_STORAGE`: lists and reads the pictures on the TV.
- `WRITE_SECURE_SETTINGS`: sideload-only access to the launcher's protected theme provider;
  grant once with `adb shell pm grant com.gathin.christmas android.permission.WRITE_SECURE_SETTINGS`.

Requires the matching `my-tv` launcher with the theme provider's refresh operation. Build with JDK 17,
Android SDK 34 and `./gradlew --no-daemon lintRelease assembleRelease`.
