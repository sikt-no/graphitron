package no.sikt.graphitron.generators.frontgen;

import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.internal.ParameterInfo;
import graphql.language.Argument;
import graphql.language.Directive;
import graphql.language.StringValue;
import no.sikt.frontgen.generate.GeneratedQueryComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.InputDefinition;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.upperCase;

public class TableUIComponentGenerator extends AbstractClassGenerator {
    public static final String DEFAULT_SAVE_DIRECTORY_NAME = "frontend";
    public static final String FILE_NAME_SUFFIX = "QueryComponent";

    private final ProcessedSchema processedSchema;

    public TableUIComponentGenerator(ProcessedSchema processedSchema) {
        this.processedSchema = processedSchema;
    }

    @Override
    public List<TypeSpec> generateAll() {
        List<TypeSpec> generatedSpecs = new ArrayList<>();

        // Get query type directly from ProcessedSchema
        ObjectDefinition queryType = processedSchema.getQueryType();
        if (queryType == null) return List.of();

        // Process each connection field
        for (ObjectField field : queryType.getFields()) {
            if (shouldGenerateUIComponent(field, processedSchema)) {
                String connectionTypeName = getConnectionTypeName(field);
                String nodeTypeName = getNodeTypeName(field, connectionTypeName);

                if (connectionTypeName != null) {
                    String className = capitalize(capitalize(field.getName())) + FILE_NAME_SUFFIX;
                    TypeSpec typeSpec = generateComponentClass(className, field, nodeTypeName, connectionTypeName);
                    generatedSpecs.add(typeSpec);
                }
            }
        }

        return generatedSpecs;
    }

    public static boolean shouldGenerateUIComponent(ObjectField field, ProcessedSchema processedSchema) {

        if ( field.getDirectives().stream()
                .map(Directive::getName)
                .anyMatch(it -> it.equals(GenerationDirective.EXCLUDE_FROM_UI.getName())
                        || processedSchema.isInterface(field) || processedSchema.isUnion(field))) {
            return false; // TODO: Handle interfaces and unions
        }

        // Check for @uiComponent or @asConnection directives
//        boolean hasUIDirective = field.hasFieldDirective().stream()
//                .anyMatch(directive -> directive.getName().equals("uiComponent") ||
//                        directive.getName().equals("asConnection"));

        // Or check for Connection type
        boolean isConnectionType = processedSchema.isConnectionObject(field);

//        return hasUIDirective || isConnectionType;
        return isConnectionType;
    }

    private String getConnectionTypeName(ObjectField field) {
        String typeName = field.getTypeName();

        // If it's already a Connection type
        if (processedSchema.isConnectionObject(field)) {
            return typeName;
        }

//        // For fields with @asConnection - convert to Connection pattern
//        boolean hasAsConnection = field.getDirectives().stream()
//                .anyMatch(directive -> directive.getName().equals("asConnection"));
//
//        if (hasAsConnection) {
//            // For list types with @asConnection, use field name + Connection
//            return capitalize(field.getName()) + "Connection";
//        }

        return null;
    }

    private String getNodeTypeName(ObjectField field, String connectionTypeName) {
        if (connectionTypeName == null) return null;

        // If it's a connection, get the node type from ProcessedSchema
        if (processedSchema.isConnectionObject(field)) {
            return processedSchema.getConnectionObject(field).getNodeType();
        }

        // Remove Connection suffix to get node type
        if (connectionTypeName.endsWith("Connection")) {
            return connectionTypeName.substring(0, connectionTypeName.length() - "Connection".length());
        }

        // Handle plurals ending with 's'
        String fieldName = field.getName();
        if (fieldName.endsWith("s")) {
            return capitalize(fieldName.substring(0, fieldName.length() - 1));
        }

        return capitalize(fieldName);
    }

    private TypeSpec generateComponentClass(String className, ObjectField field,
                                            String nodeTypeName, String connectionTypeName) {

        ClassName nodeClass = ClassName.get(GeneratorConfig.generatedModelsPackage(), nodeTypeName);
        ClassName connectionClass = ClassName.get(GeneratorConfig.generatedModelsPackage(), connectionTypeName);

        List<ParameterInfo> parameters = analyzeParameters(field);

        TypeSpec.Builder builder = TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PUBLIC)
                .superclass(ParameterizedTypeName.get(
                        ClassName.get(GeneratedQueryComponent.class), nodeClass, connectionClass))
                .addMethod(generateQueryMethod(field, parameters))
                .addMethod(generateRootFieldMethod(field))
                .addMethod(generateConnectionClassMethod(connectionTypeName))
                .addMethod(generateEdgesFunctionMethod(connectionTypeName))
                .addMethod(generateNodeFunctionMethod(connectionTypeName + "Edge", nodeTypeName))
                .addMethod(generateGridCreatorMethod(field, nodeTypeName))
                .addMethod(generateButtonTextMethod(field));

        // Add parameter handling methods if needed
        if (!parameters.isEmpty()) {
            builder.addMethod(generateHasParametersMethod())
                    .addMethod(generateCreateInputSectionMethod(parameters))
                    .addMethod(generateGetQueryVariablesMethod(parameters))
                    .addMethod(generateValidateInputsMethod(parameters));

            // Add field declarations for input components
            for (ParameterInfo param : parameters) {
                if (param.isNested) {
                    // Add fields for each nested field
                    for (NestedFieldInfo nestedField : param.nestedFields) {
                        String fieldName = param.name + capitalize(nestedField.name) + "Field";
                        Class<?> fieldType = getVaadinComponentType(nestedField.type);
                        builder.addField(fieldType, fieldName, Modifier.PRIVATE);
                    }
                } else {
                    String fieldName = param.name + "Field";
                    Class<?> fieldType = getVaadinComponentType(param.type);
                    builder.addField(fieldType, fieldName, Modifier.PRIVATE);
                }
            }
        }
        return builder.build();
    }

    private Class<?> getVaadinComponentType(String javaType) {
        return switch (javaType) {
            case "Boolean" -> Checkbox.class;
            case "Integer" -> IntegerField.class;
            default -> TextField.class;
        };
    }

    private MethodSpec generateHasParametersMethod() {
        return MethodSpec.methodBuilder("hasParameters")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addStatement("return true")
                .build();
    }

    private MethodSpec generateCreateInputSectionMethod(List<ParameterInfo> parameters) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("createInputSection")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(VerticalLayout.class)
                .addStatement("$T inputLayout = new $T()", VerticalLayout.class, VerticalLayout.class);

        for (ParameterInfo param : parameters) {
            if (param.isNested) {
                // Create a section for nested input
                builder.addStatement("// Fields for $L", param.name);
                for (NestedFieldInfo nestedField : param.nestedFields) {
                    String fieldName = param.name + capitalize(nestedField.name) + "Field";
                    String label = param.name + " " + nestedField.name;
                    Class<?> componentType = getVaadinComponentType(nestedField.type);

                    if (componentType == Checkbox.class) {
                        builder.addStatement("$L = new $T($S)", fieldName, componentType, label);
                    } else {
                        builder.addStatement("$L = new $T($S)", fieldName, componentType, label);
                        if (nestedField.required) {
                            builder.addStatement("$L.setRequired($L)", fieldName, nestedField.required);
                        }
                    }
                    builder.addStatement("inputLayout.add($L)", fieldName);
                }
            } else {
                String fieldName = param.name + "Field";
                Class<?> componentType = getVaadinComponentType(param.type);

                if (componentType == Checkbox.class) {
                    builder.addStatement("$L = new $T($S)", fieldName, componentType, param.name);
                } else {
                    builder.addStatement("$L = new $T($S)", fieldName, componentType, param.name);
                    if (param.required) {
                        builder.addStatement("$L.setRequired($L)", fieldName, param.required);
                    }
                }
                builder.addStatement("inputLayout.add($L)", fieldName);
            }
        }

        builder.addStatement("return inputLayout");
        return builder.build();
    }

    private MethodSpec generateGetQueryVariablesMethod(List<ParameterInfo> parameters) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("getQueryVariables")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(Map.class, String.class, Object.class))
                .addStatement("$T<String, Object> variables = new $T<>()", Map.class, HashMap.class);

        for (ParameterInfo param : parameters) {
            if (param.isNested) {
                // Create nested object
                builder.addStatement("$T<String, Object> $LObj = new $T<>()",
                        Map.class, param.name, HashMap.class);

                boolean hasRequiredCheck = param.nestedFields.stream().anyMatch(f -> f.required);
                if (hasRequiredCheck) {
                    builder.addStatement("boolean $LHasValues = false", param.name);
                }

                for (NestedFieldInfo nestedField : param.nestedFields) {
                    String fieldName = param.name + capitalize(nestedField.name) + "Field";
                    String condition = getFieldCondition(nestedField.type, fieldName);

                    builder.beginControlFlow("if ($L)", condition);

                    if (nestedField.type.equals("Boolean")) {
                        builder.addStatement("$LObj.put($S, $L.getValue())", param.name, nestedField.name, fieldName);
                    } else if (nestedField.type.equals("Integer")) {
                        builder.addStatement("$LObj.put($S, $L.getValue())", param.name, nestedField.name, fieldName);
                    } else {
                        builder.addStatement("$LObj.put($S, $L.getValue())", param.name, nestedField.name, fieldName);
                    }

                    if (hasRequiredCheck) {
                        builder.addStatement("$LHasValues = true", param.name);
                    }

                    builder.endControlFlow();
                }

                if (hasRequiredCheck) {
                    builder.beginControlFlow("if ($LHasValues)", param.name)
                            .addStatement("variables.put($S, $LObj)", param.name, param.name)
                            .endControlFlow();
                } else {
                    builder.beginControlFlow("if (!$LObj.isEmpty())", param.name)
                            .addStatement("variables.put($S, $LObj)", param.name, param.name)
                            .endControlFlow();
                }
            } else {
                String fieldName = param.name + "Field";
                String condition = getFieldCondition(param.type, fieldName);

                builder.beginControlFlow("if ($L)", condition);

                if (param.type.equals("Boolean")) {
                    builder.addStatement("variables.put($S, $L.getValue())", param.name, fieldName);
                } else if (param.type.equals("Integer")) {
                    builder.addStatement("variables.put($S, $L.getValue())", param.name, fieldName);
                } else {
                    builder.addStatement("variables.put($S, $L.getValue())", param.name, fieldName);
                }

                builder.endControlFlow();
            }
        }

        builder.addStatement("return variables");
        return builder.build();
    }

    private String getFieldCondition(String javaType, String fieldName) {
        return switch (javaType) {
            case "Boolean" -> fieldName + " != null"; // Checkbox always has a value
            case "Integer" -> fieldName + " != null && " + fieldName + ".getValue() != null";
            default -> fieldName + " != null && !" + fieldName + ".isEmpty()";
        };
    }

    private MethodSpec generateValidateInputsMethod(List<ParameterInfo> parameters) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("validateInputs")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class);

        if (parameters.isEmpty()) {
            builder.addStatement("return true");
        } else {
            List<String> validationConditions = new ArrayList<>();

            for (ParameterInfo param : parameters) {
                if (param.isNested) {
                    if (param.required) {
                        // For required nested objects, at least one required field must be filled
                        List<String> requiredNestedFields = param.nestedFields.stream()
                                .filter(f -> f.required)
                                .map(f -> param.name + capitalize(f.name) + "Field")
                                .toList();

                        if (!requiredNestedFields.isEmpty()) {
                            String condition = requiredNestedFields.stream()
                                    .map(fieldName -> getValidationCondition(
                                            param.nestedFields.stream()
                                                    .filter(nf -> (param.name + capitalize(nf.name) + "Field").equals(fieldName))
                                                    .findFirst()
                                                    .map(nf -> nf.type)
                                                    .orElse("String"),
                                            fieldName))
                                    .collect(Collectors.joining(" || "));
                            validationConditions.add("(" + condition + ")");
                        }
                    }
                } else {
                    String fieldName = param.name + "Field";
                    if (param.required) {
                        validationConditions.add("(" + getValidationCondition(param.type, fieldName) + ")");
                    }
                }
            }

            if (validationConditions.isEmpty()) {
                builder.addStatement("return true");
            } else {
                String validation = "return " + String.join(" && ", validationConditions);
                builder.addStatement(validation);
            }
        }

        return builder.build();
    }

    private String getValidationCondition(String javaType, String fieldName) {
        return switch (javaType) {
            case "Boolean" -> fieldName + " != null"; // Checkbox is always valid
            case "Integer" -> fieldName + " != null && " + fieldName + ".getValue() != null";
            default -> fieldName + " != null && !" + fieldName + ".isEmpty()";
        };
    }

    private MethodSpec generateQueryMethod(ObjectField field, List<ParameterInfo> parameters) {
        String query = buildGraphQLQuery(field, parameters);

        return MethodSpec.methodBuilder("getQuery")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return $S", query)
                .build();
    }

    private String buildGraphQLQuery(ObjectField field, List<ParameterInfo> parameters) {
        String fieldName = field.getName();
        String connectionTypeName = getConnectionTypeName(field);
        String nodeTypeName = getNodeTypeName(field, connectionTypeName);

        StringBuilder queryBuilder = new StringBuilder("query");

        // Add parameter declarations
        if (!parameters.isEmpty()) {
            queryBuilder.append("(");
            for (int i = 0; i < parameters.size(); i++) {
                if (i > 0) queryBuilder.append(", ");
                ParameterInfo param = parameters.get(i);
                queryBuilder.append("$").append(param.name).append(": ");

                // For nested types, use the original GraphQL type name
                if (param.isNested) {
                    queryBuilder.append(getOriginalGraphQLTypeName(param.name, field));
                } else {
                    queryBuilder.append(mapJavaTypeToGraphQL(param.type));
                }

                if (param.required) queryBuilder.append("!");
            }
            queryBuilder.append(")");
        }

        queryBuilder.append(" { ").append(fieldName).append("(");

        // Add arguments
        boolean firstArg = true;
        for (ParameterInfo param : parameters) {
            if (!firstArg) queryBuilder.append(", ");
            queryBuilder.append(param.name).append(": $").append(param.name);
            firstArg = false;
        }

        // Add pagination arguments
        if (!firstArg) queryBuilder.append(", ");
        queryBuilder.append("first: 100");

        // Build field selections
        StringBuilder queryFields = new StringBuilder();
        if (nodeTypeName != null) {
            ObjectDefinition objectDefinition = processedSchema.getObject(nodeTypeName);
            if (objectDefinition != null) {
                addQueryFields(objectDefinition, queryFields, new HashSet<>());
            }
        }

        queryBuilder.append(") { edges { node {").append(queryFields).append(" } } } }");
        return queryBuilder.toString();
    }

    private String getOriginalGraphQLTypeName(String paramName, ObjectField field) {
        // Find the argument in the field to get its original GraphQL type
        return field.getArguments().stream()
                .filter(arg -> arg.getName().equals(paramName))
                .findFirst()
                .map(arg -> arg.getTypeName().replaceAll("[!\\[\\]]", "")) // Remove nullability and list markers
                .orElse("String"); // fallback
    }

    private String mapJavaTypeToGraphQL(String javaType) {
        return switch (javaType) {
            case "ID" -> "ID";
            case "Integer" -> "Int";
            case "Double" -> "Float";
            case "Boolean" -> "Boolean";
            default -> "String";
        };
    }

    private void addQueryFields(ObjectDefinition definition, StringBuilder queryFields, Set<String> visitedTypes) {
        if (visitedTypes.contains(definition.getName())) {
            return; // Prevent circular references
        }
        visitedTypes.add(definition.getName());

        for (ObjectField nodeField : definition.getFields()) {
            if (nodeField.isGeneratedWithResolver() || nodeField.isResolver()) {
                continue;
            }

            // For scalar fields, just add the field name
            if (!processedSchema.isObject(nodeField)) {
                queryFields.append(" ").append(nodeField.getName());
            }
            // For object fields, add nested field selection
            else {
                ObjectDefinition nestedObject = processedSchema.getObject(nodeField.getTypeName());
                if (nestedObject != null && !visitedTypes.contains(nestedObject.getName()) && !nestedObject.isGeneratedWithResolver()) {
                    queryFields.append(" ").append(nodeField.getName()).append(" { ");
                    addQueryFields(nestedObject, queryFields, new HashSet<>(visitedTypes));
                    queryFields.append(" }");
                }
            }
        }
    }

    private MethodSpec generateRootFieldMethod(ObjectField field) {
        return MethodSpec.methodBuilder("getRootField")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return $S", field.getName())
                .build();
    }

    private MethodSpec generateConnectionClassMethod(String connectionTypeName) {
        ClassName connectionClass = ClassName.get(GeneratorConfig.generatedModelsPackage(), connectionTypeName);

        TypeName returnType = ParameterizedTypeName.get(
                ClassName.get(Class.class),
                connectionClass
        );

        return MethodSpec.methodBuilder("getConnectionClass")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType)
                .addStatement("return $T.class", connectionClass)
                .build();
    }

    private MethodSpec generateEdgesFunctionMethod(String connectionTypeName) {
        ClassName connectionClass = ClassName.get(GeneratorConfig.generatedModelsPackage(), connectionTypeName);

        // Create proper type with wildcards for List<?>
        TypeName listType = ParameterizedTypeName.get(
                ClassName.get(List.class),
                WildcardTypeName.subtypeOf(Object.class)
        );

        // Create the Function<ConnectionClass, List<?>> type
        TypeName returnType = ParameterizedTypeName.get(
                ClassName.get(Function.class),
                connectionClass,
                listType
        );

        return MethodSpec.methodBuilder("getEdgesFunction")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType)
                .addStatement("return $L::getEdges", connectionTypeName)
                .build();
    }

    private MethodSpec generateNodeFunctionMethod(String edgeTypeName, String nodeTypeName) {
        ClassName nodeClass = ClassName.get(GeneratorConfig.generatedModelsPackage(), nodeTypeName);

        TypeName returnType = ParameterizedTypeName.get(
                ClassName.get(Function.class),
                ClassName.get(Object.class),
                nodeClass
        );

        return MethodSpec.methodBuilder("getNodeFunction")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType)
                .addStatement("return edge -> (($T) edge).getNode()",
                        ClassName.get(GeneratorConfig.generatedModelsPackage(), edgeTypeName))
                .build();
    }

    private MethodSpec generateGridCreatorMethod(ObjectField field, String nodeTypeName) {
        ClassName nodeClass = ClassName.get(GeneratorConfig.generatedModelsPackage(), nodeTypeName);

        TypeName gridType = ParameterizedTypeName.get(ClassName.get(Grid.class), nodeClass);
        TypeName listType = ParameterizedTypeName.get(ClassName.get(List.class), nodeClass);

        TypeName returnType = ParameterizedTypeName.get(
                ClassName.get(Function.class),
                listType,
                gridType
        );

        String itemsParam = getItemsParameterName(nodeTypeName);

        MethodSpec.Builder builder = MethodSpec.methodBuilder("getGridCreator")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType)
                .addCode("return $L -> {\n", itemsParam)
                .addStatement("    $T<$T> grid = new $T<>($T.class, false)",
                        Grid.class, nodeClass, Grid.class, nodeClass);

        // Get object definition from schema
        ObjectDefinition objectDefinition = processedSchema.getObject(nodeTypeName);
        if (objectDefinition != null) {
            // Add columns for basic fields
            addBasicColumns(builder, objectDefinition, nodeClass);

            // Add columns for complex object fields
            addComplexColumns(builder, objectDefinition, nodeClass);
        }

        builder.addStatement("    grid.setItems($L)", itemsParam)
                .addStatement("    return grid")
                .addCode("};\n");

        return builder.build();
    }

    private void addBasicColumns(MethodSpec.Builder builder, ObjectDefinition objectDefinition, ClassName nodeClass) {
        for (ObjectField nodeField : objectDefinition.getFields()) {

            // Only add simple scalar fields
            if (!processedSchema.isObject(nodeField) && !nodeField.isIterableWrapped()) {
                String fieldName = nodeField.getName();
                String capitalizedName = capitalize(fieldName);
                String headerText = splitCamelCase(capitalizedName);

                builder.addStatement("    grid.addColumn($T::get$L)\n        .setHeader(\"$L\")\n        .setFlexGrow(1)",
                        nodeClass, capitalizedName, headerText);
            }
        }
    }

    private void addComplexColumns(MethodSpec.Builder builder, ObjectDefinition objectDefinition, ClassName nodeClass) {
        for (ObjectField nodeField : objectDefinition.getFields()) {

            if (processedSchema.isObject(nodeField) && shouldBeGenerated( nodeField)) {
                String fieldName = nodeField.getName();
                String capitalizedFieldName = capitalize(fieldName);
                String headerText = splitCamelCase(capitalizedFieldName);
                ObjectDefinition nestedObjectDef = processedSchema.getObject(nodeField.getTypeName());

                if (nestedObjectDef != null) {
                    List<String> scalarFields = nestedObjectDef.getFields().stream()
                            .filter(f -> !processedSchema.isObject(f) && shouldBeGenerated( f) && !f.getName().equals("id"))
                            .map(ObjectField::getName)
                            .toList();

                    if (!scalarFields.isEmpty()) {
                        // Create a formatted display combining scalar fields
                        builder.addCode("""
                    grid.addColumn(entity -> {
                        $1T $2L = entity.get$3L();
                        if ($2L != null) {
                            StringBuilder sb = new StringBuilder();
                    """, ClassName.get(GeneratorConfig.generatedModelsPackage(), nestedObjectDef.getName()),
                                fieldName, capitalizedFieldName);

                        // Add each field with separator
                        for (int i = 0; i < scalarFields.size(); i++) {
                            String nestedField = scalarFields.get(i);
                            String nestedFieldCapitalized = capitalize(nestedField);

                            if (i == 0) {
                                builder.addCode("""
                            if ($1L.get$2L() != null) {
                                sb.append($1L.get$2L());
                            }
                            """, fieldName, nestedFieldCapitalized);
                            } else {
                                builder.addCode("""
                            if ($1L.get$2L() != null) {
                                if (sb.length() > 0) sb.append(", ");
                                sb.append($1L.get$2L());
                            }
                            """, fieldName, nestedFieldCapitalized);
                            }
                        }

                        builder.addCode("""
                            return sb.length() > 0 ? sb.toString() : "N/A";
                        }
                        return "N/A";
                    })
                    .setHeader("$L")
                    .setFlexGrow(1);

                    """, headerText);
                    }

                    // Handle second-level nested objects
                    for (ObjectField nestedField : nestedObjectDef.getFields()) {
                        if (nestedField.getName().equals("id")) continue;

                        if (processedSchema.isObject(nestedField) && shouldBeGenerated( nestedField)) {
                            String nestedFieldName = nestedField.getName();
                            String nestedCapitalizedName = capitalize(nestedFieldName);
                            String nestedHeaderText = splitCamelCase(capitalizedFieldName + " " + nestedCapitalizedName);
                            ObjectDefinition level2ObjectDef = processedSchema.getObject(nestedField.getTypeName());

                            if (level2ObjectDef != null) {
                                // Get scalar fields of the second-level nested object
                                List<String> level2ScalarFields = level2ObjectDef.getFields().stream()
                                        .filter(f -> !processedSchema.isObject(f) && shouldBeGenerated(f) && !f.getName().equals("id"))
                                        .map(ObjectField::getName)
                                        .toList();

                                if (!level2ScalarFields.isEmpty()) {
                                    builder.addCode("""
                                grid.addColumn(entity -> {
                                    $1T $2L = entity.get$3L();
                                    if ($2L != null) {
                                        $4T $5L = $2L.get$6L();
                                        if ($5L != null) {
                                            StringBuilder sb = new StringBuilder();
                                """, ClassName.get(GeneratorConfig.generatedModelsPackage(), nestedObjectDef.getName()),
                                            fieldName, capitalizedFieldName,
                                            ClassName.get(GeneratorConfig.generatedModelsPackage(), level2ObjectDef.getName()),
                                            nestedFieldName, nestedCapitalizedName);

                                    // Add each field with separator
                                    for (int i = 0; i < level2ScalarFields.size(); i++) {
                                        String level2Field = level2ScalarFields.get(i);
                                        String level2FieldCapitalized = capitalize(level2Field);

                                        if (i == 0) {
                                            builder.addCode("""
                                        if ($1L.get$2L() != null) {
                                            sb.append($1L.get$2L());
                                        }
                                        """, nestedFieldName, level2FieldCapitalized);
                                        } else {
                                            builder.addCode("""
                                        if ($1L.get$2L() != null) {
                                            if (sb.length() > 0) sb.append(", ");
                                            sb.append($1L.get$2L());
                                        }
                                        """, nestedFieldName, level2FieldCapitalized);
                                        }
                                    }

                                    builder.addCode("""
                                            return sb.length() > 0 ? sb.toString() : "N/A";
                                        }
                                    }
                                    return "N/A";
                                })
                                .setHeader("$L")
                                .setFlexGrow(1);
                                
                                """, nestedHeaderText);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean shouldBeGenerated(ObjectField objectField) {
        return !objectField.isIterableWrapped() && !objectField.isResolver() && !objectField.isGeneratedWithResolver();
    }

    // Helper method to convert camelCase to human-readable form
    private String splitCamelCase(String s) {
        return s.replaceAll(
                String.format("%s|%s|%s",
                        "(?<=[A-Z])(?=[A-Z][a-z])",
                        "(?<=[^A-Z])(?=[A-Z])",
                        "(?<=[A-Za-z])(?=[^A-Za-z])"),
                " ");
    }

    private String getItemsParameterName(String nodeTypeName) {
        // Convert 'Customer' to 'customers', 'Film' to 'films'
        return nodeTypeName.substring(0, 1).toLowerCase() + nodeTypeName.substring(1) + "s";
    }

    private MethodSpec generateButtonTextMethod(ObjectField field) {
        String buttonText = getButtonText(field);

        return MethodSpec.methodBuilder("getButtonText")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return $S", buttonText)
                .build();
    }

    private String getButtonText(ObjectField field) {
        // Check for buttonText in directive
        for (Directive directive : field.getDirectives()) {
            if (directive.getName().equals("uiComponent")) {
                for (Argument arg : directive.getArguments()) {
                    if (arg.getName().equals("buttonText") && arg.getValue() instanceof StringValue) {
                        return ((StringValue) arg.getValue()).getValue();
                    }
                }
            }
        }

        // Default text based on field name
        return "List " + capitalize(field.getName());
    }

    private List<ParameterInfo> analyzeParameters(ObjectField field) {
        return field.getArguments().stream()
                .filter(arg -> !arg.getName().equals("first") && !arg.getName().equals("after"))
                .map(arg -> {
                    String typeName = arg.getTypeName().replaceAll("[!\\[\\]]", ""); // Clean type name
                    boolean isNested = processedSchema.isInputType(typeName) || processedSchema.isObject(typeName);
                    return new ParameterInfo(
                            arg.getName(),
                            mapGraphQLTypeToJava(typeName),
                            arg.isNonNullable(),
                            isNested,
                            isNested ? getNestedFields(typeName) : List.of()
                    );
                })
                .toList();
    }

    private List<NestedFieldInfo> getNestedFields(String typeName) {
        InputDefinition inputObject = processedSchema.getInputType(typeName);

        if (inputObject != null) {
            return inputObject.getFields().stream()
                    .map(field -> new NestedFieldInfo(
                            field.getName(),
                            mapGraphQLTypeToJava(field.getTypeName()),
                            field.isNonNullable()
                    ))
                    .toList();
        }
        return List.of();
    }

    private static class ParameterInfo {
        final String name;
        final String type;
        final boolean required;
        final boolean isNested;
        final List<NestedFieldInfo> nestedFields;

        ParameterInfo(String name, String type, boolean required, boolean isNested, List<NestedFieldInfo> nestedFields) {
            this.name = name;
            this.type = type;
            this.required = required;
            this.isNested = isNested;
            this.nestedFields = nestedFields;
        }
    }

    private static class NestedFieldInfo {
        final String name;
        final String type;
        final boolean required;

        NestedFieldInfo(String name, String type, boolean required) {
            this.name = name;
            this.type = type;
            this.required = required;
        }
    }

    private String mapGraphQLTypeToJava(String graphqlType) {
        String baseType = graphqlType.replaceAll("[!\\[\\]]", "");
        return switch (baseType) {
            case "ID" -> "ID";
            case "Int" -> "Integer";
            case "Float" -> "Double";
            case "Boolean" -> "Boolean";
            default -> "String";
        };
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DEFAULT_SAVE_DIRECTORY_NAME;
    }

    @Override
    public String getFileNameSuffix() {
        return FILE_NAME_SUFFIX;
    }

}
