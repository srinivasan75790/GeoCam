# GeoCam – Geotagging Camera for Android

Takes a photo with the camera, reads the phone's GPS position, and:

1. **Writes the coordinates into the JPEG's EXIF data** (the real geotag – shows up in Google Photos, "Details" in the gallery, any EXIF viewer).
2. **Stamps address, lat/long, accuracy and date/time on the image** (like GPS Map Camera).
3. Saves it to **Pictures/GeoCam** in the gallery.

## Run it

1. Install **Android Studio** (Koala or newer).
2. `File > Open` and select this `GeoCam` folder; let Gradle sync.
3. Connect a real phone (USB debugging on) – the emulator has no real GPS/camera.
4. Press **Run**. Allow *Camera* and *Location* when asked.
5. Wait until the top bar shows `Lat … Long … ±…m`, then tap **Capture**.

## How it works

| Piece | Where |
|---|---|
| Permissions (CAMERA, FINE/COARSE location) | `AndroidManifest.xml`, `MainActivity.permissionLauncher` |
| Live camera preview + capture | CameraX in `startCamera()` / `takePhoto()` |
| GPS fixes | `FusedLocationProviderClient` in `startLocationUpdates()` |
| EXIF geotag | `ExifInterface.setGpsInfo(location)` in `processAndSave()` |
| Address text | `Geocoder` in `reverseGeocode()` (needs internet; skipped if offline) |
| Save to gallery | `MediaStore` in `saveToGallery()` (no storage permission needed on Android 10+) |

## Checking the geotag

Open the photo in Google Photos > swipe up (info), or run `exiftool GEO_*.jpg` and look for `GPS Latitude` / `GPS Longitude`.

## Notes

- Turn **Location** on in the phone's quick settings, and go outdoors for a better fix (accuracy shows as ±m).
- Min Android version: 7.0 (API 24).
- Remove the `drawStamp(...)` call in `processAndSave()` if you want only the hidden EXIF tag and a clean image.
