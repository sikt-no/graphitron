package no.sikt.graphitron.validation;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.fields.containedtypes.FieldReference;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphql.schema.ProcessedSchema;
import org.apache.commons.text.similarity.LevenshteinDistance;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.mappings.TableReflection.*;
import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessageAndThrow;
import static no.sikt.graphql.directives.GenerationDirective.MULTITABLE_REFERENCE;
import static no.sikt.graphql.directives.GenerationDirective.REFERENCE;

/**
 * Base class for schema validators, providing shared state and utility methods.
 */
abstract class AbstractSchemaValidator {
    protected final ProcessedSchema schema;
    protected final List<ObjectField> allFields;

    protected AbstractSchemaValidator(ProcessedSchema schema, List<ObjectField> allFields) {
        this.schema = schema;
        this.allFields = allFields;
    }

    abstract void validate();

    /**
     * Finds strings similar to the target using Levenshtein distance, including the distance values.
     * @param target The string to match against
     * @param candidates Stream of candidate strings
     * @param maxDistance Maximum allowed distance for suggestions
     * @return Map of similar strings to their Levenshtein distance within the minimum distance threshold (up to maxDistance)
     */
    protected static Map<String, Integer> findSimilarStringsWithDistance(String target, Stream<String> candidates, int maxDistance) {
        var levenshtein = new LevenshteinDistance(12);

        var distances = candidates.collect(Collectors.toMap(
                Function.identity(),
                candidate -> levenshtein.apply(target, candidate)));

        var distanceThreshold = distances.values().stream()
                .filter(it -> it > -1 && it <= maxDistance)
                .min(Integer::compare)
                .orElse(0);

        return distances.entrySet().stream()
                .filter(it -> it.getValue() > -1 && it.getValue() <= distanceThreshold)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    protected void validateReferencePath(GenerationField field, String sourceTable, String targetTable) {
        if (sourceTable.equals(targetTable) && !field.createsDataFetcher() && !field.hasFieldReferences() && !field.hasNodeID()) {
            return;
        }
        for (FieldReference fieldReference : field.getFieldReferences()) {
            if (fieldReference.hasTableCondition() && !fieldReference.hasKey()) {
                return;
            } else if (fieldReference.hasKey()) {
                String nextTable;
                String keyName = fieldReference.getKey().getName();
                var keyTarget = getKeyTargetTableJavaName(keyName).orElse("");
                if (!keyTarget.equals(sourceTable)) {
                    nextTable = keyTarget;
                } else {
                    nextTable = getKeySourceTableJavaName(keyName).orElse("");
                }
                if (nextTable.equals(targetTable)) {
                    return;
                } else {
                    sourceTable = nextTable;
                }
            } else if (fieldReference.hasTable()) {
                String nextTable = fieldReference.getTable().getName();
                addErrorMessageAndThrowIfNoImplicitPath(field, sourceTable, nextTable);
                if (nextTable.equals(targetTable)) {
                    return;
                } else {
                    sourceTable = nextTable;
                }
            }
        }

        // Because scalar fields have the whole reference path in the reference directive, validation has completed at this point
        if (schema.isScalar(field)) {
            return;
        }

        addErrorMessageAndThrowIfNoImplicitPath(field, sourceTable, targetTable);
    }

    protected void addErrorMessageAndThrowIfNoImplicitPath(GenerationField field, String leftTable, String rightTable) {
        var possibleKeys = getNumberOfForeignKeysBetweenTables(leftTable, rightTable);
        if (possibleKeys == 1) return;

        var isMultitableField = field instanceof VirtualSourceField;

        addErrorMessageAndThrow("Error on field \"%s\" in type \"%s\": " +
                        "%s found between tables \"%s\" and \"%s\"%s. Please specify path with the @%s directive.",
                isMultitableField ? ((VirtualSourceField) field).getOriginalFieldName() : field.getName(),
                field.getContainerTypeName(),
                possibleKeys == 0 ? "No foreign key" : "Multiple foreign keys",
                leftTable,
                rightTable,
                isMultitableField ? String.format(" in reference path for type '%s'", field.getTypeName()) : "",
                isMultitableField ? MULTITABLE_REFERENCE.getName() : REFERENCE.getName()
        );
    }
}
