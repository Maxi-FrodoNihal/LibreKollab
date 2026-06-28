# LibereKollab

A LibreOffice extension (.oxt) that exposes document editing as [MCP](https://modelcontextprotocol.io/) tools. Install it once and Claude Code can read, edit, and annotate any document you have open in LibreOffice — tracked changes, comments, chapters, pages, and more.

## What it does

LibereKollab bridges LibreOffice and AI agents. The extension starts an MCP server inside LibreOffice and exposes its document-editing capabilities as tools. Claude Code connects to that server and can:

- **Read** full text, specific pages, or individual chapters
- **Edit** text ranges — all edits are recorded as LibreOffice tracked changes for human review
- **Inspect** tracked changes with `BEFORE` / `FUSION` / `AFTER` views
- **Manage comments** — add, update, delete, and retrieve annotations anchored to specific text ranges

Humans retain full control: accept or reject tracked changes directly in LibreOffice.

## Architecture

```
Claude Code CLI
      │  MCP (SSE, localhost:8080)
LibereKollab OXT extension
      │  in-process UNO
LibreOffice document(s)
```

The extension runs inside LibreOffice's JVM — no external server, no Docker, no database.

## Installation

### Prerequisites

- LibreOffice 7.x or newer
- Java 17+ (bundled with most LibreOffice installations)

### Build the extension

```bash
./gradlew oxt
```

The extension is written to `build/oxt/LibereKollab-1.0-SNAPSHOT.oxt`.

### Install in LibreOffice

`Extras > Extension Manager > Add...` → select the `.oxt` file.

The MCP server starts automatically the next time LibreOffice opens a document.

### Configure Claude Code

Add the server to your Claude Code MCP config (`~/.config/claude-code/mcp.json` on Linux, `%APPDATA%\claude-code\mcp.json` on Windows):

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

## Options dialog

`Extras > Optionen > LibereKollab > MCP Server`

Shows the current status, lets you change the port, start/stop the server, and open the log file.

## Available tools

| Tool | Description |
|------|-------------|
| `list_documents` | List file names of all open documents |
| `get_text` | Full document text (`changeStatus`: `BEFORE`/`FUSION`/`AFTER`) |
| `get_page_count` | Number of pages |
| `get_chapters` | List chapter headings |
| `get_text_by_pages` | Text of a page range |
| `get_text_by_chapter` | Text of a specific chapter |
| `get_changes` | All tracked changes as JSON |
| `get_edit_mode` | Whether Track Changes is enabled |
| `edit_text` | Replace a text range (creates tracked change) |
| `get_comments` | All comments as JSON |
| `get_comment` | Single comment by ID |
| `add_comment` | Add a comment anchored to a text range |
| `update_comment` | Update comment text |
| `delete_comment` | Delete a comment |

The `documentId` parameter is the file name of the open document (e.g. `report.odt`).

### changeStatus

Text-reading tools accept an optional `changeStatus` parameter:

| Value | Meaning |
|-------|---------|
| `BEFORE` | Original text before tracked edits |
| `FUSION` | Both deleted and inserted text visible (default) |
| `AFTER` | Text with all tracked changes applied |

## Configuration

Settings can be changed in the Options dialog or via Java system properties (`-Dliberekollab.port=8080`).

| Property | Default | Description |
|----------|---------|-------------|
| `liberekollab.port` | `8080` | MCP server port |
| `liberekollab.workspace` | `<tmp>/liberekollab` | Temp directory for file operations |

Logs are written to `~/.config/liberekollab/` (Linux/macOS) or `%APPDATA%\liberekollab\` (Windows).

## Running tests

```bash
./gradlew test
```

Integration tests spin up a real LibreOffice container via Testcontainers. The container is reused across runs for faster iteration.

## Building from source

```bash
# Compile and run tests
./gradlew test

# Build the .oxt extension
./gradlew oxt
```

Requires `libs/uno/libreoffice.jar`, copied from the host LibreOffice installation:

```bash
cp /usr/lib/libreoffice/program/classes/libreoffice.jar libs/uno/
```

## Tech stack

- **Kotlin** + **Ktor** (Netty, SSE transport)
- **MCP Kotlin SDK** (`io.modelcontextprotocol:kotlin-sdk`)
- **LibreOffice** UNO in-process API
- **Testcontainers** for integration tests
