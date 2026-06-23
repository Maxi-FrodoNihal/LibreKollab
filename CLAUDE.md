# LibereKollab

Kotlin/Ktor REST API that exposes LibreOffice document editing via UNO as an AI-usable tool interface. An AI agent can read text, navigate chapters/pages, edit text, read and manage comments — all via HTTP.

## Architecture

```
HTTP Client / AI Agent
        │
   Ktor REST API (port 8080)
        │
   ┌────┴─────────────────┐
   │                       │
UNO socket (port 2002)   MinIO (port 9000)
LibreOffice headless      document storage
```

**Scaling model:** one Kubernetes Pod (Ktor + LibreOffice sidecar) per open document. MinIO is shared centrally. The `limitedParallelism(1)` dispatcher in `LibereKollab` serializes all UNO calls — LibreOffice is not thread-safe.

## Package structure

```
org.msc.liberekollab
├── abstrakt/           # Interfaces: IOAPI, KollabAPI
├── controller/         # IOController, KollabController
├── storage/            # MinioObject
├── model/              # TextAnchor, Comment, Change, ChangeAction, ChangeStatus
├── request/            # AddCommentRequest, UpdateCommentRequest, EditTextRequest
├── response/           # Response data classes
├── LibereKollab.kt     # KollabAPI implementation (UNO)
├── KtorServer.kt       # Server setup (plugins, routing, Swagger)
└── Main.kt             # Entrypoint — calls KtorServer().start()
```

## Key design rules

- **DDD port/adapter separation**: never reference `MinioObject` or `LibereKollab` directly except in `KtorServer.kt` and tests. Use `IOAPI` and `KollabAPI` everywhere else.
- All classes that talk to external systems must support constructor injection so tests can pass container coordinates. The no-arg constructor reads from `.env` via dotenv-kotlin.
- `KollabAPI` functions are `suspend` — callers must use coroutines or `runBlocking` in tests.
- No test tags, no separate source sets — all tests live in `src/test/kotlin/`.
- Controller route handlers are extracted as `private suspend fun RoutingContext.xxx()` — never inline lambdas in `registerRoutes`.
- Route registration uses `io.github.smiley4.ktoropenapi` HTTP method imports (`get`, `post`, `patch`, `delete`) — these shadow Ktor's built-in equivalents and accept an optional documentation lambda as second parameter.

## Workspace flow

`LibereKollab` downloads from MinIO → writes to `workspacePath` (host-side) → passes `containerWorkspacePath` to LibreOffice via UNO → deletes temp file.

- **Read-only** (`getText`, `getPageCount`, etc.): use `withDocument` → calls `withWorkspaceFile` — temp file deleted after block
- **Mutating** (`editText`, `addComment`, etc.): use `withDocumentMutating` → calls `withWorkspaceFileAndWriteback` — calls `XStorable.store()` then uploads back to MinIO before deleting

Object names in MinIO are `"${documentId}_${fileName}"`. `IOAPI.nextId()` generates the UUID; `upload(documentId, fileName, stream)` stores the object and returns the `documentId`.

### Workspace paths

Two separate path concepts exist because in dev mode the Ktor app runs on the host while LibreOffice runs in Docker:

- `workspacePath` — host-side path where the JVM writes temp files. Defaults to `user.dir + "/workspace"` if `WORKSPACE_PATH` env var is not set.
- `containerWorkspacePath` — Linux path inside the LibreOffice container (always `/workspace`). Hardcoded in the no-arg constructor.

In production both containers mount the same Docker volume at `/workspace`, so `WORKSPACE_PATH=/workspace` is set and both paths are identical.

## Edit mode / Track Changes

`withEnsuredEditMode(documentId)` checks `RecordChanges` and enables it if needed. It is used **exclusively for `editText`** — text body changes must be tracked so humans can review them.

```
withEnsuredEditMode(documentId) {       // checks + enables RecordChanges if off
    withDocumentMutating(documentId) {  // loads, runs block, XStorable.store(), uploads
        // UNO text mutations here
    }
}
```

**Comments (annotations) are NOT subject to Track Changes** — `addComment`, `updateComment`, and `deleteComment` bypass `withEnsuredEditMode` entirely. LibreOffice does not track annotation add/delete/update in its redline system.

- `setEditMode` / `getEditMode` are part of `KollabAPI` but **not exposed via HTTP** — `setEditMode` is called implicitly by `withEnsuredEditMode`.
- `GET /kollab/text/{documentId}/editmode` is the only HTTP endpoint for edit mode (read-only).

## Track Changes model

When `editText` runs, LibreOffice records a Delete redline (original text) and an Insert redline (new text). These are exposed via:

- **`ChangeAction`** (`model/`) — enum: `INSERT`, `DELETE`
- **`ChangeStatus`** (`model/`) — enum controlling which text variant is returned:
  - `BEFORE` — original text (insertions hidden, deletions visible)
  - `FUSION` — raw LibreOffice string including both deleted and inserted text (default)
  - `AFTER` — text with changes applied (deletions hidden, insertions visible)
- **`Change`** (`model/`) — holds `action: ChangeAction`, `author`, `dateTime`, `text`, `anchor: TextAnchor`

Text-reading endpoints accept `?changeStatus=BEFORE|FUSION|AFTER` as a query parameter (default: `FUSION`).

Accept/reject tracked changes is intentionally **not in the API** — that is a human editorial decision made directly in LibreOffice.

## Comments (UNO annotations)

Comments are LibreOffice annotations (`com.sun.star.text.TextField.Annotation`) anchored to a `TextAnchor`.

**`TextAnchor`** (`model/`) is the domain PK for a text position:
- `text` — the anchored string
- `paragraphIndex` — 0-based paragraph index in document order
- `charStart` / `charEnd` — character offsets within the paragraph
- `toHash()` — SHA-256 of `"$text:$paragraphIndex:$charStart:$charEnd"` → used as `Comment.id`

**`Comment`** (`model/`) holds `id`, `anchor`, `author`, `content`, `dateTime: LocalDateTime`.

**Reading** (`getComments`): uses `XTextFieldsSupplier`, filters for the Annotation service, builds `TextAnchor` by creating a cursor from doc start to anchor start and counting `\n` characters. Results are sorted by `(paragraphIndex, charStart)`.

**Writing** (`addComment`): resolves the anchor range by walking paragraphs to `paragraphIndex` then using `goRight`, creates the annotation field via `XMultiServiceFactory`, sets `Content`, `Author`, and `DateTimeValue` (must be set explicitly — LibreOffice does not auto-fill it via UNO). Uses `absorb = true` in `insertTextContent` to create a range annotation (not a collapsed point annotation).

**Updating** (`updateComment`): enumerates annotations, matches by anchor hash, sets `Content` and updates `DateTimeValue` to now. Throws `NoSuchElementException` if not found.

**Deleting** (`deleteComment`): enumerates annotations, matches by anchor hash, calls `XText.removeTextContent`. Throws `NoSuchElementException` if not found.

## UNO / LibreOffice JAR

`libs/uno/libreoffice.jar` is copied from the host LibreOffice installation:

```bash
cp /usr/lib/libreoffice/program/classes/libreoffice.jar libs/uno/
```

Modern LibreOffice (≥7.x) consolidated all UNO classes into this single JAR. The old individual JARs (`ridl.jar`, `jurt.jar`, etc.) are empty stubs — do not use them.

## Running locally (dev)

Start MinIO + LibreOffice:
```bash
docker compose -f docker-compose.dev.yml up
```

Then run `Main.kt` from IntelliJ. LibreOffice is reachable at `localhost:2002`, MinIO at `localhost:9000`. Configuration is read from `.env`. No `WORKSPACE_PATH` needed — defaults to `<project-root>/workspace` which is bind-mounted into the LibreOffice container.

## Running in Docker

```bash
docker compose up --build
```

App, LibreOffice, and MinIO all start as separate containers with a shared workspace volume. All three are on an isolated Docker bridge network — only `liberekollab` exposes port 8080 to the host. MinIO and LibreOffice have no `ports:` mappings and are unreachable from outside.

## Swagger / OpenAPI

The OpenAPI spec and Swagger UI are auto-generated from route definitions at startup using `io.github.smiley4:ktor-openapi:5.0.0` and `io.github.smiley4:ktor-swagger-ui:5.0.0`.

| URL | Description |
|-----|-------------|
| `http://localhost:8080/swagger/index.html` | Swagger UI |
| `http://localhost:8080/swagger` | Redirects to Swagger UI |
| `http://localhost:8080/api.json` | Raw OpenAPI JSON spec |

Route documentation is declared inline in `registerRoutes` via the ktor-openapi DSL — no separate YAML file to maintain. Each route has a documentation lambda:

```kotlin
get("/path", {
    tags = listOf("Tag")
    summary = "Short description"
    request { pathParameter<String>("id") { } }
    response { code(HttpStatusCode.OK) { body<ResponseType>() } }
}) { handler() }
```

## Tests

Integration tests use Testcontainers 2.0.5 (required for Docker 29.x — older versions hardcode Docker API ≤1.32). Container reuse is enabled via `src/test/resources/testcontainers.properties`. AssertJ is used for assertions.

```bash
./gradlew test
```

- **`LibereKollabIT`** — tests KollabAPI directly (LibreOffice + MinIO containers, workspace bind-mounted)
- **`IOControllerIT`** — tests all IOController routes via Ktor `testApplication` (MinIO container, fresh bucket per test)

The LibreOffice container uses a fixed image name (`liberekollab-libreoffice-test:latest`, `deleteOnExit=false`) and a fixed workspace path so `withReuse(true)` actually works — subsequent test runs reuse the running container instead of rebuilding.

## API

### Document storage (`/documents`)

| Method | Route | Description |
|--------|-------|-------------|
| `GET` | `/documents/health` | Health check |
| `POST` | `/documents/upload` | Multipart upload → returns `documentId` |
| `GET` | `/documents` | List all document IDs |
| `GET` | `/documents/{id}` | Download raw bytes |
| `DELETE` | `/documents/{id}` | Delete document |

### Kollab (`/kollab`)

| Method | Route | Description |
|--------|-------|-------------|
| `GET` | `/kollab/health` | Health check |
| `GET` | `/kollab/text/{documentId}?changeStatus=` | Full document text (`BEFORE`/`FUSION`/`AFTER`) |
| `PATCH` | `/kollab/text/{documentId}` | Edit a text range (body: `EditTextRequest`) |
| `GET` | `/kollab/text/{documentId}/changes` | List tracked changes |
| `GET` | `/kollab/text/{documentId}/pagecount` | Number of pages |
| `GET` | `/kollab/text/{documentId}/chapters` | List chapter headings |
| `GET` | `/kollab/text/{documentId}/pages/{fromPage}/{toPage}?changeStatus=` | Text of page range |
| `GET` | `/kollab/text/{documentId}/chapters/{chapter}?changeStatus=` | Text of a chapter |
| `GET` | `/kollab/text/{documentId}/comments` | List all comments |
| `GET` | `/kollab/text/{documentId}/comments/{commentId}` | Get single comment |
| `POST` | `/kollab/text/{documentId}/comments` | Add a comment (body: `AddCommentRequest`) |
| `PATCH` | `/kollab/text/{documentId}/comments/{commentId}` | Update comment text (body: `UpdateCommentRequest`) |
| `DELETE` | `/kollab/text/{documentId}/comments/{commentId}` | Delete a comment |
| `GET` | `/kollab/text/{documentId}/editmode` | Check if Track Changes is active |

### Request bodies

**`AddCommentRequest`**
```json
{
  "commentText": "...",
  "author": "...",
  "anchor": { "text": "...", "paragraphIndex": 0, "charStart": 7, "charEnd": 20 }
}
```

**`UpdateCommentRequest`**
```json
{ "newText": "..." }
```

**`EditTextRequest`**
```json
{
  "anchor": { "text": "...", "paragraphIndex": 0, "charStart": 0, "charEnd": 5 },
  "newText": "..."
}
```

## What is still missing (planned)

- Error handling for UNO connection failures and document load errors
- **Real HTTP integration tests**: `IOControllerIT` currently uses Ktor's in-memory `testApplication` (test engine, not Netty). 90% coverage but Netty-specific behavior (connection handling, real HTTP frames) is untested. Future refactoring: start a real `embeddedServer(Netty)` on a random port in tests, make real HTTP calls, stop after test. Requires `start(port)` + `stop()` on `KtorServer`.

### Logging Decorator

Add `LoggingKollabAPI(delegate: KollabAPI) : KollabAPI` and `LoggingIOAPI(delegate: IOAPI) : IOAPI` — wrap the interfaces, log each call (method + parameters + duration), delegate to the real implementation. In `KtorServer.kt`, wrap `LibereKollab()` and `MinioObject()` with the decorators.

### RichText — Text formatting properties

Text-returning methods (`getText`, `getTextByPages`, `getTextByChapter`) currently return plain `String`. Replace with `RichText`:

```kotlin
enum class SpanType { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH }
data class TextSpan(val from: Int, val to: Int, val type: SpanType)
data class RichText(val text: String, val spans: List<TextSpan>)
```

UNO implementation: enumerate text portions, read `CharWeight`, `CharPosture`, `CharUnderline`, `CharStrikeout` properties to build the span list alongside the text string.

## Environment variables (`.env`)

| Variable | Default | Description |
|---|---|---|
| `LIBREOFFICE_HOST` | `localhost` | UNO socket host |
| `LIBREOFFICE_PORT` | `2002` | UNO socket port |
| `MINIO_HOST` | `localhost` | MinIO host |
| `MINIO_PORT` | `9000` | MinIO API port |
| `MINIO_ACCESS_KEY` | `minioadmin` | MinIO credentials |
| `MINIO_SECRET_KEY` | `minioadmin` | MinIO credentials |
| `MINIO_BUCKET` | `documents` | Bucket name |
| `WORKSPACE_PATH` | `<user.dir>/workspace` | Host-side workspace path (optional in dev) |
