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
 * Generator that creates the default data fetching methods
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
        var isSplitQuery = target.isResolver() && processedSchema.isObjectOrConnectionNodeWithPreviousTableObject(target.getContainerTypeName());
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
        return target.isResolver() && processedSchema.isObjectOrConnectionNodeWithPreviousTableObject(target.getContainerTypeName())
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
        var isSplitQuery = target.isResolver() && processedSchema.isObjectOrConnectionNodeWithPreviousTableObject(target.getContainerTypeName());

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
        // Check if we're in a double nesting scenario where we should inline table types
        if (isGeneratingHelperMethod && hasDoubleNestedWrapperPattern()) {
            var recordType = processedSchema.getRecordType(field);
            if (recordType != null && recordType.hasTable()) {
                // If we're in a double nested wrapper pattern and this field maps to a table, inline it
                return null;
            }
        }

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

    /**
     * Detects if the current parent field has a double nested wrapper pattern:
     * Wrapper1 -> Wrapper2 -> TableType
     * where Wrapper1 and Wrapper2 are record types without tables, and the final type has a table.
     */
    private boolean hasDoubleNestedWrapperPattern() { //TODO: this seems strange and not generic
        if (currentParentField == null) {
            return false;
        }

        // Check if the parent field is a record type without a table (first wrapper)
        var parentRecordType = processedSchema.getRecordType(currentParentField);
        if (parentRecordType == null || parentRecordType.hasTable()) {
            return false; // Parent should be a wrapper type without a table
        }

        // Look for nested record types within the parent that might be wrapper types
        var parentObject = processedSchema.getObjectOrConnectionNode(currentParentField);
        if (parentObject == null) {
            return false;
        }

        // Check if any field in the parent is a wrapper type that leads to a table type
        for (var field : parentObject.getFields()) {
            if (processedSchema.isRecordType(field)) {
                var fieldRecordType = processedSchema.getRecordType(field);
                if (fieldRecordType != null && !fieldRecordType.hasTable()) {
                    // This is a potential second wrapper, check if it leads to a table type
                    var fieldObject = processedSchema.getObjectOrConnectionNode(field);
                    if (fieldObject != null) {
                        for (var nestedField : fieldObject.getFields()) {
                            if (processedSchema.isRecordType(nestedField)) {
                                var nestedRecordType = processedSchema.getRecordType(nestedField);
                                if (nestedRecordType != null && nestedRecordType.hasTable()) {
                                    // Found the pattern: Wrapper1 -> Wrapper2 -> TableType
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }

        return false;
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

            // Set current parent field to check for double nested wrapper pattern
            currentParentField = field;

            // Skip nested helper method generation for double nested wrapper patterns
            if (!hasDoubleNestedWrapperPattern()) {
                helperMethods.addAll(generateNestedHelperMethods(field));
            }

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
        var isSplitQuery = target.isResolver() && processedSchema.isObjectOrConnectionNodeWithPreviousTableObject(target.getContainerTypeName());

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
        var aliasesToDeclare = new java.util.LinkedHashSet<AliasWrapper>();

        for (var alias : allAliases) {
            var aliasName = alias.getAlias().getMappingName();
            if (!alias.hasTableMethod() &&
                (!aliasName.equals(targetParameterAlias))) {
                // For split queries: only declare derived aliases, not the parameter
                // For regular queries: declare all aliases
                if (isSplitQuery) {
                    // Only declare aliases that appear to be derived from relationships
                    if (aliasName.matches(".*_\\d+_.*")) { // Pattern like "_a_city_621065670_country"
                        aliasesToDeclare.add(alias);
                    }
                } else {
                    aliasesToDeclare.add(alias);
                }
            }
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

                // Check if this field would create a circular reference or is already handled by a split query
                var fieldTypeName = processedSchema.getRecordType(objectField).getName();
                var methodParts = parentHelperMethodName.split("_");
                boolean shouldSkipGeneration = false;

                // Check for circular reference in method chain
                for (int i = 1; i < methodParts.length; i++) { // Skip the base method name part
                    var part = methodParts[i];
                    if (part.equalsIgnoreCase(fieldTypeName) ||
                        (part.endsWith("s") && part.substring(0, part.length()-1).equalsIgnoreCase(fieldTypeName))) {
                        // This field type is already in the method chain, would create a circular reference
                        shouldSkipGeneration = true;
                        break;
                    }
                }

                // Check if this field is already handled by its own split query resolver
                if (!shouldSkipGeneration && objectField.isResolver() &&
                    processedSchema.isObjectOrConnectionNodeWithPreviousTableObject(objectField.getContainerTypeName())) {
                    // This field has its own split query resolver, don't generate nested helper methods for it
                    shouldSkipGeneration = true;
                }

                // Check if this field's record type has a table - if not, it might be a subtype that should be handled inline
                // But we need to generate helper methods for wrapper types in multiset contexts
                var nestedRecordType = processedSchema.getRecordType(objectField);
                if (!shouldSkipGeneration && (nestedRecordType == null || !nestedRecordType.hasTable())) {
                    // Check if this is a list/array field that needs a helper method for multiset handling
                    if (!objectField.isIterableWrapped()) {
                        // This is a singular subtype without its own table, handle it inline in the parent
                        shouldSkipGeneration = true;
                    }
                }

                // Check if this is a container pattern: non-table parent with single field to table type
                // We distinguish container types from wrapper types based on whether the current root query field
                // has input parameters that map to tables. If it does, intermediate non-table types
                // should not generate helper methods (container pattern).
                if (!shouldSkipGeneration && !recordType.hasTable() &&
                    !objectField.isIterableWrapped() && !objectField.hasFieldReferences()) {
                    // Check if the nested type has a table
                    var nestedType = processedSchema.getRecordType(objectField);
                    if (nestedType != null && nestedType.hasTable()) {
                        // Check if the current root query field has input parameters that map to tables
                        boolean rootHasInputTableArguments = currentRootField != null &&
                            currentRootField.getArguments().stream()
                                .anyMatch(processedSchema::hasInputJOOQRecord);

                        if (rootHasInputTableArguments) {
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
                    if (!nestedHelperMethod.code().isEmpty()) {
                        nestedMethods.add(nestedHelperMethod);
                    }

                    // Recursively generate methods for deeper nesting, maintaining the naming chain
                    var nestedHelperMethodName = parentHelperMethodName + "_" + objectField.getName();
                    nestedMethods.addAll(generateNestedHelperMethods(objectField, nestedHelperMethodName, visitedTypes));
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

            // Check if the nested field's record type has a table
            var nestedRecordType = processedSchema.getRecordType(nestedField);
            if (nestedRecordType != null && nestedRecordType.hasTable()) {
                // Create a context for the nested field
                var context = new FetchContext(processedSchema, nestedField, getLocalObject(), false);
                var refContext = nestedField.isResolver() ? context.nextContext(nestedField) : context;

                var selectRowBlock = generateSelectRow(refContext);

                // For nested helper methods, add base table as parameter and declare derived aliases
                var allAliases = refContext.getAliasSet();
                var aliasesToDeclare = new java.util.LinkedHashSet<AliasWrapper>();
                String tableParameterAlias = null;

                // Determine parameter type based on field characteristics
                var targetAlias = refContext.getTargetAlias();
                var targetTable = refContext.getTargetTable();
                var isSplitQuery = nestedField.isResolver() && processedSchema.isObjectOrConnectionNodeWithPreviousTableObject(nestedField.getContainerTypeName());
                var hasComplexReferencePath = nestedField.hasFieldReferences() && nestedField.getFieldReferences().size() > 1;

                // Use the same logic as method call generation to determine parameter type
                if ((nestedField.isIterableWrapped() && (isSplitQuery || hasComplexReferencePath)) ||
                    (!nestedField.isIterableWrapped() && nestedField.hasFieldReferences())) {
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

                // Declare remaining aliases that are derived from the base table parameter
                // For fields that receive target table directly, don't declare aliases
                // For fields that receive base table parameters, declare the aliases they need
                // Use the same logic as parameter determination: only declare aliases for fields using base table parameters
                boolean usesTargetTableParameter = (nestedField.isIterableWrapped() && (isSplitQuery || hasComplexReferencePath)) ||
                    (!nestedField.isIterableWrapped() && nestedField.hasFieldReferences());
                boolean shouldDeclareAliases = !usesTargetTableParameter;

                if (shouldDeclareAliases) {
                    for (var alias : allAliases) {
                        var aliasName = alias.getAlias().getMappingName();
                        if (!alias.hasTableMethod() &&
                            (!aliasName.equals(tableParameterAlias))) {
                            // These are derived aliases that need to be declared within the method
                            aliasesToDeclare.add(alias);
                        }
                    }
                }

                if (!aliasesToDeclare.isEmpty()) {
                    methodBuilder.addCode(createAliasDeclarations(aliasesToDeclare));
                }

                return methodBuilder
                        .addCode("return $L;\n", selectRowBlock)
                        .addParameterIf(GeneratorConfig.shouldMakeNodeStrategy(), NODE_ID_STRATEGY.className, VAR_NODE_STRATEGY)
                        .build();
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

}
