package no.fellesstudentsystem.graphitron.generators.context;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.fields.FieldReference;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQTableMapping;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLAlias;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLJoinStatement;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import static no.fellesstudentsystem.graphitron.mappings.ReferenceHelpers.findReferencedObjectDefinition;
import static no.fellesstudentsystem.graphitron.mappings.ReferenceHelpers.usesIDReference;
import static no.fellesstudentsystem.graphitron.mappings.TableReflection.tableHasMethod;

/**
 * A helper class to handle traversal of nested types in queries. Since such queries will require nested layers of rows,
 * this class can generate the next "iteration" of itself for handling deeper layers.
 */
public class FetchContext {
    private final FetchContext previousContext;
    private final AbstractField referenceObjectField;
    private final ObjectDefinition referenceObject;
    private final String currentJoinSequence;
    private final ObjectDefinition previousTableObject;

    private final ArrayList<SQLJoinStatement> joinList;
    private final ArrayList<SQLAlias> aliasList;
    private final ArrayList<String> conditionList;

    private final String graphPath;
    private final int recCounter;
    private final boolean hasJoinedAlready;
    private boolean shouldUseEnhancedNullOnAllNullCheck = false;

    private boolean hasKeyReference = false;
    private final ProcessedSchema processedSchema;

    /**
     * @param referenceObjectField The referring field that contains an object.
     * @param pastJoinSequence The current sequence of joins that must prepended.
     * @param previousTableObject The last object that was joined on in some previous iteration.
     * @param joinList List of joins that must be declared outside this recursion.
     * @param pastGraphPath The path in the GraphQL schema so far.
     * @param recCounter Counter that limits recursion depth to the max value of integers.
     */
    private FetchContext(
            ProcessedSchema processedSchema,
            AbstractField referenceObjectField,
            String pastJoinSequence,
            ObjectDefinition localObject,
            ObjectDefinition previousTableObject,
            ArrayList<SQLJoinStatement> joinList,
            ArrayList<SQLAlias> aliasList,
            ArrayList<String> conditionList,
            String pastGraphPath,
            int recCounter,
            FetchContext previousContext
    ) {
        if (recCounter == Integer.MAX_VALUE - 1) {
            throw new RuntimeException("Recursion depth has reached the integer max value.");
        }
        this.recCounter = recCounter;
        this.processedSchema = processedSchema;
        hasJoinedAlready = recCounter == 0 && (!joinList.isEmpty() || !aliasList.isEmpty());
        referenceObject = findReferencedObjectDefinition(referenceObjectField, processedSchema);
        this.joinList = joinList;
        this.aliasList = aliasList;
        this.conditionList = conditionList;

        var targetObjectForID = previousTableObject != null ? previousTableObject : referenceObject;
        if (pastJoinSequence.isEmpty() && localObject != null) {

            if (referenceObjectField.hasFieldReferences()) {
                hasKeyReference = allFieldReferencesUseIDReferences(localObject, referenceObjectField.getFieldReferences());
            } else {
                hasKeyReference = usesIDReference(localObject, referenceObject.getTable());
            }
            targetObjectForID = hasKeyReference ? referenceObject : localObject;
        }

        this.referenceObjectField = referenceObjectField;
        this.previousTableObject = targetObjectForID;
        graphPath = pastGraphPath + (pastGraphPath.isEmpty() ? "" : "/");

        this.currentJoinSequence = iterateSourceMultipleSequences(pastJoinSequence);
        this.previousContext = previousContext;
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
                allFieldReferencesUsesIDReferences = !hasRelationConditionOrKey && usesIDReference(localObject, referenceObjectTable);
            } else {
                JOOQTableMapping sourceTable = fieldReferences.get(i - 1).getTable();
                allFieldReferencesUsesIDReferences = !hasRelationConditionOrKey && usesIDReference(sourceTable, referenceObjectTable) && allFieldReferencesUsesIDReferences;
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
            AbstractField referenceObjectField,
            ObjectDefinition localObject
    ) {
        this(
                processedSchema,
                referenceObjectField,
                "",
                localObject,
                null,
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                "",
                0,
                null
        );
    }

    /**
     * @return The table used as the target table in the previous context.
     */
    public ObjectDefinition getPreviousTableObject() {
        return previousTableObject;
    }

    /**
     * @return List of joins created by this and any other context created from this one.
     */
    public List<SQLJoinStatement> getJoinList() {
        return joinList;
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
        return getGraphPath()
                .replaceAll("/\\z", "") //remove slash at end of string
                .replaceAll("/", "_");
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
     * @return List of all aliases created up to this point by this context or any contexts created from it.
     */
    public List<SQLAlias> getAliasList() {
        return aliasList;
    }

    /**
     * @return List of all conditions created up to this point by this context or any contexts created from it.
     */
    public List<String> getConditionList() {
        return conditionList;
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
    public AbstractField getReferenceObjectField() {
        return referenceObjectField;
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
    public FetchContext nextContext(AbstractField referenceObjectField) {
        return new FetchContext(
                processedSchema,
                referenceObjectField,
                currentJoinSequence,
                null,
                referenceObject.hasTable() ? referenceObject : previousTableObject,
                joinList,
                aliasList,
                conditionList,
                graphPath + referenceObjectField.getName(),
                recCounter + 1,
                this
        );
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
            String currentJoinSequence = processFieldReferences(pastJoinSequence);

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
     * Iterate through this layer's reference field's references and create a new join sequence.
     * @return The new join sequence, with the provided join sequence extended by all references in this layer's reference field.
     */
    private String processFieldReferences(String previousJoinSequence) {
        String currentJoinSequence = getCurrentJoinSequence();
        var referenceTable = referenceObject.getTable();
        var previousTable = this.previousTableObject != null ? this.previousTableObject.getTable() : null;

        for (FieldReference fRef : referenceObjectField.getFieldReferences()) {
            var table = fRef.hasTable() ? fRef.getTable() : referenceTable;
            var previousTableName = previousTable != null ? previousTable.getName() : referenceTable.getName();

            if (!table.getName().equals(previousTableName)) {
                currentJoinSequence = previousJoinSequence;

                if (fRef.hasTableCondition()) {
                    if (fRef.hasTableKey() || hasDirectJoin(table, previousTableName)) {
                        this.conditionList.add(
                                ".and(" + fRef.getTableCondition().formatToString(List.of(CodeBlock.of(previousTableName), CodeBlock.of(table.getName()))) + ")"
                        );
                    } else {
                        currentJoinSequence = processConditionJoinReference(fRef, currentJoinSequence, table, table.getCodeName(), previousJoinSequence, previousTableName);
                    }
                } else {
                    currentJoinSequence = processReference(fRef, table, previousJoinSequence, previousTableName);
                }
                previousJoinSequence = currentJoinSequence;
                previousTable = table;
            }
        }
        return currentJoinSequence;
    }

    /**
     * Process a single field reference.
     * @return A join sequence created by applying this reference to the previous sequence.
     */
    private String processReference(FieldReference fRef, JOOQTableMapping table, String previousJoinSequence, String previousTableName) {
        String currentJoinSequence;
        String snakeCasedGraphPath = getSnakeCasedGraphPath();
        String referenceName = (snakeCasedGraphPath.isBlank() ? table.getName() : snakeCasedGraphPath);
        String shortAliasName = createShortAliasName(referenceName, previousTableName);
        var alias = fRef.createAliasFor(
                referenceName,
                previousTableName,
                previousJoinSequence,
                table.getCodeName(),
                shortAliasName
        );
        if (alias != null) {
            aliasList.add(alias);
            currentJoinSequence = alias.getName();
        } else {
            var join = fRef.createJoinOnKeyFor(
                    referenceObjectField,
                    previousTableName,
                    previousJoinSequence,
                    table.getName(),
                    shortAliasName
            );
            joinList.add(join);
            currentJoinSequence = join.getJoinAlias();
        }
        return currentJoinSequence;
    }

    /**
     * Process this field reference as a join.
     * @return An alias for this join.
     */
    private String processConditionJoinReference(FieldReference fRef, String currentJoinSequence, JOOQTableMapping table, String refTableCode, String previousJoinSequence, String previousTableName) {
        var join = fRef.createConditionJoinFor(
                referenceObjectField,
                previousTableName,
                currentJoinSequence != null && currentJoinSequence.endsWith(refTableCode) ? currentJoinSequence : previousJoinSequence,
                table.getName(),
                createShortAliasName(referenceObjectField.getName(), previousTableName)
        );
        joinList.add(join);
        return join.getJoinAlias();
    }

    /**
     * @return Can these tables be joined with an implicit join?
     */
    private static boolean hasDirectJoin(JOOQTableMapping referenceTable, String previousTableName) {
        var refTableName = referenceTable.getName();
        var refTableCode = referenceTable.getCodeName();
        return !previousTableName.isEmpty()
                && !previousTableName.equals(refTableName)
                && tableHasMethod(previousTableName, refTableCode);
    }

    private static String createMetodCallString(String className, String methodName) {
        return className + "." + methodName + "()";
    }

    public String createShortAliasName(String referenceName, String previousJoinTable) {
        CRC32 crc32 = new CRC32();
        crc32.reset();
        crc32.update(referenceName.getBytes());
        return previousJoinTable + "_" + crc32.getValue() + "";
    }
}
