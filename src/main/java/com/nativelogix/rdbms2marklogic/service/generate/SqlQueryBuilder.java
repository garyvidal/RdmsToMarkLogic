package com.nativelogix.rdbms2marklogic.service.generate;

import com.nativelogix.rdbms2marklogic.model.Connection.ConnectionType;
import com.nativelogix.rdbms2marklogic.model.project.XmlColumnMapping;
import com.nativelogix.rdbms2marklogic.model.project.XmlTableMapping;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds parameterized SQL SELECT statements from {@link XmlTableMapping} definitions.
 *
 * <p>All identifiers are quoted to handle reserved words and mixed-case names.
 * Limit syntax varies by database vendor.</p>
 */
@Component
public class SqlQueryBuilder {

    /**
     * Builds a SELECT query for the root table.
     *
     * @param mapping    the root table mapping whose columns drive the SELECT list
     * @param dbType     the RDBMS vendor (determines LIMIT syntax)
     * @param limit      maximum number of rows to return
     * @return parameterized SQL string (no bind parameters)
     */
    public String buildRootQuery(XmlTableMapping mapping, ConnectionType dbType, int limit) {
        String columns = buildSelectList(mapping);
        String table   = qualifiedTable(mapping);

        return switch (dbType) {
            case SqlServer -> "SELECT TOP %d %s FROM %s".formatted(limit, columns, table);
            case Oracle    -> "SELECT %s FROM %s FETCH FIRST %d ROWS ONLY".formatted(columns, table, limit);
            default        -> "SELECT %s FROM %s LIMIT %d".formatted(columns, table, limit);  // Postgres, MySQL
        };
    }

    /**
     * Builds a SELECT query for a child table, filtered by a single join column value.
     *
     * <p>Returns a query with one {@code ?} placeholder for the parent-side join value.</p>
     *
     * @param mapping        the child table mapping
     * @param childJoinCol   the column on the child table used in the WHERE clause
     * @return SQL with one {@code ?} bind parameter
     */
    public String buildChildQuery(XmlTableMapping mapping, String childJoinCol) {
        String columns = buildSelectList(mapping);
        String table   = qualifiedTable(mapping);
        String where   = quote(childJoinCol) + " = ?";
        return "SELECT %s FROM %s WHERE %s".formatted(columns, table, where);
    }

    // -------------------------------------------------------------------------

    private String buildSelectList(XmlTableMapping mapping) {
        List<XmlColumnMapping> cols = mapping.getColumns();
        if (cols == null || cols.isEmpty()) {
            return "*";
        }
        return cols.stream()
                .filter(c -> !"CUSTOM".equals(c.getMappingType()))
                .map(c -> quote(c.getSourceColumn()))
                .collect(Collectors.joining(", "));
    }

    private String qualifiedTable(XmlTableMapping mapping) {
        String schema = mapping.getSourceSchema();
        String table  = mapping.getSourceTable();
        if (schema != null && !schema.isBlank()) {
            return quote(schema) + "." + quote(table);
        }
        return quote(table);
    }

    private String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
