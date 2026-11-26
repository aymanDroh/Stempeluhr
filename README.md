# Stempeluhr

Private Android-App (für Samsung Galaxy, z. B. S22 und S25), die die eigenen Arbeitszeiten
**automatisch** mitschreibt, ohne Zugriff auf die Stechuhr der Firma zu haben. Die Daten liegen nur
auf dem Handy und lassen sich als CSV für Excel exportieren.

## Bedienung

Fünf Tabs am unteren Rand:

- **Heute:** Zifferblatt mit der Arbeitszeit des Tages und dem Fortschritt zum Soll, dazu immer beide
  Knöpfe **Kommen** und **Gehen** (der naheliegende ist hervorgehoben). Nach dem Stempeln erscheint
  unten eine Meldung mit **Rückgängig**. Darunter: Status der drei Quellen, die laufende Woche und
  der Verlauf des Tages. Einträge werden durch Antippen bearbeitet.
- **Verlauf:** alle Tage nach Monaten. Jeder Monat hat eine Zeile mit **Stunden und Saldo**; Antippen
  klappt seine Tage auf oder zu. Zugeklappt stehen die Monate direkt untereinander – so sieht man auf
  einen Blick, wie viel in welchem Monat gearbeitet wurde. Am Anfang ist nur der neueste Monat
  aufgeklappt, **„Alle zuklappen“ / „Alle aufklappen“** schaltet alle auf einmal; die App merkt sich
  die Wahl. Filter **„Gehen fehlt“** zeigt nur die Tage, an denen noch etwas nachzutragen ist.
- **Wochen:** die 26-Wochen-Regel für Werkstudenten (siehe unten). Im Diagramm lässt sich jede
  Woche antippen.
- **Abgleich:** erscheint, solange ein importierter Stundenzettel noch nicht bestätigt ist.
- **Optionen:** Übersicht mit Unterseiten für Einrichtung, WLAN, Standort, Kachel, Wertung, Import
  und Export. Unter „Darstellung“ lässt sich hell/dunkel festlegen.

## Erfassungsarten

| Quelle | Wie | Handy entsperren? | Genauigkeit |
|---|---|---|---|
| **WLAN** | Kommen = Verbindung mit dem Arbeits-WLAN, Gehen = getrennt | nein | gut (Betreten/Verlassen des Gebäudes) |
| **Standort (Geofence)** | Kommen/Gehen beim Betreten/Verlassen eines Kreises um die Arbeit | nein | ca. 1–5 Minuten |
| **Manuell** | Buttons in der App, Kachel in den Schnelleinstellungen, Bearbeiten/Nachtragen | Kachel: nein | so genau wie man tippt |

Alle Quellen werden parallel gespeichert. Für jeden Tag wird Kommen/Gehen nach einer einstellbaren
Prioritätsliste gewertet (Standard: Manuell → WLAN → Standort). Alle anderen Zeiten bleiben sichtbar.

Die frühere Erfassung per NFC-Aufkleber gibt es nicht mehr. Ältere NFC-Einträge bleiben gespeichert,
werden weiter angezeigt und gewertet und lassen sich aus einem Rohdaten-Export wieder einlesen.

Kurze WLAN- oder GPS-Aussetzer werden geglättet: Ein „weg“ zählt erst als Gehen, wenn die Quelle
länger als die eingestellte Verzögerung (Standard 5 Minuten) weg bleibt. Das Gehen erhält dann den
Zeitpunkt des ersten „weg“. Fehlt ein Gehen (z. B. weil das Handy aus war), wird **keine** Zeit
erfunden, sondern die Lücke bleibt sichtbar und kann manuell nachgetragen werden.

Korrekturen ändern die Zeit, die ursprüngliche Zeit bleibt gespeichert. Löschen markiert nur.
Beides ist im Rohdaten-Export nachvollziehbar.

## Werkstudent: 26-Wochen-Regel

Der Tab **Wochen** zählt rollierend über die letzten 52 Kalenderwochen (Mo–So), in wie vielen Wochen
mehr als 20 Stunden (brutto) gearbeitet wurde, und zeigt:

- wie viele Wochen über 20 h noch erlaubt sind (Standard: 26)
- die aktuelle Woche und wie viele Stunden noch gehen, bevor sie zählt
- wann welche Woche aus dem Zeitraum fällt, also wann wieder eine Woche frei wird
- alle 52 Wochen einzeln

Wochen in den Semesterferien zählen genauso mit. Grenze und Wochenzahl sind in den Einstellungen
änderbar.

## Import

*Einstellungen → Import → Datei importieren*:

- **Stundenzettel als PDF** (Datum + Dauer je Tag, optional Bemerkung wie „krank“). Die Monatssumme
  aus der Datei wird zur Kontrolle mit den gelesenen Tagen verglichen.
- **CSV** mit Spalten `Datum` und `Dauer` (h:mm), optional `Bemerkung`.
- **Rohdaten-Export dieser App:** stellt alle Ereignisse wieder her (ohne Einstellungen und
  importierte Tage). Bereits vorhandene Ereignisse werden sekundengenau erkannt und übersprungen.

Vor dem Speichern zeigt die App eine Vorschau mit allen Monaten und allen nicht verstandenen Zeilen.
Importierte Tage enthalten nur die Dauer. Bei Überschneidung mit der eigenen Erfassung zählt
standardmäßig der Import. Ob Krank- und Urlaubstage mitzählen, ist einstellbar.

## Abgleich mit dem Stundenzettel der Firma

Nach dem Import eines Monats öffnet sich der Tab **Abgleich**. Dort wird jeder Tag der Firmendaten
mit der eigenen Erfassung verglichen:

| Status | Bedeutung |
|---|---|
| stimmt | Dauer gleich (innerhalb der Toleranz, Standard ±5 min) |
| Abweichung | eigene Dauer und Firmen-Dauer unterscheiden sich |
| fehlt bei Firma | selbst erfasst, aber nicht im Stundenzettel |
| nur bei Firma | im Stundenzettel, aber nichts selbst erfasst |
| eigene Erfassung unvollständig | Kommen ohne Gehen |
| krank/Urlaub laut Firma | Bemerkung im Stundenzettel, keine eigene Erfassung |

Dazu gibt es die Monatssummen beider Seiten, einen Filter „Nur Auffälligkeiten“ und einen CSV-Export
des Abgleichs (z. B. zum Weitergeben an die Personalabteilung). Die Firmendaten enthalten nur die
Dauer je Tag, deshalb wird die Dauer verglichen, nicht die Uhrzeit.

## Installation

1. **Einmalig: Signatur-Schlüssel als GitHub-Secrets hinterlegen**
   (`Settings → Secrets and variables → Actions`): `STEMPELUHR_KEYSTORE_BASE64` und
   `STEMPELUHR_KEYSTORE_PASSWORD`. Ohne festen Schlüssel lassen sich spätere Versionen nicht als
   Update installieren, weil sich die Signatur ändern würde. Den Schlüssel **nie** ins Repo legen,
   denn das Repo ist öffentlich.
2. Nach jedem Push auf `main` baut GitHub Actions die APK und veröffentlicht sie unter
   **Releases** (`Stempeluhr-<Nummer>.apk`).
3. Die APK auf dem Handy im Browser herunterladen und öffnen. Dabei die Installation aus dieser
   Quelle erlauben.

## Einrichtung auf dem Handy

In der App unter **Optionen → Einrichtung** müssen alle Punkte einen Haken haben (offene Punkte
antippen):

- Benachrichtigungen erlauben
- Standort „genau“ erlauben und danach **„Immer erlauben“**. Das ist auch für WLAN nötig, weil
  Android den WLAN-Namen sonst nicht herausgibt.
- Standortdienst eingeschaltet lassen
- Akku-Optimierung ausschalten. Zusätzlich bei Samsung: *Einstellungen → Akku →
  Hintergrundnutzungslimits → Nie in Standby-Modus versetzte Apps* → Stempeluhr hinzufügen.

Danach:

- **WLAN** (*Optionen → WLAN*): Im Arbeits-WLAN auf „Aktuelles WLAN übernehmen“ tippen oder den
  Namen eintippen.
- **Standort** (*Optionen → Standort*): An der Arbeit auf „Aktuellen Standort übernehmen“ tippen.
  Radius 150 m ist ein guter Startwert.
- **Kachel** (*Optionen → Kachel & manuell*): „Kachel hinzufügen“ tippen. Von Hand:
  Schnelleinstellungen aufziehen → Stift/Bearbeiten → Kachel „Stempeln“ hinzufügen.
- Optional: Seitentaste doppelt drücken → App öffnen (*Einstellungen → Erweiterte Funktionen →
  Seitentaste*).

Die dauerhafte Benachrichtigung „Stempeluhr läuft“ ist nötig, damit Android die WLAN-Erkennung
nicht beendet. Sie kann in den App-Benachrichtigungen ausgeblendet werden (Kanal
„Hintergrund-Erfassung“).

## Technik

- Kotlin, Jetpack Compose, minSdk 29, targetSdk 35
- WLAN: `ConnectivityManager.NetworkCallback` in einem Foreground-Service (`specialUse`)
- Standort: Google Play-Dienste Geofencing API
- Daten: SQLite (`stempel.db`), Export als CSV (Semikolon, UTF-8 mit BOM), Speichern als Datei oder
  Senden über den Teilen-Dialog (`FileProvider`)
- Oberfläche: Material 3 mit eigenem Farbschema (hell/dunkel), Schrift *Plus Jakarta Sans*
  (SIL Open Font License 1.1, siehe `licenses/`), Icons aus *Material Symbols* (Apache 2.0)
- Reine Logik (Auswertung, Glättung, CSV) in `app/src/main/java/de/droh/stempeluhr/core`, mit
  Unit-Tests in `app/src/test`

Lokal bauen (benötigt Android SDK): `./gradlew testDebugUnitTest assembleDebug`
