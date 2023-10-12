package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.DirectivesContainer;
import graphql.language.NamedNode;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQTableMapping;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.CodeReference;
import no.fellesstudentsystem.graphitron.definitions.sql.*;
import no.fellesstudentsystem.graphql.directives.DirectiveHelpers;

import java.util.List;
import java.util.zip.CRC32;

import static no.fellesstudentsystem.graphql.directives.DirectiveHelpers.*;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.REFERENCE;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.*;

public class FieldReference {
    private final JOOQTableMapping table;
    private final String tableKey;
    private final SQLCondition tableCondition;

    public <T extends NamedNode<T> & DirectivesContainer<T>> FieldReference(T field) {
        var relatedTable = "";
        var relatedTableKey = "";
        SQLCondition relatedTableCondition = null;
        if (field.hasDirective(REFERENCE.getName())) {
            relatedTable = getOptionalDirectiveArgumentString(field, REFERENCE, TABLE).orElse("");
            relatedTableKey = getOptionalDirectiveArgumentString(field, REFERENCE, KEY).orElse("");

            var referenceFields = getOptionalDirectiveArgumentObjectFields(field, REFERENCE, CONDITION);
            if (referenceFields.isPresent()) {
                var fields = referenceFields.get();
                var classReference = stringValueOf(getObjectFieldByName(fields, NAME));
                var methodName = getOptionalObjectFieldByName(fields, METHOD).map(DirectiveHelpers::stringValueOf).orElse(field.getName());
                relatedTableCondition = new SQLCondition(new CodeReference(classReference, methodName));
            }
        }

        table = new JOOQTableMapping(relatedTable);
        tableKey = relatedTableKey;
        tableCondition = relatedTableCondition;
    }

    public FieldReference(JOOQTableMapping table, String tableKey, SQLCondition tableCondition) {
        this.table = table;
        this.tableKey = tableKey;
        this.tableCondition = tableCondition;
    }

    public boolean hasTable() {
        return !table.getName().isEmpty();
    }

    public JOOQTableMapping getTable() {
        return table;
    }

    public String getTableKey() {
        return tableKey;
    }

    public boolean hasTableKey() {
        return !tableKey.isEmpty();
    }

    public SQLCondition getTableCondition() {
        return tableCondition;
    }

    public boolean hasTableCondition() {
        return tableCondition != null;
    }

    private SQLJoinStatement createJoinFor(
            String referencePath,
            String tableNameBackup,
            String previousJoinTable,
            String pastJoinSequence,
            SQLJoinField joinField,
            boolean isNullable
    ) {
        var tableName = !table.getName().isEmpty() ? table.getName() : tableNameBackup;
        var adjustedReference = pastJoinSequence.equals(tableName) ? tableName.toLowerCase() : referencePath;
        return new SQLJoinStatement(
                !pastJoinSequence.isEmpty() ? pastJoinSequence : previousJoinTable,
                tableName,
                createAliasName(previousJoinTable, adjustedReference),
                createShortAliasName(previousJoinTable, adjustedReference),
                List.of(joinField),
                isNullable ? SQLJoinType.JOIN : SQLJoinType.LEFT
        );
    }

    /**
     * @return A join statement based on a condition.
     */
    public SQLJoinStatement createConditionJoinFor(String referencePath, String previousJoinTable, String pastJoinSequence, String tableNameBackup, boolean isNullable) {
        var conditionName = tableCondition.getConditionReference().getMethodName().toLowerCase();
        return createJoinFor(
                referencePath + "_" + (conditionName.isEmpty() ? tableNameBackup : conditionName),
                tableNameBackup,
                previousJoinTable,
                pastJoinSequence,
                new SQLJoinOnCondition(tableCondition),
                isNullable
        );
    }

    /**
     * @return A join statement based on a key reference.
     */
    public SQLJoinStatement createJoinOnKeyFor(String referencePath, String previousJoinTable, String pastJoinSequence, String tableNameBackup, boolean isNullable) {
        var keyName = tableKey.toLowerCase();
        return createJoinFor(
                referencePath + "_" + (keyName.isEmpty() ? tableNameBackup : keyName),
                tableNameBackup,
                previousJoinTable,
                pastJoinSequence,
                new SQLJoinOnKey(tableKey),
                isNullable
        );
    }

    public String createAliasName(String previous, String referencePath) {
        return (previous + "_" + referencePath).toLowerCase();
    }

    /**
     * Short name for Alias due to character limit on Oracle Alias values (JIRA reference: <a href="https://unit.atlassian.net/browse/ROK-685">ROK-685</a>).
     */
    public String createShortAliasName(String from, String to) {
        var crc32 = new CRC32();
        crc32.reset();
        crc32.update(to.getBytes());
        return from + "_" + crc32.getValue();
    }
}
