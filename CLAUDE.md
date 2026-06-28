# LibereKollab

Kotlin LibreOffice extension (.oxt) that exposes document editing as MCP tools. The extension runs inside LibreOffice's JVM, starts an MCP server over SSE, and lets Claude Code read text, navigate chapters/pages, edit text with tracked changes, and manage comments — all via in-process UNO.

## Architecture

```
Claude Code CLI
      │  MCP (SSE, localhost:8080)
LibereKollab OXT extension
      │  in-process UNO (no socket in production)
LibreOffice document(s)
```

The extension implements `XJob` and starts automatically on `onFirstVisibleTask`. An `AtomicBoolean` guard prevents double-start. `McpServer.startSse()` launches a Ktor/Netty server non-blocking (`wait = false`) and returns an `EmbeddedServer<*, *>` so the plugin can stop it later.

`documentId` is the file name of a currently open document in LibreOffice (e.g. `report.odt`). `LibereKollab` resolves it by enumerating `XDesktop.getComponents()`.

## Package structure

```
org.msc.liberekollab
├── domain/
│   ├── model/          # TextAnchor, Comment, Change, ChangeAction, ChangeStatus, MarkedText, ...
│   │   ├── change/
│   │   └── text/
│   │       └── properties/
│   └── port/           # IOAPI, KollabAPI
├── adapter/
│   ├── mcp/            # McpServer
│   ├── plugin/         # LibereKollabPlugin (XJob), OptionsHandler (XContainerWindowEventHandler)
│   ├── uno/            # CoreKollab (abstract), LibereKollab (in-process, production)
│   ├── local/          # LocalFileAdapter
│   └── logging/        # LoggingKollabAPI, LoggingIOAPI
└── LogDirResolver.kt   # Cross-platform log directory (extends PropertyDefinerBase)
```

Test sources:

```
src/test/kotlin/
└── org/msc/liberekollab/
    ├── adapter/uno/TestKollab.kt   # Socket-based KollabAPI for tests
    └── McpServerIT.kt
```

## Coding guidelines

### DDD / Package rules

- `domain/` has zero imports from `adapter/` — domain model and ports are framework-agnostic
- Adapters import from `domain/port/` and `domain/model/` only — never from other adapters
- `LibereKollabPlugin` is the composition root: the only place that instantiates `LibereKollab`, `LocalFileAdapter`, and `McpServer` and wires them together
- All classes that talk to external systems support constructor injection so tests can pass container coordinates

### Kotlin style

- Block body `{ }` for `Unit`-returning functions — expression body `= expr` infers the last expression's type; JUnit rejects `@Test` methods that return non-`Unit`
- `data class` for value objects; `sealed interface` for polymorphic domain types (e.g. `TextProperty`)
- Values always computable from other fields must not be nullable — use a default parameter instead (e.g. `TextAnchor.id`)
- No comments unless the WHY is non-obvious; never describe WHAT the code does

### I/O and concurrency

- All methods on `IOAPI` that touch the filesystem are `suspend` — wrap blocking calls in `withContext(Dispatchers.IO)`
- `nextId()` on `IOAPI` is NOT `suspend` — pure UUID generation, no I/O
- UNO calls are serialized via `limitedParallelism(1)` on `Dispatchers.IO` in `CoreKollab` — never call UNO from multiple coroutines concurrently
- `KollabAPI` functions are `suspend` — callers must use coroutines or `runBlocking` in tests

## CoreKollab / LibereKollab / TestKollab

`CoreKollab` is the abstract base class implementing `KollabAPI`. It holds the `libreOfficeDispatcher` and all UNO logic. Subclasses only need to implement document access:

```kotlin
protected abstract suspend fun <T> withDocument(documentId: String, block: (XTextDocument) -> T): T
protected abstract suspend fun <T> withDocumentMutating(documentId: String, block: (XTextDocument) -> T): T
```

**`LibereKollab`** (`adapter/uno/`, production) — takes `XComponentContext`, finds open documents via `XDesktop.getComponents()`. In `withDocumentMutating` it calls `XStorable.store()` after the block.

**`TestKollab`** (`src/test/`, socket-based) — connects to LibreOffice via UNO socket, loads documents from `IOAPI` storage into a workspace directory, uploads mutated documents back. Created via `TestKollab.viaSocket(host, port, storage, containerWorkspacePath)`.

## OXT packaging

The extension is built by `./gradlew oxt` → `build/oxt/LibereKollab-1.0-SNAPSHOT.oxt`.

```
LibereKollab.oxt (ZIP)
├── liberekollab-all.jar      # fat JAR (excludes libreoffice.jar)
├── LibereKollab.components   # UNO service registration
├── Jobs.xcu                  # auto-start on onFirstVisibleTask
├── OptionsDialog.xcu         # registers Extras > Optionen page
├── dialogs/OptionsDialog.xdl # dialog layout
├── description.xml           # extension identifier org.msc.liberekollab
├── description-en.txt
└── META-INF/manifest.xml
```

`libreoffice.jar` is `compileOnly` + `testImplementation` — present for compilation and tests, excluded from the fat JAR to avoid bundling what LibreOffice already provides.

## Plugin lifecycle

`LibereKollabPlugin` implements `XJob` + `XServiceInfo`. LibreOffice calls `execute()` automatically on first visible task.

- `running: AtomicBoolean` — `compareAndSet(false, true)` prevents double-start
- `engine: EmbeddedServer<*, *>?` — holds the running Ktor server
- `instance: LibereKollabPlugin?` — held in companion object so `OptionsHandler` can call `start()` / `close()`
- `close()` — stops the engine, sets `engine = null`, resets `running` to `false`
- `__create(context)` — JVM static factory required by UNO; stores `instance`

`OptionsHandler` implements `XContainerWindowEventHandler` + `XServiceInfo`. Registered via `OptionsDialog.xcu`. Handles methods `"initialize"` and `"ok"`. Button `btnToggle` starts/stops the server; `btnOpenLog` opens the log file via `java.awt.Desktop.open()` (falls back to opening the directory if the log file doesn't exist yet).

## Workspace / IOAPI

`IOAPI` is implemented by `LocalFileAdapter` — reads and writes files directly to a local directory (`liberekollab.workspace` system property, defaults to `<tmp>/liberekollab`). `LoggingIOAPI` wraps it for structured logging.

`IOAPI.ls()` — lists file names in the workspace.  
`IOAPI.nextId()` — pure UUID, not `suspend`.  
`IOAPI.upload(documentId, fileName, stream)` — writes the stream to `workspacePath/fileName`.  
`IOAPI.download(documentId)` — returns an `InputStream` for the file.

In `TestKollab`: `withDocument` downloads from `IOAPI` to `workspacePath`, loads via UNO `loadComponentFromURL`, runs the block, then deletes the temp file. `withDocumentMutating` additionally calls `XStorable.store()` and uploads back.

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

## Comments (UNO annotations)

`TextAnchor` — domain PK for a text position: `text`, `paragraphIndex`, `charStart`, `charEnd`, `id` (12-char SHA-256 of `"$text:$paragraphIndex:$charStart:$charEnd"`).

`Comment` — `id`, `anchor`, `author`, `content`, `dateTime: LocalDateTime`.

Writing: anchor resolved by walking paragraphs; annotation created via `XMultiServiceFactory`; `DateTimeValue` must be set explicitly.  
Updating/deleting: enumerate fields, match by anchor id; throws `NoSuchElementException` if not found.

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

## Logging

`LogDirResolver` extends `PropertyDefinerBase` (logback). Returns:
- Linux/macOS: `~/.config/liberekollab`
- Windows: `%APPDATA%\liberekollab`

`logback.xml` writes to both console and `${logDir}/liberekollab.log`.

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

`McpServerIT` — starts a real LibreOffice container (Testcontainers, reused via `withReuse(true)`), creates a `TestKollab` via `viaSocket`, and drives the full MCP tool surface over an in-process SSE connection.

Container reuse is enabled via `src/test/resources/testcontainers.properties`. The LibreOffice container uses a fixed image name (`liberekollab-libreoffice-test:latest`, `deleteOnExit=false`). Docker image is built from `docker/libreoffice/Dockerfile`.

Testcontainers 2.0.5 is required for Docker 29.x compatibility (`junit-jupiter` artifact, not the old `junit-5`).

## Local development

For manual plugin testing: build the OXT, install it in a local LibreOffice, open a document, and connect Claude Code.

```bash
./gradlew oxt
# → build/oxt/LibereKollab-1.0-SNAPSHOT.oxt
```

Claude Code MCP config (`~/.config/claude-code/mcp.json`):

```json
{
  "mcpServers": {
    "liberekollab": {
      "type": "sse",
      "url": "http://localhost:8080/sse"
    }
  }
}
```
