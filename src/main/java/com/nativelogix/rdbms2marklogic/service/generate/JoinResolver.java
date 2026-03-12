package com.nativelogix.rdbms2marklogic.service.generate;

import com.nativelogix.rdbms2marklogic.model.project.JoinCondition;
import com.nativelogix.rdbms2marklogic.model.project.Project;
import com.nativelogix.rdbms2marklogic.model.project.SyntheticJoin;
import com.nativelogix.rdbms2marklogic.model.project.XmlTableMapping;
import com.nativelogix.rdbms2marklogic.model.relational.DbRelationship;
import com.nativelogix.rdbms2marklogic.model.relational.DbSchema;
import com.nativelogix.rdbms2marklogic.model.relational.DbTable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Resolves the join path between two table mappings using the project's
 * FK relationships first, then falling back to user-defined synthetic joins.
 */
@Component
public class JoinResolver {

    /**
     * Result of a join resolution: which column on the parent side and which column
     * on the child side link the two tables.
     */
    public record JoinPath(String parentColumn, String childColumn) {}

    /**
     * Resolve how {@code parentMapping}'s table connects to {@code childMapping}'s table.
     *
     * @throws IllegalArgumentException if no join path can be found
     */
    public JoinPath resolve(XmlTableMapping parentMapping, XmlTableMapping childMapping, Project project) {
        // 1. Try FK relationships stored on the parent table
        JoinPath fkPath = resolveViaForeignKey(parentMapping, childMapping, project);
        if (fkPath != null) return fkPath;

        // 2. Try in reverse: FK on child table pointing back to parent
        JoinPath reverseFkPath = resolveViaForeignKeyReverse(parentMapping, childMapping, project);
        if (reverseFkPath != null) return reverseFkPath;

        // 3. Try user-defined synthetic joins
        JoinPath syntheticPath = resolveViaSyntheticJoin(parentMapping, childMapping, project);
        if (syntheticPath != null) return syntheticPath;

        throw new IllegalArgumentException(
                "No join path found between '%s.%s' and '%s.%s'. Add a synthetic join or ensure a foreign key exists."
                        .formatted(parentMapping.getSourceSchema(), parentMapping.getSourceTable(),
                                   childMapping.getSourceSchema(), childMapping.getSourceTable()));
    }

    /** Check FK relationships defined on the parent table pointing to the child table. */
    private JoinPath resolveViaForeignKey(XmlTableMapping parent, XmlTableMapping child, Project project) {
        DbTable parentTable = getDbTable(project, parent.getSourceSchema(), parent.getSourceTable());
        if (parentTable == null || parentTable.getRelationships() == null) return null;

        for (DbRelationship rel : parentTable.getRelationships()) {
            if (child.getSourceTable().equalsIgnoreCase(rel.getToTable())) {
                return new JoinPath(rel.getFromColumn(), rel.getToColumn());
            }
        }
        return null;
    }

    /** Check FK relationships on the child table pointing back to the parent table. */
    private JoinPath resolveViaForeignKeyReverse(XmlTableMapping parent, XmlTableMapping child, Project project) {
        DbTable childTable = getDbTable(project, child.getSourceSchema(), child.getSourceTable());
        if (childTable == null || childTable.getRelationships() == null) return null;

        for (DbRelationship rel : childTable.getRelationships()) {
            if (parent.getSourceTable().equalsIgnoreCase(rel.getToTable())) {
                // Relationship is on the child pointing to parent: child.fromColumn → parent.toColumn
                return new JoinPath(rel.getToColumn(), rel.getFromColumn());
            }
        }
        return null;
    }

    /** Check user-defined synthetic joins in both directions. */
    private JoinPath resolveViaSyntheticJoin(XmlTableMapping parent, XmlTableMapping child, Project project) {
        if (project.getSyntheticJoins() == null) return null;

        for (SyntheticJoin sj : project.getSyntheticJoins()) {
            List<JoinCondition> conditions = sj.getConditions();
            if (conditions == null || conditions.isEmpty()) continue;

            // Forward: source=parent, target=child
            if (matches(sj.getSourceSchema(), sj.getSourceTable(), parent) &&
                matches(sj.getTargetSchema(), sj.getTargetTable(), child)) {
                JoinCondition first = conditions.get(0);
                return new JoinPath(first.getSourceColumn(), first.getTargetColumn());
            }

            // Reverse: source=child, target=parent
            if (matches(sj.getSourceSchema(), sj.getSourceTable(), child) &&
                matches(sj.getTargetSchema(), sj.getTargetTable(), parent)) {
                JoinCondition first = conditions.get(0);
                return new JoinPath(first.getTargetColumn(), first.getSourceColumn());
            }
        }
        return null;
    }

    private boolean matches(String schema, String table, XmlTableMapping mapping) {
        return table.equalsIgnoreCase(mapping.getSourceTable()) &&
               (schema == null || schema.equalsIgnoreCase(mapping.getSourceSchema()));
    }

    private DbTable getDbTable(Project project, String schemaName, String tableName) {
        if (project.getSchemas() == null) return null;
        for (Map.Entry<String, DbSchema> schemaEntry : project.getSchemas().entrySet()) {
            DbSchema schema = schemaEntry.getValue();
            if (schema == null || schema.getTables() == null) continue;
            if (!schemaEntry.getKey().equalsIgnoreCase(schemaName) &&
                (schema.getName() == null || !schema.getName().equalsIgnoreCase(schemaName))) continue;
            for (Map.Entry<String, DbTable> tableEntry : schema.getTables().entrySet()) {
                if (tableEntry.getKey().equalsIgnoreCase(tableName)) {
                    return tableEntry.getValue();
                }
            }
        }
        return null;
    }
}
