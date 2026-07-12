# Code Review (2026-07-12)

Frischer Durchgang über das ganze Projekt nach dem `CoreKollab`→`LibreKollab`-Merge und der Aufspaltung in `adapter/libreoffice/component/`. Snapshot-Stand, kein lebendes Dokument — nach dem Abarbeiten der Punkte kann diese Datei wieder gelöscht werden.

## Gut

1. **Komponentenaufteilung ist konsequent und einheitlich** — `CommentComponent`, `SearchComponent`, `TextComponent`, `RedlineComponent`, `ImageComponent`, `AuthorComponent` sind alle per Konstruktor-Injection verankert (Regel 6), mit eigenen Companion-Object-Konstanten statt Magic Strings (Regel 24) — ein echter Sprung gegenüber dem ehemaligen 630-Zeilen-Monolithen.
2. **`DocumentComponent`/`AuthorComponent`-Split ist sauber begründet, nicht aus Prinzip eingeführt** — `DocumentComponent` bekommt ein Interface, weil Produktion und Test wirklich unterschiedlich auf Dokumente zugreifen (offene Desktop-Dokumente vs. Laden/Speichern/Verwerfen); `AuthorComponent` bleibt bewusst eine einzelne geteilte Klasse, weil beide Seiten identische UNO-Aufrufe machen. Genau die Unterscheidung, die Regel 21 verlangt.
3. **`ImageCache`-Design ist durchdacht** — `byId` (bytegewichtet) + `shapeToId` (klein, nach Anzahl begrenzt) vermeidet doppelte Speicherbudgets, mit im Code erklärtem Warum (Regel 23).
4. **`domain/` ist tatsächlich frei von `adapter/`-Imports** — jede Datei unter `domain/` einzeln per Grep verifiziert, nicht nur stichprobenartig. Regel 17 ist zu 100 % eingehalten.
5. **Composition-Root-Disziplin (Regel 19) wird eingehalten** — `LibreKollab`/`McpServer`/`LoggingKollabAPI` werden ausschließlich in `LibreKollabPlugin.start()` instanziiert; keine Streuung anderswo (verifiziert per Grep). Bindet zudem korrekt nur an `127.0.0.1`.
6. **OXT-Packaging ist vollständig konsistent mit `CLAUDE.md`** — `manifest.xml`, `Jobs.xcu`, `OptionsDialog.xcu`/`.xdl` und `OptionsHandler.getSupportedMethodNames()` passen exakt zusammen.
7. **`TextComponent`s Kernmethoden sind funktional/Stream-orientiert** (Regeln 2/13/14) — `getText`, `getTextByPages`, `extractParagraphMarkedText` und der Fold-basierte Scan-State-Machine-Ansatz sind gute Beispiele; einzige Ausnahme ist `getTextByChapter` (siehe unten).
8. **`TestDocumentComponent.withLoadedDocument`** wickelt das Dispose des geladenen UNO-Components sauber in `try/finally`, auch wenn `block()`/`store()` wirft.

## Schlecht / Risiken

1. **`Comment.id`-Kollision, wenn zwei Kommentare denselben Anchor teilen.** `domain/model/Comment.kt:13` leitet `id` standardmäßig aus `anchor.id` ab, und `TextAnchor.id` ist nur ein Hash aus `text:paragraphIndex:charStart:charEnd` — enthält weder Autor noch Zeitstempel. Kommentieren zwei Reviewer exakt dieselbe Textstelle (realistisches Szenario für ein Kollaborationstool), bekommen beide Kommentare dieselbe `id`. `CommentComponent.findCommentField()` (Zeile 67-71) löst per `firstOrNull` auf — `get_comment`/`update_comment`/`delete_comment` können dann nur den einen der beiden erreichen; ein `update_comment`/`delete_comment` auf "den zweiten" Kommentar verändert/löscht in Wahrheit den ersten. Echter Datenintegritäts-Bug, kein Style-Thema.

2. **`LibreKollabPlugin.start()` bleibt in `running=true` hängen, wenn der Port keine Zahl ist.** `plugin/LibreKollabPlugin.kt:36-39`: `running.compareAndSet(false, true)` läuft, *bevor* `System.getProperty(PORT_PROPERTY, ...).toInt()` geparst wird. Tippt der Nutzer im Options-Dialog einen nicht-numerischen Port, wirft `toInt()` — noch bevor die erste `log.info(...)`-Zeile erreicht wird, also komplett ohne Logeintrag — und propagiert ungefangen durch `OptionsHandler.toggle()` (`plugin/OptionsHandler.kt:93-102`), sodass `updateUI(c)` nie erreicht wird: der Dialog zeigt weiterhin "Start server", obwohl `running` intern schon `true` ist. Erst ein zweiter Klick (der wegen `isRunning()==true` `close()` statt `start()` aufruft) setzt den Zustand zurück. Kein Try/Catch, keine Portvalidierung in `OptionsHandler.savePort()` (Zeile 104-109, nur ein `isNotEmpty()`-Check).

3. **`LibreDocumentComponent` — die tatsächlich in Produktion genutzte Dokumentzugriffs-Klasse — hat keine automatisierte Testabdeckung.** `TestDocumentComponent` (Testcode) implementiert einen komplett anderen Mechanismus (Laden aus `basePath`/`containerWorkspacePath` statt Wiederfinden bereits offener Dokumente über `XDesktop.getComponents()`), sodass `LibreDocumentComponent.findDocument()`/`openDocuments()` — inklusive der URL-Matching-Regel `url.endsWith("/$documentId") || url == documentId` und dem `NoSuchElementException`-Pfad bei unbekannter `documentId` — nie unter CI läuft. Passend dazu: kein einziger Test in `McpServerIT` ruft irgendein Tool mit einer unbekannten `documentId` auf (T30 testet nur einen ungültigen `anchorParagraphIndex` *innerhalb* eines validen Dokuments).

4. **UNO-Service-Instanzen ohne `dispose()` — zwei Stellen.**
   - `AuthorComponent.userProfileAccess()` (`component/AuthorComponent.kt:38-48`) erzeugt bei jedem `editText`/`addComment` mit bekanntem Autor eine frische `ConfigurationProvider`/`ConfigurationUpdateAccess`-Instanz; nur die Profil-Werte werden im `finally` zurückgesetzt, die Zugriffs-Objekte selbst nie disposed.
   - `ImageComponent.exportShapePng()` (`component/ImageComponent.kt:92-111`) disposed nur die Pipe-Streams (`closeOutput()`/`closeInput()`), nicht die `Pipe`- und `GraphicExportFilter`-Serviceinstanzen selbst.
   Beide liegen auf häufig durchlaufenen Pfaden (jeder Edit-Call mit Autor, jeder Bild-Export) — in einer lange laufenden LibreOffice-Session potenziell ein echtes Ressourcenleck, kein Edge Case. (Vorhanden schon vor dem aktuellen Refactor, aber unverändert mit übernommen.)

5. **`OptionsHandler` verstößt gegen Regel 18 (kein Adapter-zu-Adapter-Import) und Regel 6 (Constructor Injection).** `plugin/OptionsHandler.kt:19` importiert `adapter.logging.LogDirResolver` direkt und instanziiert es inline in `openLogFile()` (`LogDirResolver()`, Zeile 131) statt es über den Konstruktor zu injizieren. Anders als `LibreKollabPlugin` (der laut Regel 19 explizit die Composition Root ist und deshalb mehrere Adapter verdrahten darf) hat `OptionsHandler` keine solche Ausnahme.

6. **`OptionsHandler` verstößt gegen Regel 9 (Klassenreihenfolge).** Das `companion object` (`OptionsHandler.kt:27-49`) steht vor dem Instanz-Attribut `private var container` (Zeile 51) — Regel 9 verlangt Attribute vor Companion Object.

7. **`codingRules.md` Regel 25 ist veraltet.** Sie besagt, UNO-Aufrufe würden "in `CoreKollab`" serialisiert — diese Klasse existiert nicht mehr, der Dispatcher lebt jetzt in `LibreKollab.kt:44` (`libreOfficeDispatcher`). Sollte auf `LibreKollab` aktualisiert werden.

8. **Zwei Regel-3-Verstöße (fehlende geschweifte Klammern).**
   - `component/TextComponent.kt:227`: `if (line.isEmpty()) return emptyList()`
   - `component/ImageComponent.kt:118`: `if (n == 0) break`

9. **Mehrere Regel-24-Verstöße (Magic Strings/Numbers), die dem sonstigen Konstanten-Stil des Projekts widersprechen.**
   - `.take(12)` (Id-Kürzung) inline dupliziert in `domain/model/anchor/TextAnchor.kt:22` und `domain/model/image/Image.kt:23`.
   - Die UNO-Connection-URL `"uno:socket,host=$host,port=$port;urp;StarOffice.ComponentContext"` (`src/test/.../TestKollab.kt:28`) ist nicht extrahiert, obwohl die beiden Servicenamen daneben schon als Konstanten ausgelagert sind.
   - `OptionsHandler.kt:89`: `"MultiLine"` (UNO-Property-Name) inline, während analoge Property-Namen anderswo im Projekt konsequent als Konstanten geführt werden.
   - `OptionsHandler.kt:79-80`: die Event-Namen `"initialize"`/`"back"`/`"ok"` sind nicht extrahiert, obwohl die Geschwister-Konstanten `METHOD_EXTERNAL_EVENT`/`METHOD_TOGGLE`/`METHOD_OPEN_LOG` im selben File es sind.
   - `McpServer.kt:303`: `mimeType = "image/png"` inline, während `ImageScaler` im selben Subsystem ein äquivalentes Literal (`PNG_FORMAT`) korrekt auslagert.

10. **`TextComponent.getTextByChapter` folgt nicht dem funktionalen Stil des restlichen Files (Regeln 2/10/13).** `component/TextComponent.kt:81-110`: `while`-Schleife mit `var inChapter`, `var chapterLevel`, `var paraIdx`, mutablem `StringBuilder`/`mutableListOf` und einem `break` mitten in der Schleife (Zeile 96) — genau die Muster, die Regel 10 explizit als zu vermeiden nennt. Einziger Ausreißer in einer sonst konsequent `fold`/`map`-basierten Klasse.

11. **Regel-20-Inkonsistenz in `LibreKollab.kt`.** `setEditMode` (Zeile 104), `updateComment` (125) und `deleteComment` (132) sind als `override suspend fun ...(): Unit = withContext(...) { ... }` deklariert (Expression-Body), während `editText` und `addComment` im selben File korrekt Block-Body (`{ }`) nutzen. Regel 20 verlangt Block-Body für `Unit`-Funktionen durchgängig.

12. **`CLAUDE.md`s MCP-Tool-Tabelle fehlt `edit_text`s optionaler `author`-Parameter.** `EditTextRequest.kt` deklariert `author: String = KollabAPI.UNKNOWN_AUTHOR` mit eigener `@Description`, vollständig verdrahtet in `McpServer.editText()` — aber die Tabelle in `CLAUDE.md` (Zeile 223) listet für `edit_text` nur `newTextProperties` als optional.

13. **`LoggingKollabAPI.getChapters` loggt das Ergebnis nicht.** `adapter/logging/LoggingKollabAPI.kt:51-54` ist die einzige Read-Methode der Klasse, die nur den Aufruf loggt (`"getChapters(documentId=$documentId)"`) und direkt `delegate.getChapters(documentId)` zurückgibt, statt das Ergebnis wie jede andere Query-Methode in einer `val` abzufangen und mitzuloggen.

14. **Testlücken.**
    - `get_image` mit unbekannter `imageId` wird nie getestet (der "not found"-Zweig in `McpServer.getImage()` existiert, ist aber unbesucht — Pendant zu den bereits getesteten `get_comment`/`update_comment`/`delete_comment`-"not found"-Fällen).
    - Kein Tool wird je mit einer unbekannten `documentId` aufgerufen (siehe auch Punkt 3).
    - `ImageScaler.scale()` hat keinerlei Test — weder als Unit-Test noch über `get_image`s `scale`-Parameter mit Grenzwerten (`0`, negativ, `>1`) oder einem korrupten Bild, das den `requireNotNull(ImageIO.read(...))`-Guard auslösen würde. Es existiert generell keine Unit-Test-Datei für Domain-Logik — alles läuft ausschließlich über die einzige Docker-basierte `McpServerIT`.

15. **Duplizierte UNO-Enumerations-Idiom, 7 Stellen.** `UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text).createEnumeration()` wird wortwörtlich wiederholt in `LibreKollab.kt`, `UnoClient.kt` (zweimal), `TextComponent.kt` (viermal) und `SearchComponent.kt`. `UnoClient` zentralisiert bereits andere generische UNO-Primitiven (`enumerationSequence`, `viewCursorOf`, `paragraphAt`, ...), aber ausgerechnet das am häufigsten wiederholte Snippet nicht. Ein `UnoClient.paragraphsOf(textDoc): Sequence<Any>` würde alle 7 Duplikate beseitigen.

### Kleinere Beobachtungen (nicht dringend)

- `ImageScaler.scale()` (Zeile 23-27): `dispose()` steht innerhalb von `.apply { ...; drawImage(...); dispose() }` ohne `try/finally` — wirft `drawImage`, wird `dispose()` übersprungen. Geringe Praxisrelevanz (In-Memory-`Graphics2D`, GC-reclaimable).
- `TestKollab.viaSocket()` (`src/test/.../TestKollab.kt:16-34`): wählt `existingBridges.firstOrNull()` — fragil, falls mehr als eine UNO-Bridge im JVM existiert; zudem kann bei einem `requireNotNull`-Fehlschlag nach bereits erfolgtem `resolve(...)` die währenddessen geöffnete Verbindung nicht mehr disposed werden (kein `onClose` existiert an dem Punkt noch). Test-only, geringe Praxisrelevanz.
- `ImageComponent.cachedImageOf`/`LibreKollab.getImage`: leichtes TOCTOU zwischen Cache-Check und -Befüllung — Caffeine selbst ist thread-safe, im schlimmsten Fall wird ein Bild einmal redundant exportiert, keine Dateninkonsistenz.
- `LibreKollab.withEnsuredEditMode` (Zeile 166-169): `getEditMode`-Check und der eigentliche Edit sind zwei getrennte `withContext(libreOfficeDispatcher)`-Aufrufe, nicht atomar — ein dazwischenkommender `set_edit_mode`-Aufruf könnte das Ergebnis theoretisch beeinflussen. Sehr unwahrscheinlich in der Praxis, aber ein echter Lücke im "immer serialisiert"-Modell von Regel 25.
- Jeder erste Edit einer Session löst `.store()` zweimal aus (einmal implizit durch `setEditMode(true)` in `withEnsuredEditMode`, einmal durch den eigentlichen Edit) — Performance-Nit, keine Korrektheitsfrage.
- `private val log = LoggerFactory.getLogger(...)` in `LibreKollab.kt:43` ist mit `@Suppress("unused")` markiert und wird nirgends im File verwendet (verifiziert per Grep) — Altlast aus der ehemaligen `CoreKollab`, die den Merge unverändert überlebt hat.
- `ToolSchemaGenerator.typeSchema()` hat keinen Fall für `PrimitiveKind.BOOLEAN`/`LONG` oder verschachtelte `StructureKind.CLASS`-Typen und würde bei einer zukünftigen DTO-Erweiterung mit `error(...)` beim Serverstart abbrechen. Aktuell nicht akut, da kein Request-DTO diese Typen nutzt.
- `get_image`/`get_comment`-"not found"-Antworten sind reiner `TextContent("not found")` mit `isError=false`, im Unterschied zu echten Fehlern (die der SDK-eigene Catch-all zu `isError=true` macht) — ein Caller muss also auf den Text matchen statt `isError` zu prüfen, um "nicht gefunden" von "erfolgreich, aber leer" zu unterscheiden.
- `OptionsHandler.updateUI()` und `LoggingKollabAPI.getImage()` deklarieren `val x: Type` und weisen ihn dann in `if`/`else`-Zweigen zu, statt der in Regel 4 als Standard genannten Form `val x = if (cond) { a } else { b }`. Klammern sind vorhanden (Regel 3 erfüllt), aber nicht die kanonische Form.

