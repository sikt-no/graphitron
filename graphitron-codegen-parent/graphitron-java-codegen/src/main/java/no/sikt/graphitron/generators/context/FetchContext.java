package no.sikt.graphitron.generators.context;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.fields.containedtypes.FieldReference;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.JoinElement;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.mapping.Alias;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.definitions.mapping.TableRelation;
import no.sikt.graphitron.definitions.mapping.AliasWrapper;
import no.sikt.graphitron.definitions.sql.SQLJoinStatement;
import no.sikt.graphitron.generators.codebuilding.KeyWrapper;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jooq.Table;

import java.util.*;
import java.util.stream.Stream;

import static no.sikt.graphitron.definitions.mapping.JOOQMapping.fromTable;
import static no.sikt.graphitron.generators.codebuilding.KeyWrapper.findKeyForResolverField;
import static no.sikt.graphitron.mappings.TableReflection.*;

/**
 * A helper class to handle traversal of nested types in queries. Since such queries will require nested layers of rows,
 * this class can generate the next "iteration" of itself for handling deeper layers.
 */
public class FetchContext {
    private final FetchContext previousContext;
    private final GenerationField referenceObjectField;
    private final RecordObjectSpecification<?> referenceObject;
    private final JOOQMapping referenceTable, previousTable, inputTable;
    private final JoinListSequence currentJoinSequence;

    private final LinkedHashSet<SQLJoinStatement> joinSet;
    private final LinkedHashSet<AliasWrapper> aliasSet;
    private final List<GenerationField> conditionSourceFields;
    private final ArrayList<CodeBlock> conditionList;
    private final String graphPath;
    private final int recCounter;
    private final KeyWrapper resolverKey;
    private boolean shouldUseOptional;
    private boolean shouldUseEnhancedNullOnAllNullCheck = false;
    private final boolean hasApplicableTable;
    private final ProcessedSchema processedSchema;

    /* Midlertidig hack på count for paginerte kanter, som ikke bruker subspørringer enda */
    private final boolean addAllJoinsToJoinSet;

    private final boolean useTableWithoutAliasInFirstStep; // Necessary for mutation queries

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
            LinkedHashSet<AliasWrapper> aliasSet,
            ArrayList<CodeBlock> conditionList,
            String pastGraphPath,
            int recCounter,
            FetchContext previousContext,
            boolean shouldUseOptional,
            boolean addAllJoinsToJoinSet,
            boolean useTableWithoutAliasInFirstStep
    ) {
        if (recCounter == Integer.MAX_VALUE - 1) {
            throw new RuntimeException("Recursion depth has reached the integer max value.");
        }
        this.addAllJoinsToJoinSet = addAllJoinsToJoinSet;
        this.useTableWithoutAliasInFirstStep = useTableWithoutAliasInFirstStep;
        this.recCounter = recCounter;
        this.processedSchema = processedSchema;

        if (referenceObjectField.hasNodeID()) {
             referenceObject = processedSchema.getNodeTypeForNodeIdFieldOrThrow(referenceObjectField);
         } else {
             referenceObject = processedSchema.getRecordType(referenceObjectField);
         }

        this.joinSet = joinSet;
        this.aliasSet = aliasSet;
        this.conditionList = conditionList;

        this.referenceObjectField = referenceObjectField;
        var optionalPrevious = Optional.ofNullable(previousContext);
        var previousTableObject = processedSchema.getPreviousTableObjectForObject(previousObject);
        this.previousTable = previousTableObject != null ? previousTableObject.getTable() : null;
        this.referenceTable = referenceObject != null ? referenceObject.getTable() : null;

        // For more complex cases, the input table may need to be expanded to a sequence.
        var allFoundInputTables = processedSchema.findInputTables(referenceObjectField);
        var foundInputTable = allFoundInputTables.stream().findFirst().orElse(null);
        var inputTable = foundInputTable != null ? foundInputTable : optionalPrevious.map(it -> it.inputTable).orElse(null);

        graphPath = pastGraphPath;

        this.previousContext = previousContext;
        this.shouldUseOptional = shouldUseOptional;
        this.resolverKey = findKeyForResolverField(referenceObjectField, processedSchema);

        hasApplicableTable = previousTable != null || referenceTable != null;
        var hasPreviousApplicableTable = optionalPrevious.map(FetchContext::hasApplicableTable).orElse(false);
        var previousSourceFields = optionalPrevious.map(FetchContext::getConditionSourceFields).orElse(List.of());
        if (!previousSourceFields.isEmpty() && hasPreviousApplicableTable) {
            this.conditionSourceFields = List.of();
            this.inputTable = null;  // Note that this is important in order to not break longer join sequences by accidentally reusing this table.
        } else if (!hasApplicableTable) {
            this.conditionSourceFields = Stream.concat(previousSourceFields.stream(), Stream.of(referenceObjectField)).toList();
            this.inputTable = inputTable;
        } else {
            this.conditionSourceFields = previousSourceFields;
            this.inputTable = inputTable;
        }
        currentJoinSequence = iterateJoinSequence(pastJoinSequence);
    }

    /**
     * @param referenceObjectField The referring field that contains an object.
     * @param previousObject       Object of origin for this context.
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
                previousObject,
                addAllJoinsToJoinSet,
                false
        );
    }

    public FetchContext(
            ProcessedSchema processedSchema,
            ObjectField referenceObjectField,
            RecordObjectSpecification<?> previousObject,
            boolean addAllJoinsToJoinSet,
            boolean useTableWithoutAliasInFirstStep
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
                addAllJoinsToJoinSet,
                useTableWithoutAliasInFirstStep
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
    public Set<AliasWrapper> getAliasSet() {
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
        return referenceTable;
    }

    /**
     * @return The previously used reference table.
     */
    public JOOQMapping getPreviousTable() {
        if (previousContext != null) {
            return previousContext.getTargetTable();
        } else {
            return previousTable;
        }
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

    public String getSourceAlias() {
        return !currentJoinSequence.isEmpty() ? currentJoinSequence.getFirst().getMappingName() : null;
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
        return (referenceObject != null && referenceObject.hasTable()) ? referenceObject.getTable() : previousTable;
    }

    /**
     * @return The reference field which points to the reference object for this layer.
     */
    public GenerationField getReferenceObjectField() {
        return referenceObjectField;
    }

    /**
     * @return The sources for conditions that could not be applied in previous steps.
     */
    public List<GenerationField> getConditionSourceFields() {
        return conditionSourceFields;
    }

    public KeyWrapper getResolverKey() {
        return resolverKey;
    }

    public boolean hasApplicableTable() {
        return hasApplicableTable;
    }

    public boolean hasNonSubqueryFields() {
        return getReferenceObject() == null || getReferenceObject()
                .getFields()
                .stream()
                .anyMatch(it -> !it.invokesSubquery() || processedSchema.isRecordType(it) && processedSchema.getRecordType(it).hasTable() && !processedSchema.getRecordType(it).getTable().equals(getTargetTable()));
    }

    public boolean hasPreviousContext() {
        return previousContext != null;
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
        var previousGraphPath = graphPath;

        if (!referenceObjectField.isResolver() && processedSchema.isObject(referenceObjectField)) {
            previousGraphPath += referenceObjectField.getName() + "/";
            var refTable = processedSchema.getObject(referenceObjectField).getTable();

            if ((refTable == null || refTable.equals(getReferenceTable())) && !currentJoinSequence.isEmpty()) {
                newJoinListSequence.add(currentJoinSequence.getLast());
            }
        } else if (!processedSchema.isObject(referenceObjectField) && referenceObjectField.isIterableWrapped()
                && !referenceObjectField.hasFieldReferences() && !currentJoinSequence.isEmpty()) {
            newJoinListSequence.addAll(currentJoinSequence);
        }

        return new FetchContext(
                processedSchema,
                referenceObjectField,
                newJoinListSequence,
                referenceObject,
                new LinkedHashSet<>(),
                aliasSet,
                new ArrayList<>(conditionList),
                previousGraphPath,
                recCounter + 1,
                this,
                shouldUseOptional,
                addAllJoinsToJoinSet,
                false);
    }

    public FetchContext forVirtualField(VirtualSourceField field) {
        return new FetchContext(
                processedSchema,
                field,
                getCurrentJoinSequence(),
                referenceObject,
                new LinkedHashSet<>(),
                aliasSet,
                new ArrayList<>(conditionList),
                graphPath,
                recCounter + 1,
                this,
                shouldUseOptional,
                addAllJoinsToJoinSet,
                false);
    }

    /**
     * Iterate the table sequence as if several context layers were traversed with the provided references.
     * @return The new join sequence, with the provided join sequence extended by all references in this layer's reference field,
     * or other appropriate start points for a sequence.
     */
    public JoinListSequence iterateJoinSequenceFor(GenerationField field) {
        var currentSequence = getCurrentJoinSequence();
        List<FieldReference> fieldReferences = field.getFieldReferences();

        if (processedSchema.isNodeIdField(field)) {
            var nodeType = processedSchema.getNodeTypeForNodeIdFieldOrThrow(field);
            if (!nodeType.getName().equals(getReferenceObjectField().getTypeName())) {
                // Add implicit table reference from typeName in @nodeId directive
                fieldReferences = Stream.of(
                                field.getFieldReferences(),
                                List.of(new FieldReference(fromTable(nodeType.getTable().getName()))))
                        .flatMap(Collection::stream).toList();
            }
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
     * @return The new join sequence, with the provided join sequence extended by all references in this layer's reference field,
     * or other appropriate start points for a sequence.
     */
    public JoinListSequence iterateJoinSequence(JoinListSequence previousSequence) {
        var refTable = getReferenceTable() == null && !hasApplicableTable() ? inputTable : getReferenceTable();
        if (refTable == null && !referenceObjectField.hasFieldReferences()) {
            return previousSequence;
        }

        var referencesFromField = referenceObjectField.getFieldReferences();
        var newJoinSequence = processFieldReferences(previousSequence, getReferenceOrPreviousTable(), referencesFromField, false, true);
        var updatedSequence = !newJoinSequence.isEmpty() ? newJoinSequence : previousSequence;

        if (refTable == null) {
            return updatedSequence;
        }

        var lastTable = !updatedSequence.isEmpty() ? updatedSequence.getLast().getTable() : getPreviousTable(); // Wrong if key was reverse.
        if (Objects.equals(lastTable, refTable) && (!referencesFromField.isEmpty() || processedSchema.isInterface(referenceObjectField.getContainerTypeName()))) {
            if (updatedSequence.isEmpty()) {
                return makeJoinSequence(refTable);
            } else {
                return updatedSequence;
            }
        }

        var finalSequence = resolveNextSequence(
                new FieldReference(refTable),
                new TableRelation(lastTable, refTable),
                updatedSequence,
                false
        ); // Add fake reference to the reference table so that the last step is also executed if no table or key is specified.
        if (!finalSequence.isEmpty()) {
            return finalSequence;
        } else {
            return makeJoinSequence(refTable);
        }
    }

    /**
     * Iterate through the provided references and create a new join sequence.
     * @return The new join sequence, with the provided join sequence extended by all the references provided.
     */
    private JoinListSequence processFieldReferences(JoinListSequence joinSequence, JOOQMapping refTable, List<FieldReference> references, boolean requiresLeftJoin, boolean checkLastRef) {
        var previousTable = joinSequence.isEmpty() ? getPreviousTable() : joinSequence.getLast().getTable();
        if (getReferenceObjectField().isResolver() && previousContext != null && checkLastRef) previousTable = previousContext.getPreviousTable();

        var relations = new ArrayList<TableRelation>();
        for (var fRef : references) {
            var table = inferNextTable(fRef.getTable(), fRef.getKey(), previousTable, refTable);
            relations.add(new TableRelation(previousTable, table, fRef.getKey()));
            previousTable = table;
        }

        if (previousTable == null) {
            previousTable = getPreviousTable();
        }

        if (checkLastRef && !Objects.equals(previousTable, refTable)) {
            relations.add(new TableRelation(previousTable, refTable));
        }

        for (int i = 0; i < references.size(); i++) {
            if (getReferenceObjectField().isResolver() && previousContext == null && i > 0) break;
            joinSequence = resolveNextSequence(references.get(i), relations.get(i), joinSequence, requiresLeftJoin);
        }
        return joinSequence;
    }

    private JoinListSequence resolveNextSequence(FieldReference fRef, TableRelation relation, JoinListSequence joinSequence, boolean requiresLeftJoin) {
        var previous = relation.getFrom();
        var target = relation.getToTable();

        if (previous == null) {
            return makeJoinSequence(target);
        }
        if (getReferenceObjectField().isResolver() && previousContext == null) {
            return makeJoinSequence(previous);
        }

        var targetOrPrevious = target != null ? target : previous;

        var newSequence = joinSequence;

        var keyToUse = fRef.hasKey() || fRef.hasTableCondition()
                ? fRef.getKey()
                : findImplicitKey(previous.getMappingName(), targetOrPrevious.getMappingName()).map(JOOQMapping::fromKey).orElse(null);

        if (keyToUse != null && !keyToUse.getTable().equals(target)) {
            keyToUse = keyToUse.getInverseKey();
        }

        if (fRef.hasTableCondition() && keyToUse == null) {
            if (newSequence.isEmpty()) {
                var joinElement = getJoinElement(previous.getCodeName() + "_" + getReferenceObjectField().getName(), JoinListSequence.of(previous));
                newSequence.add(joinElement);

                var primaryKey = getTable(previous.getName()).map(Table::getPrimaryKey).stream().findFirst()
                        .orElseThrow(() ->
                                new IllegalArgumentException(String.format("Code generation failed for %s.%s as the table %s must have a primary key in order to reference another table without a foreign key.",
                                        referenceObjectField.getContainerTypeName(), referenceObjectField.getName(), previous.getName())));

                for (var fieldName : getJavaFieldNamesForKey(previous.getName(), primaryKey)) {
                    this.conditionList.add(CodeBlock.of("$L.$L.eq($L.$L)", previousContext.getCurrentJoinSequence().getLast().getMappingName(), fieldName, joinElement.getMappingName(), fieldName));
                }
            }
            var join = fRef.createConditionJoinFor(newSequence, targetOrPrevious, requiresLeftJoin);
            if (!newSequence.isEmpty() || addAllJoinsToJoinSet) joinSet.add(join);
            aliasSet.add(join.getJoinAlias());
            return newSequence.cloneAdd(join.getJoinAlias().getAlias());
        }

        if (keyToUse != null) {
            var aliasJoinSequence = newSequence.clone();
            if (newSequence.isEmpty()) aliasJoinSequence = aliasJoinSequence.cloneAdd(previousContext == null || previousContext.getCurrentJoinSequence().isEmpty() ? previous : previousContext.getCurrentJoinSequence().getLast());
            aliasJoinSequence = aliasJoinSequence.cloneAdd(keyToUse);

            var join = createJoinOnExplicitPathFor(fRef, keyToUse, aliasJoinSequence, target, requiresLeftJoin);
            if (!newSequence.isEmpty() || addAllJoinsToJoinSet)  joinSet.add(join);
            aliasSet.add(join.getJoinAlias());
            newSequence = newSequence.cloneAdd(join.getJoinAlias().getAlias());
        }

        if (fRef.hasTableCondition() && relation.hasRelation()) {
            var previousTableWithAlias = newSequence.getSecondLast() == null && previousContext != null ? previousContext.getCurrentJoinSequence().render() : newSequence.render(newSequence.getSecondLast());
            this.conditionList.add(fRef.getTableCondition().formatToString(List.of(previousTableWithAlias, newSequence.render())));
        }

        return newSequence;
    }

    private JoinElement getJoinElement(String aliasPrefix, JoinListSequence sequence) {
        if (useTableWithoutAliasInFirstStep) {
            return sequence.getFirst();
        }
        return getAlias(aliasPrefix, sequence, false);
    }

    private Alias getAlias(String aliasPrefix, JoinListSequence sequence, boolean isLeft) {
        var aliasWrapper = new AliasWrapper(new Alias(aliasPrefix, sequence, isLeft), referenceObjectField, processedSchema);
        aliasSet.add(aliasWrapper);
        return aliasWrapper.getAlias();
    }

    /**
    /**
     * @return A join statement based on a key reference using path
     */

    public SQLJoinStatement createJoinOnExplicitPathFor(FieldReference fRef, JOOQMapping keyOverride, JoinListSequence joinSequence, JOOQMapping tableNameBackup, boolean isNullable) {

        var targetTable = fRef.hasTable() ? fRef.getTable() : tableNameBackup;
        var prefix = joinSequence.getSecondLast().getCodeName().startsWith("_") ? joinSequence.getSecondLast().getMappingName() : joinSequence.getSecondLast().getCodeName();
        var alias = new AliasWrapper(getAlias(prefix + "_" + keyOverride.getCodeName(), joinSequence, isNullable), referenceObjectField, targetTable.equals(referenceTable), processedSchema);

        return new SQLJoinStatement(
                joinSequence,
                targetTable,
                alias,
                List.of(),
                isNullable
        );
    }

    private JoinListSequence makeJoinSequence(JOOQMapping mapping) {
        var aliasOrTable = getJoinElement(mapping.getCodeName(), JoinListSequence.of(mapping));
        return JoinListSequence.of(aliasOrTable);
    }

    public CodeBlock renderQuerySource(JOOQMapping localTable) {
        return currentJoinSequence.render(localTable == null ? getReferenceTable() : localTable);
    }

    private static JOOQMapping inferNextTable(JOOQMapping referenceTable, JOOQMapping referenceKey, JOOQMapping previousTable, JOOQMapping nextTable) {
        if (referenceTable != null) {
            return referenceTable;
        }

        // Infer based on the key provided.
        if (referenceKey != null) {
            var keyName = referenceKey.getMappingName();
            var sourceTable = getKeySourceTable(keyName).map(JOOQMapping::fromTable).orElse(null);
            var targetTable = getKeyTargetTable(keyName).map(JOOQMapping::fromTable).orElse(null);

            // Self reference key.
            if (Objects.equals(sourceTable, targetTable) && Objects.equals(sourceTable, previousTable) && hasSelfRelation(previousTable.getMappingName())) {
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
}
