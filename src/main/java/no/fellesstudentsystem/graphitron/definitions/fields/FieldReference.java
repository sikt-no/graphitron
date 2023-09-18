package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.DirectivesContainer;
import graphql.language.NamedNode;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQTableMapping;
import no.fellesstudentsystem.graphitron.definitions.sql.*;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

import static no.fellesstudentsystem.graphitron.mappings.TableReflection.searchTableForMethodByKey;
import static no.fellesstudentsystem.graphitron.mappings.TableReflection.tableHasMethod;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.REFERENCE;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.*;
import static no.fellesstudentsystem.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentString;

public class FieldReference {
    private final JOOQTableMapping table;
    private final String tableKey;
    private final SQLCondition tableCondition;

    public <T extends NamedNode<T> & DirectivesContainer<T>> FieldReference(T field) {
        var relatedTable = "";
        var relatedTableKey = "";
        SQLCondition relatedTableCondition = null;
        if (field.hasDirective(REFERENCE.getName())) {
            relatedTable = getOptionalDirectiveArgumentString(field, REFERENCE, REFERENCE.getParamName(TABLE)).orElse("");
            relatedTableKey = getOptionalDirectiveArgumentString(field, REFERENCE, REFERENCE.getParamName(KEY)).orElse("");
            relatedTableCondition = getOptionalDirectiveArgumentString(field, REFERENCE, REFERENCE.getParamName(CONDITION))
                    .map(SQLCondition::new)
                    .orElse(null);
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

    /**
     * Create an alias for this reference.
     * @param referenceName The name of the join end point. Used for making the alias unique.
     * @param previousJoinTable Previously encountered table.
     * @param pastJoinSequence The entire sequence of implicit joins up to this point.
     * @param tableCodeNameBackup Backup for the table name should it be undefined.
     * @return new SQLAlias from the parameters.
     */
    @Nullable
    public SQLAlias createAliasFor(String referenceName, String previousJoinTable, String pastJoinSequence, String tableCodeNameBackup) {
        var relationTableSource = !table.getCodeName().isEmpty() ? table.getCodeName() : tableCodeNameBackup;
        var hasNaturalImplicitJoin = tableHasMethod(previousJoinTable, relationTableSource);
        Optional<String> joinReference = tableKey.isEmpty() || hasNaturalImplicitJoin ? Optional.empty() : searchTableForMethodByKey(previousJoinTable, tableKey);
        if (hasNaturalImplicitJoin || joinReference.isPresent()) {
            String joinSourceTable = pastJoinSequence == null || pastJoinSequence.isEmpty() ? previousJoinTable : pastJoinSequence;
            return new SQLAlias(
                    (joinSourceTable + "_" + referenceName).toLowerCase(),
                    joinSourceTable,
                    joinReference.orElse(relationTableSource)
            );
        }
        return null;
    }

    private SQLJoinStatement createJoinFor(
            AbstractField reference,
            String previousJoinTable,
            String pastJoinSequence,
            String tableNameBackup,
            SQLJoinField joinField
    ) {
        var name = table.getName();
        return new SQLJoinStatement(
                !pastJoinSequence.isEmpty() ? pastJoinSequence : previousJoinTable,
                !name.isEmpty() ? name : tableNameBackup,
                (previousJoinTable + "_" + reference.getName()).toLowerCase(),
                List.of(joinField),
                reference.getFieldType().isNonNullable() ? SQLJoinType.JOIN : SQLJoinType.LEFT
        );
    }

    /**
     * @return A join statement based on a condition.
     */
    public SQLJoinStatement createConditionJoinFor(AbstractField reference, String previousJoinTable, String pastJoinSequence, String tableNameBackup) {
        return createJoinFor(reference, previousJoinTable, pastJoinSequence, tableNameBackup, new SQLJoinOnCondition(tableCondition));
    }

    /**
     * @return A join statement based on a key reference.
     */
    public SQLJoinStatement createJoinOnKeyFor(AbstractField reference, String previousJoinTable, String pastJoinSequence, String tableNameBackup) {
        return createJoinFor(reference, previousJoinTable, pastJoinSequence, tableNameBackup, new SQLJoinOnKey(tableKey));
    }
}
