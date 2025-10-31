package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.mapping.AliasWrapper;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.LookupHelpers;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Stream;

import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.STATIC;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.indentIfMultiline;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryMethodName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.TableReflection.tableHasPrimaryKey;
import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_ENTITIES_FIELD;

/**
 * Generator that creates data fetching methods for GraphQL fields mapped to database tables using jOOQ.
 * <p>
 * This generator produces two types of methods:
 * <ol>
 *   <li><b>Main query methods</b> - Public methods that correspond to GraphQL query fields.
 *       These handle parameter binding, WHERE clauses, and result mapping.</li>
 *   <li><b>Helper methods</b> - Private static methods that extract the SelectField row mapping logic
 *       for record types. This extraction improves compilation performance by reducing method complexity
 *       and enables reuse of mapping logic across different query contexts.</li>
 * </ol>
 *
 * <h3>Key Patterns</h3>
 * <ul>
 *   <li><b>Split Queries</b> - Resolver fields that reference a table object from a previous context.
 *       These use correlated subqueries and require special parameter handling.</li>
 *   <li><b>Container Pattern</b> - Non-table types that exist solely to wrap input parameters for
 *       a nested table query (e.g., FilmContainer). These skip helper method generation and traverse
 *       directly to nested fields.</li>
 *   <li><b>Wrapper Pattern</b> - Non-table types that add structural indirection for schema design
 *       purposes (e.g., Outer). These generate helper methods for proper nesting.</li>
 *   <li><b>Double Nested Wrappers</b> - Special case where two wrapper types appear in sequence
 *       before reaching a table type. These are inlined to avoid excessive method extraction.</li>
 * </ul>
 *
 * <h3>Helper Method Generation Rules</h3>
 * <ul>
 *   <li>Helper methods are only generated for record types (not scalars or interfaces)</li>
 *   <li>Split queries and fields with table methods receive the target table as a parameter</li>
 *   <li>Other fields receive base table parameters with alias declarations in the method body</li>
 *   <li>Circular references are detected and prevented during nested helper generation</li>
 *   <li>Input parameters are omitted from split query helpers (applied at main method level)</li>
 * </ul>
 *
 * @see FetchDBMethodGenerator
 */
public class FetchMappedObjectDBMethodGenerator extends FetchDBMethodGenerator {
    private ObjectField currentParentField = null;
    private ObjectField currentRootField = null;  // Track the root Query field
    private boolean isGeneratingHelperMethod = false;
    private String currentHelperMethodName = null;

    public FetchMappedObjectDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    /**
     * @param target A {@link ObjectField} for which a method should be generated for.
     *                       This must reference an object with the
     *                       "{@link GenerationDirective#TABLE table}" directive set.
     * @return The complete javapoet {@link MethodSpec} based on the provided reference field.
     */
    @Override
    public MethodSpec generate(ObjectField target) {
        var localObject = getLocalObject();
        var context = new FetchContext(processedSchema, target, localObject, false);

        // Note that this must happen before alias declaration.
        var selectRowBlock = getSelectRowOrField(target, context);
        var whereBlock = formatWhereContents(context, resolverKeyParamName, isRoot, target.isResolver());
        for (var alias: context.getAliasSet()) {
            if (alias.hasTableMethod()){
                createServiceDependency(alias.getReferenceObjectField());
            }
        }
        var querySource = context.renderQuerySource(getLocalTable());
        var refContext = target.isResolver() ? context.nextContext(target) : context;
        var actualRefTable = refContext.getTargetAlias();
        var actualRefTableName = refContext.getTargetTableName();
        var selectAliasesBlock = createAliasDeclarations(context.getAliasSet());
        var orderFields = !LookupHelpers.lookupExists(target, processedSchema) && (target.isIterableWrapped() || target.hasForwardPagination() || !isRoot)
                ? createOrderFieldsDeclarationBlock(target, actualRefTable, actualRefTableName)
                : CodeBlock.empty();

        var returnType = processedSchema.isRecordType(target)
                ? processedSchema.getRecordType(target).getGraphClassName()
                : inferFieldTypeName(context.getReferenceObjectField(), true);

        // For record types that are NOT split queries, extract the row mapping into a helper method
        // Split queries need to preserve their correlated subquery structure
        var isSplitQuery = isSplitQueryField(target);
        var selectBlock = processedSchema.isRecordType(target) && !isSplitQuery
                ? createSelectBlockWithHelperMethod(target, context, actualRefTable)
                : createSelectBlock(target, context, actualRefTable, selectRowBlock);

        return getSpecBuilder(target, returnType, new InputParser(target, processedSchema))
                .addCode(declareAllServiceClassesInAliasSet(context.getAliasSet()))
                .addCode(selectAliasesBlock)
                .addCode(orderFields)
                .addCode("return $N\n", VariableNames.VAR_CONTEXT)
                .indent()
                .indent()
                .addCode(".select($L)\n", selectBlock)
                .addCodeIf(!querySource.isEmpty() && (context.hasNonSubqueryFields() || context.hasApplicableTable()), ".from($L)\n", querySource)
                .addCode(createSelectJoins(context.getJoinSet()))
                .addCode(whereBlock)
                .addCode(createSelectConditions(context.getConditionList(), !whereBlock.isEmpty()))
                .addCodeIf(!target.isResolver() && !orderFields.isEmpty(), ".orderBy($L)\n", VAR_ORDER_FIELDS)
                .addCodeIf(target.hasForwardPagination() && !target.isResolver(), this::createSeekAndLimitBlock)
                .addCode(setFetch(target))
                .unindent()
                .unindent()
                .build();
    }

    private CodeBlock getSelectRowOrField(ObjectField target, FetchContext context) {
        if (!processedSchema.isRecordType(target)) {
            return generateForField(target, context);
        }
        return isSplitQueryField(target)
                ? generateCorrelatedSubquery(target, context.nextContext(target))
                : generateSelectRow(context);
    }

    private CodeBlock createSelectBlock(ObjectField target, FetchContext context, String actualRefTable, CodeBlock selectRowBlock) {
        return indentIfMultiline(
                Stream.of(
                        getInitialKey(context),
                        CodeBlock.ofIf(target.hasForwardPagination() && !target.isResolver(), "$T.getOrderByToken($L, $L),\n", QUERY_HELPER.className, actualRefTable, VAR_ORDER_FIELDS),
                        selectRowBlock
                ).collect(CodeBlock.joining())
        );
    }

    private CodeBlock createSelectBlockWithHelperMethod(ObjectField target, FetchContext context, String actualRefTable) {
        var keyBlock = getInitialKey(context);
        var tokenBlock = CodeBlock.ofIf(target.hasForwardPagination() && !target.isResolver(), "$T.getOrderByToken($L, $L),\n", QUERY_HELPER.className, actualRefTable, VAR_ORDER_FIELDS);
        var helperMethodName = generateHelperMethodName(target);

        // Collect input parameters from InputParser
        var parser = new InputParser(target, processedSchema);
        var inputParameters = new java.util.ArrayList<String>();

        // Add method input parameters
        for (var entry : parser.getMethodInputsWithOrderField().entrySet()) {
            inputParameters.add(entry.getKey());
        }

        // Collect table parameters that need to be passed to the helper method
        // Pass table parameters for split queries and tables with table methods (same logic as method signature generation)
        var refContext = target.isResolver() ? context.nextContext(target) : context;
        var tableParameters = new java.util.ArrayList<String>();

        // Check if this is a split query that would have table parameters in its signature
        var isSplitQuery = isSplitQueryField(target);

        // Check if the target table has a table method applied
        boolean hasTableMethodAlias = false;
        for (var alias : refContext.getAliasSet()) {
            if (alias.hasTableMethod() && alias.getAlias().getMappingName().equals(refContext.getTargetAlias())) {
                hasTableMethodAlias = true;
                break;
            }
        }

        if (isSplitQuery || hasTableMethodAlias) {
            // For split queries and table methods, pass the target table alias as parameter
            var targetAlias = refContext.getTargetAlias();
            tableParameters.add(targetAlias);
        }

        // Combine all parameters
        var allParameters = new java.util.ArrayList<String>();
        allParameters.addAll(inputParameters);
        allParameters.addAll(tableParameters);

        // Add nodeIdStrategy parameter if needed
        if (GeneratorConfig.shouldMakeNodeStrategy()) {
            allParameters.add(VAR_NODE_STRATEGY);
        }

        var parameterList = String.join(", ", allParameters);
        var helperCall = CodeBlock.of("$L($L)", helperMethodName, parameterList);

        return indentIfMultiline(
                Stream.of(keyBlock, tokenBlock, helperCall)
                        .collect(CodeBlock.joining())
        );
    }

    @Override
    protected CodeBlock getHelperMethodCallForCorrelatedSubquery(ObjectField field, FetchContext context) {
        // Use helper methods for record types in correlated subqueries
        if (processedSchema.isRecordType(field)) {
            // Check if this is a nested field within a parent field
            // If the reference object is not null, this is likely a nested context
            var isNested = context.getReferenceObject() != null && context.getReferenceObject() != getLocalObject();

            String helperMethodName;
            if (currentHelperMethodName != null) {
                // When we're already inside a helper method, use its name as the parent
                helperMethodName = currentHelperMethodName + "_" + field.getName();
            } else if (isNested && currentParentField != null) {
                // For nested fields, use parent helper method name + field name
                var parentHelperName = generateHelperMethodName(currentParentField);
                helperMethodName = parentHelperName + "_" + field.getName();
            } else {
                // For top-level fields, use standard naming
                helperMethodName = generateHelperMethodName(field);
            }

            // Build parameter list for the helper method call
            var parameters = new java.util.ArrayList<String>();
            parameters.add(context.getTargetAlias());

            // Add nodeIdStrategy parameter if needed
            if (GeneratorConfig.shouldMakeNodeStrategy()) {
                parameters.add(VAR_NODE_STRATEGY);
            }

            var parameterList = String.join(", ", parameters);
            return CodeBlock.of("$L($L)", helperMethodName, parameterList);
        }
        return null;
    }

    private String generateHelperMethodName(ObjectField target) {
        // Generate method name like: queryForQuery_outer
        // Pattern: [callingMethod]_[returnType]
        var callingMethodName = asQueryMethodName(target.getName(), getLocalObject().getName());
        var returnTypeName = processedSchema.getRecordType(target).getName();
        // Make first letter lowercase for camelCase
        returnTypeName = returnTypeName.substring(0, 1).toLowerCase() + returnTypeName.substring(1);
        return callingMethodName + "_" + returnTypeName;
    }


    private CodeBlock setFetch(ObjectField referenceField) {
        var refObject = processedSchema.getObjectOrConnectionNode(referenceField);
        if (refObject == null) {
            return CodeBlock.statementOf(
                    ".fetch$L($L -> $N.into($T.class))",
                    referenceField.isIterableWrapped() ? "" : "One",
                    VAR_ITERATOR,
                    VAR_ITERATOR,
                    referenceField.getTypeClass()
            );
        }

        if (referenceField.hasForwardPagination()) {
            return getPaginationFetchBlock();
        }

        var lookupExists = LookupHelpers.lookupExists(referenceField, processedSchema);
        if (isRoot && !lookupExists) {
            return CodeBlock.statementOf(
                    ".fetch$L($L -> $N.into($T.class))",
                    referenceField.isIterableWrapped() ? "" : "One",
                    VAR_ITERATOR,
                    VAR_ITERATOR,
                    refObject.getGraphClassName()
            );
        }

        var code = CodeBlock.builder()
                .add(".fetchMap(")
                .addIf(lookupExists, "$T::value1, ", RECORD2.className)
                .addIf(!lookupExists, "$1L -> $1N.value1().valuesRow(), ", VAR_RECORD_ITERATOR);

        if (processedSchema.isObjectOrConnectionNodeWithPreviousTableObject(referenceField.getContainerTypeName()) && referenceField.isIterableWrapped() && !lookupExists || referenceField.hasForwardPagination()) {
            if (referenceField.hasForwardPagination() && (referenceField.getOrderField().isPresent() || tableHasPrimaryKey(refObject.getTable().getName()))) {
                return code.addStatement("$1L -> $1N.value2().map($2T::value2))", VAR_RECORD_ITERATOR, RECORD2.className).build();
            }

            return code.addStatement("$1L -> $1N.value2().map($2T::value1))", VAR_RECORD_ITERATOR, RECORD1.className).build();
        }

        return code.addStatement("$T::value2)", RECORD2.className).build();
    }

    private CodeBlock getPaginationFetchBlock() {
        var code = CodeBlock.builder();

        if (isRoot) {
            code
                    .add(".fetch()\n")
                    .addStatement(".map($1L -> new $2T<>($1N.value1(), $1N.value2()))", VAR_ITERATOR, IMMUTABLE_PAIR.className);
        } else {
            code
                    .add(".fetchMap(\n")
                    .indent()
                    .add("$1L -> $1N.value1().valuesRow(),\n", VAR_RECORD_ITERATOR)
                    .add(
                            "$1L -> $1N.value2().map($2L -> $2N.value2() == null ? null : new $3T<>($2N.value1(), $2N.value2()))",
                            VAR_ITERATOR,
                            VAR_RECORD_ITERATOR,
                            IMMUTABLE_PAIR.className
                    )
                    .unindent()
                    .addStatement(")");
        }
        return code.build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        var mainMethods = getLocalObject()
                .getFields()
                .stream()
                .filter(it -> !processedSchema.isInterface(it) && !processedSchema.isUnion(it))
                .filter(it -> !it.getName().equals(FEDERATION_ENTITIES_FIELD.getName()))
                .filter(it -> !processedSchema.isFederationService(it))
                .filter(GenerationSourceField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .toList();

        var topLevelFields = getLocalObject()
                .getFields()
                .stream()
                .filter(it -> !processedSchema.isInterface(it) && !processedSchema.isUnion(it))
                .filter(it -> !it.getName().equals(FEDERATION_ENTITIES_FIELD.getName()))
                .filter(it -> !processedSchema.isFederationService(it))
                .filter(GenerationSourceField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .filter(processedSchema::isRecordType)  // Only generate helpers for record types
                .toList();

        var helperMethods = new java.util.ArrayList<MethodSpec>();

        // Generate helper methods for top-level fields
        for (var field : topLevelFields) {
            // Set current root field for this top-level field
            currentRootField = field;

            var helperMethod = generateHelperMethod(field);
            if (!helperMethod.code().isEmpty()) {
                helperMethods.add(helperMethod);
            }

            // Set current parent field for context
            currentParentField = field;

            // Generate nested helper methods
            helperMethods.addAll(generateNestedHelperMethods(field));

            // Clear current parent field and root field
            currentParentField = null;
            currentRootField = null;
        }

        var allMethods = new java.util.ArrayList<MethodSpec>();
        allMethods.addAll(mainMethods);
        allMethods.addAll(helperMethods);
        return allMethods;
    }

    private MethodSpec generateHelperMethod(ObjectField target) {
        // Set the current parent field for nested method generation
        currentParentField = target;
        // Set flag to prevent nested helper method extractions
        isGeneratingHelperMethod = true;

        var helperMethodName = generateHelperMethodName(target);
        // Set current helper method name for correlated subqueries
        var previousHelperMethodName = currentHelperMethodName;
        currentHelperMethodName = helperMethodName;

        try {
            var context = new FetchContext(processedSchema, target, getLocalObject(), false);
            var refContext = target.isResolver() ? context.nextContext(target) : context;

            var returnType = processedSchema.getRecordType(target).getGraphClassName();
            var selectRowBlock = generateSelectRow(refContext);  // Use refContext to get the correct table context

            var methodBuilder = MethodSpec.methodBuilder(helperMethodName)
                    .addModifiers(PRIVATE, STATIC)
                    .returns(ParameterizedTypeName.get(SELECT_FIELD.className, returnType));

            // For split queries, we don't add input parameters to the helper method
            // as they're used in the main method's WHERE clause, not in the helper
            var isSplitQuery = isSplitQueryField(target);

            // Add input parameters from the original method only for non-split queries
            // Split query helper methods don't need input parameters as WHERE clauses are applied at the main level
            if (!isSplitQuery) {
                var parser = new InputParser(target, processedSchema);
                methodBuilder.addParameters(getMethodParametersWithOrderField(parser));
            }

            // For split queries and tables with table methods, we need the target table as a parameter
            String targetParameterAlias = null;

            // Check if the target table has a table method applied
            boolean hasTableMethodAlias = false;
            for (var alias : refContext.getAliasSet()) {
                if (alias.hasTableMethod() && alias.getAlias().getMappingName().equals(refContext.getTargetAlias())) {
                    hasTableMethodAlias = true;
                    break;
                }
            }

            if (isSplitQuery || hasTableMethodAlias) {
                var targetAlias = refContext.getTargetAlias();
                var targetTable = refContext.getTargetTable();
                methodBuilder.addParameter(targetTable.getTableClass(), targetAlias);
                targetParameterAlias = targetAlias;
            }

            // Declare aliases used within this method (excluding the target parameter for split queries)
            var allAliases = refContext.getAliasSet();
            var aliasesToDeclare = filterAliasesToDeclare(allAliases, targetParameterAlias, isSplitQuery);

            // For split queries with target table parameters, keep aliases that form a valid derivation chain
            // from the parameter, excluding aliases that reference undefined parent context variables
            if (isSplitQuery && targetParameterAlias != null) {
                aliasesToDeclare = filterToValidAliasChain(aliasesToDeclare, targetParameterAlias);
            }

            if (!aliasesToDeclare.isEmpty()) {
                methodBuilder.addCode(createAliasDeclarations(aliasesToDeclare));
            }

            return methodBuilder
                    .addCode("return $L;\n", selectRowBlock)
                    .addParameterIf(GeneratorConfig.shouldMakeNodeStrategy(), NODE_ID_STRATEGY.className, VAR_NODE_STRATEGY)
                    .build();
        } finally {
            // Clear the current parent field and reset flag after processing
            currentParentField = null;
            isGeneratingHelperMethod = false;
            // Restore the previous helper method name
            currentHelperMethodName = previousHelperMethodName;
        }
    }

    private java.util.List<MethodSpec> generateNestedHelperMethods(ObjectField parentField) {
        var parentHelperName = generateHelperMethodName(parentField);
        return generateNestedHelperMethods(parentField, parentHelperName, new java.util.HashSet<>());
    }

    private java.util.List<MethodSpec> generateNestedHelperMethods(ObjectField parentField, String parentHelperMethodName, java.util.Set<String> visitedTypes) {
        var nestedMethods = new java.util.ArrayList<MethodSpec>();

        if (!processedSchema.isRecordType(parentField)) {
            return nestedMethods;
        }

        var recordType = processedSchema.getRecordType(parentField);
        if (recordType == null) {
            return nestedMethods;
        }

        // Prevent infinite recursion by tracking visited types
        var currentTypeName = recordType.getName();
        if (visitedTypes.contains(currentTypeName)) {
            return nestedMethods;
        }
        visitedTypes.add(currentTypeName);

        // Find nested record fields
        for (var field : recordType.getFields()) {
            if (field instanceof ObjectField objectField &&
                    !objectField.isExplicitlyNotGenerated() &&  // Skip fields with @notGenerated directive
                    processedSchema.isRecordType(objectField) &&
                    processedSchema.getRecordType(objectField) != null &&
                    !processedSchema.isUnion(objectField)) {  // Skip union types

                // Check various conditions to determine if we should skip helper method generation
                // Check if this field is already handled by its own split query resolver
                // This field has its own split query resolver, don't generate nested helper methods for it
                boolean shouldSkipGeneration = objectField.isResolver() &&
                        processedSchema.isObjectOrConnectionNodeWithPreviousTableObject(objectField.getContainerTypeName());

                // Check if this field's record type has a table - if not, it might be a wrapper that should be traversed
                // But we need to generate helper methods for wrapper types in multiset contexts
                var nestedRecordType = processedSchema.getRecordType(objectField);
                boolean isWrapperType = false;
                if (!shouldSkipGeneration && (nestedRecordType == null || !nestedRecordType.hasTable())) {
                    // Check if this is a list/array field that needs a helper method for multiset handling
                    if (!objectField.isIterableWrapped()) {
                        // This is a singular wrapper without its own table
                        // Don't generate a helper for the wrapper itself, but DO recurse to its children
                        shouldSkipGeneration = true;
                        isWrapperType = true;
                    }
                }

                // Skip helper generation for fields with @reference in wrappers only if the target also has no table
                // Allow extraction when the field's target type HAS a table, even if parent doesn't.
                // For example: WrapperType → TableType via @reference is valid and should be extracted.
                if (!shouldSkipGeneration && !recordType.hasTable() && objectField.hasFieldReferences()) {
                    var fieldTargetType = processedSchema.getRecordType(objectField);
                    if (fieldTargetType == null || !fieldTargetType.hasTable()) {
                        // Parent has no table AND field's target has no table - don't extract helper
                        shouldSkipGeneration = true;
                    }
                }

                // Container pattern detection:
                // A "container" is a non-table type that exists solely to wrap input parameters, where
                // the input parameters directly map to the nested table type (e.g., FilmContainer with
                // films field - the container exists to accept input parameters for the Film query).
                //
                // This is distinct from a "wrapper" type, which is a non-table type that adds a layer
                // of indirection for structural purposes (e.g., Outer wrapping Customer - the wrapper
                // exists for schema design, not to accept query parameters).
                //
                // Detection: If the root query field has input parameters that map to tables, and we
                // encounter a non-table parent with a single non-list field to a table type, this is
                // a container pattern. We skip generating a helper method for the container itself
                // and traverse directly to the nested table type's fields.
                if (!shouldSkipGeneration && !recordType.hasTable() &&
                        !objectField.isIterableWrapped() && !objectField.hasFieldReferences()) {
                    // Check if the nested type has a table
                    var nestedType = processedSchema.getRecordType(objectField);
                    if (nestedType != null && nestedType.hasTable()) {
                        // Check if the current root query field has input parameters that map to tables
                        if (rootFieldHasInputTableArguments()) {
                            // This is a container pattern, skip the intermediate helper
                            shouldSkipGeneration = true;
                            // Traverse nested fields using parent method name
                            nestedMethods.addAll(generateNestedHelperMethods(objectField, parentHelperMethodName, visitedTypes));
                        }
                    }
                }

                if (!shouldSkipGeneration) {
                    // Generate helper method for this nested field, passing the parent helper method name
                    var nestedHelperMethod = generateNestedHelperMethodWithParentName(parentHelperMethodName, objectField);
                    boolean helperWasGenerated = !nestedHelperMethod.code().isEmpty();

                    if (helperWasGenerated) {
                        nestedMethods.add(nestedHelperMethod);

                        // Recursively generate methods for deeper nesting, maintaining the naming chain
                        // Only recurse if we actually generated a helper (not bailed out with empty method)
                        var nestedHelperMethodName = parentHelperMethodName + "_" + objectField.getName();
                        nestedMethods.addAll(generateNestedHelperMethods(objectField, nestedHelperMethodName, visitedTypes));
                    }
                    // If helper generation was skipped (empty method), don't recurse into children
                } else if (isWrapperType) {
                    // For wrapper types, check if any child fields have @reference to non-table types
                    // Only skip recursion if child references lead to non-table types (problematic cases)
                    // Allow recursion if child @reference fields point to table types (valid extraction)
                    boolean hasProblematicChildWithReference = nestedRecordType != null &&
                            nestedRecordType.getFields().stream()
                                .filter(f -> f instanceof ObjectField && ((ObjectField) f).hasFieldReferences())
                                .anyMatch(f -> {
                                    var childTarget = processedSchema.getRecordType(f);
                                    return childTarget == null || !childTarget.hasTable();
                                });

                    if (!hasProblematicChildWithReference) {
                        // For wrapper types without problematic @reference children, recurse to generate helpers
                        // Use the parent's method name directly (don't append the wrapper field name)
                        nestedMethods.addAll(generateNestedHelperMethods(objectField, parentHelperMethodName, visitedTypes));
                    }
                    // If wrapper has problematic @reference children, skip recursion - will be inlined
                }
            }
        }

        return nestedMethods;
    }

    // This method is called recursively with the correct parent helper method name
    private MethodSpec generateNestedHelperMethodWithParentName(String parentHelperMethodName, ObjectField nestedField) {
        var returnType = processedSchema.getRecordType(nestedField).getGraphClassName();

        // Generate method name like: queryForQuery_customer_address
        // Pattern: [parentHelperMethod]_[fieldName]
        var fieldName = nestedField.getName();
        var helperMethodName = parentHelperMethodName + "_" + fieldName;

        // Set the current helper method name for nested calls
        var previousHelperMethodName = currentHelperMethodName;
        currentHelperMethodName = helperMethodName;

        try {
            var methodBuilder = MethodSpec.methodBuilder(helperMethodName)
                    .addModifiers(PRIVATE, STATIC)
                    .returns(ParameterizedTypeName.get(SELECT_FIELD.className, returnType));

            // Get the record type to check if it has a table
            var nestedRecordType = processedSchema.getRecordType(nestedField);

            // Defensive check: Skip helper generation for complex @reference patterns through wrappers
            // where the target type also lacks a table (e.g., nested wrappers with references).
            // But allow extraction when the field's target type HAS a table, even if parent doesn't.
            if (nestedField.hasFieldReferences()) {
                var parentType = processedSchema.getObjectOrConnectionNode(nestedField.getContainerTypeName());
                if (parentType == null || !parentType.hasTable()) {
                    // Parent has no table - only skip if the field's target type also has no table
                    if (nestedRecordType == null || !nestedRecordType.hasTable()) {
                        return MethodSpec.methodBuilder(helperMethodName).build();
                    }
                }
            }

            // Only generate helper methods for types that have tables
            // Wrapper types without tables should be traversed, not extracted
            if (nestedRecordType != null && nestedRecordType.hasTable()) {
                try {
                    // Create a context for the nested field
                    var context = new FetchContext(processedSchema, nestedField, getLocalObject(), false);
                    var refContext = nestedField.isResolver() ? context.nextContext(nestedField) : context;

                    var selectRowBlock = generateSelectRow(refContext);

                    // For nested helper methods with tables, add base table as parameter and declare derived aliases
                    // For wrapper types without tables, we don't need table parameters
                    var allAliases = refContext.getAliasSet();
                java.util.Set<AliasWrapper> aliasesToDeclare;
                String tableParameterAlias = null;

                // Only add table parameters if this type has a table
                if (nestedRecordType.hasTable()) {
                    // Determine parameter type based on field characteristics
                    var targetAlias = refContext.getTargetAlias();
                    var targetTable = refContext.getTargetTable();

                    // Use the same logic as method call generation to determine parameter type
                    if (shouldUseTargetTableParameter(nestedField)) {
                        // For split query multisets, complex reference multisets, and single reference fields, use the target table
                        // For single reference fields, use the nested record type's table to get the correct table class
                        var tableClass = (!nestedField.isIterableWrapped() && nestedField.hasFieldReferences() && nestedRecordType.hasTable())
                                ? nestedRecordType.getTable().getTable().getTableClass()
                                : targetTable.getTableClass();
                        methodBuilder.addParameter(tableClass, targetAlias);
                        tableParameterAlias = targetAlias;
                    } else {
                        // For single fields and simple table path multisets, add base table as parameter
                        for (var alias : allAliases) {
                            if (!alias.hasTableMethod()) {
                                var tableMapping = alias.getAlias().getTable();
                                var underlyingTable = tableMapping.getTable();
                                methodBuilder.addParameter(underlyingTable.getTableClass(), alias.getAlias().getMappingName());
                                tableParameterAlias = alias.getAlias().getMappingName();
                                break; // Only add the first one as parameter
                            }
                        }
                    }

                    // Declare remaining aliases that are derived from the table parameter
                    // For fields that receive target table directly, declare only derived aliases (not the target itself)
                    // For fields that receive base table parameters, declare derived aliases (not the base table itself)
                    boolean usesTargetTableParameter = shouldUseTargetTableParameter(nestedField);

                    if (usesTargetTableParameter) {
                        // When target table is a parameter, declare only aliases derived from that parameter
                        // Filter to derived aliases, then further filter to only those starting with the parameter
                        var derivedAliases = filterAliasesToDeclare(allAliases, tableParameterAlias, true);
                        final String paramPrefix = tableParameterAlias + ".";
                        aliasesToDeclare = derivedAliases.stream()
                                .filter(alias -> alias.getAlias().getVariableValue().startsWith(paramPrefix))
                                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
                    } else {
                        // When base table is a parameter, declare all other aliases
                        aliasesToDeclare = filterAliasesToDeclare(allAliases, tableParameterAlias, false);
                    }

                    if (!aliasesToDeclare.isEmpty()) {
                        methodBuilder.addCode(createAliasDeclarations(aliasesToDeclare));
                    }
                } else {
                    // For wrapper types without tables, no parameters or alias declarations needed
                    aliasesToDeclare = java.util.Collections.emptySet();
                }

                    return methodBuilder
                            .addCode("return $L;\n", selectRowBlock)
                            .addParameterIf(GeneratorConfig.shouldMakeNodeStrategy(), NODE_ID_STRATEGY.className, VAR_NODE_STRATEGY)
                            .build();
                } catch (Exception e) {
                    // If FetchContext creation or select row generation fails, it's likely due to
                    // @reference resolution issues when context lacks proper table information.
                    // Log a warning and skip this helper - the field will be inlined instead.
                    System.err.println("Warning: Could not generate helper for field " + nestedField.getName() +
                                     " in type " + nestedField.getContainerTypeName() +
                                     ": " + e.getMessage());
                    return MethodSpec.methodBuilder(helperMethodName).build();
                }
            } else {
                // For record types without tables, just generate the simple mapping
                // This handles cases like Wrapper type without @table directive
                return methodBuilder
                        .addCode("return DSL.row().mapping($T::new);\n", returnType)
                        .addParameterIf(GeneratorConfig.shouldMakeNodeStrategy(), NODE_ID_STRATEGY.className, VAR_NODE_STRATEGY)
                        .build();
            }
        } finally {
            // Restore the previous helper method name
            currentHelperMethodName = previousHelperMethodName;
        }
    }

    /**
     * Checks if a field is a split query field.
     * Split queries are resolver fields that reference a table object from a previous context.
     *
     * @param field the field to check
     * @return true if the field is a split query field
     */
    private boolean isSplitQueryField(ObjectField field) {
        return field.isResolver() &&
                processedSchema.isObjectOrConnectionNodeWithPreviousTableObject(field.getContainerTypeName());
    }

    /**
     * Checks if the current root query field has input parameters that map to tables.
     * This is used to distinguish container patterns from wrapper patterns.
     *
     * @return true if the current root field has input table arguments
     */
    private boolean rootFieldHasInputTableArguments() {
        return currentRootField != null &&
                currentRootField.getArguments().stream()
                        .anyMatch(processedSchema::hasInputJOOQRecord);
    }

    /**
     * Determines whether a field should use the target table directly as a parameter,
     * as opposed to using a base table parameter with declared aliases.
     * <p>
     * Target table parameters are used for:
     * - Split query multisets
     * - Complex reference multisets (multiple field references)
     * - Single reference fields (non-iterable with field references)
     *
     * @param field the field to check
     * @return true if the field should receive target table as parameter
     */
    private boolean shouldUseTargetTableParameter(ObjectField field) {
        var isSplitQuery = isSplitQueryField(field);
        var hasComplexReferencePath = field.hasFieldReferences() && field.getFieldReferences().size() > 1;
        return (field.isIterableWrapped() && (isSplitQuery || hasComplexReferencePath)) ||
                (!field.isIterableWrapped() && field.hasFieldReferences());
    }

    /**
     * Filters aliases to determine which should be declared in a helper method body.
     * <p>
     * Excludes:
     * <ul>
     *   <li>Aliases with table methods (handled separately)</li>
     *   <li>The parameter alias (passed as method parameter, not declared)</li>
     * </ul>
     * <p>
     * Optionally filters to only derived aliases (for split query helpers).
     *
     * @param allAliases the complete set of aliases in scope
     * @param excludeParameterAlias the alias passed as a parameter (to exclude from declarations)
     * @param onlyDerivedAliases if true, only include aliases derived from join sequences
     * @return filtered set of aliases that should be declared in the method body
     */
    private java.util.Set<AliasWrapper> filterAliasesToDeclare(
            java.util.Set<AliasWrapper> allAliases,
            String excludeParameterAlias,
            boolean onlyDerivedAliases) {
        var aliasesToDeclare = new java.util.LinkedHashSet<AliasWrapper>();

        for (var alias : allAliases) {
            var aliasName = alias.getAlias().getMappingName();
            if (!alias.hasTableMethod() && !aliasName.equals(excludeParameterAlias)) {
                if (onlyDerivedAliases) {
                    // For split queries: only declare derived aliases (from join sequences)
                    if (alias.getAlias().isDerivedAlias()) {
                        aliasesToDeclare.add(alias);
                    }
                } else {
                    // For regular queries: declare all aliases that pass the filters
                    aliasesToDeclare.add(alias);
                }
            }
        }

        return aliasesToDeclare;
    }

    /**
     * Filters aliases to only include those that form a valid derivation chain from the parameter.
     * This keeps aliases that derive from the parameter or from other kept aliases (transitive closure),
     * while excluding aliases that reference undefined parent context variables.
     * <p>
     * Example: Given parameter {@code _a_city} and aliases:
     * <ul>
     *   <li>{@code _a_city.address()} → KEEP (derives from parameter)</li>
     *   <li>{@code _a_address.customer()} → KEEP (derives from previous)</li>
     *   <li>{@code _a_film.inventory()} → EXCLUDE (references undefined {@code _a_film})</li>
     * </ul>
     *
     * @param aliases the set of aliases to filter
     * @param parameterAlias the parameter alias name
     * @return filtered set containing only aliases in the valid derivation chain
     */
    private java.util.Set<AliasWrapper> filterToValidAliasChain(
            java.util.Set<AliasWrapper> aliases,
            String parameterAlias) {
        // Track which variable names exist in this method's scope
        var validNames = new java.util.HashSet<String>();
        validNames.add(parameterAlias);

        var result = new java.util.LinkedHashSet<AliasWrapper>();
        boolean addedAny = true;

        // Iteratively add aliases that derive from valid names until no more can be added
        while (addedAny) {
            addedAny = false;
            for (var alias : aliases) {
                if (result.contains(alias)) continue;

                if (derivesFromValidName(alias, validNames)) {
                    result.add(alias);
                    validNames.add(alias.getAlias().getMappingName());
                    addedAny = true;
                }
            }
        }

        return result;
    }

    /**
     * Checks if an alias derives from any of the given valid variable names.
     * An alias derives from a name if its variable value starts with that name followed by a dot.
     * For example: {@code _a_city.address()} derives from {@code _a_city}
     */
    private boolean derivesFromValidName(AliasWrapper alias, java.util.Set<String> validNames) {
        var variableValue = alias.getAlias().getVariableValue();
        var dotIndex = variableValue.indexOf('.');
        if (dotIndex <= 0) return false;

        var baseName = variableValue.substring(0, dotIndex);
        return validNames.contains(baseName);
    }

}
