package no.fellesstudentsystem.graphitron.mappings;

import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQTableMapping;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;

import static no.fellesstudentsystem.graphitron.mappings.TableReflection.*;

public class ReferenceHelpers {
    /**
     * This method attempts to deduce whether there exists a connection between the two objects in the database,
     * using method naming conventions and java reflection.
     *
     * @param sourceObject The object that is joined from, the left side of the join expression.
     * @param referenceObjectTable The object that is joined with, the right side of the join expression.
     * @return Is there a foreign key reference from the source object to the reference object?
     */
    public static boolean usesIDReference(ObjectDefinition sourceObject, JOOQTableMapping referenceObjectTable) {
        if (sourceObject == null || sourceObject.isRoot() || !sourceObject.hasTable()) {
            return false;
        }
        JOOQTableMapping sourceObjectTable = sourceObject.getTable();
        return usesIDReference(sourceObjectTable, referenceObjectTable);
    }

    public static boolean usesIDReference(JOOQTableMapping sourceObjectTable, JOOQTableMapping referenceObjectTable) {
        var localTableName = sourceObjectTable.getName();
        var refTableName = referenceObjectTable.getName();

        if (!tableExists(localTableName) || !tableExists(refTableName)) {
            return false;
        }

        // Denne er suspekt. Så vidt jeg kan forstå så forhindrer den self-joins, noe som er et gyldig use case.
        if (localTableName.equals(refTableName)) {
            return false;
        }

        if (!(hasSingleReference(localTableName, refTableName) || hasSingleReference(refTableName, localTableName))) {
            throw new IllegalStateException("Can not automatically infer join of '" + localTableName +  "' and '" + refTableName + "'.");
        }

        var implicitJoinIsPossible = tableHasMethod(localTableName, referenceObjectTable.getCodeName());
        return !implicitJoinIsPossible;
    }

    public static ObjectDefinition findReferencedObjectDefinition(AbstractField referenceField, ProcessedSchema processedSchema) {
        if (processedSchema.isConnectionObject(referenceField)) {
            var objectDefinition = processedSchema.getConnectionObject(referenceField);
            return processedSchema.getObject(objectDefinition.getNodeType());
        } else {
            return processedSchema.getObject(referenceField);
        }
    }
}
