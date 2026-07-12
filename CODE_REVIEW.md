# Code Review (2026-07-11)

Ein Durchgang über das ganze Projekt: was gut läuft und was noch offene Punkte sind. Snapshot-Stand, kein lebendes Dokument — nach dem Abarbeiten der Punkte kann diese Datei wieder gelöscht werden.

## Gut

1. **Saubere Schichtenarchitektur** — `domain/`↔`adapter/` konsequent getrennt, DDD-Regeln (17–19 in `codingRules.md`) werden tatsächlich eingehalten, nicht nur aufgeschrieben.
2. **`codingRules.md` wird wirklich gelebt** — selten sieht man ein Regelwerk, das so konsequent auf einen ganzen Codebase angewendet wurde.
3. **Track-Changes-Handling ist durchdacht** — der Format-Redline-Bug in `insertFormattedText` ist erkannt, dokumentiert und gezielt umgangen (Formatierung *vor* dem Einfügen setzen, nicht danach).
4. **Echte Integrationstests statt Mocks** — `McpServerIT` läuft gegen ein echtes LibreOffice im Testcontainer. Hohe Aussagekraft, kein "im Test grün, in echt kaputt".
5. **`ToolSchemaGenerator`** — MCP-Schemas werden aus den Request-Klassen generiert statt doppelt gepflegt. Vermeidet Drift zwischen Code und Doku.
6. **Concurrency-Modell ist bewusst gewählt** — UNO-Zugriffe sauber auf `limitedParallelism(1)` serialisiert (Regel 25), passend zur inhärent nicht-threadsicheren UNO-API.
7. **Klare Verantwortlichkeiten** — `LoggingKollabAPI` als Decorator, `CoreKollab` als gemeinsame Basis, `LibreKollab`/`TestKollab` als dünne Adapter für Produktion/Test.

## Schlecht / Risiken

1. ~~**Sicherheitslücke, wichtigster Punkt** — `McpServer.startSse()` band ohne `host`-Parameter auf Ktors Default `0.0.0.0` (alle Netzwerk-Interfaces).~~ **Erledigt** — `host = LOCALHOST_HOST` (`"127.0.0.1"`) wird jetzt explizit an `embeddedServer` übergeben.
2. **`Addons.xcu` wird nie geladen** (`oxt/Addons.xcu` vs. `oxt/META-INF/manifest.xml`) — die Datei registriert einen "LibreKollab"-Menüeintrag mit "MCP Server Settings", ist aber **nicht** in `META-INF/manifest.xml` gelistet. LibreOffice liest beim Installieren nur, was im Manifest steht — der Menüeintrag existiert im Code, taucht aber nie in der UI auf. Entweder Manifest-Eintrag nachtragen oder die Datei als totes Feature entfernen.
3. **Testlücken** — `get_edit_mode`/`set_edit_mode` haben gar keinen Test in `McpServerIT.kt`. Fehlerpfade (Kommentar/ID nicht gefunden, Suche ohne Treffer, ungültiger `paragraphIndex`) sind nirgends abgedeckt.
4. **Nur Integrationstests** — reine Logik wie `findAllOccurrences`, `base64EncodedByteCount`, `formattingRuns`, `ImageScaler.scale()` (alle in `CoreKollab.kt` bzw. `ImageScaler.kt`) hat keine schnellen Unit-Tests — jeder Testlauf braucht den vollen LibreOffice-Container. Für schnelles Iterieren an solcher Logik wären isolierte Unit-Tests wertvoll.
5. **`ImageScaler.scale()`** (`domain/model/image/ImageScaler.kt:17`) — `ImageIO.read(...)` wird nicht auf `null` geprüft; bei einem nicht unterstützten Bildformat gibt's eine unklare NPE statt einer sprechenden Fehlermeldung.
6. **`LogDirResolver`** (`adapter/logging/LogDirResolver.kt:9`) — `System.getenv("APPDATA")` unter Windows ist nicht null-geprüft; fehlt die Env-Var, entsteht der Pfad `"null\librekollab"` statt eines klaren Fehlers.
7. **`TestKollab`** (`src/test/kotlin/.../TestKollab.kt`) — `viaSocket()` nimmt `existingBridges.firstOrNull()` ohne Fehlerbehandlung an, dass genau eine Bridge existiert; `withDocument`/`withDocumentMutating` sind fast identischer Code (nur der `store()`-Aufruf unterscheidet) — Duplikation, die dem Rest des Projekts (Regel 1) widerspricht.
8. ~~**Wiederkehrendes Thema: kein Caching** — `getImageMetas` exportiert jedes Bild einmal komplett, `get_image` danach nochmal.~~ **Erledigt für Bilder** — `ImageCache` hält die tatsächlichen Bild-Bytes an genau einer Stelle (`byId`, Caffeine, gewichtet nach Bytes statt Anzahl, 200 MB Grenze, `expireAfterAccess`-Sicherheitsnetz). Ein zweiter, winziger Index `shapeToId` (`(documentId, shapeName) → imageId`, schon vor dem Export bekannt, nur nach Anzahl statt Gewicht begrenzt) lässt `cachedImageOf()` den Export für ein bereits gesehenes Shape komplett überspringen — auch bei einem wiederholten `get_image_metas`-Aufruf — ohne dass die Bild-Bytes ein zweites Mal im RAM gehalten werden (zwei unabhängige bytegewichtete Caches hätten mit der Zeit auseinanderdriften und das reale Speicher-Limit effektiv verdoppeln können). Da `imageId` ein Content-Hash ist, wird eine Bildänderung im Dokument automatisch zu einer neuen ID — der alte Eintrag läuft einfach über die Grenzen aus, keine explizite Invalidierung nötig. `search` scannt weiterhin bei jeder Seite neu — bleibt offen, falls das mal zum Problem wird.
9. **`CoreKollab.kt` ist mit ~630 Zeilen sehr groß** — intern zwar sauber in kleine Methoden aufgeteilt (Regel 1 eingehalten), aber die Klasse trägt mittlerweile Paragraph-Enumeration, Redline-Parsing, Kommentare, Bilder *und* Suche. Kandidat für eine Aufspaltung in kollaborierende Objekte, falls noch mehr Features dazukommen.
