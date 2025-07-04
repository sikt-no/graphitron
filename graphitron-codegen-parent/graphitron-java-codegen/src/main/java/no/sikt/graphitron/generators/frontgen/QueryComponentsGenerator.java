package no.sikt.graphitron.generators.frontgen;

import no.sikt.frontgen.components.QueryBackedView;
import no.sikt.frontgen.components.QueryComponent;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.List;

public class QueryComponentsGenerator extends AbstractClassGenerator {
    public static final String DEFAULT_SAVE_DIRECTORY_NAME = "frontend";
    public static final String FILE_NAME = "QueryComponents";

    private final ProcessedSchema processedSchema;

    public QueryComponentsGenerator(ProcessedSchema processedSchema) {
        this.processedSchema = processedSchema;
    }

    @Override
    public List<TypeSpec> generateAll() {
        TypeSpec queryComponentsClass = generateQueryComponentsClass();
        return List.of(queryComponentsClass);
    }

    private TypeSpec generateQueryComponentsClass() {
        MethodSpec getComponentsMethod = generateGetComponentsMethod();

        return TypeSpec.classBuilder(FILE_NAME)
                .addModifiers(Modifier.PUBLIC)
                .addMethod(getComponentsMethod)
                .build();
    }

    private MethodSpec generateGetComponentsMethod() {
        ObjectDefinition queryType = processedSchema.getQueryType();
        if (queryType == null) {
            return createEmptyGetComponentsMethod();
        }

        TypeName listOfQueryComponent = ParameterizedTypeName.get(
                ClassName.get(List.class),
                ClassName.get(QueryComponent.class)
        );

        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("getComponents")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(QueryBackedView.class, "view")
                .returns(listOfQueryComponent)
                .addCode("return List.of(\n");

        // Track if we need a comma
        boolean first = true;

        // For each connection field in the query type
        for (ObjectField field : queryType.getFields()) {
            if (TableUIComponentGenerator.shouldGenerateUIComponent(field, processedSchema)) {

                String componentClassName = field.getName() + "QueryComponent";

                if (!first) {
                    methodBuilder.addCode(",\n");
                }
                methodBuilder.addCode("        new $T().createComponent(view)",
                        getGeneratedClassName(componentClassName));
                first = false;

            }
        }

        methodBuilder.addCode("\n);\n");

        return methodBuilder.build();
    }

    private MethodSpec createEmptyGetComponentsMethod() {
        TypeName listOfQueryComponent = ParameterizedTypeName.get(
                ClassName.get(List.class),
                ClassName.get(QueryComponent.class)
        );

        return MethodSpec.methodBuilder("getComponents")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(QueryBackedView.class, "view")
                .returns(listOfQueryComponent)
                .addStatement("return List.of()")
                .build();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DEFAULT_SAVE_DIRECTORY_NAME;
    }

    @Override
    public String getFileNameSuffix() {
        return "";
    }
}