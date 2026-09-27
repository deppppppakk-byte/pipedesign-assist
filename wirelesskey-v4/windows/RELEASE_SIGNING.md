# WirelessKey release signing

WirelessKey does not store any private signing key or certificate in the repository.

## Android
The Android release build supports an external release keystore through environment variables:

- `WIRELESSKEY_ANDROID_KEYSTORE`
- `WIRELESSKEY_ANDROID_STORE_PASSWORD`
- `WIRELESSKEY_ANDROID_KEY_ALIAS`
- `WIRELESSKEY_ANDROID_KEY_PASSWORD`

If all four values are present, Gradle uses the release keystore. Otherwise local/CI test builds fall back to the existing debug signing identity.

Never commit a keystore or password to the repository.

## Windows
Use `sign-release.ps1` with your own code-signing PFX after building the receiver and installer.

Required environment variable:

`WIRELESSKEY_SIGNING_PASSWORD`

Example:

`powershell -ExecutionPolicy Bypass -File .\sign-release.ps1 -CertificatePath C:\secure\wirelesskey.pfx -TimestampUrl https://timestamp.digicert.com`

The script signs and verifies both:

- `publish\WirelessKeyReceiver.exe`
- `installer-output\WirelessKeySetup.exe`

Keep the PFX and password outside source control.