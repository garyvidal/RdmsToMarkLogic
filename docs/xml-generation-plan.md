# XML Document Generation — Backend Plan

## Context

The data model is already complete (`DocumentModel` → `XmlTableMapping` → `XmlColumnMapping`).
The gap is: no service queries the RDBMS using these mappings and produces XML output.

---

## New Classes

### Models (`model/generate/`)

| Class | Purpose |
|-------|---------|
| `XmlGenerationRequest.java` | Body for generation requests — `limit`, `marklogicConnectionId`, `collection`, `batchSize` |
| `XmlPreviewResult.java` | Response for preview — `List<String> documents`, `int totalRows`, `List<String> errors` |
| `XmlExportResult.java` | Response for async export — `String jobId`, `int totalDocs`, `int errors` |

### Services (`service/generate/`)

| Class | Purpose |
|-------|---------|
| `SqlQueryBuilder.java` | Builds SQL SELECT statements from `XmlTableMapping` + resolved join paths |
| `JoinResolver.java` | Resolves how two tables relate using `DbRelationship` list and `SyntheticJoin` list |
| `XmlDocumentBuilder.java` | Builds a single XML document (DOM) from a root row + child rows using the mapping |
| `XmlGenerationService.java` | Orchestrates: load project → connect JDBC → query root rows → build XML per row |

### Controller

| Class | Purpose |
|-------|---------|
| `XmlGenerationController.java` | REST endpoints under `/v1/projects/{id}/generate/` |

---

## REST API

```
POST /v1/projects/{id}/generate/preview
  Body: { "limit": 10 }
  Returns: XmlPreviewResult — first N XML docs as strings, for UI display

POST /v1/projects/{id}/generate/export
  Body: { "marklogicConnectionId": "uuid", "collection": "/orders/", "batchSize": 100 }
  Returns: XmlExportResult — writes all docs to MarkLogic
```

Start with preview only; export can follow once preview is validated.

---

## Core Algorithm

```
XmlGenerationService.generatePreview(projectId, limit):

  1. Load Project → get connectionName, mapping.documentModel, schemas, syntheticJoins
  2. Resolve SavedConnection by connectionName → decrypt password → open JDBC Connection
  3. rootMapping = documentModel.root   (mappingType = "RootElement")
  4. SELECT root rows:
       SqlQueryBuilder.buildRootQuery(rootMapping, selectedColumns, limit)
       → SELECT col1, col2, ... FROM schema.table LIMIT n
  5. For each root row:
       a. Start XML element: <{rootMapping.xmlName}>
       b. Map columns per XmlColumnMapping:
            mappingType=ElementAttribute → set as XML attribute
            mappingType=Element         → add child text element
            mappingType=CUSTOM          → invoke customFunction (phase 2)
       c. For each childMapping in documentModel.elements:
            i.  JoinResolver.resolve(rootMapping, childMapping, project)
                → returns join column pair (e.g. root.id = child.order_id)
                → checks DbRelationship first, then SyntheticJoin
            ii. SELECT child rows WHERE join_col = root_pk_value
            iii. If wrapInParent=true → wrap in <{wrapperElementName}>
            iv.  For each child row → map columns → add child element
            v.  If mappingType=InlineElement → flatten into parent (no wrapper)
       d. Serialize DOM to string → add to result list
  6. Return XmlPreviewResult
```

---

## JoinResolver Logic

```
resolve(parentMapping, childMapping, project):
  1. Look in project.schemas[parentSchema].tables[parentTable].relationships
     for a relationship where toTable == childMapping.sourceTable
  2. If found → return (fromColumn, toColumn)
  3. Else search project.syntheticJoins for a join matching
       (sourceSchema/Table == parent AND targetSchema/Table == child)
       or vice versa
  4. If none found → throw MappingException("No join path found between X and Y")
```

---

## XmlDocumentBuilder — Element structure

```xml
<!-- mappingType=RootElement, wrapInParent=false -->
<Order orderId="123" status="shipped">          ← attribute from XmlColumnMapping
  <OrderDate>2024-01-15</OrderDate>             ← element
  <Customer>Acme Corp</Customer>
  <OrderLines>                                  ← wrapperElementName (wrapInParent=true)
    <OrderLine>
      <Product>Widget</Product>
      <Qty>5</Qty>
    </OrderLine>
  </OrderLines>
</Order>
```

---

## XSD Type Handling

`XmlColumnMapping.xmlType` already carries the target type (e.g. `xs:date`, `xs:decimal`). The builder should:
- Apply `CaseConverter` to `xmlName` using `project.settings.defaultCasing`
- Format values by type: dates → ISO-8601, decimals → no trailing zeros, booleans → `true`/`false`
- Null values → omit element/attribute (or emit `xsi:nil="true"` — configurable)

---

## Implementation Order

1. `XmlGenerationRequest` + `XmlPreviewResult` models
2. `SqlQueryBuilder` — root query only (no joins yet), verified with unit test
3. `JoinResolver` — FK-based first, synthetic join second
4. `XmlDocumentBuilder` — elements + attributes, no CUSTOM functions yet
5. `XmlGenerationService` — wire it all together with real JDBC
6. `XmlGenerationController` — preview endpoint
7. `XmlExportResult` + MarkLogic write via `marklogic-client-api`
8. Export endpoint + async job tracking (Phase 2)

---

## Dependencies Already Available

- `marklogic-client-api 8.0.0` — for export step
- `java.sql` (JDBC) — already used in `JDBCConnectionService`
- `javax.xml.parsers` / `javax.xml.transform` — built into JDK (no new dep needed for XML DOM)
- `PasswordEncryptionService` — for decrypting the connection password
