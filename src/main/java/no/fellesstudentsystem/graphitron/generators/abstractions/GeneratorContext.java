package no.fellesstudentsystem.graphitron.generators.abstractions;

import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLAlias;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLJoinStatement;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQTableMapping;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;

import java.util.ArrayList;
import java.util.List;

import static no.fellesstudentsystem.graphitron.mappings.ReferenceHelpers.findReferencedObjectDefinition;
import static no.fellesstudentsystem.graphitron.mappings.ReferenceHelpers.usesIDReference;
import static no.fellesstudentsystem.graphitron.mappings.TableReflection.*;

public class GeneratorContext {
    private final GeneratorContext previousContext;
    private final AbstractField referenceObjectField;
    private final ObjectDefinition referenceObject;
    private final String currentJoinSequence;
    private final ObjectDefinition previousTableObject;

    private ArrayList<SQLJoinStatement> joinList;
    private ArrayList<SQLAlias> aliasList;
    private ArrayList<String> conditionList;

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
    private GeneratorContext(
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
            GeneratorContext previousContext
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
            var hasRelation = referenceObjectField.hasFieldReference();
            var hasRelationCondition = hasRelation && referenceObjectField.getFieldReference().hasTableCondition();
            var hasRelationKey = hasRelation && referenceObjectField.getFieldReference().hasTableKey();
            var hasRelationConditionOrKey = hasRelationCondition || hasRelationKey;
            hasKeyReference = !hasRelationConditionOrKey && usesIDReference(localObject, referenceObject);

            targetObjectForID = hasKeyReference ? referenceObject : localObject;
        }

        this.referenceObjectField = referenceObjectField;
        this.previousTableObject = targetObjectForID;
        graphPath = pastGraphPath + (pastGraphPath.isEmpty() ? "" : "/");

        this.currentJoinSequence = iterateSourceSequence(pastJoinSequence);
        this.previousContext = previousContext;
    }

    /**
     * @param referenceObjectField The referring field that contains an object.
     * @param localObject          Object of origin for this context.
     */
    public GeneratorContext(
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

    public ObjectDefinition getPreviousTableObject() {
        return previousTableObject;
    }

    public List<SQLJoinStatement> getJoinList() {
        return joinList;
    }

    public String getGraphPath() {
        return graphPath;
    }

    public String getSnakeCasedGraphPath() {
        return getGraphPath()
                .replaceAll("/\\z", "") //remove slash at end of string
                .replaceAll("/", "_");
    }

    public boolean hasJoinedAlreadyOrWillJoin() {
        return hasJoinedAlready || referenceObjectField.hasFieldReference();
    }

    public ObjectDefinition getReferenceObject() {
        return referenceObject;
    }

    /**
     * Find the table join path or alias where the columns are expected to be found.
     */
    public String getCurrentJoinSequence() {
        return currentJoinSequence;
    }

    public List<SQLAlias> getAliasList() {
        return aliasList;
    }

    public List<String> getConditionList() {
        return conditionList;
    }

    public boolean hasKeyReference() {
        return hasKeyReference;
    }

    public JOOQTableMapping getReferenceTable() {
        return referenceObject.hasTable() ? referenceObject.getTable() : previousTableObject.getTable();
    }

    public AbstractField getReferenceObjectField() {
        return referenceObjectField;
    }

    public boolean shouldUseEnhancedNullOnAllNullCheck() {
        return shouldUseEnhancedNullOnAllNullCheck;
    }

    public void setParentContextShouldUseEnhancedNullOnAllNullCheck() {
        previousContext.shouldUseEnhancedNullOnAllNullCheck = true;
    }

    public GeneratorContext nextContext(AbstractField referenceObjectField) {
        return new GeneratorContext(
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

    private String iterateSourceSequence(String pastJoinSequence) {
        if (hasJoinedAlready || !referenceObject.hasTable()) {
            return pastJoinSequence;
        }

        var refTable = referenceObject.getTable();
        var refTableName = refTable.getName();
        var refTableCode = refTable.getCodeName();
        var previousTable = this.previousTableObject != null ? this.previousTableObject.getTable() : null;
        var previousTableName = previousTable != null ? previousTable.getName() : refTableName;
        var hasNaturalImplicitJoin = !previousTableName.isEmpty()
                && !previousTableName.equals(refTableName)
                && tableHasMethod(previousTableName, refTableCode);

        if (referenceObjectField.hasFieldReference()) {
            var fRef = referenceObjectField.getFieldReference();
            var table = fRef.hasTable() ? fRef.getTable() : refTable;
            var hasImplicitJoin = fRef.hasTableKey() || hasNaturalImplicitJoin;

            if (!table.getName().equals(previousTableName)) {
                if (fRef.hasTableCondition()) {
                    if (hasImplicitJoin) {
                        this.conditionList.add(
                                ".and(" + fRef.getTableCondition().formatToString(
                                        List.of(
                                                !pastJoinSequence.equals(currentJoinSequence) && !pastJoinSequence.isEmpty()
                                                        ? pastJoinSequence
                                                        : previousTableName,
                                                currentJoinSequence
                                        )
                                ) + ")"
                        );
                    } else {
                        var join = fRef.createConditionJoinFor(
                                referenceObjectField,
                                previousTableName,
                                pastJoinSequence,
                                table.getName()
                        );
                        joinList.add(join);
                        return join.getJoinAlias();
                    }
                } else {
                    if (hasImplicitJoin) {
                        var alias = fRef.createAliasFor(
                                getSnakeCasedGraphPath().isBlank() ? referenceObjectField.getName() : getSnakeCasedGraphPath(),
                                previousTableName,
                                pastJoinSequence,
                                table.getCodeName()
                        );
                        if (alias != null) {
                            aliasList.add(alias);
                            return alias.getName();
                        } else {
                            var join = fRef.createJoinOnKeyFor(
                                    referenceObjectField,
                                    previousTableName,
                                    pastJoinSequence,
                                    table.getName()
                            );
                            joinList.add(join);
                            return join.getJoinAlias();
                        }
                    }
                }
            }

            refTableName = table.getName();
            refTableCode = table.getCodeName();
        }

        if (pastJoinSequence.isEmpty()) {
            return hasNaturalImplicitJoin ? previousTableName + "." + refTableCode + "()" : refTableName;
        }

        if (hasNaturalImplicitJoin) {
            return pastJoinSequence + "." + refTableCode + "()";
        }
        return pastJoinSequence;
    }
}
