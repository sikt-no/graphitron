package no.sikt.graphitron.generators.db;


import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.mapping.Alias;
import no.sikt.graphitron.definitions.mapping.AliasWrapper;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.STATIC;
import static no.sikt.graphitron.configuration.GeneratorConfig.optionalSelectIsEnabled;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.inputPrefix;
import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.prefixName;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;

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
    private final Map<String, Integer> methodDefinitionCounters = new HashMap<>();
    private final Map<Pattern, Pattern> aliasPatternCache = new HashMap<>();

    // Per-method state (set for each top-level field)
    MethodGenerationState methodState = null;

    // Per-helper state (set for each helper being generated)
    HelperGenerationContext helperContext = null;

    /**
     * Container pattern detection only applies at depth 0.
     * At this depth, we're processing the root helper method's direct fields.
     */
    static final int CONTAINER_PATTERN_DEPTH = 0;

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

    public NestedFetchDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField field) {
        // Create per-method state for this top-level field
        var rootContext = new FetchContext(processedSchema, field, getLocalObject(), false);
        methodState = new MethodGenerationState(field, rootContext);

        var method = generateHelperMethod(field);

        // Clear per-method state before moving to next field
        methodState = null;
        return method;
    }

    public List<MethodSpec> generateNested(ObjectField field) {
        // Create per-method state for this top-level field and all its nested helpers
        var rootContext = new FetchContext(processedSchema, field, getLocalObject(), false);
        methodState = new MethodGenerationState(field, rootContext);
        var parentHelperName = generateHelperMethodName(field);

        // For resolver fields (like splitQuery), we need to navigate to get the correct context
        // For non-resolver fields, rootFetchContext already represents the correct context
        var context = methodState.rootFetchContext;
        var parentContext = field.isResolver() ? context.nextContext(field) : context;
        // Start at depth 0 (root helper method level)
        var helperMethods = generateNestedHelperMethods(field, parentHelperName, parentContext, new HashSet<>(), 0);

        // Clear per-method state before moving to next field
        methodState = null;
        return helperMethods;
    }

    MethodSpec generateHelperMethod(ObjectField target) {
        return generateHelperMethod(null, target, null, 0).orElseThrow();
    }

    private Optional<MethodSpec> generateHelperMethod(String methodName, ObjectField target, FetchContext parentContext, int depth) {
        var isRootHelper = depth == 0;

        if (isRootHelper) {
            methodState.parentField = target;
        }

        var nestedRecordType = processedSchema.getRecordType(target);
        if (!isRootHelper && !shouldGenerateHelperMethod(target, nestedRecordType)) {
            return Optional.empty();
        }

        // Method naming: root uses generateHelperMethodName(), nested uses passed-in name with depth prefix
        var helperMethodName = isRootHelper ? generateHelperMethodName(target) : methodName;
        var finalMethodName = isRootHelper ? helperMethodName : prefixName(String.valueOf(depth), helperMethodName);

        var previousHelperContext = helperContext;
        helperContext = new HelperGenerationContext(helperMethodName, depth);

        try {
            // Context: root uses rootFetchContext (with optional nextContext for resolvers), nested uses parentContext.nextContext()
            var context = isRootHelper
                    ? (target.isResolver() ? methodState.rootFetchContext.nextContext(target) : methodState.rootFetchContext)
                    : parentContext.nextContext(target);

            var returnType = nestedRecordType.getGraphClassName();
            var selectRowBlock = generateSelectRow(context);

            var methodBuilder = MethodSpec.methodBuilder(finalMethodName)
                    .addModifiers(PRIVATE, STATIC)
                    .addParameterIf(GeneratorConfig.shouldMakeNodeStrategy(), NODE_ID_STRATEGY.className, VAR_NODE_STRATEGY)
                    .returns(ParameterizedTypeName.get(SELECT_FIELD.className, returnType));

            // Parameters: root adds full input params (for non-split queries), nested adds only table method inputs
            var parser = new InputParser(isRootHelper ? target : methodState.rootField, processedSchema);
            if (isRootHelper && !processedSchema.isReferenceResolverField(target)) {
                methodBuilder.addParameters(parser.getMethodParameterSpecs(true, false, false));
            } else if (!isRootHelper) {
                var tableMethodInputs = collectTableMethodInputNames(context.getAliasSet());
                methodBuilder.addParameters(addTableMethodInputParameters(tableMethodInputs, methodState.rootField));
            }

            var allAliases = context.getAliasSet();
            var usedAliases = filterToUsedAliasesOnly(allAliases, selectRowBlock);

            methodBuilder
                    .addCode(declareAllServiceClassesInAliasSet(usedAliases))
                    .addCodeIf(!usedAliases.isEmpty(), () -> createAliasDeclarations(usedAliases))
                    .addCode(returnWrap(selectRowBlock))
                    .addParameters(parser.getContextParameterSpecs())
                    .addParameterIf(optionalSelectIsEnabled(), SELECTION_SET.className, VAR_SELECT);

            return Optional.of(methodBuilder.build());
        } finally {
            if (isRootHelper) {
                methodState.parentField = null;
            }
            helperContext = previousHelperContext;
        }
    }

    @Override
    protected CodeBlock getHelperMethodCallForNestedField(ObjectField field, FetchContext context) {
        // Root-level case: delegate to base class implementation
        if (helperContext == null) {
            return super.getHelperMethodCallForNestedField(field, context);
        }

        // Nested case: when calling from within a helper at depth N, the nested helper is at depth N+1
        var nestedDepth = helperContext.depth + 1;
        var baseName = generateNestedMethodName(helperContext.helperMethodName, field instanceof VirtualSourceField ? field.getContainerTypeName() : field.getName());
        var helperMethodName = prefixName(String.valueOf(nestedDepth), helperContext.getNextCallName(baseName));

        var parameters = new ArrayList<String>();
        if (GeneratorConfig.shouldMakeNodeStrategy()) {
            parameters.add(VAR_NODE_STRATEGY);
        }

        var tableMethodInputs = collectTableMethodInputNames(methodState.rootFetchContext.getAliasSet());
        parameters.addAll(tableMethodInputs);
        parameters.addAll(new InputParser(methodState.rootField, processedSchema).getContextFieldNames());
        if (optionalSelectIsEnabled()) parameters.add(VAR_SELECT);

        return CodeBlock.of("$L($L)", helperMethodName, String.join(", ", parameters));
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
            if (shouldSkipHelperAndTraverse(objectField, recordType, nestedRecordType, depth, methodState.rootField.getArguments().stream().anyMatch(processedSchema::hasInputJOOQRecord))) {
                nestedMethods.addAll(generateNestedHelperMethods(objectField, parentHelperMethodName, parentContext.nextContext(objectField), siblingVisitedTypes, depth));
                continue;
            }

            // Generate helper method for this field (at depth + 1 since it's nested under parent at depth)

            // Generate method name like: _1_queryForQuery_customer_address (_{depth}_[parentHelperMethod]_[fieldName])
            // Depth is included to prevent collisions between staff.store (depth 2) and staff_store (depth 1)
            var baseName = generateNestedMethodName(parentHelperMethodName, objectField.getName());
            // Ensure uniqueness by appending counter if base name already used
            var helperMethodName = baseName + makeUniqueSuffix(prefixName(String.valueOf(depth + 1), baseName));
            var nestedHelperMethod = generateHelperMethod(helperMethodName, objectField, parentContext, depth + 1);

            nestedHelperMethod.ifPresent(method -> {
                nestedMethods.add(method);
                nestedMethods.addAll(generateNestedHelperMethods(objectField, helperMethodName, parentContext.nextContext(objectField), siblingVisitedTypes, depth + 1));
            });
        }

        return nestedMethods;
    }

    private String makeUniqueSuffix(String baseName) {
        var counter = methodDefinitionCounters.getOrDefault(baseName, 0);
        methodDefinitionCounters.put(baseName, counter + 1);
        return counter == 0 ? "" : "_" + counter;
    }

    private boolean shouldSkipField(ObjectField field) {
        return field.isExplicitlyNotGenerated() ||
                !processedSchema.isRecordType(field) ||
                processedSchema.isUnion(field);
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
     * @param rootFieldHasInputTableArguments Whether the root field has input table arguments
     * @return true if helper should be skipped and children should be traversed, false otherwise
     */
    private boolean shouldSkipHelperAndTraverse(ObjectField field, RecordObjectSpecification<?> parentType,
                                                RecordObjectSpecification<?> nestedType, int depth,
                                                boolean rootFieldHasInputTableArguments) {
        // Input parameter container at root (depth 0)
        if (depth == CONTAINER_PATTERN_DEPTH &&
                !parentType.hasTable() &&
                !field.isIterableWrapped() &&
                !field.hasFieldReferences() &&
                nestedType != null && nestedType.hasTable() &&
                rootFieldHasInputTableArguments) {
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
                .collect(Collectors.toSet());
    }

    private List<ParameterSpec> addTableMethodInputParameters(Set<String> inputNames, ObjectField sourceField) {
        return sourceField
                .getArguments()
                .stream()
                .filter(arg -> inputNames.contains(inputPrefix(arg.getName())))
                .map(arg -> ParameterSpec.of(arg.getTypeClass(), inputPrefix(arg.getName())))
                .toList();
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