package no.sikt.graphitron.definitions.fields.containedtypes;

import graphql.language.DirectivesContainer;
import graphql.language.NamedNode;
import no.sikt.graphitron.configuration.externalreferences.CodeReference;
import no.sikt.graphitron.definitions.mapping.Alias;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.definitions.sql.*;
import no.sikt.graphitron.generators.context.JoinListSequence;

import java.util.List;

import static no.sikt.graphql.directives.DirectiveHelpers.*;
import static no.sikt.graphql.directives.GenerationDirective.REFERENCE;
import static no.sikt.graphql.directives.GenerationDirectiveParam.*;

public class FieldReference {
    private final JOOQMapping table;
    private final JOOQMapping key;
    private final SQLCondition tableCondition;

    public <T extends NamedNode<T> & DirectivesContainer<T>> FieldReference(T field) {
        JOOQMapping relatedTable = null;
        JOOQMapping relatedTableKey = null;
        SQLCondition relatedTableCondition = null;
        if (field.hasDirective(REFERENCE.getName())) {
            relatedTable = getOptionalDirectiveArgumentString(field, REFERENCE, TABLE).map(JOOQMapping::fromTable).orElse(null);
            relatedTableKey = getOptionalDirectiveArgumentString(field, REFERENCE, KEY).map(JOOQMapping::fromKey).orElse(null);

            var condition = getOptionalDirectiveArgumentObjectFields(field, REFERENCE, CONDITION);
            if (condition.isPresent()) {
                relatedTableCondition = new SQLCondition(new CodeReference(condition.get(), field.getName()));
            }
        }

        table = relatedTable;
        key = relatedTableKey;
        tableCondition = relatedTableCondition;
    }

    public FieldReference(JOOQMapping table, JOOQMapping key, SQLCondition tableCondition) {
        this.table = table;
        this.key = key;
        this.tableCondition = tableCondition;
    }

    public FieldReference(JOOQMapping table) {
        this(table, null, null);
    }

    public boolean hasTable() {
        return table != null && !table.getMappingName().isEmpty();
    }

    public JOOQMapping getTable() {
        return table;
    }

    public JOOQMapping getKey() {
        return key;
    }

    public boolean hasKey() {
        return key != null && !key.getMappingName().isEmpty();
    }

    public SQLCondition getTableCondition() {
        return tableCondition;
    }

    public boolean hasTableCondition() {
        return tableCondition != null;
    }

    private SQLJoinStatement createJoinFor(
            String joinName,
            JoinListSequence joinSequence,
            JOOQMapping tableNameBackup,
            SQLJoinField joinField,
            boolean isNullable
    ) {
        var targetTable = hasTable() ? getTable() : tableNameBackup;
        var secondLast = joinSequence.getSecondLast();
        var adjustedReference = joinSequence.getLast().equals(targetTable)
                ? secondLast != null ? secondLast.getCodeName() : targetTable.getCodeName()
                : joinSequence.render() + (joinName.isEmpty() ? "" : "_" + joinName);
        return new SQLJoinStatement(
                joinSequence,
                targetTable,
                new Alias(adjustedReference, targetTable, isNullable),
                List.of(joinField),
                isNullable
        );
    }

    /**
     * @return A join statement based on a condition.
     */
    public SQLJoinStatement createConditionJoinFor(JoinListSequence joinSequence, JOOQMapping tableNameBackup, boolean isNullable) {
        return createJoinFor(
                tableCondition.getConditionReference().getMethodName().toLowerCase(),
                joinSequence,
                tableNameBackup,
                new SQLJoinOnCondition(tableCondition),
                isNullable
        );
    }

    /**
     * @return A join statement based on a key reference.
     */
    public SQLJoinStatement createJoinOnKeyFor(JoinListSequence joinSequence, JOOQMapping tableNameBackup, boolean isNullable) {
        return createJoinFor(
                !joinSequence.isEmpty() && joinSequence.getLast().equals(key) ? "" : key.getCodeName(),
                joinSequence,
                tableNameBackup,
                new SQLJoinOnKey(key),
                isNullable
        );
    }

    /**
     * @return A join statement based on a key reference.
     */
    public SQLJoinStatement createJoinOnKeyFor(JOOQMapping keyOverride, JoinListSequence joinSequence, JOOQMapping tableNameBackup, boolean isNullable) {
        return createJoinFor(
                !joinSequence.isEmpty() && joinSequence.getLast().equals(keyOverride) ? "" : keyOverride.getCodeName(),
                joinSequence,
                tableNameBackup,
                new SQLJoinOnKey(keyOverride),
                isNullable
        );
    }

    /**
     * @return A join statement based on a key reference using path
     */

    public SQLJoinStatement createJoinOnExplicitPathFor(JOOQMapping keyOverride, JoinListSequence joinSequence, JOOQMapping tableNameBackup, boolean isNullable) {

        var targetTable = hasTable() ? getTable() : tableNameBackup;
        var prefix = joinSequence.getSecondLast().getCodeName().startsWith("_") ? joinSequence.getSecondLast().getMappingName() : joinSequence.getSecondLast().getCodeName();
        var alias = new Alias(prefix + "_" + keyOverride.getCodeName(), joinSequence, isNullable);

        return new SQLJoinStatement(
                joinSequence,
                targetTable,
                alias,
                List.of(),
                isNullable
        );
    }
}
