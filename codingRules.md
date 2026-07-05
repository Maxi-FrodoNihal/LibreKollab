# Coding Rules

Nummeriert nach Wichtigkeit, absteigend — Regel 1 ist am wichtigsten. Beim Einsortieren einer neuen Regel rutschen die dahinterliegenden Nummern nach hinten; die Kategorien darunter dienen nur der Gruppierung, nicht der Nummerierung.

Für alle Regeln gilt: der Entwickler kann im Einzelfall begründete Ausnahmen definieren.

## Allgemein

1. Kleine, sprechende Methoden verwenden. Jede Methode bekommt entweder eine dezidierte fachliche Aufgabe, oder ihre Aufgabe ist die Koordination anderer Methoden (Orchestrierung) — keine Vermischung von beidem in einer Methode. Das dient auch der Wiederverwendbarkeit. Ausnahmen sind möglich, wenn eine Methode so trivial ist, dass eine weitere Aufteilung keinen Sinn ergibt (z.B. 3 Zeilen oder nur ein einzelnes `if`).
2. Code ist funktional und stream-orientiert. Wir verwenden seltener `while`-/`for`-Schleifen, meist Sequences/Collection-Operationen (`map`, `filter`, `fold`, ...). Eine klassische Schleife kann sinnvoll sein, wenn sie die Sache klarer macht — ansonsten gilt funktionale Stream-Programmierung als Standard.
3. `if`-Statements haben immer geschweifte Klammern, auch bei nur einer Anweisung im Block. Das gilt ebenso für Methoden.
4. `if` gibt nie einen Wert zurück — wird wie ein Void-Statement behandelt, kein Ausdruck. Weder als inline-Ternary (`if (cond) a else b`) noch als mehrzeiliger Expression-Body/Value-Provider (`val x = if (cond) { a } else { b }`). Stattdessen wird die Variable vorher deklariert (`val x: T`) und in jedem `if`/`else`-Zweig einzeln zugewiesen — das ist in Kotlin auch mit `val` möglich, solange der Compiler die Zuweisung auf jedem Pfad eindeutig nachvollziehen kann.
5. In String-Templates (`${...}`) steht kein Code außer einfachen Werten oder Methoden-/Property-Aufrufen — keine eingebettete Logik wie `if`/`else`. Eine solche Fallunterscheidung wird vorher in einer Variable berechnet (siehe Regel 4) und nur die Variable im Template verwendet.
6. Klassen injecten ihre Abhängigkeiten vorrangig über den Konstruktor — klassisches Constructor Injection wie man es aus Quarkus oder Spring kennt, nur dass wir uns um das Injecten und die Konstruktoren selbst kümmern müssen (kein DI-Framework). Insbesondere Klassen, die mit externen Systemen sprechen, müssen das unterstützen, damit Tests Container-Koordinaten o.ä. übergeben können.
7. Keine `of()`-/Builder-Factory-Methoden für einfache Objekterzeugung — der Konstruktor (ggf. mit Default-Parametern, siehe Regel 22) ist genau dafür da.
8. Keine Top-Level-Funktionen und keine Extension-Funktionen auf fremden Klassen. Jede Methode gehört zu einer selbst definierten Klasse — Methoden hängen sich nicht von außen an fremde Klassen an.
9. Der Aufbau einer Klasse folgt immer demselben Muster, in dieser Reihenfolge: Attribute, Konstruktoren, Companion Object, öffentliche Methoden, private Methoden.
10. Einfacher Kontrollfluss pro Methode: ein Early Return, klar durch `if`s abgegrenzt, ist erlaubt — meist gefolgt von einem "normalen" Return am Ende der Methode. Zu vermeiden ist es, den Kontrollfluss mittendrin aus sonstigen Gründen abzubrechen, ebenso `continue` oder `break` mitten in einer Schleife.
11. `when`-Strukturen bekommen fast immer eine eigene Methode (folgt schon aus Regel 1 — hängt eng mit dem einfachen Kontrollfluss aus Regel 10 zusammen).
12. Nur spezifische Exceptions fangen, nie pauschal `Exception`/`Throwable`. Ansonsten Exceptions grundsätzlich durchlaufen lassen (nicht künstlich vermeiden) — es spricht nichts dagegen, Exceptions gezielt zur Kontrollfluss-Steuerung einzusetzen, wenn es den Code einfacher und lesbarer macht.
13. `val` statt `var`, mutable Akkumulatoren vermeiden — funktionale Alternativen (`fold`, `map`, ...) bevorzugen, passend zu Regel 2.
14. Never-Nesting-Ansatz: möglichst wenig verschachteln. Lieber Guard Clauses für einen frühen Ausstieg nutzen oder verschachtelte Teile in eigene Methoden auslagern, statt `if`s ineinanderzustapeln.
15. Kein `!!` und kein `Any` in Kotlin-Code — beides so gut wie verboten. Stattdessen saubere Typmodellierung, `requireNotNull`/`checkNotNull` mit Fehlermeldung, sealed types oder Generics. Ausnahme: gibt ein Framework (z.B. die UNO-API) selbst `Any` zurück, akzeptieren wir das an dieser Stelle — dagegen können wir nichts machen.
16. Eine Datei = eine Klasse (bzw. ein Interface): pro Datei genau ein öffentlicher Typ, Dateiname entspricht dem Klassennamen.

## DDD / Package rules

17. `domain/` hat keine Imports aus `adapter/` — Domain-Model und Ports sind framework-agnostisch.
18. Adapter importieren nur aus `domain/` und `domain/model/` — nie aus anderen Adaptern.
19. `LibreKollabPlugin` ist die Composition Root: einziger Ort, der `LibreKollab` und `McpServer` instanziiert und verdrahtet.

## Kotlin style

20. Block body `{ }` für `Unit`-Funktionen — Expression body `= expr` leitet den Typ vom letzten Ausdruck ab; JUnit lehnt `@Test`-Methoden mit Nicht-`Unit`-Rückgabe ab.
21. `data class` für Value Objects; `sealed interface` für polymorphe Domain-Typen (z.B. `TextProperty`). Allgemeiner: ein Interface schlägt immer eine erbende Klasse (Vererbung), sofern sich die Klasse ohne Funktionsverlust in ein Interface umwandeln lässt (also keine gemeinsame Zustands-/Methodenimplementierung über die Hierarchie hinweg nötig ist).
22. Werte, die immer aus anderen Feldern berechenbar sind, dürfen nicht nullable sein — stattdessen Default-Parameter (z.B. `TextAnchor.id`).
23. Keine Kommentare, außer das WARUM ist nicht offensichtlich; nie das WAS beschreiben.
24. Keine Magic Strings/Numbers im Code — literale Werte wie Algorithmus-Namen oder Format-Strings (z.B. `"SHA-256"`, `"%02x"`) werden als benannte Konstanten ausgelagert (z.B. im Companion Object), nicht inline verwendet.

## I/O and concurrency

25. UNO-Aufrufe werden über `limitedParallelism(1)` auf `Dispatchers.IO` in `CoreKollab` serialisiert — nie UNO aus mehreren Coroutines gleichzeitig aufrufen.
26. `KollabAPI`-Funktionen sind `suspend` — Aufrufer müssen Coroutines oder `runBlocking` in Tests nutzen.

---

## Fortschritt: Anwenden der Regeln auf den bestehenden Code

Reihenfolge: `KollabAPI.kt` → `domain/model/*` → `LoggingKollabAPI.kt` → `LogDirResolver.kt` → `McpServer.kt` → `LibreKollab.kt` → `LibreKollabPlugin.kt` → `OptionsHandler.kt` → `CoreKollab.kt`

- [x] `KollabAPI.kt` — `UNKNOWN_AUTHOR`-Konstante statt leerem String als Autor-Default (Regel 22); Methoden-Gruppierung geprüft und bewusst so belassen (`editText` bleibt isoliert, da einziger textmutierender Punkt); KDoc-Kommentar bewusst als Ausnahme zu Regel 23 behalten
- [x] `domain/model/TextAnchor.kt` — `HASH_ALGORITHM`/`HEX_FORMAT`-Konstanten statt Magic Strings (Regel 24)
- [x] `domain/model/Comment.kt` — `of()`-Factory entfernt, `id` als Default-Parameter (Regel 7 + 22); Call-Site in `CoreKollab.kt` angepasst
- [x] `domain/model/change/Change.kt`, `ChangeAction.kt`, `ChangeStatus.kt` — keine Verstöße
- [x] `domain/model/image/Image.kt` — WHY-Kommentar für `equals`/`hashCode`-Override ergänzt (Regel 23), `HASH_ALGORITHM`/`HEX_FORMAT`-Konstanten (Regel 24)
- [x] `domain/model/image/ImageMeta.kt` — keine Verstöße
- [x] `domain/model/text/MarkIndex.kt`, `MarkedText.kt` — keine Verstöße
- [x] `domain/model/text/properties/TextProperty.kt` + 4 Properties — `sealed class` → `sealed interface` (Regel 21)
- [x] `domain/model/image/ImageScaler.kt` — neu angelegt, aus `McpServer.kt` extrahiert (Bildskalierung ist keine MCP-Aufgabe)
- [x] `adapter/logging/LoggingKollabAPI.kt` — `preview()` ans Klassenende verschoben (Regel 9), `PREVIEW_LENGTH` als Companion-Konstante (Regel 24), `if`-Verstöße in `getImage()` gefixt (Regel 3 + 4 + 5)
- [x] `adapter/logging/LogDirResolver.kt` — `if`-Verstoß gefixt (Regel 4), `isWindows()` extrahiert (Regel 1)
- [ ] `adapter/mcp/McpServer.kt` — noch nicht abgehakt, soll nochmal gesondert angeschaut werden. Bisher erledigt: alle `!!` durch `requiredString`/`requiredInt` mit `requireNotNull` ersetzt (Regel 15); zwei `if`-Verstöße gefixt (Regel 4, in `getComment`/`getImage`); `ImageScaler` per Default-Parameter injected, `prepareImage()` extrahiert
  - offen: Regel 9 (`buildServer()` steht zwischen den öffentlichen Methoden statt bei den privaten), Wiederholung beim `buildJsonObject`-Aufbau der Tool-Schemas noch nicht angegangen
- [ ] `adapter/libreoffice/LibreKollab.kt` — nur die `withAuthor`-Änderung (Regel 22-Folge), noch kein vollständiger Regel-Durchgang
- [ ] `adapter/libreoffice/plugin/LibreKollabPlugin.kt` — bereits gefundener Verstoß gegen Regel 9 (Companion Object steht nach den Methoden statt davor), noch nicht gefixt
- [ ] `adapter/libreoffice/plugin/OptionsHandler.kt` — noch nicht durchgegangen
- [ ] `adapter/libreoffice/CoreKollab.kt` — größte/komplexeste Datei, noch nicht durchgegangen. Bereits gefundene Verstöße:
  - Extension-Funktion `XPropertySet.outlineLevel()` (Regel 8)
  - mehrere `break`/`continue` in Schleifen (Regel 10)
  - `when`-Blöcke in `getChanges`/`extractParagraphMarkedText` nicht in eigener Methode (Regel 11)
  - `Any`-Nutzung an mehreren Stellen — vermutlich gerechtfertigte Ausnahme, da von der UNO-API erzwungen (Regel 15)
