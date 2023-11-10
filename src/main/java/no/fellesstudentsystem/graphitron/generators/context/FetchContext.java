package no.fellesstudentsystem.graphitron.generators.context;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.definitions.fields.FieldReference;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQTableMapping;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLAlias;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLJoinStatement;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.mappings.ReferenceHelpers.usesReverseReference;
import static no.fellesstudentsystem.graphitron.mappings.TableReflection.*;

/**
 * A helper class to handle traversal of nested types in queries. Since such queries will require nested layers of rows,
 * this class can generate the next "iteration" of itself for handling deeper layers.
 */
public class FetchContext {
    private final FetchContext previousContext;
    private final ObjectField referenceObjectField;
    private final ObjectDefinition referenceObject, previousTableObject, localObject;
    private final String currentJoinSequence;

    private final LinkedHashSet<SQLJoinStatement> joinSet;
    private final LinkedHashSet<SQLAlias> aliasSet;
    private final LinkedHashSet<String> conditionSet;

    private final String graphPath, snakeCasedGraphPath;
    private final int recCounter;
    private final boolean hasJoinedAlready;
    private boolean shouldUseOptional;
    private boolean shouldUseEnhancedNullOnAllNullCheck = false;

    private boolean hasKeyReference = false;
    private final ProcessedSchema processedSchema;

    /**
     * @param referenceObjectField The referring field that contains an object.
     * @param pastJoinSequence The current sequence of joins that must prepended.
     * @param previousTableObject The last object that was joined on in some previous iteration.
     * @param joinSet List of joins that must be declared outside this recursion.
     * @param pastGraphPath The path in the GraphQL schema so far.
     * @param recCounter Counter that limits recursion depth to the max value of integers.
     */
    private FetchContext(
            ProcessedSchema processedSchema,
            ObjectField referenceObjectField,
            String pastJoinSequence,
            ObjectDefinition localObject,
            ObjectDefinition previousTableObject,
            LinkedHashSet<SQLJoinStatement> joinSet,
            LinkedHashSet<SQLAlias> aliasSet,
            LinkedHashSet<String> conditionSet,
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
        hasJoinedAlready = recCounter == 0 && (!joinSet.isEmpty() || !aliasSet.isEmpty());
        referenceObject = processedSchema.getObjectOrConnectionNode(referenceObjectField);
        this.joinSet = joinSet;
        this.aliasSet = aliasSet;
        this.conditionSet = conditionSet;
        this.localObject = localObject;

        var targetObjectForID = previousTableObject != null ? previousTableObject : localObject;
        var tempPreviousTableObject = targetObjectForID != null && targetObjectForID.hasTable()
                ? targetObjectForID
                : processedSchema.getPreviousTableObjectForObject(targetObjectForID);
        if (pastJoinSequence.isEmpty() && tempPreviousTableObject != null) {
            if (referenceObjectField.hasFieldReferences()) {
                hasKeyReference = allFieldReferencesUseIDReferences(tempPreviousTableObject, referenceObjectField.getFieldReferences());
            } else {
                hasKeyReference = usesReverseReference(tempPreviousTableObject, referenceObject.getTable());
            }
            tempPreviousTableObject = hasKeyReference ? referenceObject : tempPreviousTableObject;
        }

        this.referenceObjectField = referenceObjectField;
        this.previousTableObject = tempPreviousTableObject;
        graphPath = pastGraphPath + (pastGraphPath.isEmpty() ? "" : "/");
        snakeCasedGraphPath = graphPath
                .replaceAll("/\\z", "") //remove slash at end of string
                .replaceAll("/", "_");

        this.currentJoinSequence = iterateSourceMultipleSequences(pastJoinSequence);
        this.previousContext = previousContext;
        this.shouldUseOptional = shouldUseOptional;
    }

    private boolean allFieldReferencesUseIDReferences(ObjectDefinition localObject, List<FieldReference> fieldReferences) {
        boolean allFieldReferencesUsesIDReferences = true;

        for (int i = 0; i < fieldReferences.size(); i++) {
            var fieldReference = fieldReferences.get(i);
            var hasRelationCondition = fieldReference.hasTableCondition();
            var hasRelationKey = fieldReference.hasTableKey();
            var hasRelationConditionOrKey = hasRelationCondition || hasRelationKey;
            var referenceObjectTable = i == fieldReferences.size()-1 ? referenceObject.getTable() : fieldReference.getTable();

            if (i == 0) {
                allFieldReferencesUsesIDReferences = !hasRelationConditionOrKey && usesReverseReference(localObject, referenceObjectTable);
            } else {
                JOOQTableMapping sourceTable = fieldReferences.get(i - 1).getTable();
                allFieldReferencesUsesIDReferences = !hasRelationConditionOrKey && usesReverseReference(sourceTable, referenceObjectTable) && allFieldReferencesUsesIDReferences;
            }
        }
        return allFieldReferencesUsesIDReferences;
    }

    /**
     * @param referenceObjectField The referring field that contains an object.
     * @param localObject          Object of origin for this context.
     */
    public FetchContext(
            ProcessedSchema processedSchema,
            ObjectField referenceObjectField,
            ObjectDefinition localObject
    ) {
        this(
                processedSchema,
                referenceObjectField,
                "",
                localObject,
                null,
                new LinkedHashSet<>(),
                new LinkedHashSet<>(),
                new LinkedHashSet<>(),
                "",
                0,
                null,
                true
        );
    }

    /**
     * @return The table used as the target table in the previous context.
     */
    public ObjectDefinition getPreviousTableObject() {
        return previousTableObject;
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
     * @return The path to this context in the schema itself with slashes replaced by underscores.
     */
    public String getSnakeCasedGraphPath() {
        return snakeCasedGraphPath;
    }

    /**
     * Note: This method may be outdated.
     * @return Whether this context has any joins up to this point, or will have one here.
     */
    public boolean hasJoinedAlreadyOrWillJoin() {
        return hasJoinedAlready || referenceObjectField.hasFieldReferences();
    }

    /**
     * @return The referred object being processed in the current context.
     */
    public ObjectDefinition getReferenceObject() {
        return referenceObject;
    }

    /**
     * Find the table join path or alias where the columns are expected to be found.
     */
    public String getCurrentJoinSequence() {
        return currentJoinSequence;
    }

    /**
     * @return Set of all aliases created up to this point by this context or any contexts created from it.
     */
    public Set<SQLAlias> getAliasSet() {
        return aliasSet;
    }

    /**
     * @return Set of all conditions created up to this point by this context or any contexts created from it.
     */
    public Set<String> getConditionSet() {
        return conditionSet;
    }

    /**
     * @return Does this context use a reverse ID join to construct this layer.
     */
    public boolean hasKeyReference() {
        return hasKeyReference;
    }

    /**
     * @return The reference table which fields on this layer are taken from.
     */
    public JOOQTableMapping getReferenceTable() {
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
                currentJoinSequence,
                localObject,
                referenceObject.hasTable() ? referenceObject : previousTableObject,
                joinSet,
                aliasSet,
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
    public String iterateSourceMultipleSequences(List<FieldReference> references) {
        var currentSequence = getCurrentJoinSequence();
        var newJoinSequence = processFieldReferences(this.referenceObject, currentSequence, references);
        if (newJoinSequence != null && !newJoinSequence.isBlank()) {
            return newJoinSequence;
        }
        return currentSequence;
    }

    /**
     * Iterate the table sequence as if several context layers were traversed.
     * @return The new join sequence, with the provided join sequence extended by all references in this layer's reference field,
     * or other appropriate start points for a sequence.
     */
    public String iterateSourceMultipleSequences(String pastJoinSequence) {
        if (hasJoinedAlready || !referenceObject.hasTable()) {
            return pastJoinSequence;
        }

        if (referenceObjectField.hasFieldReferences()) {
            String currentJoinSequence = processFieldReferences(this.previousTableObject, pastJoinSequence, referenceObjectField.getFieldReferences());

            if (currentJoinSequence != null && !currentJoinSequence.isBlank()) {
                return currentJoinSequence;
            }
        }

        var table = referenceObject.getTable();
        var refTableName = table.getName();
        var refTableCode = table.getCodeName();
        var previousTable = this.previousTableObject != null ? this.previousTableObject.getTable() : null;
        var previousTableName = previousTable != null ? previousTable.getName() : refTableName;
        var hasDirectJoin = hasDirectJoin(table, previousTableName);

        if (pastJoinSequence.isEmpty()) {
            return hasDirectJoin ? createMetodCallString(previousTableName, refTableCode) : refTableName;
        }

        if (hasDirectJoin) {
            return createMetodCallString(pastJoinSequence, refTableCode);
        }
        return pastJoinSequence;
    }

    /**
     * Iterate through the provided references and create a new join sequence.
     * @return The new join sequence, with the provided join sequence extended by all the references provided.
     */
    private String processFieldReferences(ObjectDefinition fromObject, String joinSequence, List<FieldReference> references) {
        var referenceTable = getReferenceTable();
        var previousTable = fromObject != null ? fromObject.getTable() : null;


        String currentJoinSequence = getCurrentJoinSequence();
        for (FieldReference fRef : references) {
            var table = fRef.hasTable() ? fRef.getTable() : referenceTable;
            var previousTableName = previousTable != null ? previousTable.getName() : referenceTable.getName();

            if (!table.getName().equals(previousTableName)) {
                currentJoinSequence = resolveNextSequence(
                        fRef,
                        table,
                        joinSequence == null || joinSequence.isEmpty() ? previousTableName : joinSequence,
                        previousTableName
                );
                joinSequence = currentJoinSequence;
                previousTable = table;
            }
        }
        return currentJoinSequence;
    }

    private String resolveNextSequence(FieldReference fRef, JOOQTableMapping table, String joinSequence, String previousTableName) {
        var snakeCasedGraphPath = getSnakeCasedGraphPath();
        var tableName = table.getName();
        var referencePath = (snakeCasedGraphPath.isBlank() ? tableName : snakeCasedGraphPath);

        var canImplicitJoin = fRef.hasTableKey() || hasDirectJoin(table, previousTableName);
        if (fRef.hasTableCondition()) {
            if (canImplicitJoin) {
                this.conditionSet.add(
                        ".and(" + fRef.getTableCondition().formatToString(List.of(CodeBlock.of(previousTableName), CodeBlock.of(tableName))) + ")"
                );
            } else {
                var join = fRef.createConditionJoinFor(
                        referencePath,
                        previousTableName,
                        joinSequence,
                        tableName,
                        referenceObjectField.getFieldType().isNonNullable()
                );
                joinSet.add(join);
                return join.getJoinAlias();
            }
        }

        if (canImplicitJoin) {
            var refTable = fRef.getTable();
            var refKey = fRef.getTableKey();

            var relationTableSource = !refTable.getCodeName().isEmpty() ? refTable.getCodeName() : table.getCodeName();
            var hasNaturalImplicitJoin = searchTableForMethodWithName(previousTableName, relationTableSource).isPresent();
            Optional<String> joinReference = refKey.isEmpty() || hasNaturalImplicitJoin ? Optional.empty() : searchTableForKeyMethodName(previousTableName, refKey);
            if (hasNaturalImplicitJoin || joinReference.isPresent()) {
                var reference = joinReference.orElse(relationTableSource);
                if (joinSequence == null || joinSequence.isEmpty()) {
                    return !refTable.getName().isEmpty() ? refTable.getName() : tableName;
                } else {
                    return joinSequence + "." + reference + "()";
                }
            }

            var join = fRef.createJoinOnKeyFor(
                    referencePath,
                    previousTableName,
                    joinSequence,
                    table.getName(),
                    referenceObjectField.getFieldType().isNonNullable()
            );
            joinSet.add(join);
            return join.getJoinAlias();
        }
        return joinSequence;
    }

    /**
     * @return Can these tables be joined with an implicit join?
     */
    private static boolean hasDirectJoin(JOOQTableMapping referenceTable, String previousTableName) {
        var refTableName = referenceTable.getName();
        var refTableCode = referenceTable.getCodeName();
        return !previousTableName.isEmpty()
                && !previousTableName.equals(refTableName)
                && searchTableForMethodWithName(previousTableName, refTableCode).isPresent();
    }

    private static String createMetodCallString(String className, String methodName) {
        return className + "." + methodName + "()";
    }
}
