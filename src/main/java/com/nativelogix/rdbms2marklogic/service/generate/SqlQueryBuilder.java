package com.nativelogix.rdbms2marklogic.service.generate;

import com.nativelogix.rdbms2marklogic.model.Connection.ConnectionType;
import com.nativelogix.rdbms2marklogic.model.project.JsonColumnMapping;
import com.nativelogix.rdbms2marklogic.model.project.JsonTableMapping;
import com.nativelogix.rdbms2marklogic.model.project.XmlColumnMapping;
import com.nativelogix.rdbms2marklogic.model.project.XmlTableMapping;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds parameterized SQL SELECT statements from table mapping definitions.
 * Supports both XML and JSON mapping types.
 *
 * <p>All identifiers are quoted to handle reserved words and mixed-case names.
 * Limit syntax varies by database vendor.</p>
 */
@Component
public class SqlQueryBuilder {

    // ── XML mappings ──────────────────────────────────────────────────────────

    public String buildRootQuery(XmlTableMapping mapping, ConnectionType dbType, int limit) {
        String columns = buildXmlSelectList(mapping);
        String table   = qualifiedTable(mapping.getSourceSchema(), mapping.getSourceTable());
        return buildLimitedSelect(columns, table, dbType, limit);
    }

    public String buildChildQuery(XmlTableMapping mapping, String childJoinCol) {
        String columns = buildXmlSelectList(mapping);
        String table   = qualifiedTable(mapping.getSourceSchema(), mapping.getSourceTable());
        return "SELECT %s FROM %s WHERE %s".formatted(columns, table, quote(childJoinCol) + " = ?");
    }

    // ── JSON mappings ─────────────────────────────────────────────────────────

    public String buildRootQuery(JsonTableMapping mapping, ConnectionType dbType, int limit) {
        String columns = buildJsonSelectList(mapping);
        String table   = qualifiedTable(mapping.getSourceSchema(), mapping.getSourceTable());
        return buildLimitedSelect(columns, table, dbType, limit);
    }

    public String buildChildQuery(JsonTableMapping mapping, String childJoinCol) {
        String columns = buildJsonSelectList(mapping);
        String table   = qualifiedTable(mapping.getSourceSchema(), mapping.getSourceTable());
        return "SELECT %s FROM %s WHERE %s".formatted(columns, table, quote(childJoinCol) + " = ?");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String buildLimitedSelect(String columns, String table, ConnectionType dbType, int limit) {
        return switch (dbType) {
            case SqlServer -> "SELECT TOP %d %s FROM %s".formatted(limit, columns, table);
            case Oracle    -> "SELECT %s FROM %s FETCH FIRST %d ROWS ONLY".formatted(columns, table, limit);
            default        -> "SELECT %s FROM %s LIMIT %d".formatted(columns, table, limit);
        };
    }

    private String buildXmlSelectList(XmlTableMapping mapping) {
        List<XmlColumnMapping> cols = mapping.getColumns();
        if (cols == null || cols.isEmpty()) return "*";
        return cols.stream()
                .filter(c -> !"CUSTOM".equals(c.getMappingType()))
                .map(c -> quote(c.getSourceColumn()))
                .collect(Collectors.joining(", "));
    }

    private String buildJsonSelectList(JsonTableMapping mapping) {
        List<JsonColumnMapping> cols = mapping.getColumns();
        if (cols == null || cols.isEmpty()) return "*";
        return cols.stream()
                .filter(c -> !"CUSTOM".equals(c.getMappingType()))
                .map(c -> quote(c.getSourceColumn()))
                .collect(Collectors.joining(", "));
    }

    private String qualifiedTable(String schema, String table) {
        if (schema != null && !schema.isBlank()) {
            return quote(schema) + "." + quote(table);
        }
        return quote(table);
    }

    private String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
