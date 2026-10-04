# APK bauen (Windows)

Die App ist eine native Android-App ohne externe Laufzeitbibliotheken. Der Build nutzt direkt die offiziellen Android-Werkzeuge und einen portablen Java-Compiler. Android Studio und Gradle sind nicht erforderlich.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1
```

Beim ersten Lauf werden Microsoft OpenJDK 21.0.12.1, Android Build Tools 36.0.0 und Android Platform 36 r02 ausschließlich nach `.tools/` geladen. SHA-256-Prüfsummen sind im Setup-Skript festgelegt. Die Android-Archive wurden zusätzlich gegen die SHA-1-Prüfsummen aus Googles SDK-Repository geprüft. Die Quellarchive enthalten die jeweiligen Lizenztexte. Es gibt keine globale Installation und keine dauerhaften Änderungen am PATH.

Der Build erzeugt `dist/UB-BPM-Counter-v1.2.4.apk` sowie eine SHA-256-Datei. Er kompiliert Ressourcen mit AAPT2, Java mit `javac`, DEX mit D8, richtet das APK mit `zipalign` aus und signiert es mit `apksigner` (v1, v2 und v3). Anschließend werden Signatur und Ausrichtung geprüft. Weitere Builds funktionieren offline mit `-SkipSetup`. Die vorherigen APKs bleiben erhalten.

- Paket: `de.mk2.bpm`, Version 1.2.4 / Code 8, App-Name: UB BPM Counter
- Minimum: Android 6.0 / API 23
- Ziel- und Compile-API: Android 16 / API 36
- Kein Native-Code: universelles APK für ARM, ARM64, x86 und x86_64
- Berechtigungen: Vibration und Mikrofon. Mikrofonzugriff wird erst beim Start der Erkennung angefragt. Keine Internetberechtigung.

## Icon in 1.2.4

Die Vordergrundgrafik wird um den Mittelpunkt auf 78 Prozent ihrer bisherigen Breite und Höhe skaliert. Das Display erhält so mehr silbernen Abstand zum Launcher-Rand. Form, Farben und Proportionen bleiben erhalten; App-Oberfläche und Funktionen ändern sich nicht. Die Branding-Vorschau exportiert zusätzlich `ub-icon-circle-96.png`, um die Lesbarkeit unter einer runden Maske in Homescreen-Größe zu prüfen.

## Icon in 1.2.3

Das große UB entfällt im Launcher-Icon. Ein größeres, eingelassenes dunkles Display mit orangefarbener „128“ steht jetzt im Mittelpunkt. Der gebürstete Silberhintergrund, Metallbevels, dezente Glasreflexion und kleine BPM-Gravur beziehen sich auf die App. Die native Vektorgrafik ersetzt nur `icon_face.xml`; die App-Oberfläche und die Funktionen bleiben unverändert.

## Name und Icon in 1.2.2

Launcher und Instrumentkopf heißen jetzt „UB BPM Counter“. Das Icon besteht aus nativen Vektoren: gebürstete Silberoberfläche, dunkles Display und orangefarbene Siebensegment-Zahl „128“. Android 8 und neuer verwendet ein adaptives Icon mit getrennten Hintergrund-/Vordergrundebenen; seine zentralen Elemente bleiben innerhalb der sicheren Maskenfläche. Android 6/7 verwendet die klassische Variante, Android 6 ohne Farbverlauf. Paket-ID und Signierschlüssel bleiben für ein direktes Update erhalten. Tempo-/Audio-/Tap-/LED-Logik bleiben unverändert.

Die visuelle Android-Prüfung startet mit `scripts/test-android.ps1 -BrandingOnly` und exportiert das echte Launcher-Icon sowie Hoch-/Querformatbilder nach `tests/artifacts/`.

## LED-Latenz korrigiert in 1.2.1

Die LED wartet nicht mehr auf das mehrsekündige Analysefenster der BPM-Erkennung. `LiveBeatTracker` erkennt frische Spitzen der vorhandenen Onset-Hüllkurven, bestätigt sie mit dem nächsten Analyseframe und liefert einen eigenen LED-Zeitstempel. `MicrophoneAnalyzer` sendet diese Ereignisse direkt nach jedem PCM-Puffer; die BPM-/Pegelupdates behalten ihren bisherigen Abstand von 250 ms. Vor dem ersten BPM-Wert blitzt die LED bereits bei tatsächlichen Audio-Attacks. Sobald ein Tempo vorliegt, richtet sie das bestehende Blinkraster an diesen frischen Signalen aus. Die BPM-Berechnung, ihre Daten und ihr historischer Phasenwert bleiben unverändert; die LED verwendet den historischen Phasenwert nicht mehr.

Ab Android 7 nutzt `MicrophoneAnalyzer` verfügbare [AudioRecord-Zeitstempel](https://developer.android.com/reference/android/media/AudioRecord#getTimestamp(android.media.AudioTimestamp,%20int)), um den PCM-Anker mit der UI-Uhr zu verbinden. Android 6 und Geräte ohne gültigen Audio-Zeitstempel nutzen näherungsweise den Lesezeitpunkt vor der DSP-Verarbeitung. Mehr als 200 ms alte Live-Callbacks werden übersprungen. Hardware-/Mikrofonlatenz und musikalische Mehrdeutigkeit können die absolute Ausrichtung begrenzen.

`BeatPulseClock` berechnet absolute Einzeltermine aus der auf eine Dezimalstelle angezeigten BPM. Die Halb-/Doppeltempo-Einstellung gilt auch für die LED; regelmäßige Phasenupdates bewahren bei halbem Tempo die Wahl jedes zweiten Schlags. Verzögerte UI-Termine werden übersprungen, Phasenkorrekturen erzeugen keine eng aufeinanderfolgenden Doppelblitze. Die LED leuchtet für höchstens 90 ms pro Puls. Die App setzt pro Puls einen Timer für den nächsten Beat und einen für das Erlöschen; ein zusätzlicher Audio-Thread oder permanentes Frame-Polling ist nicht erforderlich.

Stoppen, Reset, Tippen und Verlassen/Drehen der App beenden die automatischen Pulse. Ohne Tempo gibt es nur einzelne Live-Attacks, keinen vorausschauenden Timer; bei zu leisem Audio endet das Blinkraster. Die LED verändert weder Tap-Zähler noch Haptik. Die manuelle Flash-Funktion und die Oberfläche bleiben unverändert.

Die Tests prüfen Audio-Attacks bei 44,1 und 48 kHz vor dem ersten BPM-Wert sowie nach einem Phasenwechsel im laufenden Signal: Die Ereignisse wurden innerhalb von 160 ms nach den bekannten Signalstarts geliefert. 190 Audio-, 17 Pulsuhr-, 64 Tap-Estimator- und 220 Android-Integrationsprüfungen bestehen. Das Android-Testprogramm prüft auch den direkten LED-Callback vor dem ersten BPM-Wert und die sichtbare orange LED. Die Mikrofon-/Displaylatenz des Galaxy S23 wurde hier nicht gemessen.

## Tap-Fix in 1.1.1

Die Eingabe zählt auf `ACTION_DOWN` und `ACTION_POINTER_DOWN`, ohne Druck-/Größenschwelle. Pointer-IDs halten pro Kontakt fest, ob er schon gezählt wurde. `MOVE`, `UP` und `CANCEL` erzeugen keinen weiteren Beat. Motion-Event-Splitting im Instrument ist deaktiviert; zusätzliche Kontakte außerhalb der TAP-Fläche werden innerhalb einer laufenden Pad-Geste ignoriert. Eine native `TouchDelegate` erweitert die Trefferfläche um 12 dp links/rechts, 7 dp oben und 3 dp unten, ohne die benachbarten Bedienelemente zu überdecken.

Der Estimator verwirft neue Kontakte nicht länger pauschal für 140 ms. Sehr kurze Kontakte zählen, verschieben aber bei Intervallen unter 160 ms nicht den Anker für die BPM-Schätzung (Messbereich 30–300 BPM). Dadurch bleibt ein laufender Beat bei raschen Zusatzkontakten stabil. Der Zustandsdatensatz erhält einen zusätzlichen Zeitstempel und kann weiterhin Zustände der bisherigen Version lesen. Die zusätzliche Eingabeverarbeitung besteht aus konstanten Pointer-/Grenzprüfungen; es gibt keinen neuen Polling-Thread oder Sensorzugriff. Die App kann ausschließlich Berührungen verarbeiten, die Android an das Fenster liefert.

## Mikrofon-Erkennung

`MicrophoneAnalyzer` liefert mono PCM16 über AudioRecord an `AudioTempoDetector`. Die Analyse läuft auf einem eigenen Thread. Drei Frequenzbänder liefern Spektraländerungen; normalisierte Autokorrelation sucht wiederkehrende Schläge in höchstens 22 Sekunden Audiokontext. Gespeichert werden nur Analysewerte im RAM, keine Audiodateien. Ein erster Wert ist bei klaren Beats nach etwa 6–8 Sekunden möglich; längeres Zuhören verbessert die Stabilität. Der Suchbereich beträgt 45–220 BPM, mit Halb-/Doppeltempo-Anzeige über die TEMPO-Taste. Die Stabilitätsanzeige ist ein Maß für die rhythmische Regelmäßigkeit, keine garantierte Genauigkeit.

Die Aufnahme endet beim Stoppen, Reset, Wechsel zum Tippen, Verlassen der App oder Drehen des Displays. Der letzte Wert bleibt sichtbar; zurück in der App muss die Erkennung erneut gestartet werden. Bei längerer Stille wird ein veralteter Messwert gelöscht. Schwache, unregelmäßige oder stark überlagerte Rhythmen können ohne Ergebnis bleiben; halbes und doppeltes Tempo können musikalisch mehrdeutig sein.

## Signierschlüssel sichern

Der erste Build erzeugt einen individuellen lokalen RSA-Schlüssel in `.signing/mk2-release.p12` mit zufälligem Passwort in `.signing/keystore-password.txt`. **Beide Dateien gemeinsam privat sichern.** Sie werden weder ins APK noch ins Git aufgenommen. Updates auf dem Handy müssen mit demselben Schlüssel signiert werden; ein neu erzeugter Schlüssel erfordert die Deinstallation der alten App.

## Offizielle Werkzeugquellen

- [Microsoft OpenJDK](https://learn.microsoft.com/en-us/java/openjdk/download)
- [Android SDK-Werkzeuge](https://developer.android.com/tools)
- [Google SDK-Paketmetadaten](https://dl.google.com/android/repository/repository2-3.xml)
- [AAPT2](https://developer.android.com/tools/aapt2)
- [D8](https://developer.android.com/tools/d8)
- [APK-Signaturen](https://developer.android.com/tools/apksigner)

Vor Verwendung der Android-SDK-Werkzeuge gelten die [Android-SDK-Lizenzbedingungen](https://developer.android.com/studio/terms).
