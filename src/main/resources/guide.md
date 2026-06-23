# LibereKollab — AI Tool Guide

LibereKollab exposes a LibreOffice document for reading and editing via HTTP.
One running instance manages exactly one document.

## Typical workflow

1. Upload a document → receive a `documentId`
2. Read the document text to understand its content
3. Edit text ranges, add or update comments
4. Download the modified document when done

## Document storage

### Upload
`POST /documents/upload` — multipart form, field `file`, returns `{ "documentId": "..." }`

### Download
`GET /documents/{documentId}` — returns raw bytes (application/octet-stream)

### Delete
`DELETE /documents/{documentId}`

## Reading text

`GET /kollab/text/{documentId}` — returns the full document text as `MarkedText`:

```json
{
  "text": { "text": "Hello world", "properties": [] }
}
```

Use `?changeStatus=BEFORE` / `AFTER` / `FUSION` (default) to control how tracked changes are shown:
- `BEFORE` — original text before any edits
- `AFTER` — text with all edits applied
- `FUSION` — raw text including both deleted and inserted content

## Anchors

Every text operation uses a `TextAnchor` to identify a position:

```json
{ "text": "Hello", "paragraphIndex": 0, "charStart": 0, "charEnd": 5 }
```

- `text` — the exact string at that position (used for verification)
- `paragraphIndex` — 0-based paragraph index
- `charStart` / `charEnd` — character offsets within the paragraph

Always read the current text first to obtain correct anchor values before editing.

## Editing text

`PATCH /kollab/text/{documentId}` — replaces a text range with new content.
All edits are recorded as tracked changes (Track Changes is always on).

```json
{
  "anchor": { "text": "Hello", "paragraphIndex": 0, "charStart": 0, "charEnd": 5 },
  "newText": { "text": "Hi", "properties": [] }
}
```

Returns `200` on success, `400` if the anchor does not match the document.

## Comments

### Add
`POST /kollab/text/{documentId}/comments`
```json
{
  "commentText": "Please rephrase this.",
  "author": "AI Assistant",
  "anchor": { "text": "Hello", "paragraphIndex": 0, "charStart": 0, "charEnd": 5 }
}
```

### List
`GET /kollab/text/{documentId}/comments` — returns all comments sorted by position.

### Update
`PATCH /kollab/text/{documentId}/comments/{commentId}`
```json
{ "newText": "Updated comment." }
```

### Delete
`DELETE /kollab/text/{documentId}/comments/{commentId}`

## Navigation

`GET /kollab/text/{documentId}/chapters` — list of chapter headings

`GET /kollab/text/{documentId}/chapters/{chapter}` — text of a single chapter

`GET /kollab/text/{documentId}/pages/{fromPage}/{toPage}` — text of a page range

`GET /kollab/text/{documentId}/pagecount` — total number of pages
