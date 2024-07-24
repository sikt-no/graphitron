package no.fellesstudentsystem.graphitron.generators.context;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.fields.containedtypes.FieldReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQMapping;
import no.fellesstudentsystem.graphitron.definitions.mapping.TableRelation;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLJoinStatement;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.*;

import static no.fellesstudentsystem.graphitron.mappings.TableReflection.*;

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
    private final LinkedHashMap<FetchContext, List<CodeBlock>> conditionSet;
    private final String graphPath;
    private final int recCounter;
    private JOOQMapping keyForMapping;
    private boolean shouldUseOptional;

    private boolean requireAlias;
    private List<? extends GenerationField> multisetObjectFields;
    private boolean fromMultiset = false;
    private FetchContext currentMultisetContext = null;
    private boolean shouldUseEnhancedNullOnAllNullCheck = false;

    private final ProcessedSchema processedSchema;

    /**
     * @param referenceObjectField The referring field that contains an object.
     * @param pastJoinSequence The current sequence of joins that must prepended.
     * @param previousObject The last object that was joined on in some previous iteration.
     * @param joinSet List of joins that must be declared outside this recursion.
     * @param conditionSet List of conditions that must be declared outside this recursion.
     * @param pastGraphPath The path in the GraphQL schema so far.
     * @param recCounter Counter that limits recursion depth to the max value of integers.
     */
    private FetchContext(
            ProcessedSchema processedSchema,
            GenerationField referenceObjectField,
            JoinListSequence pastJoinSequence,
            RecordObjectSpecification<?> previousObject,
            LinkedHashSet<SQLJoinStatement> joinSet,
            LinkedHashMap<FetchContext, List<CodeBlock>> conditionSet,
            String pastGraphPath,
            int recCounter,
            FetchContext previousContext,
            boolean shouldUseOptional,
            boolean requireAlias,
            List<? extends GenerationField> multisetObjectFields,
            FetchContext currentMultisetContext
    ) {
        if (recCounter == Integer.MAX_VALUE - 1) {
            throw new RuntimeException("Recursion depth has reached the integer max value.");
        }
        this.recCounter = recCounter;
        this.processedSchema = processedSchema;
        referenceObject = processedSchema.getObjectOrConnectionNode(referenceObjectField);
        this.joinSet = joinSet;
        this.conditionSet = conditionSet;

        this.referenceObjectField = referenceObjectField;
        this.previousTableObject = processedSchema.getPreviousTableObjectForObject(previousObject);
        graphPath = pastGraphPath + (pastGraphPath.isEmpty() ? "" : "/");

        this.previousContext = previousContext;
        this.shouldUseOptional = shouldUseOptional;
        this.requireAlias = requireAlias;
        this.multisetObjectFields = multisetObjectFields;
        this.currentMultisetContext = currentMultisetContext;
        currentJoinSequence = iterateJoinSequence(pastJoinSequence);
    }

    /**
     * @param referenceObjectField The referring field that contains an object.
     * @param previousObject       Object of origin for this context.
     */
    public FetchContext(
            ProcessedSchema processedSchema,
            ObjectField referenceObjectField,
            RecordObjectSpecification<?> previousObject
    ) {
        this(
                processedSchema,
                referenceObjectField,
                new JoinListSequence(),
                previousObject,
                new LinkedHashSet<>(),
                new LinkedHashMap<>(),
                "",
                0,
                null,
                referenceObjectField.getOrderField().isEmpty(), //do not use optional in combination with orderBy
                true,
                new ArrayList<>(),
                null
        );
    }

    /**
     * @return Set of joins created by this and any other context created from this one.
     */
    public Set<SQLJoinStatement> getJoinSet() {
        return joinSet;
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
     * @return Map of all conditions created up to this point by this context or any contexts created from it.
     */
    public LinkedHashMap<FetchContext, List<CodeBlock>> getConditionSet() {
        return conditionSet;
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
        return previousTableObject != null ? previousTableObject.getTable() : null;
    }

    /**
     * @return The reference table which fields on this layer are taken from.
     */
    public JOOQMapping getReferenceOrPreviousTable() {
        return (referenceObject != null && referenceObject.hasTable()) ? referenceObject.getTable() : previousTableObject != null ? previousTableObject.getTable() : null;
    }

    /**
     * @return The reference field which points to the reference object for this layer.
     */
    public GenerationField getReferenceObjectField() {
        return referenceObjectField;
    }

    public JOOQMapping getKeyForMapping() {
        return keyForMapping;
    }

    public List<? extends GenerationField> getMultisetObjectFields() {
        return multisetObjectFields;
    }

    public void setMultisetObjectFields(List<? extends GenerationField> multisetObjectFields) {
        this.multisetObjectFields = multisetObjectFields;
    }

    public boolean isFromMultiset() {
        return fromMultiset;
    }

    public boolean getShouldUseOptional() {
        return shouldUseOptional;
    }

    public FetchContext withShouldUseOptional(boolean shouldUseOptional) {
        this.shouldUseOptional = shouldUseOptional;
        return this;
    }

    public boolean requiresAlias() {
        return requireAlias;
    }

    public FetchContext toMultisetContext() {
        this.shouldUseOptional = false;
        this.fromMultiset = true;
        this.requireAlias = false;
        this.currentMultisetContext = this;
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
        newJoinListSequence.add(getReferenceTable());
        return new FetchContext(
                processedSchema,
                referenceObjectField,
                fromMultiset ? newJoinListSequence : currentJoinSequence.clone(),
                referenceObject,
                joinSet,
                conditionSet,
                graphPath + referenceObjectField.getName(),
                recCounter + 1,
                this,
                shouldUseOptional,
                requireAlias,
                new ArrayList<>(),
                currentMultisetContext
        );
    }

    /**
     * Iterate the table sequence as if several context layers were traversed with the provided references.
     * @return The new join sequence, with the provided join sequence extended by all references in this layer's reference field,
     * or other appropriate start points for a sequence.
     */
    public JoinListSequence iterateJoinSequenceFor(GenerationField field) {
        var currentSequence = getCurrentJoinSequence();
        if (!field.hasFieldReferences()) {
            if(fromMultiset) {
                var newSequence = new JoinListSequence();
                newSequence.add(getReferenceTable());
                return newSequence;
            }
            return currentSequence;
        }

        var newJoinSequence = processFieldReferences(
                getCurrentJoinSequence(),
                getReferenceTable(),
                field.getFieldReferences(),
                field.isNullable() && !fieldIsNullable(getReferenceOrPreviousTable().getMappingName(), field.getUpperCaseName()).orElse(true),
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
        var refTable = getReferenceTable();
        if (refTable == null && !referenceObjectField.hasFieldReferences()) {
            return previousSequence;
        }

        var referencesFromField = referenceObjectField.getFieldReferences();
        var nullable = referenceObjectField.isNullable();
        var newJoinSequence = processFieldReferences(previousSequence, getReferenceOrPreviousTable(), referencesFromField, nullable, true);
        var updatedSequence = !newJoinSequence.isEmpty() ? newJoinSequence : previousSequence;

        if (refTable == null) {
            return updatedSequence;
        }

        var lastTable = !updatedSequence.isEmpty() ? updatedSequence.getLast().getTable() : getPreviousTable(); // Wrong if key was reverse.
        if (Objects.equals(lastTable, refTable) && (!referencesFromField.isEmpty() || processedSchema.isInterface(referenceObjectField.getContainerTypeName()))) {
            return !updatedSequence.isEmpty() ? updatedSequence : JoinListSequence.of(refTable);
        }

        var finalSequence = resolveNextSequenceWithPath(
                new FieldReference(refTable),
                new TableRelation(lastTable, refTable),
                updatedSequence,
                nullable
        ); // Add fake reference to the reference table so that the last step is also executed if no table or key is specified.
        return !finalSequence.isEmpty() ? finalSequence : JoinListSequence.of(refTable);
    }

    /**
     * Iterate through the provided references and create a new join sequence.
     * @return The new join sequence, with the provided join sequence extended by all the references provided.
     */
    private JoinListSequence processFieldReferences(JoinListSequence joinSequence, JOOQMapping refTable, List<FieldReference> references, boolean requiresLeftJoin, boolean checkLastRef) {
        requiresLeftJoin = true;
        var previousTable = joinSequence.isEmpty() ? getPreviousTable() : joinSequence.getLast().getTable();

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
            joinSequence = resolveNextSequenceWithPath(references.get(i), relations.get(i), joinSequence, requiresLeftJoin);
        }
        return joinSequence;
    }

    private boolean isMultisetContext() {
        return (getReferenceObjectField().isIterableWrapped()
                && !getReferenceObjectField().isResolver()
                && !((ObjectField) getReferenceObjectField()).hasInputFields()
                && getPreviousTable() != null
                && processedSchema.isObject(getReferenceObjectField())
        );
    }

    private JoinListSequence resolveNextSequenceWithPath(FieldReference fRef, TableRelation relation, JoinListSequence joinSequence, boolean requiresLeftJoin) {
        requiresLeftJoin = true;
        var previous = relation.getFrom();
        var target = relation.getToTable();
        if (previous == null) {
            return JoinListSequence.of(target);
        }
        var targetOrPrevious = target != null ? target : previous;

        var newSequence = joinSequence.isEmpty() ? JoinListSequence.of(previous) : joinSequence;
        var keyToUse = fRef.hasKey() || fRef.hasTableCondition()
                ? fRef.getKey()
                : findImplicitKey(previous.getMappingName(), targetOrPrevious.getMappingName()).map(JOOQMapping::fromKey).orElse(null);

        keyForMapping = keyToUse;

        if (keyToUse != null && !keyToUse.getTable().equals(target)) {
            keyToUse = keyToUse.getInverseKey();
        }

        if (fRef.hasTableCondition() && keyToUse == null) {
            var join = fRef.createConditionJoinFor(newSequence, targetOrPrevious, requiresLeftJoin);
            joinSet.add(join);
            return newSequence.cloneAdd(join.getJoinAlias());
        }

        if (keyToUse != null) {
            var aliasJoinSequence = newSequence.clone();
            aliasJoinSequence = aliasJoinSequence.cloneAdd(keyToUse);

            var join = fRef.createJoinOnExplicitPathFor(keyToUse, aliasJoinSequence, target, requiresLeftJoin);
            joinSet.add(join);
            newSequence = newSequence.cloneAdd(join.getJoinAlias());
        }

        if (fRef.hasTableCondition() && relation.hasRelation()) {
            var conditionString = fRef.getTableCondition().formatToString(List.of(newSequence.render(newSequence.getSecondLast()), newSequence.render()));
            var listOfConditions = this.conditionSet.get(currentMultisetContext);
            if(listOfConditions == null) {
                listOfConditions = new ArrayList<>();
            }
            listOfConditions.add(conditionString);
            this.conditionSet.put(currentMultisetContext, listOfConditions);
        }

        return newSequence;
    }

    private JoinListSequence resolveNextSequence(FieldReference fRef, TableRelation relation, JoinListSequence joinSequence, boolean requiresLeftJoin, boolean hasFutureJoin) {
        var previous = relation.getFrom();
        var multisetSkipJoin = isMultisetContext();
        var target = relation.getToTable();
        if (previous == null) {
            return JoinListSequence.of(target);
        }
        var targetOrPrevious = target != null ? target : previous;

        var iteratedSequence = relation.hasRelation() ? relation.inferJoinStep(joinSequence) : joinSequence;
        var newSequence = iteratedSequence.isEmpty() ? JoinListSequence.of(previous) : iteratedSequence;
        var keyToUse = fRef.hasKey()
                ? fRef.getKey()
                : findImplicitKey(previous.getMappingName(), targetOrPrevious.getMappingName()).map(JOOQMapping::fromKey).orElse(null);
        keyForMapping = keyToUse;
        // If we lack this table from a previous join or there is no key, we can not make a key join with implicit steps.
        var lastIsNotJoin = joinSequence.size() > 1 && !joinSequence.getLast().clearsPreviousSequence();
        if (!multisetSkipJoin && fRef.hasTableCondition() && (keyToUse == null || lastIsNotJoin)) {
            var join = fRef.createConditionJoinFor(newSequence, targetOrPrevious, requiresLeftJoin);
            joinSet.add(join);
            return newSequence.cloneAdd(join.getJoinAlias());
        }

        if (!multisetSkipJoin && (relation.isReverse() || hasFutureJoin) && keyToUse != null) { // Tried to make it (relation.isReverse() || requiresLeftJoin), but that didn't work out. Theoretically, including it should be the most correct.
            var join = fRef.createJoinOnKeyFor(keyToUse, newSequence, targetOrPrevious, requiresLeftJoin);
            joinSet.add(join);
            var alias = join.getJoinAlias();
            if (newSequence.getLast().equals(alias.getTable()) && !hasSelfRelation(alias.getTable().getMappingName())) {
                newSequence.removeLast();
            }
            newSequence = newSequence.cloneAdd(alias);
        }

        if (fRef.hasTableCondition() && relation.hasRelation()) {
            var conditionString = fRef.getTableCondition().formatToString(List.of(newSequence.render(newSequence.getSecondLast()), newSequence.render()));
            var listOfConditions = this.conditionSet.get(currentMultisetContext);
            if(listOfConditions == null) {
                listOfConditions = new ArrayList<>();
            }
            listOfConditions.add(conditionString);
            this.conditionSet.put(currentMultisetContext, listOfConditions);
        }

        return newSequence;
    }

    public Optional<CodeBlock> generateMultisetAliasFromJoinSequence(JoinListSequence joinSequence) {
        var codeBlock = CodeBlock.builder();
        if(joinSequence.size() <= 1) {
            return Optional.empty();
        }
        codeBlock.add(joinSequence.get(0).getMappingName());
        for(var i = 1; i < joinSequence.size(); i++) {
            codeBlock.add(".$L()", joinSequence.get(i).getTable().getCodeName());
        }
        return Optional.of(codeBlock.build());
    }

    private boolean hasExplicitJoin(FieldReference fRef, TableRelation relation) {
        var previous = relation.getFrom();
        var target = relation.getToTable();

        if (previous == null || fRef.hasTableCondition()) { // TODO: Does not account for conditions that do not result in joins.
            return false;
        }

        var hasKey = fRef.hasKey() || findImplicitKey(previous.getMappingName(), (target != null ? target : previous).getMappingName()).isPresent();
        return relation.isReverse() && hasKey;
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
