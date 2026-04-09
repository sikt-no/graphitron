package no.sikt.graphitron.validation;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.FieldSpecification;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.definitions.objects.AbstractObjectDefinition;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessage;
import static no.sikt.graphitron.validation.ValidationHandler.throwIfErrors;
import static no.sikt.graphql.directives.GenerationDirective.SPLIT_QUERY;

/**
 * Validates that the schema has no unresolved cycles in input or output types.
 * Must run before other validators to prevent infinite loops during validation.
 */
class CycleValidator extends AbstractSchemaValidator {

    CycleValidator(ProcessedSchema schema, List<ObjectField> allFields) {
        super(schema, allFields);
    }

    @Override
    void validate() {
        validateInputCycles();
        validateOutputTypeCycles();
    }

    private void validateInputCycles() {
        var alreadyExplored = new HashSet<String>();
        schema.getInputTypes().values()
                .forEach(input ->
                        detectCycles(input.getName(), new LinkedHashMap<>(), alreadyExplored, "Input type cycles are not allowed."));
    }

    private void validateOutputTypeCycles() {
        var alreadyExplored = new HashSet<String>();
        var errorSuffix = String.format("A resolver is required to break the cycle, for example by adding @%s to (one of) the field(s).",
                SPLIT_QUERY.getName());

        schema.getObjects().values()
                .forEach(type -> detectCycles(type.getName(), new LinkedHashMap<>(), alreadyExplored, errorSuffix));
        schema.getInterfaces().values()
                .forEach(type -> detectCycles(type.getName(), new LinkedHashMap<>(), alreadyExplored, errorSuffix));
    }

    /**
     * Detects cycles in input types, and cycles in output types not broken by a resolver.
     *
     * @param typeName        the name of the type, interface or input currently being visited
     * @param currentPath     ordered map of types on the current DFS path, each mapped to the field paths leading from it
     * @param alreadyExplored types whose entire subgraph has already been checked
     * @param errorSuffix     appended after the cycle path in the error message, e.g. a workaround suggestion
     */
    private void detectCycles(String typeName, LinkedHashMap<String, List<String>> currentPath, Set<String> alreadyExplored, String errorSuffix) {
        if (alreadyExplored.contains(typeName)) return;

        if (currentPath.containsKey(typeName)) {
            addErrorMessage("Cycle detected: %s. %s", formatCyclePath(currentPath, typeName), errorSuffix);
            return;
        }

        getNextPaths(typeName)
                .forEach((fieldTargetType, fieldPaths) -> {
                    currentPath.put(typeName, fieldPaths);
                    detectCycles(fieldTargetType, currentPath, alreadyExplored, errorSuffix);
                });
        currentPath.remove(typeName);
        alreadyExplored.add(typeName);
    }

    /** Returns a type's non-resolver fields, grouped by target type name. */
    private Map<String, List<String>> getNextPaths(String typeName) {
        if (schema.isInputType(typeName)) {
            return schema.getInputType(typeName)
                    .getFields().stream()
                    .filter(GenerationTarget::isGenerated)
                    .filter(field -> schema.isInputType(field.getTypeName()))
                    .collect(Collectors.groupingBy(
                            FieldSpecification::getTypeName,
                            LinkedHashMap::new,
                            Collectors.mapping(GenerationField::formatPath, Collectors.toList())
                            )
                    );
        }
        var fields = schema.isObject(typeName)
                ? schema.getObject(typeName).getFields()
                : Optional.ofNullable(schema.getInterface(typeName))
                    .map(AbstractObjectDefinition::getFields)
                    .orElse(List.of());

        return fields.stream()
                .filter(GenerationTarget::isGenerated)
                .filter(field -> !field.createsDataFetcher())
                .filter(field -> !schema.isScalar(field))
                .flatMap(field ->
                        resolveFieldTargets(field)
                                .map(targetTypeName -> Map.entry(field.formatPath(), targetTypeName)))
                .collect(Collectors.groupingBy(
                        Map.Entry::getValue,
                        LinkedHashMap::new,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
    }

    /** Resolves the type names a field can point to, expanding interfaces to their implementations and unions to their members. */
    private Stream<String> resolveFieldTargets(GenerationField field) {
        if (schema.isObject(field) || schema.isInputType(field)) {
            return Stream.of(field.getTypeName());
        } else if (schema.isInterface(field)) {
            return Stream.concat(
                    Stream.of(field.getTypeName()), // Include interface name in order to check for interface cycles
                    schema.getImplementationsForInterface(field.getTypeName()).stream()
                            .map(AbstractObjectDefinition::getName)
            );
        } else if (schema.isUnion(field)) {
            return schema.getUnionSubTypes(field.getTypeName()).stream()
                    .map(AbstractObjectDefinition::getName);
        }
        return Stream.empty();
    }

    /**
     * Formats the cycle portion of a path as a human-readable string, e.g. {@code 'TypeA.b' -> 'TypeB.a' -> 'TypeA'}.
     * @param fullPath Ordered map of types on the current DFS path, each mapped to the field paths leading from it
     * @param typeNameInCycle Name of a type/interface/input type which is in the cycle.
     * @return Path as a human-readable string
     */
    private static String formatCyclePath(LinkedHashMap<String, List<String>> fullPath, String typeNameInCycle) {
        var cyclePath = new LinkedHashMap<>(fullPath);
        for (var key : fullPath.keySet()) {
            if (key.equals(typeNameInCycle)) break;
            cyclePath.remove(key);
        }

        var path = cyclePath.values()
                .stream()
                .map(fieldPaths -> String.join("/", fieldPaths))
                .reduce("",
                        (accumulated, step) -> accumulated + step + " -> ");

        // Append the repeated type name to close the cycle
        return path + String.format("'%s'", cyclePath.keySet().iterator().next());
    }
}
