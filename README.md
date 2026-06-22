# LibereKollab

A Kotlin/Ktor REST API that exposes LibreOffice document editing via UNO as an AI-usable tool interface. An AI agent can read text, navigate chapters and pages, edit text with tracked changes, and manage comments — all over HTTP.

## What it does

LibereKollab bridges the gap between AI agents and LibreOffice documents. Instead of working with raw file bytes, an agent can interact with a document through a structured API:

- **Read** full text, specific pages, or individual chapters
- **Edit** text ranges — all edits are recorded as LibreOffice tracked changes for human review
- **Inspect** tracked changes with `BEFORE` / `FUSION` / `AFTER` views
- **Manage comments** — add, update, delete, and retrieve annotations anchored to specific text ranges

Humans retain full control: accept or reject tracked changes directly in LibreOffice.

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

One Pod (Ktor + LibreOffice sidecar) per open document. MinIO is shared centrally.

## Quick start

### Production

```bash
docker compose up --build
```

All three services start in an isolated Docker network. Only port 8080 is exposed to the host.

### Local development

```bash
docker compose -f docker-compose.dev.yml up
```

Then run `Main.kt` from IntelliJ. LibreOffice is at `localhost:2002`, MinIO at `localhost:9000`.

## API

Interactive documentation is available at **`http://localhost:8080/swagger`** once the server is running.

Raw OpenAPI spec: `http://localhost:8080/api.json`

### Document storage (`/documents`)

| Method | Route | Description |
|--------|-------|-------------|
| `GET` | `/documents/health` | Health check |
| `POST` | `/documents/upload` | Upload a document → returns `documentId` |
| `GET` | `/documents` | List all document IDs |
| `GET` | `/documents/{id}` | Download raw bytes |
| `DELETE` | `/documents/{id}` | Delete document |

### Text & editing (`/kollab`)

| Method | Route | Description |
|--------|-------|-------------|
| `GET` | `/kollab/health` | Health check |
| `GET` | `/kollab/text/{documentId}` | Full document text |
| `PATCH` | `/kollab/text/{documentId}` | Edit a text range (tracked change) |
| `GET` | `/kollab/text/{documentId}/changes` | List tracked changes |
| `GET` | `/kollab/text/{documentId}/pagecount` | Number of pages |
| `GET` | `/kollab/text/{documentId}/chapters` | Chapter headings |
| `GET` | `/kollab/text/{documentId}/pages/{from}/{to}` | Text of a page range |
| `GET` | `/kollab/text/{documentId}/chapters/{chapter}` | Text of a chapter |
| `GET` | `/kollab/text/{documentId}/editmode` | Whether Track Changes is active |

Text endpoints accept `?changeStatus=BEFORE|FUSION|AFTER` (default: `FUSION`):

| Value | Meaning |
|-------|---------|
| `BEFORE` | Original text before any tracked edits |
| `FUSION` | Raw LibreOffice string — shows both deleted and inserted text |
| `AFTER` | Text with all tracked changes applied |

### Comments (`/kollab/text/{documentId}/comments`)

| Method | Route | Description |
|--------|-------|-------------|
| `GET` | `/comments` | List all comments |
| `GET` | `/comments/{commentId}` | Get a single comment |
| `POST` | `/comments` | Add a comment |
| `PATCH` | `/comments/{commentId}` | Update comment text |
| `DELETE` | `/comments/{commentId}` | Delete a comment |

### Key request bodies

**Edit text**
```json
{
  "anchor": { "text": "Hello", "paragraphIndex": 0, "charStart": 0, "charEnd": 5 },
  "newText": "Hi"
}
```

**Add comment**
```json
{
  "commentText": "Consider rephrasing this.",
  "author": "AI Agent",
  "anchor": { "text": "Hello", "paragraphIndex": 0, "charStart": 0, "charEnd": 5 }
}
```

## Configuration

| Variable | Default | Description |
|---|---|---|
| `LIBREOFFICE_HOST` | `localhost` | UNO socket host |
| `LIBREOFFICE_PORT` | `2002` | UNO socket port |
| `MINIO_HOST` | `localhost` | MinIO host |
| `MINIO_PORT` | `9000` | MinIO port |
| `MINIO_ACCESS_KEY` | `minioadmin` | MinIO credentials |
| `MINIO_SECRET_KEY` | `minioadmin` | MinIO credentials |
| `MINIO_BUCKET` | `documents` | Bucket name |
| `WORKSPACE_PATH` | `<project-root>/workspace` | Shared volume path (optional in dev) |

## Running tests

```bash
./gradlew test
```

Integration tests use Testcontainers and spin up real LibreOffice and MinIO containers. The LibreOffice container is reused across test runs for faster iteration.

## Tech stack

- **Kotlin** + **Ktor** (Netty)
- **LibreOffice** headless via **UNO** socket
- **MinIO** for document storage
- **Testcontainers** for integration tests
- **ktor-openapi** + **ktor-swagger-ui** for auto-generated API documentation
