# FilmLab

A focused Android photo editor with seven film-inspired looks.

Choose a photo, preview the films on your own image, adjust the strength, and save a copy. Processing happens on your device. FilmLab never replaces your original photograph.

## Editing a photo

1. Tap **Choose photo** for Android's photo picker, or **Browse files** for camera files and external storage.
2. Choose a film thumbnail. **Original** is selected when a new photo opens.
3. Adjust **Strength**. Use **Compare** to switch between the original and your edit. Pinch or double-tap to inspect detail; **Fit** returns to the full image.
4. Open **Adjust** for grain and reset. **Info** shows source details, decoding limitations, and the actual export dimensions.
5. Tap **Save copy**, then **View** to open the saved JPEG in your gallery.

The app keeps one current edit in private, non-backed-up app storage and restores it after restarting. Returning to the opening screen offers **Continue editing**. A failed import keeps the previous photograph available.

The same editor adapts to phones, wide screens, and foldable hinges. It follows the device's light/dark appearance and supports larger text and accessible controls.

## Film looks

Kodachrome 64, Kodak Portra 400, Fujifilm Velvia 50, CineStill 800T, Fujifilm Pro 400H, Ilford HP5 Plus, and Kodak Tri-X 400.

These are artistic simulations using tone curves, colour transforms, procedural grain, and a highlight bloom for CineStill. They are not a claim of measured equivalence to a specific physical film or lab scan.

## Image quality and RAW files

- The editing image is limited to **2,048 pixels on its long edge**. Smaller images are not enlarged. JPEG copies use this editing resolution, with actual dimensions shown in Info.
- Full-resolution and high-bit-depth RAW export are not supported.
- RAW support depends on the file's encoding. Some files use a sensor decode; others use their embedded JPEG preview or Android's decoder. Info describes the path used.
- Monochrome files stay monochrome when genuine colour data cannot be decoded. FilmLab does not invent replacement colours.
- Missing camera and sensor details stay unknown rather than being inferred from a brand name.

The original file remains outside the editing pipeline. The one saved draft contains the working image and its adjustment settings; opening another photo replaces that draft after the new photo has loaded.

## Build and test

Requirements: JDK 21 and Android SDK platform 36.1 / build tools 36.0.0. The checked-in Gradle wrapper uses Gradle 9.3.1.

    ./gradlew :app:assembleDebug
    ./gradlew :app:testDebugUnitTest

Debug builds install as **FilmLab Preview**, package **com.aistudio.filmlab.vntg64.debug**, using the standard Android debug key. They can coexist with the release app.

Release signing uses KEYSTORE_PATH, STORE_PASSWORD, and KEY_PASSWORD environment variables. Do not commit signing keys or secrets.

Tests cover film processing, image dimensions, zoom state, truthful RAW fallback, draft restoration and interrupted writes, gallery export cleanup, and the main Compose editing journeys.

## Source map

- ui/FilmLabScreen.kt — opening screen, adaptive editor, adjustments, picker and gallery actions.
- ui/FilmLabViewModel.kt — import/render jobs, thumbnails, current edit, export state.
- ui/EditDraftStore.kt — atomic persistence of one private edit.
- ui/GalleryExporter.kt — verified JPEG writes and gallery publication.
- ui/RawComponents.kt — source information and export details.
- film/ — film presets and image processing.
- raw/ — image decoding, metadata and real sensor/preview provenance.

Generated test scenes in SamplePhotos.kt are fixtures, not real camera captures or proof of RAW colour accuracy.

The welcome photograph is credited in [assets/PHOTO_CREDIT.md](assets/PHOTO_CREDIT.md).
