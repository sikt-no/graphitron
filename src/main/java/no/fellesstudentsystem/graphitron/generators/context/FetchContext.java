package no.fellesstudentsystem.graphitron.generators.context;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.fields.FieldReference;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQMapping;
import no.fellesstudentsystem.graphitron.definitions.mapping.TableRelation;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLJoinStatement;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.mappings.TableReflection.*;

/**
 * A helper class to handle traversal of nested types in queries. Since such queries will require nested layers of rows,
 * this class can generate the next "iteration" of itself for handling deeper layers.
 */
public class FetchContext {
    private final FetchContext previousContext;
    private final ObjectField referenceObjectField;
    private final ObjectDefinition referenceObject, previousTableObject;
    private final JoinListSequence currentJoinSequence;

    private final LinkedHashSet<SQLJoinStatement> joinSet;
    private final LinkedHashSet<CodeBlock> conditionSet;

    private final String graphPath;
    private final int recCounter;
    private boolean shouldUseOptional;
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
            ObjectField referenceObjectField,
            JoinListSequence pastJoinSequence,
            ObjectDefinition previousObject,
            LinkedHashSet<SQLJoinStatement> joinSet,
            LinkedHashSet<CodeBlock> conditionSet,
            String pastGraphPath,
            int recCounter,
            FetchContext previousContext,
            boolean shouldUseOptional
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
        currentJoinSequence = iterateJoinSequence(pastJoinSequence);
    }

    /**
     * @param referenceObjectField The referring field that contains an object.
     * @param previousObject       Object of origin for this context.
     */
    public FetchContext(
            ProcessedSchema processedSchema,
            ObjectField referenceObjectField,
            ObjectDefinition previousObject
    ) {
        this(
                processedSchema,
                referenceObjectField,
                new JoinListSequence(),
                previousObject,
                new LinkedHashSet<>(),
                new LinkedHashSet<>(),
                "",
                0,
                null,
                true
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
    public ObjectDefinition getReferenceObject() {
        return referenceObject;
    }

    /**
     * @return The table join path or alias where the columns are expected to be found.
     */
    public JoinListSequence getCurrentJoinSequence() {
        return currentJoinSequence;
    }

    /**
     * @return Set of all conditions created up to this point by this context or any contexts created from it.
     */
    public Set<CodeBlock> getConditionSet() {
        return conditionSet;
    }

    /**
     * @return The reference table the reference field points to.
     */
    public JOOQMapping getReferenceTable() {
        return referenceObject.getTable();
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
        return referenceObject.hasTable() ? referenceObject.getTable() : previousTableObject.getTable();
    }

    /**
     * @return The reference field which points to the reference object for this layer.
     */
    public ObjectField getReferenceObjectField() {
        return referenceObjectField;
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
        previousContext.shouldUseEnhancedNullOnAllNullCheck = true;
    }

    /**
     * @return The next iteration of this context based on the provided reference field.
     */
    public FetchContext nextContext(ObjectField referenceObjectField) {
        return new FetchContext(
                processedSchema,
                referenceObjectField,
                currentJoinSequence.clone(),
                referenceObject,
                joinSet,
                conditionSet,
                graphPath + referenceObjectField.getName(),
                recCounter + 1,
                this,
                shouldUseOptional
        );
    }

    /**
     * Iterate the table sequence as if several context layers were traversed with the provided references.
     * @return The new join sequence, with the provided join sequence extended by all references in this layer's reference field,
     * or other appropriate start points for a sequence.
     */
    public JoinListSequence iterateJoinSequenceFor(AbstractField field) {
        var currentSequence = getCurrentJoinSequence();
        if (!field.hasFieldReferences()) {
            return currentSequence;
        }

        var newJoinSequence = processFieldReferences(
                getCurrentJoinSequence(),
                getReferenceTable(),
                field.getFieldReferences(),
                field.isNullable() && !fieldIsNullable(getReferenceOrPreviousTable().getMappingName(), field.getUpperCaseName()).orElse(true)
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
        var newJoinSequence = processFieldReferences(previousSequence, getReferenceOrPreviousTable(), referencesFromField, nullable);
        var updatedSequence = !newJoinSequence.isEmpty() ? newJoinSequence : previousSequence;

        if (refTable == null) {
            return updatedSequence;
        }

        var lastTable = !updatedSequence.isEmpty() ? updatedSequence.getLast().getTable() : getPreviousTable(); // Wrong if key was reverse.
        if (Objects.equals(lastTable, refTable)) {
            return !updatedSequence.isEmpty() ? updatedSequence : JoinListSequence.of(refTable);
        }

        var finalSequence = resolveNextSequence(
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
    private JoinListSequence processFieldReferences(JoinListSequence joinSequence, JOOQMapping refTable, List<FieldReference> references, boolean requiresLeftJoin) {
        var previousTable = joinSequence.isEmpty() ? getPreviousTable() : joinSequence.getLast().getTable();
        for (var fRef : references) {
            var table = inferNextTable(fRef.getTable(), fRef.getKey(), previousTable, refTable);
            joinSequence = resolveNextSequence(fRef, new TableRelation(previousTable, table, fRef.getKey()), joinSequence, requiresLeftJoin);
            previousTable = table;
        }

        return joinSequence;
    }

    private JoinListSequence resolveNextSequence(FieldReference fRef, TableRelation relation, JoinListSequence joinSequence, boolean requiresLeftJoin) {
        var previous = relation.getFrom();

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

        // If we lack this table from a previous join or there is no key, we can not make a key join with implicit steps.
        var lastIsJoin = !joinSequence.isEmpty() && joinSequence.getLast().clearsPreviousSequence();
        if (fRef.hasTableCondition() && (keyToUse == null || !lastIsJoin && joinSequence.size() > 1)) {
            var join = fRef.createConditionJoinFor(newSequence, targetOrPrevious, requiresLeftJoin);
            joinSet.add(join);
            return newSequence.cloneAdd(join.getJoinAlias());
        }

        if (relation.isReverse() && keyToUse != null) { // Tried to make it (relation.isReverse() || requiresLeftJoin), but that didn't work out. Theoretically, including it should be the most correct.
            var join = fRef.createJoinOnKeyFor(keyToUse, newSequence, targetOrPrevious, requiresLeftJoin);
            joinSet.add(join);
            var alias = join.getJoinAlias();
            if (newSequence.getLast().equals(alias.getTable()) && !hasSelfRelation(alias.getTable().getMappingName())) {
                newSequence.removeLast();
            }
            newSequence = newSequence.cloneAdd(alias);
        }

        if (fRef.hasTableCondition() && relation.hasRelation()) {
            this.conditionSet.add(CodeBlock.of(".and($L)\n", fRef.getTableCondition().formatToString(List.of(newSequence.render(newSequence.getSecondLast()), newSequence.render()))));
        }

        return newSequence;
    }

    public CodeBlock renderQuerySource(JOOQMapping localTable) {
        return currentJoinSequence.render(localTable == null ? getReferenceTable() : localTable);
    }

    private static JOOQMapping inferNextTable(JOOQMapping referenceTable, JOOQMapping referenceKey, JOOQMapping previousTable, JOOQMapping nextTable) {
        if (referenceTable != null) {
            if (referenceKey != null) {
                // Tiny hack for reverse references.
                referenceKey.setTable(referenceTable);
            }
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
                // Tiny hack for reverse references.
                referenceKey.setTable(targetTable);
                return targetTable;
            }

            // Key is the "wrong" way.
            if (Objects.equals(targetTable, previousTable)) {
                // Tiny hack for reverse references.
                referenceKey.setTable(sourceTable);
                return sourceTable;
            }
        }

        return nextTable;
    }
}
