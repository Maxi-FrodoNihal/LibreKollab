# LibreKollab

*Libre*, for LibreOffice. *Kollab*, for Kollaboration — collaboration, spelled with a K on purpose, because it's written in Kotlin.

A LibreOffice extension (.oxt) that turns a document you have open into an [MCP](https://modelcontextprotocol.io/) server, so an AI agent isn't just editing a file on disk somewhere — it's looking at and working on the exact document you have open. Any MCP-compatible agent can connect, not just one specific tool.

## Why

This is a hobby project — I write novels in LibreOffice and wanted a real way to work on them together with an AI, not just hand it the file and hope for the best.

Editing a document with an AI usually means it rewrites the whole file and you diff it afterwards, hoping nothing important got lost. That's not really collaboration, it's a leap of faith. LibreKollab plugs straight into LibreOffice's own Track Changes instead: every edit the AI makes shows up as a normal tracked change, right next to your own. You review and accept or reject it the same way you would with a human co-author — nothing happens behind your back.

## What it does

The extension embeds an MCP server inside LibreOffice itself and exposes the open document's editing capabilities as tools. Any MCP-compatible AI agent can connect to it and:

- **Read** full text, specific pages, or individual chapters
- **Edit** text ranges — all edits are recorded as LibreOffice tracked changes for human review
- **Inspect** tracked changes with `BEFORE` / `FUSION` / `AFTER` views
- **Manage comments** — add, update, delete, and retrieve annotations anchored to specific text ranges
- **Read images** — list embedded image metadata (size, page, position) and retrieve the full image as PNG, optionally downscaled
- **Search** — full-text search across the whole document, paginated

## Architecture

```
MCP client (e.g. Claude Code)
      │  MCP (SSE, localhost:8080)
LibreKollab OXT extension
      │  in-process UNO
LibreOffice document(s)
```

The extension runs inside LibreOffice's JVM — no external server, no Docker, no database.

## Installation

### Prerequisites

- LibreOffice 7.x or newer
- Java 25+ — LibreOffice must be configured to use a Java 25 (or newer) runtime under `Extras > Optionen > LibreOffice > Erweitert` (`Tools > Options > LibreOffice > Advanced` on English installs). An older configured runtime (e.g. Java 21, which some installs default to) makes the extension's JAR fail to load silently: the Options page entry still shows up in the tree, but the page itself renders blank because the `OptionsHandler` can't be instantiated.

### Build the extension

```bash
./gradlew oxt
```

The extension is written to `build/oxt/LibreKollab-1.0.0.oxt`.

### Install in LibreOffice

`Extras > Extension Manager > Add...` → select the `.oxt` file, restart LibreOffice.

### Start the MCP server

This is deliberate: the extension never starts a server on its own, not even in the background. Whether an MCP server is listening at all is always an explicit choice you make yourself, by opening the settings page and clicking **Start server**:

`Extras > Optionen > Internet > LibreKollab MCP Server`

### Connect an MCP client

Point any MCP-compatible client at `http://localhost:8080/sse` (SSE transport). For Claude Code, for example:

```bash
claude mcp add --transport sse --scope user librekollab http://localhost:8080/sse
```

This registers the server globally for all your Claude Code sessions.

## Settings

`Extras > Optionen > Internet > LibreKollab MCP Server`

| Control | Description |
|---------|-------------|
| Status | Shows whether the server is running and on which port |
| Port | Port the MCP server listens on (default: 8080) |
| Start / Stop server | Toggles the server; port change takes effect on next start |
| Open log file | Opens `~/.config/librekollab/librekollab.log` |

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
| `get_image_metas` | List metadata for all embedded images (id, size in 1/100mm, PNG size in MB, page, anchor) |
| `get_image` | Retrieve a single image as PNG by its ID; optional `scale` (0–1) downscales it first |
| `search` | Full-text search across the whole document (case-insensitive); paginated via optional `page` (default 1) and `size` (default 10) |

The `documentId` parameter is the file name of the open document (e.g. `report.odt`).

### changeStatus

Text-reading tools accept an optional `changeStatus` parameter:

| Value | Meaning |
|-------|---------|
| `BEFORE` | Original text before tracked edits |
| `FUSION` | Both deleted and inserted text visible (default) |
| `AFTER` | Text with all tracked changes applied |

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `librekollab.port` | `8080` | MCP server port |

Logs are written to `~/.config/librekollab/librekollab.log` (Linux/macOS) or `%APPDATA%\librekollab\librekollab.log` (Windows).

## Running tests

```bash
./gradlew test
```

Integration tests spin up a real LibreOffice container via Testcontainers. Docker image caching keeps subsequent runs fast.

Note: the headless LibreOffice used in tests doesn't always behave identically to a real, GUI-attached LibreOffice instance for tracked-changes edge cases. When in doubt about track-changes behavior, verify against a real instance (build the `.oxt`, install it, connect a real MCP client) rather than trusting the test container alone.

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
