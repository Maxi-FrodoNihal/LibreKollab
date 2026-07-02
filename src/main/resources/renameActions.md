# Umbenennung: Libere → Libre

Tracking-Dokument für die schrittweise Umbenennung von `Libere`/`libere` zu `Libre`/`libre` im gesamten Projekt (Code, Packaging, Config, Docs). Wird abgehakt, sobald ein Schritt erledigt und verifiziert ist.

**Kontext:** Plugin ist noch nicht veröffentlicht, GitHub-Repo ist noch privat → Breaking Changes (Extension-ID, Repo-Name) sind unkritisch und können mitgenommen werden.

**Zielname:** `LibreKollab` / Package `org.msc.librekollab`

---

## Phase 1 — Kotlin-Package & Klassennamen

Größter Brocken, am besten per IDE-Refactoring (Rename), nicht per Hand.

- [ ] Package `org.msc.liberekollab` → `org.msc.librekollab`
  - Verzeichnis `src/main/kotlin/org/msc/liberekollab/` → `src/main/kotlin/org/msc/librekollab/`
  - Verzeichnis `src/test/kotlin/org/msc/liberekollab/` → `src/test/kotlin/org/msc/librekollab/`
  - ⚠️ Nach IDE-Refactor prüfen, ob dabei `.iml`-Dateien verändert wurden — laut Regel vor jeder `.iml`-Änderung kurz Bescheid geben
- [ ] Klasse/Datei `LibereKollab.kt` → `LibreKollab.kt`
- [ ] Klasse/Datei `LibereKollabPlugin.kt` → `LibreKollabPlugin.kt`
- [ ] Alle `package`-Deklarationen und Imports in folgenden Dateien aktualisieren (passiert bei IDE-Rename automatisch, sonst manuell prüfen):
  - `CoreKollab.kt`, `LibereKollab.kt`, `LibereKollabPlugin.kt`, `OptionsHandler.kt`
  - `LogDirResolver.kt`, `LoggingKollabAPI.kt`, `McpServer.kt`
  - `KollabAPI.kt`, `Comment.kt`, `TextAnchor.kt`
  - `change/Change.kt`, `change/ChangeAction.kt`, `change/ChangeStatus.kt`
  - `text/MarkIndex.kt`, `text/MarkedText.kt`
  - `text/properties/{Bold,Italic,Strikethrough,Underline}Property.kt`, `TextProperty.kt`
  - `McpServerIT.kt`, `adapter/libreoffice/TestKollab.kt`
- [ ] `./gradlew test` grün

## Phase 2 — Gradle Build-Konfiguration

- [ ] `settings.gradle.kts`: `rootProject.name = "LibereKollab"` → `"LibreKollab"`
- [ ] `build.gradle.kts`:
  - `group = "org.msc.liberekollab"` → `"org.msc.librekollab"`
  - `archiveBaseName.set("liberekollab-all")` → `"librekollab-all"`
  - `archiveBaseName.set("LibereKollab")` → `"LibreKollab"`
  - `attributes["Implementation-Title"] = "LibereKollab"` → `"LibreKollab"`
  - `attributes["RegistrationClassName"] = "org.msc.liberekollab...LibereKollabPlugin"` → neuer Package-/Klassenpfad
  - `rename { "liberekollab-all.jar" }` → `"librekollab-all.jar"`
- [ ] `./gradlew oxt` läuft durch, Artefaktname ist `LibreKollab-1.0-SNAPSHOT.oxt`

## Phase 3 — OXT-Packaging

⚠️ Extension-ID-Änderung heißt für LibreOffice: **neue Extension**, nicht nur Umbenennung. Alte Installation muss vorher deinstalliert werden (siehe Phase 5/7).

- [ ] `oxt/LibereKollab.components` → `oxt/LibreKollab.components` umbenennen; Inhalt anpassen:
  - `uri="liberekollab-all.jar"` → `"librekollab-all.jar"`
  - Implementation-Namen `org.msc.liberekollab.*` → `org.msc.librekollab.*`
  - Service-Namen `org.msc.liberekollab.LibereKollabPlugin` / `.OptionsHandler` → neue Namen
- [ ] `oxt/META-INF/manifest.xml`: Referenz auf `LibereKollab.components` → `LibreKollab.components`
- [ ] `oxt/description.xml`:
  - `<identifier value="org.msc.liberekollab"/>` → `org.msc.librekollab`
  - `<display-name>` Texte `LibereKollab` → `LibreKollab` (en + de)
- [ ] `oxt/description-en.txt`: Fließtext "LibereKollab exposes..." → "LibreKollab exposes..."
- [ ] `oxt/Jobs.xcu`:
  - Node-Name `LibereKollabStartup` → `LibreKollabStartup`
  - Klassenreferenz `org.msc.liberekollab.LibereKollabPlugin` → neu
- [ ] `oxt/OptionsDialog.xcu`:
  - Node/Service-Name `org.msc.liberekollab.McpServer` → `org.msc.librekollab.McpServer`
  - Sichtbarer UI-Text `"LibereKollab MCP Server"` → `"LibreKollab MCP Server"`
  - Referenz auf `OptionsHandler`-Implementierung
- [ ] `oxt/Addons.xcu`:
  - Menü-Node `org.msc.liberekollab.menu` → `org.msc.librekollab.menu`
  - Sichtbarer Menütext `"LibereKollab"` → `"LibreKollab"` (en + de)
  - Service-URL `service:org.msc.liberekollab.LibereKollabPlugin?settings` → neu
- [ ] Manuell verifizieren: `.oxt` bauen, in LibreOffice installieren, Options-Dialog öffnet sich, Server startet (siehe "Local development" in `CLAUDE.md`)

## Phase 4 — Laufzeit-Pfade & Config

- [ ] `LogDirResolver.kt`: `~/.config/liberekollab` → `~/.config/librekollab`, `%APPDATA%\liberekollab` → `%APPDATA%\librekollab`
- [ ] `logback.xml`: `liberekollab.log`, `liberekollab.%d{yyyy-MM-dd}.%i.log` → `librekollab.*`
- [ ] System-Property `liberekollab.port` → `librekollab.port` (Lesestelle + README-Tabelle)
- [ ] Hinweis: alter Log-Ordner `~/.config/liberekollab` bleibt lokal liegen — bei Gelegenheit manuell aufräumen, kein Code-Migrationsbedarf

## Phase 5 — Deploy-Skript

- [ ] `deploy.sh`: `unopkg remove org.msc.liberekollab` → alte ID (einmalig, für Cleanup) dann neue ID für künftige Removes
- [ ] `deploy.sh`: Pfad `build/oxt/LibereKollab-1.0-SNAPSHOT.oxt` → `LibreKollab-1.0-SNAPSHOT.oxt`

## Phase 6 — Dokumentation

- [ ] `README.md`: Titel, Fließtext, MCP-Registrierbefehl (`claude mcp add ... liberekollab ...` → `librekollab`), Pfadangaben, Options-Menüpfad
- [ ] `CLAUDE.md` (Projekt-Root): alle Vorkommen (Architekturdiagramm, Package-Struktur, Beschreibungstexte)

## Phase 7 — Extern (optional, später)

- [ ] Lokal installierte Extension mit alter ID (`org.msc.liberekollab`) deinstallieren, neue installieren
- [ ] GitHub-Repo `Maxi-FrodoNihal/LibereKollab` → `LibreKollab` umbenennen
- [ ] Lokalen Git-Remote nach Repo-Rename aktualisieren (`git remote set-url origin ...`)

---

## Abschlussverifikation

- [ ] `./gradlew test` grün
- [ ] `./gradlew oxt` baut fehlerfrei, Artefaktname korrekt
- [ ] `grep -ril "libere" . --include="*" | grep -v -E "/\.git/|/build/"` liefert keine Treffer mehr (außer ggf. dieser Datei, danach löschen)
- [ ] Manueller Test in echter LibreOffice-Instanz (Install, Options-Dialog, Server-Start, MCP-Verbindung)
