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
| name | String | Eindeutig |
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

### Zustände einer FaceRegion
| personId | name | ignored | Bedeutung |
|---|---|---|---|
| null | null | false | Unbekannt, nicht geclustert |
| gesetzt | null | false | Clustering-Vorschlag, unbestätigt |
| gesetzt | gesetzt | false | Bestätigt |
| null | null | true | Ignoriert |

**Regel:** Eine ignorierte FaceRegion hat immer `personId = null` und `name = null`.

---

## Unterstützte Dateiformate

Nur JPEG. Erkennung über Magic Bytes (erste 3 Bytes: `FF D8 FF`), nicht über Dateiendung.

---

## Hintergrundverarbeitung

Drei verkettete WorkManager-WorkRequests, gestartet beim App-Start.

### Schritt 1 – Foto-Sync

Abgleich Dateisystem ↔ DB für alle JPEG-Dateien im DCIM-Ordner.

| Fall | Aktion |
|---|---|
| Pfad neu | Photo anlegen, XMP einlesen, ML Kit ausführen |
| Pfad bekannt, Timestamp unverändert | nichts tun |
| Pfad bekannt, Timestamp geändert | `modifiedAt` aktualisieren, FaceRegions + Thumbnails löschen, verwaiste Persons löschen, dann wie „Pfad neu": XMP einlesen, ML Kit ausführen falls keine FaceRegions gefunden |
| Pfad in DB, Datei weg | Photo löschen, FaceRegions löschen, Thumbnails löschen |
| Nach jedem Durchlauf | verwaiste Persons löschen |

**XMP einlesen (pro neuem/geändertem Foto):**
- `mwg-rs:Regions` Typ `Face` → FaceRegion anlegen (name aus RegionName, nullable)
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
- Format: WebP, 128×128px
- Dateiname: `<faceRegionId>.webp`
- Ablage: `filesDir/thumbnails/`

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

- Clustert nur FaceRegions wo `personId IS NULL` und `ignored = false`
- Ordnet ähnliche Gesichter bestehenden Persons zu oder schlägt neue Persons vor
- Ergebnis: `FaceRegion.personId` gesetzt, `name` bleibt `null` (= Vorschlag)
- Centroid pro Person berechnen:
  - Bestätigte Gesichter vorhanden (`name IS NOT NULL`, `ignored = false`) → Centroid aus diesen
  - Keine bestätigten Gesichter → Centroid aus allen unbestätigten Gesichtern (`name IS NULL`, `ignored = false`)
- `Person.representativeFaceId` = FaceRegion mit geringstem Abstand zum Centroid

**Clustering auch auslösbar durch Nutzer (läuft ebenfalls im Hintergrund).**

---

## XMP-Schreibkonventionen

| Tag | Inhalt | Wann |
|---|---|---|
| `mwg-rs:Regions` Typ Face | Gesichtsregion ohne Namen (ML Kit) oder mit Namen (bestätigt) | Beim Sync + bei Bestätigung |
| `Iptc4xmpExt:PersonInImage` | Alle bestätigten Namen des Fotos, dedupliziert | Beim Sync + bei Bestätigung |
| `dc:subject` `People/[Name]` | Analog PersonInImage | Beim Sync + bei Bestätigung |

**Regel:** Nach jedem XMP-Schreibvorgang `Photo.modifiedAt` neu aus `File.lastModified()` lesen und in DB aktualisieren.

**PersonInImage und People/-Subjects** werden immer frisch aus den bestätigten FaceRegions des Fotos berechnet, nie direkt aus der DB gelesen.

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

## ML-Modelle

### ML Kit Face Detection
- Gesichtserkennung (Koordinaten/Bounding Boxes)
- On-device, kostenlos, keine Cloud-Verbindung

### FaceNet512 (TFLite)
- Embedding-Berechnung
- Ablage: `app/src/main/assets/facenet_512.tflite`
- Begleitdatei: `app/src/main/assets/facenet_512_model_info.txt`

```
Model:      FaceNet512 (TFLite, FP16)
Source:     https://github.com/shubham0204/OnDevice-Face-Recognition-Android
File:       app/src/main/assets/facenet_512.tflite
Origin:     DeepFace library (https://github.com/serengil/deepface)
Updated:    2025-12
Embedding:  512-dimensional float vector
Input:      160x160 RGB, standardized (x' = (x - mean) / std_dev)
License:    MIT (shubham0204 repo), Apache 2.0 (DeepFace)
```

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
- Erstes Gesicht per Longpress, weitere per Tap
- Selektierte Gesichter: blauer transparenter Overlay
- Aktionsleiste am unteren Rand mit denselben Aktionen wie Einzel-Tap

Tap auf Thumbnail → Foto-Vollbild

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
5. ML Kit sofort ausführen
6. Neue FaceRegions anlegen, Thumbnails erstellen
7. Embeddings berechnen
8. Clustering durchführen
9. XMP neu schreiben
10. `Photo.modifiedAt` aktualisieren

Alles läuft im Hintergrund (WorkManager), Fortschritt in der Statusleiste.
