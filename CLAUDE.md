# LibreKollab

Kotlin LibreOffice extension (.oxt) that exposes document editing as MCP tools. The extension runs inside LibreOffice's JVM, starts an MCP server over SSE and Streamable HTTP, and lets Claude Code read text, navigate chapters/pages, edit text with tracked changes, manage comments, and read embedded images — all via in-process UNO.

## Architecture

```
Claude Code CLI
      │  MCP (SSE / Streamable HTTP, localhost:8080)
LibreKollab OXT extension
      │  in-process UNO (no socket in production)
LibreOffice document(s)
```

The extension implements `XJob` and is registered via `Jobs.xcu` to run on `onFirstVisibleTask`. `execute()` is a no-op — it only logs that the plugin is ready. The MCP server is **not** started automatically; the user starts it manually from the Options dialog (`Extras > Optionen > Internet > MCP Server`). `McpServer.startHttp()` launches a Ktor/Netty server non-blocking (`wait = false`) and returns an `EmbeddedServer<*, *>` so the plugin can stop it later. An `AtomicBoolean` guard prevents double-start.

`documentId` is the file name of a currently open document in LibreOffice (e.g. `report.odt`). `LibreKollab` resolves it by enumerating `XDesktop.getComponents()`.

## Package structure

```
org.msc.librekollab
├── domain/
│   ├── KollabAPI.kt    # central port — see KDoc for build instructions
│   └── model/          # Comment, Change, ChangeAction, ChangeStatus, MarkedText, ...
│       ├── anchor/      # TextAnchor, PageAnchor (composes a TextAnchor + page)
│       ├── change/
│       ├── id/          # IdGenerator (interface), HashIdGenerator — shared content-hash id computation
│       ├── image/      # Image, ImageMeta
│       └── text/
│           └── properties/
└── adapter/
    ├── mcp/            # McpServer
    │   ├── request/    # per-tool @Serializable request DTOs, decoded from the MCP call's JsonObject args
    │   └── schema/      # ToolSchemaGenerator, @Description — generates ToolSchema from a request DTO's SerialDescriptor
    ├── libreoffice/    # LibreKollab (open, concrete, implements KollabAPI directly), UnoClient, ImageCache
    │   ├── component/  # CommentComponent, SearchComponent, TextComponent, RedlineComponent, ImageComponent,
    │   │               # AuthorComponent, DocumentComponent (interface), LibreDocumentComponent
    │   └── plugin/     # LibreKollabPlugin (XJob), OptionsHandler (XContainerWindowEventHandler)
    └── logging/        # LoggingKollabAPI, LogDirResolver
```

Test sources:

```
src/test/kotlin/
└── org/msc/librekollab/
    ├── adapter/libreoffice/TestKollab.kt              # class TestKollab : LibreKollab, AutoCloseable; TestKollab.viaSocket(...)
    ├── adapter/libreoffice/component/TestDocumentComponent.kt   # socket/workspace-based DocumentComponent impl
    └── McpServerIT.kt
```

## Coding guidelines

See [`codingRules.md`](codingRules.md) for the full, numbered list (DDD/package rules, Kotlin style, I/O and concurrency). Add new rules there, appended with the next free number.

## LibreKollab

There is no abstract base class anymore — `LibreKollab` (`adapter/libreoffice/`, `open class`) implements `KollabAPI` directly. It holds the `libreOfficeDispatcher` and orchestrates `withContext`/`withDocument`/`withDocumentMutating` around each call, delegating all actual UNO work to injected collaborators from `adapter/libreoffice/component/` (`CommentComponent`, `SearchComponent`, `TextComponent`, `RedlineComponent`, `ImageComponent`, `AuthorComponent`, `DocumentComponent`). `LibreKollab` itself no longer knows about `ImageCache` at all — `getImage()`'s upfront cache check is a plain call to `imageComponent.getCachedImage(imageId)`, which needs no `textDoc` and so can run before `withDocument`/UNO are touched at all (see "Images" below).

What used to be two abstract methods overridden per subclass (`withDocument`/`withDocumentMutating`, plus `listDocuments()`) is now a single injected `documentComponent: DocumentComponent` — an interface, since document access is genuinely the one thing that differs in mechanism between production and tests (not just duplicated code, see "DocumentComponent" below). `LibreKollab`'s own `withDocument`/`withDocumentMutating` are now private one-line wrappers delegating to `documentComponent`, kept only so the ~15 call sites across `LibreKollab`'s methods didn't all need rewriting to say `documentComponent.withDocument(...)` directly.

`LibreKollab` is marked `open` — not because it has abstract members, but so `TestKollab` (`src/test/`, see below) can subclass it to swap in a test-specific `documentComponent` and add its own `close()`. Production construction (`LibreKollabPlugin`) just calls `LibreKollab(componentContext)` and gets every default: `documentComponent = LibreDocumentComponent(componentContext, unoClient)`, `authorComponent = AuthorComponent(componentContext)`, etc. `LibreKollab` itself does **not** implement `AutoCloseable` and has no `close()`/`onClose` — production never needs to close anything (LibreOffice manages its own document lifecycle), so that concern deliberately doesn't live in this class; only `TestKollab` needs it, so only `TestKollab` has it (see "TestKollab" below).

### DocumentComponent

`DocumentComponent` (`adapter/libreoffice/component/`, interface) is the one component with two implementations, because — unlike Comment/Search/Text/Redline/Image, which behave identically regardless of caller — production and tests genuinely access documents differently, not just via duplicated code:

```kotlin
interface DocumentComponent {
    suspend fun listDocuments(): List<String>
    suspend fun <T> withDocument(documentId: String, block: (XTextDocument) -> T): T
    suspend fun <T> withDocumentMutating(documentId: String, block: (XTextDocument) -> T): T
}
```

- **`LibreDocumentComponent`** (`adapter/libreoffice/component/`, production default) — finds already-open documents via `XDesktop.getComponents()`, matching on file name/URL. `listDocuments()` returns their file names. `withDocumentMutating` calls `XStorable.store()` after the block. No loading/disposing — documents are assumed already open in the running LibreOffice instance.
- **`TestDocumentComponent`** (`src/test/.../component/`, test-only, own top-level file — not nested) — loads a document fresh from a `containerWorkspacePath` via `XComponentLoader.loadComponentFromURL` for every single call, disposes it afterward, and stores it first if mutating. `listDocuments()` lists file names in the local `basePath` directory instead of asking a live desktop for open documents.

Both `DocumentComponent` implementations are dispatcher-agnostic — neither wraps its own work in `withContext(...)`. `LibreKollab` already wraps every `KollabAPI` call (including `listDocuments()`) in `withContext(libreOfficeDispatcher)` before ever touching `documentComponent`, so there is no need for a second, redundant dispatcher switch inside the component itself.

### AuthorComponent

`AuthorComponent` (`adapter/libreoffice/component/`) is a plain shared component (no interface — same UNO calls work identically whether `componentContext` is in-process or a remote socket bridge). It temporarily swaps the LibreOffice user profile's given name/surname to `author` for the duration of a block, restoring the old values afterward — this is what makes track-changes redlines record the right author. Extracted from what used to be two near-identical `withAuthor()` overrides on `LibreKollab` and `TestKollab` before the `CoreKollab` merge; unifying them into one shared component also fixed a latent inconsistency: the old `TestKollab` override guarded on `author.isEmpty()` while the old `LibreKollab` override guarded on `author == KollabAPI.UNKNOWN_AUTHOR` (`"Unknown Author"`, not `""`) — meaning tests were doing the profile-swap even for the default/unspecified author, while production correctly skipped it. `AuthorComponent` now uses the `UNKNOWN_AUTHOR` check everywhere, matching production's original (correct) behavior. Unlike `DocumentComponent`, `AuthorComponent` stayed a single shared class used by both production and `TestKollab` — the UNO calls are identical either way, so there was never a need for an interface here.

### TestKollab

`TestKollab` (`src/test/`) is a small subclass: `class TestKollab(context, basePath, containerWorkspacePath, onClose: () -> Unit = {}) : LibreKollab(context, documentComponent = TestDocumentComponent(context, basePath, containerWorkspacePath)), AutoCloseable`. It exists purely so the UNO-bridge-disposal concern (`onClose`/`close()`) stays out of `LibreKollab` — `AutoCloseable` is implemented here, not on the shared production class, since only tests ever need to close anything. A companion `TestKollab.viaSocket(host, port, basePath, containerWorkspacePath): TestKollab` bootstraps the UNO socket bridge to the Testcontainers LibreOffice instance and constructs `TestKollab` with an `onClose` that disposes that bridge. Callers (e.g. `McpServerIT`) hold the result typed as `TestKollab`, precisely so `.close()` stays visible on the type.

### UnoClient and shared feature components

`UnoClient` (`adapter/libreoffice/`) holds generic UNO primitives that don't belong to any one feature: `enumerationSequence` (wraps a UNO `XEnumeration` as a Kotlin `Sequence`), `paragraphsOf(textDoc)` (the `Sequence<Any>` of a document's paragraphs — centralizes what used to be `UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text).createEnumeration()` duplicated 7 times across `LibreKollab`/`UnoClient`/`TextComponent`/`SearchComponent`), `paragraphEnumerationOf(textDoc)` (the same lookup but returning the raw `XEnumeration`, not wrapped in a `Sequence` — needed by `TextComponent.getTextByChapter`'s deliberately imperative `while (hasMoreElements())` loop, which can't use a `Sequence`), `viewCursorOf`/`pageCursorOf`, `paragraphAt`, `resolveAnchorRange`, `buildTextAnchor`, and the `DateTime`↔`LocalDateTime` conversions `localDateTimeOf`/`unoDateTimeNow`. `LibreKollab` holds a `private val unoClient: UnoClient = UnoClient()` — `private`, not `protected`, since there's no subclass anymore needing access to it. `UnoClient` stays directly under `adapter/libreoffice/`, alongside `LibreKollab`/`ImageCache` — it isn't itself a feature component.

Feature-specific UNO logic lives under `adapter/libreoffice/component/`, injected via constructor with a default (e.g. `CommentComponent(unoClient)`, `SearchComponent(unoClient)`, `TextComponent(unoClient)`, `RedlineComponent(unoClient)`, `ImageComponent(componentContext, unoClient)`), matching the project's constructor-injection convention. Each takes a `UnoClient` (and, where needed, other already-declared constructor params — `ImageComponent` additionally needs `componentContext` for `GraphicExportFilter`/service creation) and exposes plain (non-suspend) methods operating on an already-resolved `XTextDocument`, plus its own private helpers, feature-specific constants, and collaborators (e.g. `ImageComponent`'s own `ImageCache`). `LibreKollab`'s `KollabAPI` overrides just handle `withContext`/`withDocument`/`withDocumentMutating` and delegate the body to the relevant component.

- **`CommentComponent`** — `getComments`, `addComment`, `updateComment`, `deleteComment` (constants: `ANNOTATION_SERVICE`, `AUTHOR_PROPERTY`, etc.).
- **`SearchComponent`** — `search(textDoc, searchText, page, size, paragraphTextOf)`, plus the private `matchesInParagraph`/`findAllOccurrences`. Search needs per-paragraph `ChangeStatus.FUSION` text, which is produced by `TextComponent.extractParagraphMarkedText` — not search-specific, so `SearchComponent` doesn't depend on `TextComponent` directly. Instead, `LibreKollab.search()` passes `textComponent.extractParagraphMarkedText(...)` in as a `paragraphTextOf: (Any, Int) -> String` lambda, keeping `SearchComponent` free of a dependency it doesn't own.
- **`TextComponent`** — the read/write text operations: `getText`, `getTextByPages`, `getTextByChapter`, `getChapters`, `editText`, plus the public `extractParagraphMarkedText` (also called by `LibreKollab.search()`, see above) and the private helpers `insertFormattedText`/`formattingRuns`/`applyRunProperties`/`outlineLevel`/etc.
- **`RedlineComponent`** — `changesInParagraph`, i.e. everything backing `getChanges()`: scanning `Redline`-type text portions into `Change` objects (`RedlineType`, `RedlineAuthor`, `RedlineDateTime`, `IsStart` — LibreOffice/UNO's own name for its Track Changes engine is "Redline", not a project term).
- **`ImageComponent`** — `getCachedImage(imageId)`, `getImageMetas(documentId, textDoc)`, `getImage(documentId, textDoc, imageId)`, plus the private `getGraphicShapes`/`shapeAnchorRange`/`imageOf`/`cachedImageOf`/`base64EncodedByteCount`/`exportShapePng`/`readAllBytes` and its own `ImageCache = ImageCache()` default (see "Images" below). `getCachedImage()` needs only an `imageId`, no `textDoc` — that's what lets `LibreKollab.getImage()` call it before resolving the document at all.

`TextComponent` and `RedlineComponent` both scan the same kind of UNO text-portion sequence (grouped by `TextPortionType` = `"Redline"` vs `"Text"`) but for different purposes — `TextComponent` tracks whether a portion is currently inside a visible insert/delete bracket to decide what to include in `MarkedText`, while `RedlineComponent` extracts the actual `Change` (author, timestamp, action) once a bracket closes. Both therefore declare their own small `TEXT_PORTION_TYPE_PROPERTY`/`PORTION_TYPE_REDLINE`/`PORTION_TYPE_TEXT`/`IS_START_PROPERTY`/`REDLINE_TYPE_PROPERTY`/`REDLINE_TYPE_INSERT`/`REDLINE_TYPE_DELETE` constants rather than sharing them — consistent with each component owning its own constants (see `CommentComponent`), and keeping `UnoClient` free of feature-specific property-name literals.

## OXT packaging

The extension is built by `./gradlew oxt` → `build/oxt/LibreKollab-1.1.0.oxt`.

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
- `start()` — companion method; called by `OptionsHandler` when the user clicks "Start server". Reads and parses `librekollab.port` (default `8080`) to an `Int` *before* the `compareAndSet(false, true)` guard, so a non-numeric value (bypassing the dialog, e.g. an externally-set system property) throws before `running` is touched, instead of leaving it stuck at `true` with nothing actually started. Only then creates `LibreKollab` + `McpServer` and calls `startHttp(port)`, wrapped in a `try/catch (IOException)` that resets `running` back to `false` before rethrowing — so a genuine bind failure (e.g. the port already in use by something else entirely) doesn't leave `running` stuck `true` with no server actually listening, mirroring the parse-failure fix above but for the bind step instead of the parse step.
- `close()` — stops the engine, sets `engine = null`, resets `running` to `false`
- `__getComponentFactory(implementationName)` — JVM static factory required by UNO; dispatches both `LibreKollabPlugin` and `OptionsHandler` by implementation name

`OptionsHandler` implements `WeakBase() + XContainerWindowEventHandler + XServiceInfo`. Registered via `OptionsDialog.xcu`. `WeakBase` provides `XTypeProvider`/`XInterface` required for UNO marshalling.

- `callHandlerMethod(window: XWindow, eventObject: Any, method: String)` — LibreOffice calls this for all dialog events:
  - `"external_event"` — initialization lifecycle events. `AnyConverter.toString(eventObject)` yields `"initialize"`, `"back"`, or `"ok"`. `"initialize"`/`"back"` call `initialize()` (stores `XControlContainer`, sets `MultiLine=true` on status label, refreshes UI); `"ok"` calls `savePort()`, which only persists the port field if it's non-empty *and* parses as an int (`toIntOrNull()`); an invalid value is silently ignored (logged at `warn`) rather than reaching `LibreKollabPlugin.start()`, where `toInt()` would otherwise throw *after* `running` had already flipped to `true`, leaving the plugin stuck thinking it's running when it isn't.
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

`LibreKollab.editText()` calls the private `ensureEditModeOn(textDoc)` inline, on the already-resolved `textDoc`, before delegating to `textComponent.editText()` — both run inside the same single `withContext(libreOfficeDispatcher)`/`withDocumentMutating` block as the edit itself, so the RecordChanges check-and-enable and the edit are atomic with respect to the serialized UNO dispatcher (Regel 25); no separate dispatch, and no other queued coroutine can interleave a `set_edit_mode`-style change in between. `ensureEditModeOn` is used **exclusively for `editText`**.

Comments (`addComment`, `updateComment`, `deleteComment`) bypass `ensureEditModeOn` — LibreOffice does not track annotation changes in its redline system.

`setEditMode` / `getEditMode` are on `KollabAPI` — `getEditMode` backs the `get_edit_mode` MCP tool; `setEditMode` exists on the interface but isn't called by `editText` anymore (superseded by the inline `ensureEditModeOn`) and isn't itself exposed as an MCP tool.

### Stale anchors after editing

`editText` shifts every character offset *after* the edited range within that paragraph — an `anchorCharStart`/`anchorCharEnd` computed before the edit is no longer valid afterward. A caller that edits the same paragraph twice using two offsets computed from the same earlier read (instead of re-fetching the second anchor via `search`/`get_text` after the first edit) will have the second edit land at the wrong position — a stale offset computed this way was confirmed to reproduce that exact corruption against a real manuscript. `McpServerLongSessionIT` (`T02`) covers the correct pattern: re-searching for the second anchor after the first edit lands it correctly, with the exact same `editText` code. This isn't a bug in `TextComponent`/`UnoClient` — `insertFormattedText`/`resolveAnchorRange` apply the given offsets literally and correctly; the anchor itself was just stale. See the `edit_text` MCP tool description for the caller-facing version of this rule.

## Track Changes model

`ChangeAction` — `INSERT` / `DELETE`  
`ChangeStatus` — `BEFORE` (deletions visible, insertions hidden) / `FUSION` (raw, default) / `AFTER` (insertions visible, deletions hidden)  
`Change` — `action`, `author`, `dateTime`, `text`, `anchor: TextAnchor`

## Text and formatting

`MarkedText(text: String, properties: List<TextProperty>)` — text-returning methods return this.

`TextProperty` is a sealed interface. Implementations: `BoldProperty`, `ItalicProperty`, `UnderlineProperty`, `StrikethroughProperty` — each holds a `MarkIndex(paragraphIndex, from, to)`.

`insertFormattedText` (in `TextComponent`, used by `editText`) inserts `newText` one formatting run at a time, setting the cursor's character properties *before* each `insertString` call — never insert plain text and reformat it afterward. Reformatting already-inserted, not-yet-accepted text makes LibreOffice record the attribute change as its own "Format" redline, splitting one logical insert into two adjacent redlines. `getChanges()` reads redlines by bracket (`IsStart`/`IsEnd`) and resets its accumulator on every redline end, so a Format redline ending between two Insert-redline halves silently drops everything after it. This only reproduces against a real, GUI-attached LibreOffice — see Testing below.

## Images

`ImageMeta(imageId, width, height, sizeMb, page, textAnchor)` — lightweight descriptor returned by `getImageMetas`. `width`/`height` are in 1/100 mm (LibreOffice native unit). `sizeMb` reflects the Base64-encoded size `get_image` will actually transmit, not the raw PNG size — computed via the exact `4 * ceil(n / 3)` Base64 length formula in `ImageComponent.base64EncodedByteCount()` (no actual encoding happens in the domain), so callers get an honest number to decide upfront whether to request a downscaled `get_image` call.

`Image(bytes, width, height, textAnchor, id)` — full image returned by `getImage`. `bytes` is the raw PNG data; Base64 encoding only happens at the MCP boundary in `McpServer`, never in the domain. `id` is a 12-char SHA-256 of the raw PNG bytes + dimensions + anchor, computed via `HashIdGenerator` (see "Id generation" below). The `get_image` MCP tool returns this as a native `ImageContent` block (not JSON text) and accepts an optional `scale` argument (0 exclusive–1 inclusive) to downscale the PNG in `McpServer` before Base64-encoding it — the domain layer always exports at original resolution.

Implementation in `ImageComponent` (`adapter/libreoffice/component/`, see "UnoClient and feature components" above):
- `XTextGraphicObjectsSupplier.getGraphicObjects()` enumerates all embedded images by name.
- Each shape is exported to PNG via `GraphicExportFilter` (`com.sun.star.drawing.GraphicExportFilter`): `XExporter.setSourceDocument(shape)` + `XFilter.filter(props)` writing to a `com.sun.star.io.Pipe`. The Pipe implements both `XInputStream` and `XOutputStream` and lives on the LibreOffice side — bytes are read back over the UNO bridge via `XInputStream.readBytes()`. This needs `componentContext` (for `XMultiComponentFactory.createInstanceWithContext`), which is why `ImageComponent` takes it in its constructor alongside `UnoClient`.
- Page number is resolved by moving a `XPageCursor` to the image anchor.

`ImageCache` (`adapter/libreoffice/`) holds actual image bytes in exactly one place — `byId: Cache<String, Image>` keyed by `Image.id`, bounded by total byte weight (`maximumWeight` + a `Weigher` on `image.bytes.size`, not entry count — images vary wildly in size) with an `expireAfterAccess` safety net. Since `Image.id` is a content hash (bytes + dimensions + anchor), it's only known *after* exporting, so an id-only cache can't avoid a first-time export. A second, tiny `shapeToId: Cache<Pair<String, String>, String>` — keyed by `(documentId, shape name)`, known *before* export — stores only the id string, not the image, and is bounded by entry count instead of weight. Two independently-sized byte-weighted caches would let their retained sets drift apart over time (each evicting on its own schedule) and double the real worst-case memory bound; routing the shape lookup through a lightweight id-index into the single byte-holding cache avoids that.

`ImageComponent.cachedImageOf()` calls `getByShape()` (index lookup, then `byId` lookup) first; only a full miss triggers `imageOf()` (the actual UNO export), after which `put()` populates both `byId` and `shapeToId`. `LibreKollab.getImage()` additionally calls `imageComponent.getCachedImage(imageId)` directly up front, before calling into `withDocument` at all, for the case where the caller already has an `imageId` from a prior response — since that lookup needs only the `imageId`, not a resolved `XTextDocument`, it can run ahead of any UNO access. A changed image in the document produces a different content-hash id, so a stale entry is simply never looked up again and ages out via the weight/access bounds — no explicit invalidation needed. `ImageCache` is entirely private to `ImageComponent` (`imageCache: ImageCache = ImageCache()`) — `LibreKollab` doesn't hold a reference to it at all, only to `ImageComponent`.

## Id generation

`IdGenerator` (`domain/model/id/`, interface) + `HashIdGenerator` (the sole implementation, a stateless `object`) centralize the SHA-256/hex/truncate-to-12-chars mechanics shared by every content-hash id in the domain: `IdGenerator.generate(bytes: ByteArray): String`. Each data class still builds its own key (the fields vary per class) and passes only the resulting bytes to `HashIdGenerator.generate(...)` as a default parameter expression — `data class` primary constructors require every parameter to be a `val`/`var` property, so a truly injected generator isn't possible here without polluting `equals`/`hashCode`/`toString`; the interface exists so the hashing mechanics stay swappable (e.g. a future UUID-based implementation) even though callers reference `HashIdGenerator` directly by name.

- `TextAnchor.id` — SHA-256 of `"$text:$paragraphIndex:$charStart:$charEnd"`.
- `Image.id` — SHA-256 of the raw PNG bytes + `":$width:$height:${textAnchor.id}"`.
- `Comment.id` — SHA-256 of `"${anchor.id}:$author"`. Deliberately excludes `content`/`dateTime` (both change on `updateComment`, and the id must survive an update) and deliberately includes `author` (not just `anchor.id`) so two different reviewers commenting on the exact same text range get distinct ids — `CommentComponent.findCommentField()` matches by rebuilding the full `Comment` and comparing `.id`. The one remaining edge case — the *same* author commenting the exact same anchor twice — still collides; accepted as an unlikely corner case rather than adding a persisted per-comment UUID.

**`encodeDefaults` gotcha:** `Json`'s default (`encodeDefaults = false`) omits a property from the wire JSON whenever its *current* value equals what re-running its default-parameter expression would produce right now — not "whenever the property merely has a default." Verified against the decompiled `write$Self` bytecode: for `Comment`/`TextAnchor`, the generated serializer literally re-embeds the `id` default expression and compares it (`Intrinsics.areEqual`) against the actual field before deciding whether to write it. Since every real `Comment`/`TextAnchor` in this codebase is built via the default (nothing ever passes an explicit, different `id`), that comparison is always true — so with the bare `Json` singleton, `id` would be silently missing from every response, while still round-tripping "correctly" in Kotlin-to-Kotlin tests, because `Json.decodeFromString` just recomputes the same missing default on the way back in. `McpServer` therefore uses its own `private val json = Json { encodeDefaults = true }` (companion object) instead of the bare `Json` object, for every encode *and* decode call in the file.

## Comments (UNO annotations)

`TextAnchor` — domain PK for a text position: `text`, `paragraphIndex`, `charStart`, `charEnd`, `id` (see "Id generation" above).

`Comment` — `id`, `anchor`, `author`, `content`, `dateTime: LocalDateTime`.

Writing: anchor resolved by walking paragraphs; annotation created via `XMultiServiceFactory`; `DateTimeValue` must be set explicitly. `CommentComponent.addComment()` returns the created `Comment` (built via the same `commentOf()` helper `getComments()` uses) so the caller gets the new comment's `id` back immediately, instead of having to call `get_comments` again and re-match it by anchor/author/content.  
Updating/deleting: enumerate fields, match by anchor id; throws `NoSuchElementException` if not found.

## Search

`TextAnchor` and `PageAnchor` live in `domain/model/anchor/`. `PageAnchor(textAnchor, page)` composes a `TextAnchor` with the page it's on — composition, not inheritance, since `TextAnchor` is a `data class` (implicitly final in Kotlin, can't be subclassed).

`SearchResult(page, size, totalFindings, elements: List<PageAnchor>)` in `domain/model/` — a paginated response, not a wrapper reused elsewhere. `page` is 1-based, `size` defaults to 10.

`LibreKollab.search()` resolves the document, then delegates to `SearchComponent.search()` (see "UnoClient and shared feature components" above), passing a `paragraphTextOf` lambda backed by `extractParagraphMarkedText(..., ChangeStatus.FUSION, ...)`. `SearchComponent` scans every paragraph via `unoClient.enumerationSequence()`, matching case-insensitively and non-overlappingly within each paragraph (matches never span a paragraph boundary). `totalFindings` always reflects the full scan regardless of the requested page — pagination only slices which findings are returned, it doesn't limit the scan.

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
| `edit_text` | `documentId`, `anchorText`, `anchorParagraphIndex`, `anchorCharStart`, `anchorCharEnd`, `newText` | `newTextProperties`, `author` |
| `get_comments` | `documentId` | — |
| `get_comment` | `documentId`, `commentId` | — |
| `add_comment` | `documentId`, `commentText`, `author`, `anchorText`, `anchorParagraphIndex`, `anchorCharStart`, `anchorCharEnd` | — |
| `update_comment` | `documentId`, `commentId`, `newText` | — |
| `delete_comment` | `documentId`, `commentId` | — |
| `get_image_metas` | `documentId` | — |
| `get_image` | `documentId`, `imageId` | `scale` |
| `search` | `documentId`, `searchText` | `page`, `size` |

`get_comment`/`get_image` on an unknown `commentId`/`imageId` return `CallToolResult(content = listOf(TextContent("not found")), isError = true)` — an actual tool error, not a plain text result — consistent with `update_comment`/`delete_comment` on an unknown `commentId` (which already surface as tool errors via an uncaught `NoSuchElementException` hitting the MCP SDK's own catch-all). A caller checks `isError`, not the text, to detect "not found".

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

`McpServerIT` — starts a real LibreOffice container (Testcontainers), creates a `TestKollab` via `viaSocket`, and drives the full MCP tool surface over an in-process stdio connection. Test documents are written directly to a shared workspace directory that is bind-mounted into the container. `TestKollab` is backed by `TestDocumentComponent`, which reloads each document fresh from disk and disposes it again on every single call — fine for testing each tool in isolation, but it can't accumulate any in-memory UNO state (cursors, redlines) across a sequence of calls the way a real, continuously-open document does.

`McpServerLongSessionIT` — a separate suite (own Testcontainers instance) for behavior that only shows up across *many* calls against the *same* open document, closer to a real editing session. Uses `TestKollab.viaSocketPersistent`, backed by `PersistentTestDocumentComponent` (`src/test/.../component/`), which loads each `documentId` once and keeps it open (never reloading/disposing between calls) until `close()`. `TestKollab`'s companion factory logic (`connect()`) is shared between `viaSocket` and `viaSocketPersistent` — they differ only in which `DocumentComponent` they wire up on top of the same UNO socket connection. This suite is what caught the stale-anchor-after-edit issue described under "Stale anchors after editing" above — `McpServerIT`'s per-call reload had made it untestable there.

`LibreDocumentComponentTest` — a plain JVM unit test (no Testcontainers, no LibreOffice) covering `LibreDocumentComponent` specifically, using MockK to mock the UNO interfaces it talks to (`XDesktop`, `XEnumerationAccess`/`XEnumeration`, `XTextDocument`, `XMultiComponentFactory`, `XComponentContext`). This works because `LibreDocumentComponent` only enumerates already-open documents via `XDesktop` and compares URLs as strings — it never touches the filesystem — and `UnoRuntime.queryInterface(Type, obj)` returns `obj` as-is once `Type.isInstance(obj)` already holds (verified against the decompiled bytecode), so a mock that directly implements the target interface satisfies it without any bridge/proxy involved. This is the model for unit-testing other UNO-adjacent components that don't need a live LibreOffice — reach for a Testcontainers-based `McpServerIT` case only when the behavior genuinely depends on real LibreOffice document/redline internals. MockK (`io.mockk:mockk`) is a `testImplementation` dependency; `tasks.test` passes `-XX:+EnableDynamicAgentLoading` so its ByteBuddy-based mocking doesn't print a JDK dynamic-agent-loading warning on every run.

`ImageScalerTest` (`domain/model/image/`) — the first (and so far only) pure domain-logic unit test in the project; no UNO/MockK involved at all, just plain JVM `BufferedImage`/`ImageIO` round-trips. Covers `ImageScaler.scale()`'s factor boundaries (`0`, negative, `>1`, `1.0`), a corrupt/undecodable input, and the `coerceAtLeast(1)` pixel floor for very small scale factors.

The LibreOffice container uses a fixed image name (`librekollab-libreoffice-test:latest`, `deleteOnExit=false`). Docker image is built from `docker/libreoffice/Dockerfile`.

Testcontainers 2.0.5 is required for Docker 29.x compatibility (`junit-jupiter` artifact, not the old `junit-5`).

**Known limitation:** the headless LibreOffice in the Testcontainers image does not always reproduce redline/portion behavior seen in a real, GUI-attached LibreOffice — e.g. the Format-redline-splitting described under Text and formatting only occurs in the latter. Tests here still cover the actual insertion/read logic; they just can't prove a given LibreOffice-internal quirk is gone. Treat a live retest (build the `.oxt`, install, connect a real MCP client) as the authority when in doubt.

## Local development

For manual plugin testing: build the OXT, install it in a local LibreOffice, start the server from the Options dialog, open a document, and connect Claude Code.

**Java requirement:** the project builds with `jvmToolchain(25)` (see `build.gradle.kts`), so LibreOffice's *configured* Java runtime (`Extras > Optionen > LibreOffice > Erweitert` / `Tools > Options > LibreOffice > Advanced`) must be Java 25 or newer. If it's set to an older runtime (Windows installs default to whatever JRE is detected, e.g. Java 21), the extension JAR fails to load entirely — silently. Symptom: the Options page entry still appears in the tree, but the page itself renders blank, because `OptionsHandler` can never be instantiated. `%APPDATA%\librekollab\librekollab.log` (or `~/.config/librekollab/librekollab.log`) never gets created in this case — its absence is the tell that the JAR never loaded, as opposed to an XDL/dialog bug.

```bash
./gradlew oxt
# → build/oxt/LibreKollab-1.1.0.oxt
```

Install via `Extras > Extension Manager > Add...`, restart LibreOffice, then open the Options dialog (`Extras > Optionen > Internet > MCP Server`) and click **Start server**.

Register the MCP server in Claude Code:

```bash
claude mcp add --transport sse --scope user librekollab http://localhost:8080/sse
```

## HTTP transports

`McpServer.startHttp(port)` serves both legacy SSE at `/sse` and stateful Streamable HTTP at `/mcp` on the same localhost port. `mcpStreamableHttp` installs the MCP content negotiation and SSE plugins; the legacy SSE route is registered afterward. Both factories build their own MCP server/session with the same tool registration and shared `KollabAPI`. The project uses MCP Kotlin SDK 0.15.0 (Streamable HTTP support was already available in 0.13.0). Start/stop controls manage both endpoints together.
