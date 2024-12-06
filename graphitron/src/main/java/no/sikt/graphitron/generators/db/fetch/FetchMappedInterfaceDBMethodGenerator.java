package no.sikt.graphitron.generators.db.fetch;

import com.squareup.javapoet.*;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphql.schema.ProcessedSchema;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jooq.*;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;

public class FetchMappedInterfaceDBMethodGenerator extends FetchDBMethodGenerator {

    public FetchMappedInterfaceDBMethodGenerator(
            ObjectDefinition localObject,
            ProcessedSchema processedSchema
    ) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var parser = new InputParser(target, processedSchema);
        var code = notImplementedCodeBlockBuilder().build();

        return getSpecBuilder(target, processedSchema.getInterface(target).getGraphClassName(), parser)
                .addCode(code)
                .build();
    }

    public List<MethodSpec> generateWithSubSelectMethods(ObjectField target) {
        var methods = new ArrayList<MethodSpec>();
        methods.add(generate(target));

        processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(it -> it.implementsInterface(processedSchema.getInterface(target).getName()))
                .forEach(implementation -> {
                    methods.add(
                            MethodSpec
                                    .methodBuilder(getSortFieldsMethodName(target, implementation))
                                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                                    .returns(getReturnTypeForKeysMethod())
                                    .addCode(notImplementedCodeBlockBuilder().build()).build()
                    );

                    methods.add(
                            MethodSpec
                                    .methodBuilder(getMappedMethodName(target, implementation))
                                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                                    .returns(getReturnTypeForMappedMethod(implementation.getGraphClassName()))
                                    .addCode(notImplementedCodeBlockBuilder().build()).build()
                    );
                });
        return methods;
    }

    private static @NotNull String getMappedMethodName(ObjectField target, ObjectDefinition implementation) {
        return String.format("%sFor%s", implementation.getName().toLowerCase(), StringUtils.capitalize(target.getName()));
    }

    private static @NotNull String getSortFieldsMethodName(ObjectField target, ObjectDefinition implementation) {
        return String.format("%sSortFieldsFor%s", implementation.getName().toLowerCase(), StringUtils.capitalize(target.getName()));
    }

    private static CodeBlock.Builder notImplementedCodeBlockBuilder() {
        // Skal åpenbart fjernes når alt har blitt implementert
        return CodeBlock.builder().addStatement("throw new $T()", NotImplementedException.class);
    }

    private static ParameterizedTypeName getReturnTypeForMappedMethod(ClassName implementationClassName) {
        return ParameterizedTypeName.get(
                ClassName.get(SelectJoinStep.class),
                ParameterizedTypeName.get(
                        ClassName.get(Record2.class),
                        ClassName.get(JSON.class),
                        implementationClassName
                )
        );
    }

    private static ParameterizedTypeName getReturnTypeForKeysMethod() {
        return ParameterizedTypeName.get(
                ClassName.get(SelectSeekStepN.class),
                ParameterizedTypeName.get(
                        Record2.class,
                        String.class,
                        JSON.class)
        );
    }

    @Override
    public List<MethodSpec> generateAll() {
        return ((ObjectDefinition) getLocalObject())
                .getFields()
                .stream()
                .filter(processedSchema::isInterface)
                .filter(it -> !it.getTypeName().equals(NODE_TYPE.getName()))
                .filter(GenerationField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .flatMap(it -> generateWithSubSelectMethods(it).stream())
                .filter(it -> !it.code.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        return false;
    }
}
