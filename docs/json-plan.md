# JSON Mapping Plan

## Overview

Add JSON document generation as an alternative (or complement) to XML mapping.
JSON supports a simpler structure than XML:
- **Object** — key/value pairs (like an XML element)
- **Array** — ordered list of objects
- **Property types** — `string`, `number`, `boolean`

The existing mapping paradigm (`RootElement` / `Elements` / `InlineElement`) maps naturally to JSON.
We need a parallel `JsonTableMapping` and `JsonColumnMapping` model alongside the existing XML ones.

---

## Phase 1 — Data Model

### 1.1 New Types (Frontend + Backend)

**`JsonColumnType`** — replaces `XmlSchemaType`
```
'string' | 'number' | 'boolean'
```

**`JsonColumnMapping`**
```
id?: string
sourceColumn: string
jsonKey: string          // property name in JSON output
jsonType: JsonColumnType
mappingType: 'Property' | 'CUSTOM'
customFunction?: string
```

**`JsonTableMapping`**
```
id?: string
sourceSchema: string
sourceTable: string
jsonName: string         // key name in parent object
mappingType: 'RootObject' | 'Array' | 'InlineObject'
parentRef?: string       // InlineObject: parent mapping id
columns: JsonColumnMapping[]
```

**`JsonDocumentModel`**
```
root?: JsonTableMapping
elements: JsonTableMapping[]
```

**`ProjectMapping`** — extend to hold both:
```typescript
documentModel: { root?: XmlTableMapping; elements: XmlTableMapping[] }  // existing
jsonDocumentModel?: { root?: JsonTableMapping; elements: JsonTableMapping[] }  // new
mappingType: 'XML' | 'JSON' | 'BOTH'  // default 'XML'
```

---

## Phase 2 — Project Settings UI

### 2.1 ConfigDialog — Add "Mapping Type" Setting

Add a radio/select to `ConfigDialog.tsx`:
- **XML** (default, current behavior)
- **JSON**
- **Both** (generate XML and JSON documents)

This sets `ProjectData.mapping.mappingType`.

### 2.2 DocumentModelView — Mode-Aware Tabs

When `mappingType` is `BOTH`, show tabs in `DocumentModelView`:
- **XML** tab → existing XML mapping editor
- **JSON** tab → new JSON mapping editor

When `mappingType` is `XML` only → no tabs, existing view unchanged.
When `mappingType` is `JSON` only → show JSON mapping editor directly.

---

## Phase 3 — JSON Mapping Editor UI

New components, parallel to existing XML ones:

### 3.1 `JsonDocumentModelView.tsx`
- Same structure as `DocumentModelView.tsx`
- Root Object section → Elements (Array) section
- Click table → assign as `RootObject`, `Array`, or `InlineObject`
- Same parent-ref validation (FK or synthetic join required for InlineObject)

### 3.2 `JsonMappingTableCard.tsx`
- Per-table card: set `jsonName`, mappingType
- Per-column: set `jsonKey`, `jsonType` (string/number/boolean toggle)
- No attribute vs element distinction (JSON has only properties)

### 3.3 `JsonPreview.tsx` — Client-side preview
- Generates sample JSON structure (no data, just structure/placeholders)
- Syntax-highlighted JSON display
- Parallel to `XmlPreview.tsx`

### 3.4 `GenerateJsonModal.tsx` — Server-generated preview
- Calls `POST /v1/projects/{id}/generate/json/preview`
- Shows actual JSON documents from live RDBMS data
- Document navigation + error display

---

## Phase 4 — Type Mapping

### 4.1 `TypeMapper.ts` — Add SQL → JSON type mapping

```
BOOL, BIT                                         → boolean
INT, INTEGER, BIGINT, SMALLINT, DECIMAL, NUMERIC,
FLOAT, REAL, DOUBLE, MONEY                        → number
everything else                                   → string
```

---

## Phase 5 — Backend JSON Generation

New parallel to the XML generation pipeline:

### 5.1 New Model Classes
- `JsonColumnMapping.java`
- `JsonTableMapping.java`
- `JsonDocumentModel.java`

Mirror the frontend types exactly.

### 5.2 `ProjectMapping.java` — extend
```java
DocumentModel documentModel;           // existing XML
JsonDocumentModel jsonDocumentModel;   // new JSON
String mappingType;                    // "XML" | "JSON" | "BOTH"
```

### 5.3 `JsonDocumentBuilder.java`
Parallel to `XmlDocumentBuilder.java`. Uses Jackson `ObjectMapper` (already in Spring Boot).

`build(root, rootRow, childData, casing)` → `String` (formatted JSON)

Algorithm:
1. Create root `ObjectNode`
2. For each column mapping: `node.put(jsonKey, typedValue)`
3. For each Array child: create `ArrayNode`, add child objects
4. For each InlineObject child: nest as child `ObjectNode`
5. Apply casing to all keys via existing `CaseConverter`
6. Serialize to pretty-printed JSON string

### 5.4 `JsonGenerationService.java`
Parallel to `XmlGenerationService.java`.

- Same join resolution + SQL query logic — **reuse `JoinResolver` + `SqlQueryBuilder` unchanged**
- Different document builder: `JsonDocumentBuilder`
- Returns `JsonPreviewResult { List<String> documents; int totalRows; List<String> errors }`

### 5.5 `JsonGenerationController.java`
```
POST /v1/projects/{id}/generate/json/preview
Body:    { limit: number }
Response: { documents: [...], totalRows: N, errors: [...] }
```

---

## Phase 6 — Frontend API Service

In `ProjectService.ts`, add:
```typescript
generateJsonPreview(projectId: string, limit: number): Promise<JsonPreviewResponse>
// POST /v1/projects/{id}/generate/json/preview
```

Save/load `jsonDocumentModel` as part of `ProjectMapping`.

---

## Implementation Order

| # | Task | Layer |
|---|------|-------|
| 1 | Add `mappingType` to `ProjectMapping` + `ProjectData` types | Frontend |
| 2 | Add `JsonColumnMapping`, `JsonTableMapping`, `JsonDocumentModel` types | Frontend |
| 3 | Add SQL → JSON type mapping to `TypeMapper.ts` | Frontend |
| 4 | Add "Mapping Type" selector to `ConfigDialog` | Frontend UI |
| 5 | Build `JsonDocumentModelView` + `JsonMappingTableCard` | Frontend UI |
| 6 | Build `JsonPreview` (client-side structural preview) | Frontend UI |
| 7 | Add JSON tab to `DocumentModelView` when `mappingType = BOTH` | Frontend UI |
| 8 | Add `JsonColumnMapping.java`, `JsonTableMapping.java`, `JsonDocumentModel.java` | Backend |
| 9 | Extend `ProjectMapping.java` with JSON fields | Backend |
| 10 | Build `JsonDocumentBuilder.java` | Backend |
| 11 | Build `JsonGenerationService.java` (reuse join/query logic) | Backend |
| 12 | Add `JsonGenerationController.java` | Backend |
| 13 | Wire `generateJsonPreview()` in `ProjectService.ts` | Frontend |
| 14 | Build `GenerateJsonModal.tsx` (server-generated preview) | Frontend UI |

---

## Key Design Decisions

- **JSON arrays** = `Array` mapping type (like `Elements` in XML) — child rows become array items
- **JSON objects** = `RootObject` or `InlineObject` (like `RootElement`/`InlineElement`)
- **No attributes** — JSON has no attribute concept; all columns become properties
- **Casing applies to keys** — `defaultCasing` setting applies to JSON property names same as XML element names
- **Reuse join resolution** — `JoinResolver` and `SqlQueryBuilder` are format-agnostic; JSON generation reuses them unchanged
