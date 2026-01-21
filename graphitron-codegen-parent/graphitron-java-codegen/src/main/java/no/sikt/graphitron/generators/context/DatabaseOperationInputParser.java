package no.sikt.graphitron.generators.context;

import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualInputField;
import no.sikt.graphitron.definitions.fields.containedtypes.MutationType;
import no.sikt.graphitron.definitions.helpers.InputComponent;
import no.sikt.graphitron.definitions.helpers.InputComponents;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.configuration.GeneratorConfig.useJdbcBatchingForInserts;
import static no.sikt.graphitron.configuration.Recursion.recursionCheck;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asListedRecordNameIf;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.inputPrefix;
import static no.sikt.graphitron.mappings.TableReflection.getPrimaryKeyForTable;

/**
 * Parses field arguments into categorized input components for database operations.
 * Unifies the parsing logic for both conditions (WHERE clauses) and set values (VALUES clause in INSERT/UPDATE).
 *
 * <p>The parser traverses nested input types and categorizes each field as either:
 * <ul>
 *   <li>A condition input - used in WHERE clauses for filtering</li>
 *   <li>A set value input - used in VALUES/SET clauses for mutations</li>
 * </ul>
 *
 * <p>The result is an {@link InputComponents} object containing flat components, iterable tuples,
 * and condition groupings used to build database queries.
 */
public class DatabaseOperationInputParser {
    private final ProcessedSchema processedSchema;
    private final ObjectField referenceField;
    private final InputComponents parsedInputComponents;

    private DatabaseOperationInputParser(ProcessedSchema processedSchema, ObjectField target) {
        this.processedSchema = processedSchema;
        this.referenceField = target;
        this.parsedInputComponents = parse();
    }

    /**
     * Parses the target field's arguments into categorized input components.
     *
     * @param processedSchema the schema containing type definitions
     * @param target          the field whose arguments will be parsed
     * @return parsed input components ready for code generation
     */
    public static InputComponents parse(ProcessedSchema processedSchema, ObjectField target) {
        return new DatabaseOperationInputParser(processedSchema, target).getParsedInputComponents();
    }

    /**
     * Parses the target field's arguments into categorized input components.
     *
     * <p>The parsing process:
     * <ol>
     *   <li>Creates initial components from the field's non-reserved arguments</li>
     *   <li>Traverses nested input types, flattening them into individual components</li>
     *   <li>Groups iterable fields into tuples</li>
     *   <li>Filters and organizes conditions by their declaring fields</li>
     * </ol>
     *
     * @return parsed input components ready for code generation
     */
    private InputComponents parse() {
        var conditionTracker = new InputConditionTracker(referenceField);
        var result = traverseInput(createInitialBuffer(), conditionTracker);

        var inputTuples = groupIterableComponents(result.iterablePaths, result.flatComponents);
        inputTuples.stream()
                .map(InputComponents.InputTuple::components)
                .forEach(result.flatComponents::removeAll);

        filterDeclaredConditions(conditionTracker.descendantsByInputFieldsWithCondition, inputTuples);

        return new InputComponents(
                result.flatComponents,
                inputTuples,
                conditionTracker.descendantsByInputFieldsWithCondition);
    }

    /**
     * Creates the initial buffer of input components from the reference field's arguments.
     * Each argument is categorized as either a set value (for mutations) or a condition (for filtering).
     */
    private LinkedList<InputComponent> createInitialBuffer() {
        return referenceField.getNonReservedArguments().stream()
                .map(it ->
                        new InputComponent(
                                it,
                                inputPrefix(inferFieldNamingConvention(it)),
                                processedSchema.hasRecord(it),
                                referenceField.hasOverridingCondition(),
                                isFilterInput(it)
                        )
                )
                .collect(Collectors.toCollection(LinkedList::new));
    }

    /**
     * Traverses nested input types, collecting flat components and tracking iterable paths.
     *
     * <p>For each component in the buffer:
     * <ul>
     *   <li>Scalar fields are added directly to flat components</li>
     *   <li>Input types are expanded, with their inner fields added to the buffer</li>
     *   <li>Iterable fields have their paths recorded for tuple grouping</li>
     *   <li>Fields with declared conditions are tracked for proper grouping</li>
     * </ul>
     */
    private TraversalResult traverseInput(LinkedList<InputComponent> inputBuffer, InputConditionTracker conditionTracker) {
        var iterablePaths = new ArrayList<String>();
        var flatComponents = new ArrayList<InputComponent>();
        var recursion = 0;

        while (!inputBuffer.isEmpty()) {
            recursionCheck(recursion);
            var component = inputBuffer.poll();
            var isFilterInput = component.isFilterInput();
            var field = component.getInput();

            if (conditionTracker.hasAncestorWithCondition(field)) {
                continue;
            }

            if (!processedSchema.isInputType(field)) {
                flatComponents.add(component);
                if (isFilterInput) {
                    conditionTracker.maybeRecordDescendantToInputConditionField(component);
                }
                continue;
            }

            if (field.isIterableWrapped()) {
                iterablePaths.add(component.getNameWithPathString());
            }

            if (field.hasCondition() && !processedSchema.hasRecord(field)) {
                conditionTracker.trackInputConditionField(field);
                inputBuffer.addFirst(component);
            }

            var innerInputComponents = getInnerFieldsForInputType(field).stream()
                    .map(f -> component.iterate(f, isFilterInput(f)))
                    .toList();
            inputBuffer.addAll(0, innerInputComponents);

            if (isFilterInput && processedSchema.hasRecord(field)) {
                flatComponents.add(component);
                conditionTracker.maybeRecordDescendantToInputConditionField(component);
            }
            recursion++;
        }

        return new TraversalResult(iterablePaths, flatComponents);
    }

    /**
     * Groups flat components by their iterable ancestor paths into tuples.
     * Components under the same iterable path are grouped together for batch processing.
     */
    private List<InputComponents.InputTuple> groupIterableComponents(List<String> iterablePaths, List<InputComponent> flatInputs) {
        return iterablePaths.stream()
                .map(path -> new InputComponents.InputTuple(
                        path,
                        flatInputs.stream()
                                .filter(c -> c.getNamePath().startsWith(path))
                                .toList()))
                .toList();
    }

    /**
     * Filters declared conditions to remove duplicates and components already in tuples.
     *
     * <p>For record conditions, removes any that are ancestors of other record conditions
     * (keeps only the most specific). For non-record conditions, removes any that are
     * already part of condition tuples.
     */
    private void filterDeclaredConditions(
            LinkedHashMap<GenerationField, List<InputComponent>> declaredConditionsByField,
            List<InputComponents.InputTuple> conditionTuples) {
        for (var entry : declaredConditionsByField.entrySet()) {
            var recordConditions = entry.getValue().stream()
                    .filter(InputComponent::hasRecord)
                    .toList();
            var nonRecordConditions = entry.getValue().stream()
                    .filter(c -> !c.hasRecord())
                    .collect(Collectors.toList());

            var filteredRecordConditions = removeAncestorConditions(recordConditions);

            conditionTuples.stream()
                    .map(InputComponents.InputTuple::components)
                    .forEach(nonRecordConditions::removeAll);

            declaredConditionsByField.replace(
                    entry.getKey(),
                    Stream.concat(filteredRecordConditions.stream(), nonRecordConditions.stream())
                            .toList());
        }
    }

    private List<InputComponent> removeAncestorConditions(List<InputComponent> recordConditions) {
        return recordConditions.stream()
                .filter(c1 -> recordConditions.stream()
                        .noneMatch(c2 -> !c1.getNamePath().equals(c2.getNamePath())
                                && c1.getNamePath().startsWith(c2.getNamePath())))
                .toList();
    }

    /**
     * Determines if the input field should be treated as a filter condition
     * rather than a set value (for mutations)
     * Not fully implemented for UPDATE and UPSERT.
     */
    private boolean isFilterInput(InputField inputField) {
        if (!referenceField.hasMutationType() || isForFetchingAfterMutationWithJDBCBatching()) {
            return true;
        }
        if (referenceField.getMutationType().equals(MutationType.INSERT) && !useJdbcBatchingForInserts()) {
            return false;
        }
        // TODO: identify if inputField is set value or condition for UPDATE/UPSERT
        return true;
    }

    /**
     * Checks if this operation is fetching records after a mutation using JDBC batching.
     * In this case, only primary key fields are used as conditions.
     */
    private boolean isForFetchingAfterMutationWithJDBCBatching() {
        return referenceField.hasMutationType()
                && !processedSchema.isDeleteMutationWithReturning(referenceField)
                && !processedSchema.isInsertMutationWithReturning(referenceField);
    }

    private List<? extends InputField> getInnerFieldsForInputType(InputField inputField) {
        if (isForFetchingAfterMutationWithJDBCBatching() && processedSchema.isRecordType(inputField)) {
            // For queries fetching after mutations with JDBC batching
            return getPrimaryKeyForTable(processedSchema.getRecordType(inputField).getTable().getName())
                    .map(pk -> pk.getFields().stream()
                            .map(col -> new VirtualInputField(col.getName(), inputField.getContainerTypeName()))
                            .toList())
                    .orElse(List.of());
        }
        return processedSchema.getInputType(inputField).getFields();
    }

    /**
     * Infers the naming convention for a field based on whether it has a record type.
     * Record types use a "listed record" naming format when iterable.
     */
    private String inferFieldNamingConvention(GenerationField field) {
        if (processedSchema.hasRecord(field)) {
            return asListedRecordNameIf(field.getName(), field.isIterableWrapped());
        }
        return field.getName();
    }

    /**
     * Result of traversing input types, containing paths to iterable fields
     * and the flattened list of input components.
     */
    private record TraversalResult(List<String> iterablePaths, List<InputComponent> flatComponents) {
    }

    /**
     * Tracks input fields that have declared conditions and their descendant components.
     * Used to properly group conditions by their declaring ancestor field.
     */
    private static class InputConditionTracker {

        private final Set<GenerationField> inputFieldsWithDeclaredCondition = new HashSet<>();

        private final LinkedHashMap<GenerationField, List<InputComponent>> descendantsByInputFieldsWithCondition = new LinkedHashMap<>();

        InputConditionTracker(ObjectField referenceField) {
            if (referenceField.hasCondition()) {
                descendantsByInputFieldsWithCondition.put(referenceField, new ArrayList<>());
                inputFieldsWithDeclaredCondition.add(referenceField);
            }
        }

        boolean hasAncestorWithCondition(InputField field) {
            if (inputFieldsWithDeclaredCondition.contains(field)) {
                inputFieldsWithDeclaredCondition.remove(field);
                return true;
            }
            return false;
        }

        void trackInputConditionField(InputField field) {
            inputFieldsWithDeclaredCondition.add(field);
            descendantsByInputFieldsWithCondition.put(field, new ArrayList<>());
        }

        void maybeRecordDescendantToInputConditionField(InputComponent component) {
            inputFieldsWithDeclaredCondition.forEach(ancestor ->
                    descendantsByInputFieldsWithCondition.get(ancestor).add(component));
        }


    }

    private InputComponents getParsedInputComponents() {
        return parsedInputComponents;
    }
}