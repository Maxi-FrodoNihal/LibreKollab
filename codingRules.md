# Coding Rules

Nummeriert nach Wichtigkeit, absteigend — Regel 1 ist am wichtigsten. Beim Einsortieren einer neuen Regel rutschen die dahinterliegenden Nummern nach hinten; die Kategorien darunter dienen nur der Gruppierung, nicht der Nummerierung.

Für alle Regeln gilt: der Entwickler kann im Einzelfall begründete Ausnahmen definieren.

## Allgemein

1. Kleine, sprechende Methoden verwenden. Jede Methode bekommt entweder eine dezidierte fachliche Aufgabe, oder ihre Aufgabe ist die Koordination anderer Methoden (Orchestrierung) — keine Vermischung von beidem in einer Methode. Das dient auch der Wiederverwendbarkeit. Ausnahmen sind möglich, wenn eine Methode so trivial ist, dass eine weitere Aufteilung keinen Sinn ergibt (z.B. 3 Zeilen oder nur ein einzelnes `if`).
2. Code ist funktional und stream-orientiert. Wir verwenden seltener `while`-/`for`-Schleifen, meist Sequences/Collection-Operationen (`map`, `filter`, `fold`, ...). Eine klassische Schleife kann sinnvoll sein, wenn sie die Sache klarer macht — ansonsten gilt funktionale Stream-Programmierung als Standard.
3. `if`-Statements haben immer geschweifte Klammern, auch bei nur einer Anweisung im Block. Das gilt ebenso für Methoden — insbesondere Methoden, deren Körper ein `when` ist: Block-Body mit `return when (...) { ... }`, nie Expression-Body (`= when (...) { ... }`).
4. `if` als mehrzeiliger Expression-Body/Value-Provider (`val x = if (cond) { a } else { b }`) ist erlaubt, solange jeder Zweig durch geschweifte Klammern klar abgegrenzt bleibt (Regel 3). Verboten bleibt die inline-Ternary-Form (`if (cond) a else b`) — die versteckt die Fallunterscheidung in einer Zeile statt sie sichtbar über Zweige zu strukturieren.
5. In String-Templates (`${...}`) steht kein Code außer einfachen Werten oder Methoden-/Property-Aufrufen — keine eingebettete Logik wie `if`/`else`. Eine solche Fallunterscheidung wird vorher in einer Variable berechnet (siehe Regel 4) und nur die Variable im Template verwendet.
6. Klassen injecten ihre Abhängigkeiten vorrangig über den Konstruktor — klassisches Constructor Injection wie man es aus Quarkus oder Spring kennt, nur dass wir uns um das Injecten und die Konstruktoren selbst kümmern müssen (kein DI-Framework). Insbesondere Klassen, die mit externen Systemen sprechen, müssen das unterstützen, damit Tests Container-Koordinaten o.ä. übergeben können.
7. Keine `of()`-/Builder-Factory-Methoden für einfache Objekterzeugung — der Konstruktor (ggf. mit Default-Parametern, siehe Regel 22) ist genau dafür da.
8. Keine Top-Level-Funktionen und keine Extension-Funktionen auf fremden Klassen. Jede Methode gehört zu einer selbst definierten Klasse — Methoden hängen sich nicht von außen an fremde Klassen an.
9. Der Aufbau einer Klasse folgt immer demselben Muster, in dieser Reihenfolge: Attribute, innere Klassen (private Hilfs-Datenklassen o.ä.), Konstruktoren, Companion Object, öffentliche Methoden, private Methoden. Innere Klassen stehen also gebündelt oben, nicht verteilt direkt vor ihrer jeweiligen Nutzung.
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
