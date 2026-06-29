# edit_text Bug Investigation

## Was wurde getestet

Dokument: `DieJagdImDrachenmeer_main.odt`  
Ziel: Letzten Satz auf Seite 46 um `" Dann: Dunkelheit."` erweitern, wobei `"Dunkelheit."` fett formatiert ist.

## Erster Versuch (falscher Ansatz)

**Call:**
- `anchorParagraphIndex`: 248
- `anchorCharStart`: 0, `anchorCharEnd`: 113 (gesamter Paragraph als Anker)
- `newText`: `"Schreie. Schreie ohne Luft, während das Wasser sich bereit machte den Körper zu fluten und es endlich zu beenden. Dann: Dunkelheit."`
- `newTextProperties`: bold auf Zeichen 120–131 (`"Dunkelheit."`)

**Problem:** Gesamter Paragraph wurde als ein INSERT getrackt — nicht nur die Ergänzung.  
**Ursache:** Falscher Ansatz. Nicht den ganzen Paragraph ankern, sondern nur den zu ändernden Bereich.

## Zweiter Versuch (korrekter Ansatz)

**Call:**
- `anchorParagraphIndex`: 248
- `anchorCharStart`: 112, `anchorCharEnd`: 112 (leere Range = reines Insert am Ende)
- `newText`: `" Dann: Dunkelheit."`
- `newTextProperties`: `[{"type": "bold", "markIndex": {"paragraphIndex": 0, "from": 7, "to": 18}}]`
  - `"Dunkelheit."` = Zeichen 7–18 in `newText`

**Ergebnis in LibreOffice:** Zwei Tracked Changes sichtbar — `" Dann: "` und `"Dunkelheit."` (bold) getrennt.

## Bug 1: get_changes gibt nicht alle Redlines zurück

`get_changes` lieferte nur:
```json
[{
  "action": "INSERT",
  "author": "Unknown Author",
  "dateTime": "2026-06-29T21:18:21",
  "text": " Dann: ",
  "anchor": { "paragraphIndex": 248, "charStart": 112, "charEnd": 119 }
}]
```

`"Dunkelheit."` (der fette Teil) fehlt komplett. Vermutlich bricht die Redline-Iteration bei einem Formatierungswechsel (normal → bold) ab und gibt nur die erste Redline zurück.

**Reproduzierbar:** Ja, zweimal getestet, gleiches Ergebnis.

## Bug 2: author wird nicht übernommen

Beide Changes zeigen `"author": "Unknown Author"`, obwohl der `author`-Parameter laut Entwickler inzwischen in `edit_text` ergänzt wurde. In der Session war das Schema jedoch noch ohne `author` gecacht — muss in einer neuen Session mit aktualisiertem Schema erneut getestet werden.

## Korrekte Verwendung von edit_text (Zusammenfassung)

- **Nur den zu ändernden Bereich ankern**, nicht den gesamten Paragraph.
- **Reines Insert** (nichts löschen): `anchorCharStart == anchorCharEnd` am Einfügepunkt.
- **`newText`** enthält den kompletten neuen Text (auch unformatierte Teile).
- **`newTextProperties`** beschreibt die Formatierung relativ zu `newText` (`paragraphIndex` ist 0-basiert relativ zu `newText`, `from`/`to` sind Zeichen-Offsets).
