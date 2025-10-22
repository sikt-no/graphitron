package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.fields.ObjectField;
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
import static no.sikt.graphitron.generators.codebuilding.VariableNames.ORDER_FIELDS_NAME;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphitron.mappings.TableReflection.tableHasPrimaryKey;
import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_ENTITIES_FIELD;

/**
 * Generator that creates the default data fetching methods
 */
public class FetchMappedObjectDBMethodGenerator extends FetchDBMethodGenerator {
    private ObjectField currentParentField = null;
    private boolean isGeneratingHelperMethod = false;

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
                .addCode("return $N\n", VariableNames.CONTEXT_NAME)
                .indent()
                .indent()
                .addCode(".select($L)\n", selectBlock)
                .addCodeIf(!querySource.isEmpty() && (context.hasNonSubqueryFields() || context.hasApplicableTable()), ".from($L)\n", querySource)
                .addCode(createSelectJoins(context.getJoinSet()))
                .addCode(whereBlock)
                .addCode(createSelectConditions(context.getConditionList(), !whereBlock.isEmpty()))
                .addCodeIf(!target.isResolver() && !orderFields.isEmpty(), ".orderBy($L)\n", ORDER_FIELDS_NAME)
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
                        CodeBlock.ofIf(target.hasForwardPagination() && !target.isResolver(), "$T.getOrderByToken($L, $L),\n", QUERY_HELPER.className, actualRefTable, ORDER_FIELDS_NAME),
                        selectRowBlock
                ).collect(CodeBlock.joining())
        );
    }
    
    private CodeBlock createSelectBlockWithHelperMethod(ObjectField target, FetchContext context, String actualRefTable) {
        var keyBlock = getInitialKey(context);
        var tokenBlock = CodeBlock.ofIf(target.hasForwardPagination() && !target.isResolver(), "$T.getOrderByToken($L, $L),\n", QUERY_HELPER.className, actualRefTable, ORDER_FIELDS_NAME);
        var helperMethodName = generateHelperMethodName(target);
        
        // Collect input parameters from InputParser
        var parser = new InputParser(target, processedSchema);
        var inputParameters = new java.util.ArrayList<String>();
        
        // Add method input parameters
        for (var entry : parser.getMethodInputsWithOrderField().entrySet()) {
            inputParameters.add(entry.getKey());
        }
        
        // Collect all aliases that need to be passed to the helper method
        var refContext = target.isResolver() ? context.nextContext(target) : context;
        var aliasSet = refContext.getAliasSet();
        var tableParameters = new java.util.ArrayList<String>();
        
        for (var alias : aliasSet) {
            if (!alias.hasTableMethod()) {
                tableParameters.add(alias.getAlias().getMappingName());
            }
        }
        
        // Combine all parameters
        var allParameters = new java.util.ArrayList<String>();
        allParameters.addAll(inputParameters);
        allParameters.addAll(tableParameters);
        
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
            if (isNested && currentParentField != null) {
                // For nested fields, use parent helper method name + field name
                var parentHelperName = generateHelperMethodName(currentParentField);
                helperMethodName = parentHelperName + "_" + field.getName();
            } else {
                // For top-level fields, use standard naming
                helperMethodName = generateHelperMethodName(field);
            }
            
            // For nested fields, only pass the target table alias, not all aliases
            var targetAlias = context.getTargetAlias();
            return CodeBlock.of("$L($L)", helperMethodName, targetAlias);
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
            return CodeBlock.statementOf(".fetch$L(it -> it.into($T.class))", referenceField.isIterableWrapped() ? "" : "One", referenceField.getTypeClass());
        }

        if (referenceField.hasForwardPagination()) {
            return getPaginationFetchBlock();
        }

        var lookupExists = LookupHelpers.lookupExists(referenceField, processedSchema);
        if (isRoot && !lookupExists) {
            return CodeBlock.statementOf(".fetch$L(it -> it.into($T.class))", referenceField.isIterableWrapped() ? "" : "One", refObject.getGraphClassName());
        }

        var code = CodeBlock.builder()
                .add(".fetchMap(")
                .addIf(lookupExists, "$T::value1, ", RECORD2.className)
                .addIf(!lookupExists, "r -> r.value1().valuesRow(), ");

        if (processedSchema.isObjectOrConnectionNodeWithPreviousTableObject(referenceField.getContainerTypeName()) && referenceField.isIterableWrapped() && !lookupExists || referenceField.hasForwardPagination()) {
            if (referenceField.hasForwardPagination() && (referenceField.getOrderField().isPresent() || tableHasPrimaryKey(refObject.getTable().getName()))) {
                return code.addStatement("r -> r.value2().map($T::value2))", RECORD2.className).build();
            }

            return code.addStatement("r -> r.value2().map($T::value1))", RECORD1.className).build();
        }

        return code.addStatement("$T::value2)", RECORD2.className).build();
    }

    private CodeBlock getPaginationFetchBlock() {
        var code = CodeBlock.builder();

        if (isRoot) {
            code.add(".fetch()\n");
            code.addStatement(".map(it -> new $T<>(it.value1(), it.value2()))", IMMUTABLE_PAIR.className);
        } else {
            code
                    .add(".fetchMap(\n")
                    .indent()
                    .add("r -> r.value1().valuesRow(),\n")
                    .add("it ->  it.value2().map(r -> r.value2() == null ? null : new $T<>(r.value1(), r.value2()))", IMMUTABLE_PAIR.className)
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
                .filter(it -> processedSchema.isRecordType(it))  // Only generate helpers for record types
                .toList();
        
        var helperMethods = new java.util.ArrayList<MethodSpec>();
        
        // Generate helper methods for top-level fields
        for (var field : topLevelFields) {
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
            
            // Clear current parent field
            currentParentField = null;
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
        
        try {
            var context = new FetchContext(processedSchema, target, getLocalObject(), false);
            var refContext = target.isResolver() ? context.nextContext(target) : context;

            var returnType = processedSchema.getRecordType(target).getGraphClassName();
            var selectRowBlock = generateSelectRow(refContext);  // Use refContext to get the correct table context
            var helperMethodName = generateHelperMethodName(target);

        var methodBuilder = MethodSpec.methodBuilder(helperMethodName)
                .addModifiers(PRIVATE, STATIC)
                .returns(ParameterizedTypeName.get(SELECT_FIELD.className, returnType));
        
        // Add input parameters from the original method
        var parser = new InputParser(target, processedSchema);
        methodBuilder.addParameters(getMethodParametersWithOrderField(parser));
        
        // For split queries, only use the target table parameter
        if (target.isResolver() && processedSchema.isObjectOrConnectionNodeWithPreviousTableObject(target.getContainerTypeName())) {
            var targetAlias = refContext.getTargetAlias();
            var targetTable = refContext.getTargetTable();
            methodBuilder.addParameter(targetTable.getTableClass(), targetAlias);
        } else {
            // For non-split queries, add parameters for all table aliases used
            var aliasSet = refContext.getAliasSet();
            for (var alias : aliasSet) {
                if (alias.hasTableMethod()) {
                    // Skip service dependencies - they don't need table parameters
                    continue;
                }
                var tableMapping = alias.getAlias().getTable();
                var underlyingTable = tableMapping.getTable();
                methodBuilder.addParameter(underlyingTable.getTableClass(), alias.getAlias().getMappingName());
            }
        }
        
            var method = methodBuilder
                    .addCode("return $L;\n", selectRowBlock)
                    .build();
            
            return method;
        } finally {
            // Clear the current parent field and reset flag after processing
            currentParentField = null;
            isGeneratingHelperMethod = false;
        }
    }
    
    private java.util.List<MethodSpec> generateNestedHelperMethods(ObjectField parentField) {
        return generateNestedHelperMethods(parentField, new java.util.HashSet<>());
    }
    
    private java.util.List<MethodSpec> generateNestedHelperMethods(ObjectField parentField, java.util.Set<String> visitedTypes) {
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
                processedSchema.getRecordType(objectField) != null) {
                
                // Generate helper method for this nested field
                var nestedHelperMethod = generateNestedHelperMethod(parentField, objectField);
                if (!nestedHelperMethod.code().isEmpty()) {
                    nestedMethods.add(nestedHelperMethod);
                }
                
                // Recursively generate methods for deeper nesting with cycle detection
                nestedMethods.addAll(generateNestedHelperMethods(objectField, new java.util.HashSet<>(visitedTypes)));
            }
        }
        
        return nestedMethods;
    }
    
    private MethodSpec generateNestedHelperMethod(ObjectField parentField, ObjectField nestedField) {
        var returnType = processedSchema.getRecordType(nestedField).getGraphClassName();
        
        // Generate method name like: queryForQuery_outer_customers
        // Pattern: [parentHelperMethod]_[fieldName]
        var parentHelperMethodName = generateHelperMethodName(parentField);
        var fieldName = nestedField.getName();
        var helperMethodName = parentHelperMethodName + "_" + fieldName;
        
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
            
            // Add parameter for the nested table alias using the mapping name
            var nestedAliasSet = refContext.getAliasSet();
            for (var alias : nestedAliasSet) {
                if (!alias.hasTableMethod()) {
                    var tableMapping = alias.getAlias().getTable();
                    var underlyingTable = tableMapping.getTable();
                    methodBuilder.addParameter(underlyingTable.getTableClass(), alias.getAlias().getMappingName());
                    break; // Only add one parameter for the target table
                }
            }
            
            return methodBuilder
                    .addCode("return $L;\n", selectRowBlock)
                    .build();
        } else {
            // For record types without tables, just generate the simple mapping
            // This handles cases like Wrapper type without @table directive
            return methodBuilder
                    .addCode("return DSL.row().mapping($T::new);\n", returnType)
                    .build();
        }
    }
}
