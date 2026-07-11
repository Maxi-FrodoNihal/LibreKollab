# LibreKollab

Kotlin LibreOffice extension (.oxt) that exposes document editing as MCP tools. The extension runs inside LibreOffice's JVM, starts an MCP server over SSE, and lets Claude Code read text, navigate chapters/pages, edit text with tracked changes, manage comments, and read embedded images — all via in-process UNO.

## Architecture

```
Claude Code CLI
      │  MCP (SSE, localhost:8080)
LibreKollab OXT extension
      │  in-process UNO (no socket in production)
LibreOffice document(s)
```

The extension implements `XJob` and is registered via `Jobs.xcu` to run on `onFirstVisibleTask`. `execute()` is a no-op — it only logs that the plugin is ready. The MCP server is **not** started automatically; the user starts it manually from the Options dialog (`Extras > Optionen > Internet > MCP Server`). `McpServer.startSse()` launches a Ktor/Netty server non-blocking (`wait = false`) and returns an `EmbeddedServer<*, *>` so the plugin can stop it later. An `AtomicBoolean` guard prevents double-start.

`documentId` is the file name of a currently open document in LibreOffice (e.g. `report.odt`). `LibreKollab` resolves it by enumerating `XDesktop.getComponents()`.

## Package structure

```
org.msc.librekollab
├── domain/
│   ├── KollabAPI.kt    # central port — see KDoc for build instructions
│   └── model/          # Comment, Change, ChangeAction, ChangeStatus, MarkedText, ...
│       ├── anchor/      # TextAnchor, PageAnchor (composes a TextAnchor + page)
│       ├── change/
│       ├── image/      # Image, ImageMeta
│       └── text/
│           └── properties/
└── adapter/
    ├── mcp/            # McpServer
    │   ├── request/    # per-tool @Serializable request DTOs, decoded from the MCP call's JsonObject args
    │   └── schema/      # ToolSchemaGenerator, @Description — generates ToolSchema from a request DTO's SerialDescriptor
    ├── libreoffice/    # CoreKollab (abstract), LibreKollab (in-process, production)
    │   └── plugin/     # LibreKollabPlugin (XJob), OptionsHandler (XContainerWindowEventHandler)
    └── logging/        # LoggingKollabAPI, LogDirResolver
```

Test sources:

```
src/test/kotlin/
└── org/msc/librekollab/
    ├── adapter/libreoffice/TestKollab.kt   # Socket-based KollabAPI for tests
    └── McpServerIT.kt
```

## Coding guidelines

See [`codingRules.md`](codingRules.md) for the full, numbered list (DDD/package rules, Kotlin style, I/O and concurrency). Add new rules there, appended with the next free number.

## CoreKollab / LibreKollab / TestKollab

`CoreKollab` is the abstract base class implementing `KollabAPI`. It holds the `libreOfficeDispatcher` and all UNO logic. Subclasses only need to implement document access:

```kotlin
abstract override suspend fun listDocuments(): List<String>
protected abstract suspend fun <T> withDocument(documentId: String, block: (XTextDocument) -> T): T
protected abstract suspend fun <T> withDocumentMutating(documentId: String, block: (XTextDocument) -> T): T
```

**`LibreKollab`** (`adapter/libreoffice/`, production) — takes `XComponentContext`, finds open documents via `XDesktop.getComponents()`. `listDocuments()` returns their file names. In `withDocumentMutating` it calls `XStorable.store()` after the block.

**`TestKollab`** (`src/test/`, socket-based) — connects to LibreOffice via UNO socket, loads documents from a local `basePath` directory into a container workspace, stores mutated documents back. Created via `TestKollab.viaSocket(host, port, basePath, containerWorkspacePath)`.

## OXT packaging

The extension is built by `./gradlew oxt` → `build/oxt/LibreKollab-1.0-SNAPSHOT.oxt`.

```
LibreKollab.oxt (ZIP)
├── librekollab-all.jar      # fat JAR (excludes libreoffice.jar)
├── LibreKollab.components   # UNO service registration (plugin + options handler)
├── Jobs.xcu                  # registers XJob trigger on onFirstVisibleTask
├── OptionsDialog.xcu         # registers Extras > Optionen > Internet > MCP Server leaf
├── dialogs/OptionsDialog.xdl # dialog layout (dlg:text labels, script:event buttons)
├── description.xml           # extension identifier org.msc.librekollab
├── description-en.txt
└── META-INF/manifest.xml     # lists: LibreKollab.components, Jobs.xcu, OptionsDialog.xcu
```

`libreoffice.jar` is `compileOnly` + `testImplementation` — present for compilation and tests, excluded from the fat JAR to avoid bundling what LibreOffice already provides.

## Plugin lifecycle

`LibreKollabPlugin` implements `XJob` + `XServiceInfo`. LibreOffice calls `execute()` on first visible task — the method just logs "plugin ready" and returns.

- `running: AtomicBoolean` — `compareAndSet(false, true)` in `start()` prevents double-start
- `engine: EmbeddedServer<*, *>?` — holds the running Ktor server
- `instance: LibreKollabPlugin?` — stored in `init { instance = this }` so `OptionsHandler` can call `start()` / `close()`
- `start()` — companion method; called by `OptionsHandler` when the user clicks "Start server". Reads `librekollab.port` system property (default `8080`), creates `LibreKollab` + `McpServer`, calls `startSse(port)`
- `close()` — stops the engine, sets `engine = null`, resets `running` to `false`
- `__getComponentFactory(implementationName)` — JVM static factory required by UNO; dispatches both `LibreKollabPlugin` and `OptionsHandler` by implementation name

`OptionsHandler` implements `WeakBase() + XContainerWindowEventHandler + XServiceInfo`. Registered via `OptionsDialog.xcu`. `WeakBase` provides `XTypeProvider`/`XInterface` required for UNO marshalling.

- `callHandlerMethod(window: XWindow, eventObject: Any, method: String)` — LibreOffice calls this for all dialog events:
  - `"external_event"` — initialization lifecycle events. `AnyConverter.toString(eventObject)` yields `"initialize"`, `"back"`, or `"ok"`. `"initialize"`/`"back"` call `initialize()` (stores `XControlContainer`, sets `MultiLine=true` on status label, refreshes UI); `"ok"` saves the port value.
  - `"toggle"` — called directly when btnToggle fires (via `script:event` in XDL). Starts or stops the server.
  - `"openLog"` — called directly when btnOpenLog fires. Opens the log file via `java.awt.Desktop.open()` (falls back to the directory if the file does not exist yet).
- `getSupportedMethodNames()` must return every method name routed to this handler: `["external_event", "toggle", "openLog"]`
- `EventHandlerService` in `OptionsDialog.xcu` must be the **implementation name** (fully qualified class name), not the service name

## XDL dialog format

The Options dialog is embedded in LibreOffice's Options tree — not a floating window. Key rules:

- `dlg:withtitlebar="false"` — required for embedded Options page dialogs; omitting it causes load failures
- `dlg:text` — correct element for static labels. `dlg:fixedtext` is **invalid** and causes a silent SAX parse error at EOF (`Noerror` at last line), preventing the entire dialog from loading
- Both XML namespaces must be declared on `dlg:window`: `xmlns:dlg="..."` and `xmlns:script="..."`
- Button events use `script:event` child elements:
  ```xml
  <script:event script:event-name="on-performaction"
                script:macro-name="vnd.sun.star.UNO:toggle"
                script:language="UNO"/>
  ```
  The method name after `vnd.sun.star.UNO:` must appear in `getSupportedMethodNames()`
- LibreOffice does **not** support extension-added top-level nodes in the Options dialog tree. Register under an existing node such as `Internet` via `OptionsDialog.xcu`

## Edit mode / Track Changes

`withEnsuredEditMode(documentId)` checks `RecordChanges` and enables it if needed. Used **exclusively for `editText`**.

Comments (`addComment`, `updateComment`, `deleteComment`) bypass `withEnsuredEditMode` — LibreOffice does not track annotation changes in its redline system.

`setEditMode` / `getEditMode` are on `KollabAPI` but `setEditMode` is only called implicitly by `withEnsuredEditMode` — not exposed as an MCP tool.

## Track Changes model

`ChangeAction` — `INSERT` / `DELETE`  
`ChangeStatus` — `BEFORE` (deletions visible, insertions hidden) / `FUSION` (raw, default) / `AFTER` (insertions visible, deletions hidden)  
`Change` — `action`, `author`, `dateTime`, `text`, `anchor: TextAnchor`

## Text and formatting

`MarkedText(text: String, properties: List<TextProperty>)` — text-returning methods return this.

`TextProperty` is a sealed interface. Implementations: `BoldProperty`, `ItalicProperty`, `UnderlineProperty`, `StrikethroughProperty` — each holds a `MarkIndex(paragraphIndex, from, to)`.

`insertFormattedText` (in `CoreKollab`, used by `editText`) inserts `newText` one formatting run at a time, setting the cursor's character properties *before* each `insertString` call — never insert plain text and reformat it afterward. Reformatting already-inserted, not-yet-accepted text makes LibreOffice record the attribute change as its own "Format" redline, splitting one logical insert into two adjacent redlines. `getChanges()` reads redlines by bracket (`IsStart`/`IsEnd`) and resets its accumulator on every redline end, so a Format redline ending between two Insert-redline halves silently drops everything after it. This only reproduces against a real, GUI-attached LibreOffice — see Testing below.

## Images

`ImageMeta(imageId, width, height, sizeMb, page, textAnchor)` — lightweight descriptor returned by `getImageMetas`. `width`/`height` are in 1/100 mm (LibreOffice native unit). `sizeMb` reflects the Base64-encoded size `get_image` will actually transmit, not the raw PNG size — computed via the exact `4 * ceil(n / 3)` Base64 length formula in `CoreKollab.base64EncodedByteCount()` (no actual encoding happens in the domain), so callers get an honest number to decide upfront whether to request a downscaled `get_image` call.

`Image(bytes, width, height, textAnchor, id)` — full image returned by `getImage`. `bytes` is the raw PNG data; Base64 encoding only happens at the MCP boundary in `McpServer`, never in the domain. `id` is a 12-char SHA-256 of the raw PNG bytes + dimensions + anchor. The `get_image` MCP tool returns this as a native `ImageContent` block (not JSON text) and accepts an optional `scale` argument (0 exclusive–1 inclusive) to downscale the PNG in `McpServer` before Base64-encoding it — the domain layer always exports at original resolution.

Implementation in `CoreKollab`:
- `XTextGraphicObjectsSupplier.getGraphicObjects()` enumerates all embedded images by name.
- Each shape is exported to PNG via `GraphicExportFilter` (`com.sun.star.drawing.GraphicExportFilter`): `XExporter.setSourceDocument(shape)` + `XFilter.filter(props)` writing to a `com.sun.star.io.Pipe`. The Pipe implements both `XInputStream` and `XOutputStream` and lives on the LibreOffice side — bytes are read back over the UNO bridge via `XInputStream.readBytes()`.
- Page number is resolved by moving a `XPageCursor` to the image anchor.

## Comments (UNO annotations)

`TextAnchor` — domain PK for a text position: `text`, `paragraphIndex`, `charStart`, `charEnd`, `id` (12-char SHA-256 of `"$text:$paragraphIndex:$charStart:$charEnd"`).

`Comment` — `id`, `anchor`, `author`, `content`, `dateTime: LocalDateTime`.

Writing: anchor resolved by walking paragraphs; annotation created via `XMultiServiceFactory`; `DateTimeValue` must be set explicitly.  
Updating/deleting: enumerate fields, match by anchor id; throws `NoSuchElementException` if not found.

## Search

`TextAnchor` and `PageAnchor` live in `domain/model/anchor/`. `PageAnchor(textAnchor, page)` composes a `TextAnchor` with the page it's on — composition, not inheritance, since `TextAnchor` is a `data class` (implicitly final in Kotlin, can't be subclassed).

`SearchResult(page, size, totalFindings, elements: List<PageAnchor>)` in `domain/model/` — a paginated response, not a wrapper reused elsewhere. `page` is 1-based, `size` defaults to 10.

`CoreKollab.search()` scans every paragraph via `enumerationSequence()`, matching case-insensitively and non-overlappingly within each paragraph (matches never span a paragraph boundary) against the `ChangeStatus.FUSION` text. `totalFindings` always reflects the full scan regardless of the requested page — pagination only slices which findings are returned, it doesn't limit the scan.

## MCP tools

| Tool | Required params | Optional |
|------|----------------|---------|
| `list_documents` | — | — |
| `get_text` | `documentId` | `changeStatus` |
| `get_page_count` | `documentId` | — |
| `get_chapters` | `documentId` | — |
| `get_text_by_pages` | `documentId`, `fromPage`, `toPage` | `changeStatus` |
| `get_text_by_chapter` | `documentId`, `chapter` | `changeStatus` |
| `get_changes` | `documentId` | — |
| `get_edit_mode` | `documentId` | — |
| `edit_text` | `documentId`, `anchorText`, `anchorParagraphIndex`, `anchorCharStart`, `anchorCharEnd`, `newText` | `newTextProperties` |
| `get_comments` | `documentId` | — |
| `get_comment` | `documentId`, `commentId` | — |
| `add_comment` | `documentId`, `commentText`, `author`, `anchorText`, `anchorParagraphIndex`, `anchorCharStart`, `anchorCharEnd` | — |
| `update_comment` | `documentId`, `commentId`, `newText` | — |
| `delete_comment` | `documentId`, `commentId` | — |
| `get_image_metas` | `documentId` | — |
| `get_image` | `documentId`, `imageId` | `scale` |
| `search` | `documentId`, `searchText` | `page`, `size` |

## Logging

`LogDirResolver` (`adapter/logging/`) extends `PropertyDefinerBase` (logback). Returns:
- Linux/macOS: `~/.config/librekollab`
- Windows: `%APPDATA%\librekollab`

`logback.xml` writes to both console and `${logDir}/librekollab.log`. `FeatureRegistry[Tool]` (MCP SDK internal) is suppressed at WARN in both `logback.xml` and `logback-test.xml`.

## UNO / LibreOffice JAR

`libs/uno/libreoffice.jar` — copied from the host LibreOffice installation:

```bash
cp /usr/lib/libreoffice/program/classes/libreoffice.jar libs/uno/
```

Modern LibreOffice (≥7.x) consolidated all UNO classes into this single JAR. The old individual JARs (`ridl.jar`, `jurt.jar`, etc.) are empty stubs — do not use them.

## Testing

```bash
./gradlew test
```

`McpServerIT` — starts a real LibreOffice container (Testcontainers), creates a `TestKollab` via `viaSocket`, and drives the full MCP tool surface over an in-process stdio connection. Test documents are written directly to a shared workspace directory that is bind-mounted into the container.

The LibreOffice container uses a fixed image name (`librekollab-libreoffice-test:latest`, `deleteOnExit=false`). Docker image is built from `docker/libreoffice/Dockerfile`.

Testcontainers 2.0.5 is required for Docker 29.x compatibility (`junit-jupiter` artifact, not the old `junit-5`).

**Known limitation:** the headless LibreOffice in the Testcontainers image does not always reproduce redline/portion behavior seen in a real, GUI-attached LibreOffice — e.g. the Format-redline-splitting described under Text and formatting only occurs in the latter. Tests here still cover the actual insertion/read logic; they just can't prove a given LibreOffice-internal quirk is gone. Treat a live retest (build the `.oxt`, install, connect a real MCP client) as the authority when in doubt.

## Local development

For manual plugin testing: build the OXT, install it in a local LibreOffice, start the server from the Options dialog, open a document, and connect Claude Code.

**Java requirement:** the project builds with `jvmToolchain(25)` (see `build.gradle.kts`), so LibreOffice's *configured* Java runtime (`Extras > Optionen > LibreOffice > Erweitert` / `Tools > Options > LibreOffice > Advanced`) must be Java 25 or newer. If it's set to an older runtime (Windows installs default to whatever JRE is detected, e.g. Java 21), the extension JAR fails to load entirely — silently. Symptom: the Options page entry still appears in the tree, but the page itself renders blank, because `OptionsHandler` can never be instantiated. `%APPDATA%\librekollab\librekollab.log` (or `~/.config/librekollab/librekollab.log`) never gets created in this case — its absence is the tell that the JAR never loaded, as opposed to an XDL/dialog bug.

```bash
./gradlew oxt
# → build/oxt/LibreKollab-1.0-SNAPSHOT.oxt
```

Install via `Extras > Extension Manager > Add...`, restart LibreOffice, then open the Options dialog (`Extras > Optionen > Internet > MCP Server`) and click **Start server**.

Register the MCP server in Claude Code:

```bash
claude mcp add --transport sse --scope user librekollab http://localhost:8080/sse
```
