# Attribution & Image Sources

Eidora bundles / shows a few historical photographs (store screenshots, and — if
enabled — in-app model-validation samples) that were not created by the project.
This file records their source and licence so redistribution is traceable.

> **Not legal advice.** This is a good-faith record. Each photo below was taken
> from Wikimedia Commons and is marked there as public domain (PD-old / PD-US /
> Library of Congress "no known restrictions on publication"). Verify the status
> on each linked file page before relying on it.

## Two rights to keep separate

1. **Copyright in the photograph** — the photographer's right, expiring a set
   time after their death (EU: 70 years). A photo of a long-dead person is not
   automatically public domain; the photographer's dates decide it.
2. **Personality / image rights of the subject** — used here only in a neutral,
   technical/editorial context (face-recognition demo), not for advertising or
   in a disparaging way.

## Source

All photos are fetched from Wikimedia Commons via
`https://commons.wikimedia.org/wiki/Special:FilePath/<filename>`. The
`<filename>` is the exact Commons file page:
`https://commons.wikimedia.org/wiki/File:<filename>`.

## Photos used in the screenshots (and available as test data)

> The store screenshots also carry a short attribution note next to the images
> themselves, at
> `fastlane/metadata/android/<locale>/images/phoneScreenshots/ATTRIBUTION.txt`.
> This file below remains the authoritative per-photo record.

### Albert Einstein (d. 1955)

| Year | Commons file | Basis |
|------|--------------|-------|
| 1893 | `Albert Einstein as a child.jpg` | PD-old |
| 1904 | `Einstein patentoffice.jpg` | PD-old |
| 1921 | `Einstein 1921 by F Schmutzer - restoration.jpg` | PD-old (F. Schmutzer, d. 1928) |
| 1935 | `Einstein-formal portrait-35.jpg` | PD-US |
| 1936 | `A. Einstein USA Jan1936.jpg` | PD-US |
| 1940 | `Albert Einstein citizenship NYWTS.jpg` | PD (NY World-Telegram & Sun; LoC no known restrictions) |
| 1947 | `Albert Einstein Head.jpg` | PD-US (Oren J. Turner) |

### Marie Curie (d. 1934)

| Year | Commons file | Basis |
|------|--------------|-------|
| 1900 | `Marie Curie, portrait, 1900.jpg` | PD-old |
| 1903 | `Marie Curie 1903.jpg` | PD-old |
| 1920 | `Marie Curie c1920.jpg` | PD-old |
| 1921 | `Mme. Curie LCCN2014712477.jpg` | LoC Bain Collection, no known restrictions |

### Pierre + Marie Curie (multi-face detection demo)

| Year | Commons file | Basis |
|------|--------------|-------|
| 1904 | `Pierre and Marie Curie.jpg` | PD-old |
| 1904 | `Pierre and Marie Curie at work.jpg` | PD-old |

### Mark Twain (d. 1910)

| Year | Commons file | Basis |
|------|--------------|-------|
| 1871 | `Mark Twain, Brady-Handy photo portrait, Feb 7, 1871, cropped.jpg` | PD-old (Brady-Handy; LoC) |
| 1907 | `Mark Twain Underwood 1907.jpg` | PD-old |
| 1907 | `Mark Twain 1907.jpg` | PD-old |
| 1909 | `Twain1909.jpg` | PD-old |

## The multi-face screenshot (`5453-16`)

The group photo with green detection boxes is
`Mme. Curie LCCN2014712477.jpg` — Library of Congress, George Grantham Bain
Collection, LCCN 2014712477, "no known restrictions on publication."

## Model self-test images (bundled in the APK)

The photos under `app/src/main/assets/selftest/` are real photographs with named
face regions in their XMP metadata (MWG Regions). They exist so a user can
validate a self-supplied model on device: the detector is run on each photo and
compared against the metadata face count, and named faces are cropped from the
metadata for the embedding distance check (one person appears in two photos to
give the same-person pair).

> **Source and licence:** these test photos were provided by the project
> maintainer. Record their origin and licence here before distribution, and
> ensure the depicted people's rights permit bundling them in the app. If in
> doubt, replace them — the self-test reads whatever JPGs are in the folder, so
> the test set can be swapped freely.

## Everything else

The app icon, launcher graphics and UI are original work by the Eidora project
under the repository's licence.
