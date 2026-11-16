# NambaLink (com.manuubett.nambalink)

QR phone-number sharing, scanning and payment details. Phase 1.

## Plans (set both in the payments admin)
- `nambalink`    : Personal plan
- `nambalinkbiz` : Business plan (separate price and trial)

## Set up
1. New private GitHub repo. Upload everything in this folder (keep `.github`, `app`, `settings.gradle.kts`, `gradle.properties` at the top level).
2. Actions > "Create signing key (run once)". Add the 4 secrets (paste only the long line under KEYSTORE_BASE64).
3. Push. Download the APK from the build.
4. Payments admin: add `nambalink` and `nambalinkbiz`. Store admin: upload as "NambaLink".

## Notes
- QR holds a standard vCard, so any phone camera offers "Add contact". Till/Paybill ride in extra X-NAMBA lines and the NOTE field.
- Scanner is Google's code scanner (needs Google Play services). No camera permission code.
- Posters and QR images are saved with the system file picker (no storage permission).
- Needs `android.useAndroidX=true` (already in gradle.properties).
