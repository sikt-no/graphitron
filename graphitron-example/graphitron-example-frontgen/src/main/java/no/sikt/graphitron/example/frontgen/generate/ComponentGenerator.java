package no.sikt.graphitron.example.frontgen.generate;

import com.vaadin.flow.component.grid.Grid;
import graphql.language.Argument;
import graphql.language.Directive;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.StringValue;
import graphql.language.Type;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphitron.generators.abstractions.MethodGenerator;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.apache.commons.lang3.StringUtils.capitalize;

public class ComponentGenerator extends AbstractClassGenerator {
    public static final String DEFAULT_SAVE_DIRECTORY_NAME = "frontend";
    public static final String FILE_NAME_SUFFIX = "QueryComponent";

    private static final String GENERATED_MODELS_PACKAGE = "no.sikt.graphitron.example.generated.graphitron.model";
    private static final String GENERATED_PACKAGE = "no.sikt.graphitron.example.frontgen.generate.generated";
    private static final String BASE_COMPONENT_CLASS = "no.sikt.graphitron.example.frontgen.generate.GeneratedQueryComponent";

    private final ProcessedSchema processedSchema;

    public ComponentGenerator(ProcessedSchema processedSchema) {
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
            if (shouldGenerateUIComponent(field)) {
                String connectionTypeName = getConnectionTypeName(field);
                String nodeTypeName = getNodeTypeName(field, connectionTypeName);

                if (connectionTypeName != null && nodeTypeName != null) {
                    String className = nodeTypeName + FILE_NAME_SUFFIX;
                    TypeSpec typeSpec = generateComponentClass(className, field, nodeTypeName, connectionTypeName);
                    generatedSpecs.add(typeSpec);
                }
            }
        }

        return generatedSpecs;
    }

    private boolean shouldGenerateUIComponent(ObjectField field) {
        // Check for @uiComponent or @asConnection directives
//        boolean hasUIDirective = field.hasFieldDirective().stream()
//                .anyMatch(directive -> directive.getName().equals("uiComponent") ||
//                        directive.getName().equals("asConnection"));

        // Or check for Connection type
        boolean isConnectionType = processedSchema.isConnectionObject(field);

//        return hasUIDirective || isConnectionType;
        //return isConnectionType;
        return true;
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


    private String getTypeName(Type<?> type) {
        if (type instanceof ListType t) {
            return getTypeName(t.getType());
        }
        if (type instanceof NonNullType t) {
            return getTypeName(t.getType());
        }
        if (type instanceof TypeName t) {
            return t.toString();
        }
        return "";
    }

    private TypeSpec generateComponentClass(String className, ObjectField field,
                                            String nodeTypeName, String connectionTypeName) {

        ClassName nodeClass = ClassName.get(GENERATED_MODELS_PACKAGE, nodeTypeName);
        ClassName connectionClass = ClassName.get(GENERATED_MODELS_PACKAGE, connectionTypeName);

        return TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PUBLIC)
                .superclass(ParameterizedTypeName.get(
                        ClassName.bestGuess(BASE_COMPONENT_CLASS), nodeClass, connectionClass))
                .addMethod(generateQueryMethod(field))
                .addMethod(generateRootFieldMethod(field))
                .addMethod(generateConnectionClassMethod(connectionTypeName))
                .addMethod(generateEdgesFunctionMethod(connectionTypeName))
                .addMethod(generateNodeFunctionMethod(connectionTypeName + "Edge", nodeTypeName))
                .addMethod(generateGridCreatorMethod(field, nodeTypeName))
                .addMethod(generateButtonTextMethod(field))
                .build();
    }

    private MethodSpec generateQueryMethod(ObjectField field) {
        String query = buildGraphQLQuery(field);

        return MethodSpec.methodBuilder("getQuery")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(String.class)
                .addStatement("return $S", query)
                .build();
    }

    private String buildGraphQLQuery(ObjectField field) {
        String fieldName = field.getName();

        // Extract field selections from the schema definition (simplified version)
        StringBuilder queryFields = new StringBuilder("id"); // Always include ID

        // In a full implementation, you would analyze the schema to determine proper fields
        if (fieldName.equals("films")) {
            queryFields.append(" title");
        } else if (fieldName.equals("customers")) {
            queryFields.append(" email name { firstName lastName } ")
                    .append("address { addressLine1 addressLine2 city { name countryName } }");
        }

        // Format the query with connection pattern
        return String.format("query { %s(first: 100) { edges { node { %s } } } }",
                fieldName, queryFields);
    }

    private MethodSpec generateRootFieldMethod(ObjectField field) {
        return MethodSpec.methodBuilder("getRootField")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(String.class)
                .addStatement("return $S", field.getName())
                .build();
    }

    private MethodSpec generateConnectionClassMethod(String connectionTypeName) {
        ClassName connectionClass = ClassName.get(GENERATED_MODELS_PACKAGE, connectionTypeName);

        TypeName returnType = ParameterizedTypeName.get(
                ClassName.get(Class.class),
                connectionClass
        );

        return MethodSpec.methodBuilder("getConnectionClass")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(returnType)
                .addStatement("return $T.class", connectionClass)
                .build();
    }

    private MethodSpec generateEdgesFunctionMethod(String connectionTypeName) {
        ClassName connectionClass = ClassName.get(GENERATED_MODELS_PACKAGE, connectionTypeName);

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
                .addModifiers(Modifier.PROTECTED)
                .returns(returnType)
                .addStatement("return $L::getEdges", connectionTypeName)
                .build();
    }

    private MethodSpec generateNodeFunctionMethod(String edgeTypeName, String nodeTypeName) {
        ClassName nodeClass = ClassName.get(GENERATED_MODELS_PACKAGE, nodeTypeName);

        TypeName returnType = ParameterizedTypeName.get(
                ClassName.get(Function.class),
                ClassName.get(Object.class),
                nodeClass
        );

        return MethodSpec.methodBuilder("getNodeFunction")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(returnType)
                .addStatement("return edge -> (($T) edge).getNode()",
                        ClassName.get(GENERATED_MODELS_PACKAGE, edgeTypeName))
                .build();
    }

    private MethodSpec generateGridCreatorMethod(ObjectField field, String nodeTypeName) {
        ClassName nodeClass = ClassName.get(GENERATED_MODELS_PACKAGE, nodeTypeName);

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
                .addModifiers(Modifier.PROTECTED)
                .returns(returnType)
                .addCode("return $L -> {\n", itemsParam)
                .addStatement("    $T<$T> grid = new $T<>($T.class, false)",
                        Grid.class, nodeClass, Grid.class, nodeClass);

        // Add base ID column
        builder.addStatement("    grid.addColumn($T::getId)\n        .setHeader(\"ID\")\n        .setFlexGrow(1)",
                nodeClass);

        // Add specific columns based on type
        if (nodeTypeName.equals("Film")) {
            builder.addStatement("    grid.addColumn($T::getTitle)\n        .setHeader(\"Title\")\n        .setFlexGrow(2)",
                    nodeClass);
        }
        else if (nodeTypeName.equals("Customer")) {
            builder.addStatement("    grid.addColumn($T::getEmail)\n        .setHeader(\"Email\")\n        .setFlexGrow(1)",
                    nodeClass);

            // Add name column with complex mapping logic
            builder.addCode("""
                    grid.addColumn(customer -> {
                        CustomerName name = customer.getName();
                        return name != null ? name.getFirstName() + " " + name.getLastName() : "N/A";
                    })
                    .setHeader("Full Name")
                    .setFlexGrow(1);
                    
                    """);

            // Add address column with complex mapping logic
            builder.addCode("""
                    grid.addColumn(customer -> {
                        Address address = customer.getAddress();
                        if (address != null) {
                            StringBuilder addressText = new StringBuilder();
                            if (address.getAddressLine1() != null) {
                                addressText.append(address.getAddressLine1());
                            }
                            if (address.getAddressLine2() != null && !address.getAddressLine2().isEmpty()) {
                                addressText.append(", ").append(address.getAddressLine2());
                            }
                            if (address.getCity() != null) {
                                addressText.append(", ").append(address.getCity().getName());
                                if (address.getCity().getCountryName() != null) {
                                    addressText.append(", ").append(address.getCity().getCountryName());
                                }
                            }
                            return addressText.toString();
                        }
                        return "N/A";
                    })
                    .setHeader("Address")
                    .setFlexGrow(2);
                    
                    """);
        }

        builder.addStatement("    grid.setItems($L)", itemsParam)
                .addStatement("    return grid")
                .addCode("};\n");

        return builder.build();
    }

    private String getItemsParameterName(String nodeTypeName) {
        // Convert 'Customer' to 'customers', 'Film' to 'films'
        return nodeTypeName.substring(0, 1).toLowerCase() + nodeTypeName.substring(1) + "s";
    }

    private MethodSpec generateButtonTextMethod(ObjectField field) {
        String buttonText = getButtonText(field);

        return MethodSpec.methodBuilder("getButtonText")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
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

    @Override
    public void generateAllToDirectory(String path, String packagePath) {
        List<TypeSpec> specs = generateAll();
        for (TypeSpec spec : specs) {
            writeToFile(spec, path, packagePath);
        }
    }

    @Override
    public void writeToFile(TypeSpec generatedClass, String path, String packagePath) {
        writeToFile(generatedClass, path, packagePath, DEFAULT_SAVE_DIRECTORY_NAME);
    }

    @Override
    public void writeToFile(TypeSpec generatedClass, String path, String packagePath, String directoryOverride) {
        try {
            String targetPackage = packagePath != null && !packagePath.isEmpty()
                                   ? packagePath + "." + GENERATED_PACKAGE
                                   : GENERATED_PACKAGE;

            JavaFile javaFile = JavaFile.builder(targetPackage, generatedClass)
                    .indent("    ")
                    .build();

            javaFile.writeTo(Paths.get(path).toFile());
            System.out.println("Successfully generated: " + generatedClass.name());
        } catch (IOException e) {
            System.err.println("Error writing file for " + generatedClass.name() + ": " + e.getMessage());
        }
    }

    @Override
    public Map<String, String> generateAllAsMap() {
        Map<String, String> result = new HashMap<>();

        List<TypeSpec> specs = generateAll();
        for (TypeSpec spec : specs) {
            String content = writeToString(spec);
            result.put(spec.name(), content);
        }

        return result;
    }

    @Override
    public String writeToString(TypeSpec generatedClass) {
        JavaFile javaFile = JavaFile.builder(GENERATED_PACKAGE, generatedClass)
                .indent("    ")
                .build();

        return javaFile.toString();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DEFAULT_SAVE_DIRECTORY_NAME;
    }

    @Override
    public String getFileNameSuffix() {
        return FILE_NAME_SUFFIX;
    }

    @Override
    public TypeSpec.Builder getSpec(String className, List<? extends MethodGenerator> generators) {
        // This method is not directly used in our implementation approach
        // We generate complete TypeSpecs in generateComponentClass method
        return TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PUBLIC);
    }
}
