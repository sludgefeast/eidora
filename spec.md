# Faces App – Spezifikation

## Übersicht

Android-App zur lokalen Gesichtserkennung, Personenzuordnung und XMP-Metadaten-Rückschreiben. Kompatibel mit DigiKam und Aves.

---

## App Icon

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">
  <defs>
    <clipPath id="leftClipFinal">
      <circle cx="40" cy="44" r="22"/>
    </clipPath>
  </defs>
  <rect width="108" height="108" rx="24" fill="#1A0A2E"/>
  <circle cx="40" cy="44" r="22" fill="#F97316" opacity="0.7"/>
  <circle cx="68" cy="44" r="22" fill="#EC4899" opacity="0.7"/>
  <circle cx="68" cy="44" r="22" fill="#FBBF24" opacity="0.6" clip-path="url(#leftClipFinal)"/>
  <rect x="20" y="76" width="68" height="5" rx="2.5" fill="#FBBF24" opacity="0.95"/>
  <rect x="30" y="86" width="48" height="5" rx="2.5" fill="#FBBF24" opacity="0.4"/>
</svg>
```

Beim Build aus diesem SVG die komplette Android Icon-Familie erzeugen:
- `mipmap-mdpi` 48×48
- `mipmap-hdpi` 72×72
- `mipmap-xhdpi` 96×96
- `mipmap-xxhdpi` 144×144
- `mipmap-xxxhdpi` 192×192
- Adaptive Icon mit Foreground/Background-Layer

---

## Lokalisierung

- Standardsprache: Englisch (`res/values/strings.xml`)
- Deutsch: (`res/values-de/strings.xml`)
- Alle UI-Texte, Aktionsnamen, Labels und Fehlermeldungen über String-Ressourcen

---

## Berechtigungen

Die App benötigt drei Laufzeit-Berechtigungen, bevor die Sync-Pipeline gestartet werden darf:

**1. Foto-Lesezugriff**
- Android 13+ (API 33+): `READ_MEDIA_IMAGES`
- Android < 13: `READ_EXTERNAL_STORAGE`
- Wird über den Standard-Permission-Dialog angefragt

**2. Datei-Schreibzugriff (All Files Access)**
- Android 11+ (API 30+): `MANAGE_EXTERNAL_STORAGE`
- Notwendig, da `XmpHelper` direkt über `File`/`ExifInterface` auf Dateien in `DCIM/Camera` zugreift statt über die `MediaStore`-API. Ohne diese Berechtigung verweigert Scoped Storage das Schreiben von XMP-Metadaten in fremde Mediendateien.
- Kann nicht über den normalen Permission-Dialog gewährt werden, sondern nur über die Systemeinstellungen (`Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`). Die App leitet den Nutzer dorthin weiter.
- Android < 11: `WRITE_EXTERNAL_STORAGE` (bis API 29) reicht aus

**3. Benachrichtigungen**
- Android 13+ (API 33+): `POST_NOTIFICATIONS`
- Wird über den Standard-Permission-Dialog angefragt
- Auf älteren Versionen automatisch verfügbar

**Weitere Manifest-Berechtigungen (ohne Runtime-Dialog):**
- `INTERNET` – für den einmaligen Download des ML-Modells
- `FOREGROUND_SERVICE` und `FOREGROUND_SERVICE_DATA_SYNC` – für Fortschrittsbenachrichtigungen der Hintergrundverarbeitung

**Ablauf beim App-Start:**
1. `MainActivity` prüft, ob alle drei Berechtigungen vorliegen.
2. Fehlt eine Berechtigung, wird ein Permission-Screen mit entsprechenden Buttons angezeigt; die Sync-Pipeline startet nicht.
3. Erst wenn alle Berechtigungen erteilt sind, wird `SyncPipeline.enqueue()` ausgelöst.

---

## Crash-Logging

Da auf nicht gerooteten Geräten kein Zugriff auf System-Logcat für Drittanbieter-Apps möglich ist, schreibt die App unbehandelte Exceptions zusätzlich in eine eigene Log-Datei:

- `FacesApplication.attachBaseContext()` installiert einen `Thread.UncaughtExceptionHandler`, der so früh wie möglich aktiv wird (auch vor `onCreate()`), um Abstürze während der Klassen-Initialisierung abzudecken.
- Der vorherige System-Handler wird nach dem Logging weiterhin aufgerufen, damit der reguläre "App wurde beendet"-Dialog erscheint.
- Log-Dateien werden mit Zeitstempel in mehreren Fallback-Verzeichnissen abgelegt (`getExternalFilesDir()/crash_logs/`, `filesDir/crash_logs/`, `cacheDir/crash_logs/`), da frühe Abstürze manche Verzeichnisse evtl. noch nicht verfügbar haben.
- Dateien sind über einen normalen Dateimanager unter `Android/data/de.sebastian.faces/files/crash_logs/` einsehbar, ohne Root oder ADB.

---

## Datenmodell

### Photo
| Feld | Typ | Beschreibung |
|---|---|---|
| id | UUID | Primärschlüssel |
| path | String | Absoluter Dateipfad |
| modifiedAt | Long | `File.lastModified()` |
| takenAt | Long? | EXIF `DateTimeOriginal`, nullable |
| analyzed | Boolean | true = ML Kit wurde ausgeführt |

### Person
| Feld | Typ | Beschreibung |
|---|---|---|
| id | UUID | Primärschlüssel |
| name | String? | Eindeutig, nullable – null = Clustering-Vorschlag noch ohne Namen |
| representativeFaceId | UUID? | FK → FaceRegion, nullable |

### FaceRegion
| Feld | Typ | Beschreibung |
|---|---|---|
| id | UUID | Primärschlüssel |
| photoId | UUID | FK → Photo |
| personId | UUID? | FK → Person, nullable |
| name | String? | Bestätigter Personenname, nullable |
| regionJson | String | Normalisierte MWG-Koordinaten (X, Y, W, H) |
| embedding | BLOB? | FaceNet512 512-dim FloatArray, nullable |
| ignored | Boolean | true = aus Clustering ausgeschlossen |

**Regel:** Eine ignorierte FaceRegion hat immer `personId = null` und `name = null`.

**Vorschlag-Person:** Eine `Person` mit `name = null` ist eine reine Clustering-Gruppe ohne Nutzernamen. Sie erscheint in der Vorschlagsliste. Erst wenn der Nutzer einen Namen bestätigt, wird `Person.name` gesetzt und alle zugehörigen FaceRegions erhalten ebenfalls den Namen.

---

## Unterstützte Dateiformate

Nur JPEG. Erkennung über Magic Bytes (erste 3 Bytes: `FF D8 FF`), nicht über Dateiendung.

---

## Hintergrundverarbeitung

Vier verkettete WorkManager-WorkRequests, gestartet beim App-Start.

### Schritt 0 – Modell-Download

- `ModelDownloadWorker` prüft ob `facenet_512.tflite` bereits in `context.filesDir` liegt
- Falls nicht: Download vom offiziellen Repository (siehe Sektion ML-Modell)
- Netzwerk-Constraint: erst bei Verbindung
- Fortschritt als Foreground-Notification
- Retry mit LinearBackoff (30s) bei Netzwerkfehler
- Blockiert nachfolgende Schritte bis das Modell verfügbar ist

### Schritt 1 – Foto-Sync

Abgleich Dateisystem ↔ DB für alle JPEG-Dateien in `DCIM/Camera` (nicht der gesamte DCIM-Ordner).

**Debug-Filter:** Aktuell werden nur Dateien mit Namensschema `IMG_202601*` bis `IMG_202605*` verarbeitet. Der Filter greift direkt beim Sammeln der Dateien in `collectJpegs()`, nicht-passende Dateien werden gar nicht erst geladen.

| Fall | Aktion |
|---|---|
| Pfad neu | Photo anlegen, XMP einlesen, ML Kit ausführen |
| Pfad bekannt, Timestamp unverändert | nichts tun |
| Pfad bekannt, Timestamp geändert | `modifiedAt` aktualisieren, FaceRegions + Thumbnails löschen, verwaiste Persons löschen, dann wie „Pfad neu": XMP einlesen, ML Kit ausführen falls keine FaceRegions gefunden |
| Pfad in DB, Datei weg | Photo löschen, FaceRegions löschen, Thumbnails löschen |
| Nach jedem Durchlauf | verwaiste Persons löschen |

**XMP einlesen (pro neuem/geändertem Foto):**
- `mwg-rs:Regions/mwg-rs:RegionList` Typ `Face` → FaceRegion anlegen (name aus RegionName, nullable)
- Pro benannter FaceRegion: Person suchen oder anlegen (by name)
- `PersonInImage` + `People/`-Subjects neu berechnen und schreiben

**faces-Logik beim Einlesen:**
- Face-Regionen in Metadaten vorhanden → FaceRegions anlegen, `analyzed = true`, ML Kit überspringen
- Keine Face-Regionen in Metadaten → ML Kit ausführen

**ML Kit Ausführung:**
- Gesichter gefunden → FaceRegion anlegen (`name = null`, `personId = null`), MWG-Region ohne Namen ins XMP schreiben, `analyzed = true`
- Keine Gesichter → `analyzed = true`, kein XMP-Schreibvorgang
- `Photo.modifiedAt` nach jedem XMP-Schreibvorgang aktualisieren

**Thumbnail-Erzeugung (pro FaceRegion):**
- Zuschnitt: regionJson-Koordinaten + 10% Padding auf Originalbild
- Vor dem Skalieren: Ausschnitt auf Quadrat erweitern (größere Dimension, zentriert), um Verzerrung zu vermeiden
- Format: WebP, 128×128px
- Dateiname: `<faceRegionId>.webp`
- Ablage: `filesDir/thumbnails/`

**EXIF-Rotationsbehandlung:**
- `ThumbnailHelper.loadRotatedBitmap()` liest den EXIF-Orientation-Tag und rotiert das Bitmap per `Matrix.postRotate()` bevor Koordinaten angewendet werden
- ML Kit erhält die `InputImage` inklusive Rotation → gelieferte Koordinaten sind im rotierten Bildraum
- Beim Normalisieren der ML-Kit-Koordinaten in `PhotoSyncWorker.runMlKit()`: `imgW` und `imgH` werden bei 90°/270°-Rotation vertauscht, damit die normalisierten MWG-Werte konsistent zum sichtbaren Bild sind
- Fullscreen-Rendering nutzt Coil, das die Rotation automatisch anwendet – die `intrinsicSize` aus `onSuccess` ist bereits die rotierte Größe

**Beim Löschen einer FaceRegion:**
1. Thumbnail-Datei löschen
2. DB-Eintrag löschen

### Schritt 2 – Embedding-Berechnung

- Verarbeitet alle FaceRegions wo `embedding IS NULL`
- Gesichtsausschnitt direkt aus Originalbild (ohne Padding), skaliert auf 160×160px
- Standardisierung: `x' = (x - mean) / std_dev`
- FaceNet512 berechnet 512-dim Float-Vektor
- Embedding als BLOB in `FaceRegion.embedding` speichern

### Schritt 3 – Clustering (Chinese Whispers)

**Vorbedingung:** Der Worker prüft zu Beginn ob noch FaceRegions ohne Embedding existieren. Falls ja, wird `Result.retry()` zurückgegeben und der Worker läuft nach 30s erneut. So wird sichergestellt, dass Clustering nie auf unvollständigen Daten läuft.

- Clustert nur FaceRegions wo `personId IS NULL` und `ignored = false`
- **Threshold: 0.30** (Cosine Distance) – konservativ gewählt, um False Positives zu vermeiden
- **Mindestgröße Cluster: 2** – Singleton-Cluster werden nicht als Vorschlag angelegt, sondern bleiben als „Unbekannt" bis bei weiteren Foto-Syncs mehr Belege für diese Person auftauchen
- Ordnet ähnliche Gesichter bestehenden benannten Persons zu oder legt neue Vorschlag-Persons an
- Neue Vorschlag-Person: `Person.name = null` – erscheint in der Vorschlagsliste, nicht im Grid
- Ergebnis: `FaceRegion.personId` gesetzt, `FaceRegion.name` bleibt `null` (= Vorschlag)
- Centroid pro Person berechnen:
  - Bestätigte Gesichter vorhanden (`name IS NOT NULL`, `ignored = false`) → Centroid aus diesen
  - Keine bestätigten Gesichter → Centroid aus allen unbestätigten Gesichtern (`name IS NULL`, `ignored = false`)
- `Person.representativeFaceId` = FaceRegion mit geringstem Abstand zum Centroid

**Clustering auch auslösbar durch Nutzer (läuft ebenfalls im Hintergrund).**

---

## XMP-Schreibkonventionen

Die App schreibt Personen-Tags im **DigiKam-kompatiblen Format**, sodass die Daten mit DigiKam, Lightroom und Aves kompatibel sind:

| Feld | Format | Beispiel | Zweck |
|---|---|---|---|
| `mwg-rs:Regions/mwg-rs:RegionList` | strukturiertes Array, Typ `Face` | Bounding Box + optionaler Name | MWG-Standard für Gesichtsregionen |
| `Iptc4xmpExt:PersonInImage` | Bag von Strings | `Max` | Semantisch korrekt: benannte Personen |
| `dc:subject` | Bag von Strings, **nur Blattname** | `Max` | Aves zeigt als Tag, DigiKam als flacher Tag |
| `digiKam:TagsList` | Seq von Strings, **Pfad mit `/`** | `People/Max` | DigiKam Hierarchie-Format |
| `lr:hierarchicalSubject` | Bag von Strings, **Pfad mit `\|`** | `People\|Max` | Lightroom/Darktable Hierarchie |

**Regel:** Nach jedem XMP-Schreibvorgang `Photo.modifiedAt` neu aus `File.lastModified()` lesen und in DB aktualisieren.

**PersonInImage, dc:subject, digiKam:TagsList und lr:hierarchicalSubject** werden immer frisch aus den bestätigten FaceRegions des Fotos berechnet, nie direkt aus der DB gelesen.

**Nicht-Personen-Tags bleiben unangetastet:** Beim Bereinigen von `dc:subject` werden nur die Einträge entfernt, die aktuell auch in `digiKam:TagsList` unter `People/` stehen. Ein Tag wie `Urlaub` bleibt so erhalten.

**Namespaces:**
- `mwg-rs` → `http://www.metadataworkinggroup.com/schemas/regions/`
- `Iptc4xmpExt` → `http://iptc.org/std/Iptc4xmpExt/2008-02-29/`
- `dc` → `http://purl.adobe.com/dc/elements/1.1/`
- `digiKam` → `http://www.digikam.org/ns/1.0/`
- `lr` → `http://ns.adobe.com/lightroom/1.0/`

---

## Centroid-Neuberechnung

Wird ausgelöst wenn sich die Zusammensetzung der bestätigten Gesichter einer Person ändert:
- Gesicht bestätigt
- Gesicht von Person entfernt
- Gesicht zu anderer Person zugeordnet → beide betroffenen Persons neu berechnen
- Gesicht ignoriert (war bestätigt)
- Gesicht nicht mehr ignoriert (war bestätigt)

Basis:
- Bestätigte Gesichter vorhanden (`name IS NOT NULL`, `ignored = false`) → Centroid aus diesen
- Keine bestätigten Gesichter → Centroid aus allen unbestätigten Gesichtern (`name IS NULL`, `ignored = false`)

---

## Virtuelle Personen

Rein zur Laufzeit aus DB zusammengesucht, kein eigener DB-Eintrag.

| Name | Abfrage | Thumbnail |
|---|---|---|
| Unbekannt | `personId = null AND ignored = false` | Grauer Kreis |
| Ignoriert | `ignored = true` (immer auch `personId = null`, `name = null`) | Dunkelgrauer Kreis |

- Nicht löschbar, nicht umbenennbar
- Erscheinen am Ende des Personen-Grids

---

## Bibliotheken

### XMP-Metadaten
`com.ashampoo:xmpcore-jvm` – Kotlin-nativer Port der Adobe XMP Core Java Bibliothek. Schreibt und liest XMP-Metadaten in JPEG-Dateien über `ExifInterface`. Vollständiger XMP-Pfad für MWG-Regionen: `mwg-rs:Regions/mwg-rs:RegionList[n]/mwg-rs:RegionExtensions`.

---

## ML-Modelle

### ML Kit Face Detection
- Gesichtserkennung (Koordinaten/Bounding Boxes)
- On-device, kostenlos, keine Cloud-Verbindung

### FaceNet512 (TFLite)
- Embedding-Berechnung
- Wird beim ersten App-Start heruntergeladen (nicht im APK enthalten)
- Ablage nach Download: `context.filesDir/facenet_512.tflite` (~90 MB)
- Quelle: `https://github.com/shubham0204/OnDevice-Face-Recognition-Android`
- `ModelDownloadWorker` läuft als erster Schritt der Sync-Pipeline und lädt das Modell falls noch nicht vorhanden
- Fortschritt erscheint als Benachrichtigung in der Statusleiste
- Bei Netzwerkfehler retry mit LinearBackoff (30s)
- Erforderliche Berechtigung: `INTERNET`

```
Model:      FaceNet512 (TFLite, FP16)
Source:     https://github.com/shubham0204/OnDevice-Face-Recognition-Android
URL:        https://github.com/shubham0204/OnDevice-Face-Recognition-Android/raw/main/app/src/main/assets/facenet_512.tflite
Local:      context.filesDir/facenet_512.tflite (after download)
Origin:     DeepFace library (https://github.com/serengil/deepface)
Updated:    2025-12
Embedding:  512-dimensional float vector
Input:      160x160 RGB, standardized (x' = (x - mean) / std_dev)
License:    MIT (shubham0204 repo), Apache 2.0 (DeepFace)
```

---

## Robustheit

Die App ist so ausgelegt, dass externe Fehler (fehlerhafte JPEGs, fehlerhafte XMP-Metadaten, ML Kit Failures, Netzwerkfehler) niemals zum Absturz führen:

- Jeder externe Aufruf (`ExifInterface`, `ML Kit`, `XMPMetaFactory`, `BitmapFactory`, `FaceNetModel`) ist in einem eigenen `try-catch(Throwable)` gekapselt
- Fehler bei einer einzelnen Datei oder Region überspringen nur diese, nicht den ganzen Job
- WorkManager gibt `Result.failure()` oder `Result.retry()` bei unbehandelbaren Fehlern zurück, aber nie einen ungefangenen Crash
- Der `detector` in `PhotoSyncWorker` ist nullable – wenn ML Kit nicht initialisiert werden kann, wird der Schritt übersprungen statt zu crashen
- `setForeground()`-Aufrufe sind in try-catch eingewickelt – falls die Benachrichtigung nicht angezeigt werden kann (z.B. fehlender Kanal), läuft der Worker trotzdem weiter

---

## Wiederverwendbare UI-Komponenten

### `MultiSelectState<T>` (`ui/common/`)
Generische, in beliebige ViewModel-States einbettbare Klasse für Mehrfachselektion:
- `selectedIds: Set<T>` – aktuell selektierte IDs
- `lastSelectedId: T?` – zuletzt selektiertes Item (Basis für Range-Select)
- `isActive: Boolean` – ob Multiselect aktiv ist
- Methoden: `toggle(id)`, `rangeSelect(id, orderedIds)`, `clear()`
- Wird verwendet in `PersonsViewModel`, `PersonDetailViewModel` und `PhotosViewModel`

### `CircleThumbnail` (`ui/common/`)
Kreisförmiges Thumbnail mit garantiertem 1:1-Seitenverhältnis via `Modifier.aspectRatio(1f)`. Wird überall dort verwendet, wo runde Gesichts- oder Personen-Thumbnails erscheinen (Grid, Bottom Sheets, Dialog).

### `CircleColorLabel` (`ui/common/`)
Farbiger Kreis mit zentriertem Text-Label. Wird für virtuelle Personen (Unbekannt/Ignoriert) verwendet.

### `MergeConfirmDialog` (`ui/common/`)
AlertDialog mit runder Vorschau der Zielperson und deren Name. Wird angezeigt wenn der Nutzer einen Namen wählt, der bereits einer anderen Person zugeordnet ist. Zwei Optionen: „Zusammenführen" oder „Abbrechen".

---

## Namenskonflikte

Wenn beim Bestätigen eines Vorschlags oder beim Umbenennen einer Person ein Name gewählt wird, der bereits einer anderen Person zugeordnet ist, öffnet sich `MergeConfirmDialog` mit dem repräsentativen Gesicht und Namen der bestehenden Person. Der Nutzer kann bestätigen (dann werden die Personen zusammengeführt) oder abbrechen (dann bleibt der Zustand unverändert).

**Betroffene Flows:**
- Vorschlag mit Namen bestätigen (`PersonsViewModel.confirmSuggestion`)
- Person umbenennen (`PersonsViewModel.renamePerson`)

**Nicht betroffen:**
- „Zu Person zuordnen" (AssignToPersonSheet) – hier ist die Semantik gerade eine bewusste Zuordnung zu bestehenden Personen; ein Konflikt-Dialog wäre redundant.

---

## Benachrichtigungen

Die App zeigt Fortschritt der Hintergrundverarbeitung als System-Benachrichtigungen:

- **Modell-Download** – Fortschrittsbalken in %
- **Foto-Sync** – aktueller Dateiname + Fortschritt in %
- **Embedding-Berechnung** – Fortschritt in %
- **Clustering** – unbestimmter Fortschritt (indeterminate)

**Icon:** `res/drawable-*/ic_notification.png` – einfarbig weiß auf transparent (Android-Standard für Notification-Icons).

**Kanal:** `sync` (`IMPORTANCE_LOW`, kein Sound).

**Tap-Verhalten:** Ein `PendingIntent` öffnet `MainActivity` (`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP`), sodass die App in den Vordergrund kommt bzw. gestartet wird.

**Foreground Service:** WorkManager läuft als `SystemForegroundService` mit `foregroundServiceType="dataSync"` (ab Android 14 Pflicht). Bei fehlgeschlagenem `setForeground()` läuft der Worker trotzdem weiter (Fallback, umschlossen mit try-catch).

---

## UI / Screens

### Navigation
Bottom Navigation mit zwei Tabs: Personen und Fotos.

---

### Screen: Personen-Übersicht

**Oberer Bereich – bestätigte Personen (Grid, 3 Spalten)**
- Kreisförmiges Thumbnail (repräsentatives Gesicht)
- Name darunter
- Sortierung: Anzahl bestätigter Gesichter (absteigend) → Name (alphabetisch) → neuestes Foto (absteigend)

**Unterer Bereich – Vorschläge (Liste)**
- Pro Vorschlag eine Zeile
- Links: kreisförmiges Thumbnail (Centroid aus allen Gesichtern des Clusters)
- Rechts: Texteingabefeld für den Namen
- Enter → Person anlegen oder bestehende Person finden, alle FaceRegions des Clusters bestätigen

**Am Ende des Grids – Virtuelle Personen**
- Unbekannt (grauer Kreis)
- Ignoriert (dunkelgrauer Kreis)

**Interaktionen:**
- Einfacher Tap auf Thumbnail → Screen Person-Detail
- Longpress auf Person → Multiselect-Modus aktivieren
- Weiterer Longpress im aktiven Multiselect → Range-Select (alle Personen zwischen dem zuletzt selektierten und dem aktuellen)
- Multiselect → Aktion: Zusammenführen

**Person umbenennen:**
- Einfacher Tap auf Namen im Grid → Bottom Sheet mit vorausgefülltem Texteingabefeld
- Bestätigen → `Person.name` + alle `FaceRegion.name` + XMP aktualisieren

**Personen zusammenführen (Multiselect):**
- Bottom Sheet: Liste der selektierten Personen, Nutzer wählt welche den Namen behält
- Alle FaceRegions der anderen Persons → `personId` auf gewinnende Person
- Bestätigte FaceRegions → `name` auf Namen der gewinnenden Person
- Unbestätigte FaceRegions → bleiben unbestätigt, nur `personId` wechselt
- Centroid neu berechnen, leere Persons löschen
- XMP aller betroffenen Fotos aktualisieren

---

### Screen: Person-Detail (Gesichter-Grid)

**Header:** Name der Person

**Abschnitt 1 – Unbestätigte Gesichter**
- Grid (3 Spalten), kreisförmige Thumbnails mit grünem Rand
- Sortierung: Fotoaufnahmedatum absteigend

**Abschnitt 2 – Bestätigte Gesichter**
- Neue Zeile, visuell abgesetzt (Trennlinie + Label)
- Grid (3 Spalten), kreisförmige Thumbnails ohne farbigen Rand
- Sortierung: Fotoaufnahmedatum absteigend

**Interaktionen:**

Einfacher Tap → Bottom Sheet mit Aktionen:
- Bestätigen (`FaceRegion.name = Person.name`, XMP schreiben)
- Ignorieren (`ignored = true`)
- Von Person entfernen (`personId = null`, `name = null`, XMP aktualisieren, verwaiste Person löschen)
- Gesichter neu bestimmen (Reset + Re-Sync für dieses Foto)
- Zu Person zuordnen (Bottom Sheet Personenliste)

Longpress → Multiselect-Modus:
- Erster Longpress aktiviert Multiselect, weitere Taps toggeln einzelne Gesichter
- Weiterer Longpress im aktiven Multiselect: **Range-Select** – alle Gesichter zwischen dem zuletzt selektierten und dem aktuellen Item werden selektiert
- Selektierte Gesichter: blauer transparenter Overlay
- Aktionsleiste am unteren Rand mit denselben Aktionen wie Einzel-Tap

Foto öffnen: Über die Aktion „Foto öffnen" im Bottom Sheet.

Range-Select-Reihenfolge: unbestätigte Gesichter zuerst, dann bestätigte – analog zur Darstellung im Grid.

---

### Bottom Sheet: Zu Person zuordnen

- Fährt von unten herein
- Freitext-Suchfeld (filtert Liste in Echtzeit)
- Scrollbare Liste aller Personen:
  - Links: rundes Thumbnail (repräsentatives Gesicht)
  - Rechts: Name
  - Sortierung: Anzahl bestätigter Gesichter → Name → neuestes Foto
- Kein Treffer → „Neue Person anlegen: [Name]" als erster Eintrag
- Tippen → Person zuordnen, XMP schreiben, Dialog schließen

---

### Screen: Foto-Vollbild

- Foto in Vollbild
- Gesichtsrahmen als Overlay:
  - Grün: erkannte Gesichter
  - Magenta: aktuelles Gesicht (von dem aus navigiert wurde)
  - Grau: ignorierte Gesichter
- Einzige Aktion: „Gesichter neu bestimmen"

**Gesichter neu bestimmen:**
1. Alle FaceRegions löschen (inkl. Thumbnails)
2. `mwg-rs`-Regionen aus XMP entfernen
3. `PersonInImage` + `People/`-Subjects aus XMP entfernen
4. `analyzed = false` setzen
5. WorkManager-Pipeline für dieses einzelne Foto starten (PhotoSync → Embedding → Clustering)
6. Fortschritt in der Statusleiste

Der WorkManager-Job erkennt anhand von `KEY_PHOTO_ID` im InputData, dass nur ein einzelnes Foto verarbeitet werden soll, und überspringt den vollen DCIM/Camera-Scan.

---

### Screen: Fotos

**Layout**
- Grid mit 2 Fotos pro Zeile
- Jedes Foto als Quadrat (mittiger Ausschnitt, `ContentScale.Crop`)
- Sortierung: Aufnahmedatum absteigend (jüngstes zuerst), Fotos ohne Datum ganz am Ende

**Monats-Trenner**
- Zwischen Fotos verschiedener Monate erscheint ein voller Trenner über die Bildschirmbreite
- Format: „Januar 2026" (lokalisiert)

**Jahreszahl beim Scrollen**
- Während des Scrollens wird die aktuelle Jahreszahl als semitransparenter Overlay oben rechts eingeblendet
- Verschwindet nach kurzem Stillstand wieder

**Multiselect**
- Erster LongPress aktiviert Multiselect, weiterer LongPress selektiert Bereich (wie im Gesichter-Screen)
- Selektierte Fotos: blauer transparenter Overlay
- Aktionsleiste am unteren Rand: „Gesichter neu bestimmen"
- Gesichter neu bestimmen: wie im Vollbild-Screen, für alle selektierten Fotos

**Interaktionen**
- Einfacher Tap → Foto-Vollbild (ohne aktuelles Gesicht, alle Rahmen grün/grau)
- LongPress → Multiselect aktivieren
