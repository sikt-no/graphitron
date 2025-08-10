package no.sikt.graphitron.generators.context;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.containedtypes.FieldReference;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.JoinElement;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.mapping.Alias;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.definitions.mapping.TableRelation;
import no.sikt.graphitron.definitions.sql.SQLJoinStatement;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jooq.Key;
import org.jooq.Table;

import java.util.*;
import java.util.stream.Stream;

import static no.sikt.graphitron.definitions.mapping.JOOQMapping.fromTable;
import static no.sikt.graphitron.generators.codebuilding.ResolverKeyHelpers.findKeyForResolverField;
import static no.sikt.graphitron.mappings.TableReflection.*;

/**
 * A helper class to handle traversal of nested types in queries. Since such queries will require nested layers of rows,
 * this class can generate the next "iteration" of itself for handling deeper layers.
 */
public class FetchContext {
    private final FetchContext previousContext;
    private final GenerationField referenceObjectField;
    private final RecordObjectSpecification<?> referenceObject, previousTableObject;
    private final JoinListSequence currentJoinSequence;

    private final LinkedHashSet<SQLJoinStatement> joinSet;
    private final LinkedHashSet<Alias> aliasSet;
    private final ArrayList<CodeBlock> conditionList;
    private final String graphPath;
    private final int recCounter;
    private final Key<?> resolverKey;
    private boolean shouldUseOptional;
    private boolean shouldUseEnhancedNullOnAllNullCheck = false;
    private final ProcessedSchema processedSchema;

    /* Midlertidig hack på count for paginerte kanter, som ikke bruker subspørringer enda */
    private final boolean addAllJoinsToJoinSet;

    /**
     * @param referenceObjectField The referring field that contains an object.
     * @param pastJoinSequence The current sequence of joins that must prepended.
     * @param previousObject The last object that was joined on in some previous iteration.
     * @param joinSet List of joins that must be declared outside this recursion.
     * @param aliasSet List of alias that must be declared outside this recursion.
     * @param conditionList List of conditions that must be declared outside this recursion.
     * @param pastGraphPath The path in the GraphQL schema so far.
     * @param recCounter Counter that limits recursion depth to the max value of integers.
     */
    private FetchContext(
            ProcessedSchema processedSchema,
            GenerationField referenceObjectField,
            JoinListSequence pastJoinSequence,
            RecordObjectSpecification<?> previousObject,
            LinkedHashSet<SQLJoinStatement> joinSet,
            LinkedHashSet<Alias> aliasSet,
            ArrayList<CodeBlock> conditionList,
            String pastGraphPath,
            int recCounter,
            FetchContext previousContext,
            boolean shouldUseOptional,
            boolean addAllJoinsToJoinSet
    ) {
        if (recCounter == Integer.MAX_VALUE - 1) {
            throw new RuntimeException("Recursion depth has reached the integer max value.");
        }
        this.addAllJoinsToJoinSet = addAllJoinsToJoinSet;
        this.recCounter = recCounter;
        this.processedSchema = processedSchema;

        if (referenceObjectField.hasNodeID()) {
             referenceObject = processedSchema.getObject(referenceObjectField.getNodeIdTypeName());
         } else {
             referenceObject = processedSchema.getRecordType(referenceObjectField);
         }

        this.joinSet = joinSet;
        this.aliasSet = aliasSet;
        this.conditionList = conditionList;

        this.referenceObjectField = referenceObjectField;
        this.previousTableObject = processedSchema.getPreviousTableObjectForObject(previousObject);
        graphPath = pastGraphPath;

        this.previousContext = previousContext;
        this.shouldUseOptional = shouldUseOptional;
        this.resolverKey = findKeyForResolverField(referenceObjectField, processedSchema);
        currentJoinSequence = iterateJoinSequence(pastJoinSequence);
    }

    /**
     * @param referenceObjectField The referring field that contains an object.
     * @param previousObject       Object of origin for this context.
     * @param addAllJoinsToJoinSet Boolean value mainly set to false, but set to true when used in relation to
     *                             Generators used for counting all available elements for a type.
     */
    public FetchContext(
            ProcessedSchema processedSchema,
            ObjectField referenceObjectField,
            RecordObjectSpecification<?> previousObject,
            boolean addAllJoinsToJoinSet
    ) {
        this(
                processedSchema,
                referenceObjectField,
                new JoinListSequence(),
                previousObject,
                new LinkedHashSet<>(),
                new LinkedHashSet<>(),
                new ArrayList<>(),
                "",
                0,
                null,
                referenceObjectField.getOrderField().isEmpty(), //do not use optional in combination with orderBy
                addAllJoinsToJoinSet
        );
    }

    /**
     * @return Set of joins created by this context.
     */
    public Set<SQLJoinStatement> getJoinSet() {
        return joinSet;
    }

    /**
     * @return Set of aliases created by this and any other context created from this one.
     */
    public Set<Alias> getAliasSet() {
        return aliasSet;
    }

    /**
     * @return The path to this context in the schema itself. Used to correctly check selection sets.
     */
    public String getGraphPath() {
        return graphPath;
    }

    /**
     * @return The referred object being processed in the current context.
     */
    public RecordObjectSpecification<?> getReferenceObject() {
        return referenceObject;
    }

    /**
     * @return The table join path or alias where the columns are expected to be found.
     */
    public JoinListSequence getCurrentJoinSequence() {
        return currentJoinSequence;
    }

    /**
     * @return List of all conditions created up to this point.
     */
    public List<CodeBlock> getConditionList() {
        return conditionList;
    }

    /**
     * @return The reference table the reference field points to.
     */
    public JOOQMapping getReferenceTable() {
        return referenceObject != null ? referenceObject.getTable() : null;
    }

    /**
     * @return The previously used reference table.
     */
    public JOOQMapping getPreviousTable() {
        if (previousContext != null) {
            return previousContext.getTargetTable();
        } else {
            return previousTableObject != null ? previousTableObject.getTable() : null;
        }
    }

    /**
     * @return The name of the previously used reference table.
     */
    public String getPreviousTableName() {
        return getPreviousTable() == null ? "" : getPreviousTable().getName();
    }

    public JOOQMapping getSourceTable() {
        return currentJoinSequence.isEmpty()
               ? getPreviousTable()
               : currentJoinSequence.getFirst().getTable();
    }

    public String getSourceTableName() {
        return getSourceTable() == null
               ? ""
               : currentJoinSequence.getFirst().getTable().getName();
    }

    public String getSourceAlias() {
        return currentJoinSequence.render(currentJoinSequence.getFirst()).toString();
    }

    /**
     * @return The target table of this context.
     */
    public JOOQMapping getTargetTable() {
        return currentJoinSequence.isEmpty() ? getPreviousTable() : currentJoinSequence.getLast().getTable();
    }

    /**
     * @return The target table name of this context.
     */
    public String getTargetTableName() {
        return getTargetTable() == null ? "" : getTargetTable().getName();
    }

    /**
     * @return The target table alias of this context.
     */
    public String getTargetAlias() {
        return currentJoinSequence.render().toString();
    }

    /**
     * @return The reference table which fields on this layer are taken from.
     */
    public JOOQMapping getReferenceOrPreviousTable() {
        return (referenceObject != null && referenceObject.hasTable())
               ? referenceObject.getTable()
               : previousTableObject != null
                 ? previousTableObject.getTable()
                 : null;
    }

    /**
     * @return The reference field which points to the reference object for this layer.
     */
    public GenerationField getReferenceObjectField() {
        return referenceObjectField;
    }

    public Key<?> getResolverKey() {
        return resolverKey;
    }

    public boolean getShouldUseOptional() {
        return shouldUseOptional;
    }

    public FetchContext withShouldUseOptional(boolean shouldUseOptional) {
        this.shouldUseOptional = shouldUseOptional;
        return this;
    }

    /**
     * @return Should this layer apply an expanded null check?
     */
    public boolean shouldUseEnhancedNullOnAllNullCheck() {
        return shouldUseEnhancedNullOnAllNullCheck;
    }

    /**
     * Override the previous layer's use of an expanded null check.
     */
    public void setParentContextShouldUseEnhancedNullOnAllNullCheck() {
        if (previousContext != null) {
            previousContext.shouldUseEnhancedNullOnAllNullCheck = true;
        }
    }

    /**
     * @return The next iteration of this context based on the provided reference field.
     */
    public FetchContext nextContext(GenerationField referenceObjectField) {
        var newJoinListSequence = new JoinListSequence();
        var previousGraphPath = this.graphPath;

        if (!referenceObjectField.isResolver() && processedSchema.isObject(referenceObjectField)) {
            previousGraphPath += referenceObjectField.getName() + "/";
            var refTable = processedSchema.getObject(referenceObjectField).getTable();

            if ((refTable == null || refTable.equals(getReferenceTable())) && !currentJoinSequence.isEmpty()) {
                newJoinListSequence.add(currentJoinSequence.getLast());
            }
        }

        return new FetchContext(
                this.processedSchema,
                referenceObjectField,
                newJoinListSequence,
                this.referenceObject,
                new LinkedHashSet<>(),
                this.aliasSet,
                (ArrayList<CodeBlock>) this.conditionList.clone(),
                previousGraphPath,
                this.recCounter + 1,
                this,
                this.shouldUseOptional,
                this.addAllJoinsToJoinSet);
    }

    /**
     * Iterate the table sequence as if several context layers were traversed with the provided references.
     *
     * @return The new join sequence, with the provided join sequence extended by all references in this layer's
     * reference field, or other appropriate start points for a sequence.
     */
    public JoinListSequence iterateJoinSequenceFor(GenerationField field) {
        var currentSequence = getCurrentJoinSequence();
        List<FieldReference> fieldReferences;

        if (field.hasNodeID() && !field.getNodeIdTypeName().equals(getReferenceObjectField().getTypeName())) {
            // Add implicit table reference from typeName in @nodeId directive
            fieldReferences = Stream.of(
                            field.getFieldReferences(),
                            List.of(new FieldReference(fromTable(processedSchema.getObject(field.getNodeIdTypeName()).getTable().getName()))))
                    .flatMap(Collection::stream).toList();
        } else {
            fieldReferences = field.getFieldReferences();
        }

        if (fieldReferences.isEmpty()) {
            return currentSequence;
        }

        var newJoinSequence = processFieldReferences(
                getCurrentJoinSequence(),
                getReferenceTable(),
                fieldReferences,
                true,
                false
        );
        return !newJoinSequence.isEmpty() ? newJoinSequence : currentSequence;
    }

    /**
     * Iterate the table sequence as if several context layers were traversed.
     *
     * @return The new join sequence, with the provided join sequence extended by all references in this layer's
     * reference field, or other appropriate start points for a sequence.
     */
    public JoinListSequence iterateJoinSequence(JoinListSequence previousJoinSequence) {
        if (hasNoNextTable()) {
            return previousJoinSequence;
        }

        var joinSequenceFromFieldReferences = processFieldReferences(
                previousJoinSequence,
                getReferenceOrPreviousTable(),
                referenceObjectField.getFieldReferences(),
                false,
                true
        );

        var updatedJoinSequence = !joinSequenceFromFieldReferences.isEmpty()
                                  ? joinSequenceFromFieldReferences
                                  : previousJoinSequence;

        var referenceObjectTable = getReferenceTable();

        if (referenceObjectTable == null) {
            return updatedJoinSequence;
        }

        var lastTable = !updatedJoinSequence.isEmpty()
                        ? updatedJoinSequence.getLast().getTable()
                        : getPreviousTable(); // Wrong if key was reverse.

        if (Objects.equals(lastTable, referenceObjectTable) &&
            (!referenceObjectField.getFieldReferences().isEmpty() ||
             processedSchema.isInterface(referenceObjectField.getContainerTypeName()))
        ) {
            if (updatedJoinSequence.isEmpty()) {
                var alias = new Alias(
                        "_" + referenceObjectTable.getCodeName(),
                        JoinListSequence.of(referenceObjectTable),
                        false,
                        null
                );
                aliasSet.add(alias);
                return JoinListSequence.of(alias);
            } else {
                return updatedJoinSequence;
            }
        }

        // Add fake reference to the reference table so that the last step is also executed if no table or key is
        // specified. If method creates an alias that has previously been generated, the new alias will be "ignored"
        var finalSequence = resolveNextSequence(
                new FieldReference(referenceObjectTable),
                new TableRelation(lastTable, referenceObjectTable),
                updatedJoinSequence,
                false,
                this.aliasSet.isEmpty()
        );

        if (!finalSequence.isEmpty()) {
            return finalSequence;
        }

        var alias = new Alias(
                "_" + referenceObjectTable.getCodeName(),
                JoinListSequence.of(referenceObjectTable),
                false,
                null
        );
        aliasSet.add(alias);

        return JoinListSequence.of(alias);
    }

    /**
     * Iterate through the provided references set by the reference directive and create a new join sequence.
     *
     * @param joinSequence The current join sequence.
     * @param refTable The reference table to be used.
     * @param directiveReferences The references to be processed.
     * @param checkLastRef Should the previous reference table be checked?
     * @return The new join sequence, with the provided join sequence extended by all the references provided. If no
     * references are provided, the join sequence will be returned as is.
     */
    private JoinListSequence processFieldReferences(
            JoinListSequence joinSequence,
            JOOQMapping refTable,
            List<FieldReference> directiveReferences,
            boolean requiresLeftJoin,
            boolean checkLastRef
    ) {
        var previousTable = joinSequence.isEmpty()
                            ? getPreviousTable()
                            : joinSequence.getLast().getTable();

        if (getReferenceObjectField().isResolver() && this.previousContext != null && checkLastRef)
            previousTable = this.previousContext.getPreviousTable();

        var relations = new ArrayList<TableRelation>();

        for (var fieldReference : directiveReferences) {
            var nextTable = inferNextTable(
                    fieldReference.getTable(), fieldReference.getKey(), previousTable, refTable);
            relations.add(new TableRelation(previousTable, nextTable, fieldReference.getKey()));
            previousTable = nextTable;
        }

        if (previousTable == null) {
            previousTable = getPreviousTable();
        }

        // TODO: Is this added relation necessary? It does not seem to be used anywhere.
        if (checkLastRef && !Objects.equals(previousTable, refTable)) {
            relations.add(new TableRelation(previousTable, refTable));
        }

        for (int i = 0; i < directiveReferences.size(); i++) {
            joinSequence = resolveNextSequence(
                    directiveReferences.get(i),
                    relations.get(i),
                    joinSequence,
                    requiresLeftJoin,
                    i < 1
            );
        }

        return joinSequence;
    }

    /**
     * Resolves the next sequence of joins based on the specified field reference and relation. If necessary, updates
     * the set of aliases, the list of joins and the list of conditions in the given context to ensure consistency with
     * the resolved joins.
     *
     * @param fieldRef The current field reference to be used (from the assigned reference directive).
     * @param relation From what table are we coming from and to what table are we going to.
     * @param joinSequence The current join sequence.
     * @return The new join sequence, with the provided join sequence extended by the references provided.
     */
    private JoinListSequence resolveNextSequence(
            FieldReference fieldRef,
            TableRelation relation,
            JoinListSequence joinSequence,
            boolean requiresLeftJoin,
            boolean checkResolver
    ) {
        var previousTable = relation.getFrom();
        var targetTable = relation.getToTable();

        // This indicates that the field we are processing is a field in the root type, Query.
        if (previousTable == null) {
            var alias = new Alias("_" + targetTable.getCodeName(), JoinListSequence.of(targetTable), false, null);
            this.aliasSet.add(alias);
            return JoinListSequence.of(alias);
        }

        // When field has a @splitQuery directive or field has arguments and are not in the root type Query.
        if (getReferenceObjectField().isResolver() && this.previousContext == null && checkResolver) {
            var alias = new Alias("_" + previousTable.getCodeName(), JoinListSequence.of(previousTable), false, null);
            this.aliasSet.add(alias);
            joinSequence = JoinListSequence.of(alias);
        }

        var targetOrPrevious = targetTable != null ? targetTable : previousTable;
        var newSequence = joinSequence;
        var keyToUse = getKey(fieldRef, relation);

        if (fieldRef.hasTableCondition() && keyToUse == null) {
            if (referenceObjectField.isResolver()) {
                var alias = new Alias(
                        previousTable.getCodeName() + "_" + getReferenceObjectField().getName(),
                        JoinListSequence.of(targetTable),
                        false,
                        null
                );

                this.joinSet.add(fieldRef.createConditionJoinFor(
                        this.previousContext != null && this.referenceObjectField.isResolver()
                        ? JoinListSequence.of(this.previousContext.getCurrentJoinSequence().getLast())
                        : newSequence,
                        alias,
                        targetOrPrevious,
                        requiresLeftJoin
                ));

                newSequence.add(alias);
                this.aliasSet.add(alias);
                return newSequence.cloneAdd(alias);
            }

            // Indicates first alias in new subquery.
            if (newSequence.isEmpty()) {
                var alias = new Alias(
                        previousTable.getCodeName() + "_" + getReferenceObjectField().getName(),
                        JoinListSequence.of(previousTable),
                        false,
                        previousTable
                );

                newSequence.add(alias);
                this.aliasSet.add(alias);

                var primaryKey = getTable(previousTable.getName()).map(Table::getPrimaryKey).stream().findFirst()
                        .orElseThrow(() ->
                                new IllegalArgumentException(String.format(
                                        "Code generation failed for %s.%s as the table %s must have a primary key in order to reference another table without a foreign key.",
                                        referenceObjectField.getContainerTypeName(),
                                        referenceObjectField.getName(),
                                        previousTable.getName())));

                for (var fieldName : getJavaFieldNamesForKey(previousTable.getName(), primaryKey)) {
                    this.conditionList.add(CodeBlock.of(
                            "$L.$L.eq($L.$L)",
                            previousContext.getCurrentJoinSequence().getLast().getMappingName(),
                            fieldName,
                            alias.getMappingName(),
                            fieldName));
                }
            }

            var join = fieldRef.createConditionJoinFor(newSequence, targetOrPrevious, requiresLeftJoin, targetTable);
            if (!newSequence.isEmpty() || this.addAllJoinsToJoinSet) {
                this.joinSet.add(join);
            }

            this.aliasSet.add(join.getJoinAlias());
            return newSequence.cloneAdd(join.getJoinAlias());
        }

        if (keyToUse != null) {
            var aliasJoinSequence = newSequence.clone();

            if (newSequence.isEmpty()) {
                aliasJoinSequence = aliasJoinSequence.cloneAdd(
                        this.previousContext == null || this.previousContext.getCurrentJoinSequence().isEmpty()
                        ? previousTable
                        : previousContext.getCurrentJoinSequence().getLast());
            }
            aliasJoinSequence = aliasJoinSequence.cloneAdd(keyToUse);

            var join = fieldRef.createJoinOnExplicitPathFor(keyToUse, aliasJoinSequence, targetTable, requiresLeftJoin);

            if (!newSequence.isEmpty() || addAllJoinsToJoinSet) {
                joinSet.add(join);
            }

            aliasSet.add(join.getJoinAlias());
            newSequence = newSequence.cloneAdd(join.getJoinAlias());
        }

        if (fieldRef.hasTableCondition() && relation.hasRelation()) {
            var previousTableWithAlias = newSequence.getSecondLast() == null && this.previousContext != null
                                         ? this.previousContext.getCurrentJoinSequence().render()
                                         : newSequence.render(newSequence.getSecondLast());

            this.conditionList.add(fieldRef.getTableCondition().formatToString(
                    List.of(previousTableWithAlias, newSequence.render())));
        }

        return newSequence;
    }


    public CodeBlock renderQuerySource(JoinElement localTable) {
        return currentJoinSequence.render(localTable == null ? getReferenceTable() : localTable);
    }

    private static JOOQMapping inferNextTable(
            JOOQMapping referenceTable,
            JOOQMapping referenceKey,
            JOOQMapping previousTable,
            JOOQMapping nextTable
    ) {
        if (referenceTable != null) {
            return referenceTable;
        }

        // Infer based on the key provided.
        if (referenceKey != null) {
            var keyName = referenceKey.getMappingName();
            var sourceTable = getKeySourceTable(keyName).map(JOOQMapping::fromTable).orElse(null);
            var targetTable = getKeyTargetTable(keyName).map(JOOQMapping::fromTable).orElse(null);

            // Self-reference key.
            if (Objects.equals(sourceTable, targetTable) &&
                Objects.equals(sourceTable, previousTable) &&
                hasSelfRelation(previousTable.getMappingName())
            ) {
                return sourceTable;
            }

            // Key is the "right" way.
            if (Objects.equals(sourceTable, previousTable)) {
                return targetTable;
            }

            // Key is the "wrong" way.
            if (Objects.equals(targetTable, previousTable)) {
                return sourceTable;
            }
        }

        return nextTable;
    }

    boolean hasNoNextTable() {
        return getReferenceTable() == null && !this.referenceObjectField.hasFieldReferences();
    }


    /**
     * This method returns the key either from the field reference, or if no key is provided and no condition given, it
     * will find the implicit key based on the previous and target table names.
     *
     * @param fieldReference The current field reference to be used.
     * @param relation The relation specifying what table we are coming from and to what table are we going to.
     * @return The key, if it exists, or null if no key is found.
     */
    private JOOQMapping getKey(FieldReference fieldReference, TableRelation relation) {
        var previousTable = relation.getFrom();
        var targetTable = relation.getToTable();
        var targetOrPrevious = targetTable != null ? targetTable : previousTable;

        // TODO: If a condition exists but no key is provided in the field reference, no key is registered, even though
        //  an implicit key might exist. Should this case be handled differently?
        var keyToUse = fieldReference.hasKey() || fieldReference.hasTableCondition()
                       ? fieldReference.getKey()
                       : findImplicitKey(previousTable.getMappingName(), targetOrPrevious.getMappingName())
                               .map(JOOQMapping::fromKey)
                               .orElse(null);

        if (keyToUse != null && !keyToUse.getTable().equals(targetTable)) {
            keyToUse = keyToUse.getInverseKey();
        }

        return keyToUse;
    }
}
