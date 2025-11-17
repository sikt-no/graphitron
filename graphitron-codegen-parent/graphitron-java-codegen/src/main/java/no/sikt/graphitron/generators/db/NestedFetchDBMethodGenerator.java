package no.sikt.graphitron.generators.db;


import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.mapping.Alias;
import no.sikt.graphitron.definitions.mapping.AliasWrapper;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.STATIC;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryMethodName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_NODE_STRATEGY;
import static no.sikt.graphitron.mappings.JavaPoetClassName.NODE_ID_STRATEGY;
import static no.sikt.graphitron.mappings.JavaPoetClassName.SELECT_FIELD;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Abstract base class for generators that create nested helper methods for database query generation.
 * <p>
 * This class provides the infrastructure and logic for generating private static helper methods that
 * extract SelectField row mapping logic for record types. Helper methods improve compilation performance
 * by reducing method complexity and enable reuse of mapping logic across different query contexts.
 * <p>
 * Subclasses (like {@link FetchMappedObjectDBMethodGenerator}) extend this to generate the main query
 * methods while inheriting all helper method generation capabilities and shared state management.
 *
 * <p><b>Helper Method Generation Rules:</b></p>
 * <ul>
 *   <li>Helper methods are generated for record types with tables, list fields, or fields with @reference</li>
 *   <li>Error types (marked with @error) never generate helpers</li>
 *   <li>Split queries receive the target table as a parameter</li>
 *   <li>Other fields receive base table parameters with alias declarations in the method body</li>
 *   <li>Only aliases actually referenced in the generated SELECT are declared</li>
 *   <li>Circular references are detected and prevented using visited type tracking</li>
 *   <li>Input parameters are omitted from split query helpers (applied at main method level)</li>
 * </ul>
 *
 * <p><b>Special Patterns:</b></p>
 * <ul>
 *   <li><b>Container Pattern</b> - At depth 0, non-table types with input arguments traverse directly
 *       to nested fields without generating a helper (e.g., FilmContainer wrapping Film query)</li>
 *   <li><b>Wrapper Types</b> - Non-table singular types without @reference recurse to children
 *       without generating helpers (e.g., LanguageWrapper.originalLanguage)</li>
 *   <li><b>Helper Naming</b> - Nested helpers use parent method name as prefix with depth indicators
 *       (e.g., queryForQuery_customer_d1_address). Uniqueness ensured with counters for duplicate paths.</li>
 * </ul>
 *
 * <p><b>State Management:</b></p>
 * This class maintains shared state ({@link MethodGenerationState}, {@link HelperGenerationContext})
 * that is accessible to subclasses during method generation to ensure consistent naming and proper
 * depth tracking for nested helper methods.
 *
 * @see FetchDBMethodGenerator
 * @see FetchMappedObjectDBMethodGenerator
 */
public abstract class NestedFetchDBMethodGenerator extends FetchDBMethodGenerator {

    /**
     * Container pattern detection only applies at depth 0.
     * At this depth, we're processing the root helper method's direct fields.
     */
    static final int CONTAINER_PATTERN_DEPTH = 0;

    /**
     * Global registry for tracking unique method definitions across all generated code.
     * Persists for the lifetime of the generator (never cleared).
     */
    static class GlobalMethodRegistry {
        private final Map<String, Integer> methodDefinitionCounters = new HashMap<>();
        private final Map<String, List<String>> baseNameToGeneratedNames = new HashMap<>();

        String makeUnique(String baseName) {
            var counter = methodDefinitionCounters.getOrDefault(baseName, 0);
            methodDefinitionCounters.put(baseName, counter + 1);
            var uniqueName = (counter == 0) ? baseName : baseName + "_" + counter;
            baseNameToGeneratedNames.computeIfAbsent(baseName, k -> new ArrayList<>()).add(uniqueName);
            return uniqueName;
        }
    }

    /**
     * State for a single top-level method and all its nested helpers.
     * Created once per top-level field, cleared when moving to next field.
     */
    static class MethodGenerationState {
        ObjectField parentField;
        final ObjectField rootField;
        final FetchContext rootFetchContext;

        MethodGenerationState(ObjectField rootField, FetchContext rootFetchContext) {
            this.rootField = rootField;
            this.rootFetchContext = rootFetchContext;
            this.parentField = rootField;
        }
    }

    /**
     * Context for generating a single helper method.
     * Created fresh for each helper, tracks method call occurrences and depth.
     */
    static class HelperGenerationContext {
        final String helperMethodName;
        final int depth;
        private final Map<String, Integer> methodCallCounters = new HashMap<>();

        HelperGenerationContext(String helperMethodName, int depth) {
            this.helperMethodName = helperMethodName;
            this.depth = depth;
        }

        String getNextCallName(String baseName) {
            var occurrence = methodCallCounters.getOrDefault(baseName, 0);
            methodCallCounters.put(baseName, occurrence + 1);
            return (occurrence == 0) ? baseName : baseName + "_" + occurrence;
        }
    }

    /**
     * Generates a depth-based method name to prevent collisions.
     * Format: [parentHelperMethodName]_d[depth]_[fieldName]
     * Example: queryForQuery_customer_d1_storeInfo_d2_staff
     */
    static String generateDepthBasedMethodName(String parentHelperMethodName, int depth, String fieldName) {
        return parentHelperMethodName + "_d" + depth + "_" + fieldName;
    }

    // Global state (persists across all generation)
    final GlobalMethodRegistry methodRegistry = new GlobalMethodRegistry();
    private static final Map<Pattern, Pattern> aliasPatternCache = new HashMap<>();

    // Per-method state (set for each top-level field)
    MethodGenerationState methodState = null;

    // Per-helper state (set for each helper being generated)
    HelperGenerationContext helperContext = null;

    public NestedFetchDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    String generateHelperMethodName(ObjectField target) {
        // Generate method name like: queryForQuery_outer
        // Pattern: [callingMethod]_[returnType]
        var callingMethodName = asQueryMethodName(target.getName(), localObject.getName());
        var returnTypeName = processedSchema.getRecordType(target).getName();
        return callingMethodName + "_" + uncapitalize(returnTypeName);
    }

    MethodSpec generateHelperMethod(ObjectField target) {
        methodState.parentField = target;

        var helperMethodName = generateHelperMethodName(target);
        // Create per-helper context (depth 0 for root-level helpers)
        var previousHelperContext = helperContext;
        helperContext = new HelperGenerationContext(helperMethodName, 0);

        // Use the shared root context and navigate from there
        // This maintains consistent alias names while allowing us to filter later
        var context = methodState.rootFetchContext;
        var refContext = target.isResolver() ? context.nextContext(target) : context;

        var returnType = processedSchema.getRecordType(target).getGraphClassName();
        var selectRowBlock = generateSelectRow(refContext);

        var methodBuilder = MethodSpec.methodBuilder(helperMethodName)
                .addModifiers(PRIVATE, STATIC)
                .returns(ParameterizedTypeName.get(SELECT_FIELD.className, returnType));

        var isReferenceResolverField = processedSchema.isReferenceResolverField(target);

        // Add input parameters from the original method only for non-split queries
        // Split query helper methods don't need input parameters as WHERE clauses are applied at the main level
        if (!isReferenceResolverField) {
            var parser = new InputParser(target, processedSchema);
            methodBuilder.addParameters(getMethodParametersWithOrderField(parser));
        }

        var allAliases = refContext.getAliasSet();
        var usedAliases = filterToUsedAliasesOnly(allAliases, selectRowBlock);

        // Declare service classes for aliases with table methods
        methodBuilder.addCode(declareAllServiceClassesInAliasSet(usedAliases));

        // Declare only the aliases actually used in this helper
        if (!usedAliases.isEmpty()) {
            methodBuilder.addCode(createAliasDeclarations(usedAliases));
        }

        methodState.parentField = null;
        helperContext = previousHelperContext;

        return methodBuilder
                .addStatement("return $L", selectRowBlock)
                .addParameterIf(GeneratorConfig.shouldMakeNodeStrategy(), NODE_ID_STRATEGY.className, VAR_NODE_STRATEGY)
                .addParameters(getContextParameters(target))
                .build();
    }

    List<MethodSpec> generateNestedHelperMethods(ObjectField parentField) {
        var parentHelperName = generateHelperMethodName(parentField);
        // For resolver fields (like splitQuery), we need to navigate to get the correct context
        // For non-resolver fields, rootFetchContext already represents the correct context
        var context = methodState.rootFetchContext;
        var parentContext = parentField.isResolver() ? context.nextContext(parentField) : context;
        // Start at depth 0 (root helper method level)
        return generateNestedHelperMethods(parentField, parentHelperName, parentContext, new HashSet<>(), 0);
    }

    private List<MethodSpec> generateNestedHelperMethods(ObjectField parentField, String parentHelperMethodName, FetchContext parentContext, Set<String> visitedTypes, int depth) {
        var nestedMethods = new ArrayList<MethodSpec>();

        var recordType = processedSchema.getRecordType(parentField);

        var currentTypeName = recordType.getName();
        visitedTypes.add(currentTypeName);

        for (var field : recordType.getFields()) {
            if (!(field instanceof ObjectField objectField) || shouldSkipField(objectField)) {
                continue;
            }

            // New visited set for each sibling allows same type through different paths
            // (e.g., minimumRating.language and maximumRating.language get separate helpers)
            var siblingVisitedTypes = new HashSet<>(visitedTypes);
            var nestedRecordType = processedSchema.getRecordType(objectField);

            if (processedSchema.isReferenceResolverField(objectField)) {
                continue;
            }

            // Check if this field should skip helper generation but continue traversing to children
            if (shouldSkipHelperAndTraverse(objectField, recordType, nestedRecordType, depth)) {
                nestedMethods.addAll(generateNestedHelperMethods(objectField, parentHelperMethodName, parentContext.nextContext(objectField), siblingVisitedTypes, depth));
                continue;
            }

            // Generate helper method for this field (at depth + 1 since it's nested under parent at depth)
            var nestedHelperMethod = generateNestedHelperMethodWithParentName(parentHelperMethodName, objectField, parentContext, depth + 1);

            nestedHelperMethod.ifPresent(method -> {
                nestedMethods.add(method);
                nestedMethods.addAll(generateNestedHelperMethods(objectField, method.name(), parentContext.nextContext(objectField), siblingVisitedTypes, depth + 1));
            });
        }

        return nestedMethods;
    }

    private Optional<MethodSpec> generateNestedHelperMethodWithParentName(String parentHelperMethodName, ObjectField nestedField, FetchContext parentContext, int depth) {
        var returnType = processedSchema.getRecordType(nestedField).getGraphClassName();
        var nestedRecordType = processedSchema.getRecordType(nestedField);
        var context = parentContext.nextContext(nestedField);

        // Generate method name like: queryForQuery_d1_customer_address ([parentHelperMethod]_d{depth}_[fieldName])
        // Depth is included to prevent collisions between staff.store (depth 2) and staff_store (depth 1)
        var baseName = generateDepthBasedMethodName(parentHelperMethodName, depth, nestedField.getName());
        // Ensure uniqueness by appending counter if base name already used
        var helperMethodName = methodRegistry.makeUnique(baseName);

        // Create per-helper context with current depth
        var previousHelperContext = helperContext;
        helperContext = new HelperGenerationContext(helperMethodName, depth);

        try {
            var methodBuilder = MethodSpec.methodBuilder(helperMethodName)
                    .addModifiers(PRIVATE, STATIC)
                    .returns(ParameterizedTypeName.get(SELECT_FIELD.className, returnType));

            if (shouldGenerateHelperMethod(nestedField, nestedRecordType)) {
                var selectRowBlock = generateSelectRow(context);

                var allAliases = context.getAliasSet();
                var usedAliases = filterToUsedAliasesOnly(allAliases, selectRowBlock);

                var tableMethodInputs = collectTableMethodInputNames(usedAliases);
                addTableMethodInputParameters(methodBuilder, tableMethodInputs, methodState.rootField);

                methodBuilder.addCode(declareAllServiceClassesInAliasSet(usedAliases));
                methodBuilder.addCodeIf(!usedAliases.isEmpty(),createAliasDeclarations(usedAliases));

                return Optional.of(
                        methodBuilder
                                .addStatement("return $L", selectRowBlock)
                                .addParameterIf(GeneratorConfig.shouldMakeNodeStrategy(), NODE_ID_STRATEGY.className, VAR_NODE_STRATEGY)
                                .addParameters(getContextParameters(nestedField))
                                .build());
            } else {
                // Field doesn't need a helper (wrapper types, error types, etc.)
                return Optional.empty();
            }
        } finally {
            helperContext = previousHelperContext;
        }
    }

    private boolean shouldSkipField(ObjectField field) {
        return field.isExplicitlyNotGenerated() ||
                !processedSchema.isRecordType(field) ||
                processedSchema.isUnion(field);
    }

    private boolean rootFieldHasInputTableArguments() {
        return methodState.rootField.getArguments().stream()
                .anyMatch(processedSchema::hasInputJOOQRecord);
    }

    /**
     * Determines if a field represents a structural wrapper where helper method generation should be skipped,
     * but traversal to children should continue.
     * <p>
     * Two patterns are handled:
     * <ul>
     *   <li><b>Input parameter container</b> (depth 0 only): A non-table type that wraps input parameters
     *       for a nested table field. Example: {@code type FilmContainer { films: Film }} where the Query
     *       has {@code input: FilmInput!}. Helper is skipped because input params bind directly to the
     *       nested Film table.</li>
     *   <li><b>Structural wrapper</b> (any depth): A non-table type with a single non-list field without
     *       {@code @reference}. Example: {@code type Wrapper { customer: Customer }} where Wrapper has no table.
     *       Helper is skipped because no table means no SELECT to generate.</li>
     * </ul>
     *
     * @param field The field being evaluated
     * @param parentType The parent type containing this field
     * @param nestedType The type this field references
     * @param depth Current recursion depth (0 = root helper level)
     * @return true if helper should be skipped and children should be traversed, false otherwise
     */
    private boolean shouldSkipHelperAndTraverse(ObjectField field, RecordObjectSpecification<?> parentType,
                                                RecordObjectSpecification<?> nestedType, int depth) {
        // Input parameter container at root (depth 0)
        if (depth == CONTAINER_PATTERN_DEPTH &&
                !parentType.hasTable() &&
                !field.isIterableWrapped() &&
                !field.hasFieldReferences() &&
                nestedType != null && nestedType.hasTable() &&
                rootFieldHasInputTableArguments()) {
            return true;
        }

        // Structural wrapper type (any depth)
        return (nestedType == null || !nestedType.hasTable()) &&
                !field.isIterableWrapped() &&
                !field.hasFieldReferences();
    }

    private boolean shouldGenerateHelperMethod(ObjectField field, RecordObjectSpecification<?> nestedRecordType) {
        if (processedSchema.isExceptionOrExceptionUnion(field)) {
            return false;
        }

        return nestedRecordType.hasTable() ||
                field.isIterableWrapped() ||
                field.hasFieldReferences();
    }

    private Set<String> collectTableMethodInputNames(Set<AliasWrapper> aliasSet) {
        return aliasSet.stream()
                .filter(aliasWrapper -> aliasWrapper.hasTableMethod() && !aliasWrapper.getInputNames().isEmpty())
                .flatMap(aliasWrapper -> aliasWrapper.getInputNames().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void addTableMethodInputParameters(MethodSpec.Builder methodBuilder, Set<String> inputNames, ObjectField sourceField) {
        for (var inputName : inputNames) {
            var inputArg = sourceField.getArguments().stream()
                    .filter(arg -> arg.getName().equals(inputName))
                    .findFirst();
            inputArg.ifPresent(argumentField -> methodBuilder.addParameter(argumentField.getTypeClass(), inputName));
        }
    }

    // Filters to aliases used in SELECT plus their transitive dependencies, preserving order
    private Set<AliasWrapper> filterToUsedAliasesOnly(Set<AliasWrapper> allAliases, CodeBlock selectCode) {
        var codeString = selectCode.toString();

        var allNeeded = allAliases.stream()
                .filter(aliasWrapper -> isAliasUsedInCode(aliasWrapper.getAlias(), codeString))
                .collect(Collectors.toCollection(HashSet::new));

        // Add transitive dependencies (e.g., if _a_customer.address() is needed, include _a_customer)
        boolean addedAny = true;
        while (addedAny) {
            addedAny = false;
            for (var aliasWrapper : allAliases) {
                if (allNeeded.contains(aliasWrapper)) continue;

                var aliasName = aliasWrapper.getAlias().getMappingName();
                for (var neededAlias : allNeeded) {
                    var variableValue = neededAlias.getAlias().getVariableValue();
                    if (variableValue.startsWith(aliasName + ".")) {
                        allNeeded.add(aliasWrapper);
                        addedAny = true;
                        break;
                    }
                }
            }
        }

        return allAliases.stream()
                .filter(allNeeded::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // Uses regex word boundaries to avoid partial matches (cached for performance)
    private boolean isAliasUsedInCode(Alias alias, String codeString) {
        var aliasName = alias.getMappingName();
        var pattern = aliasPatternCache.computeIfAbsent(
                Pattern.compile("\\b" + Pattern.quote(aliasName) + "\\b"),
                k -> k
        );
        return pattern.matcher(codeString).find();
    }
}