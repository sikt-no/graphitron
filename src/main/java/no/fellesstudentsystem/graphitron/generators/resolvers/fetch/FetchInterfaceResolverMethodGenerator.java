package no.fellesstudentsystem.graphitron.generators.resolvers.fetch;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.AbstractObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.InterfaceDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.abstractions.ResolverMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.dependencies.IdHandlerDependency;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.newDataFetcher;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asQueryClass;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Generates resolvers for queries returning an interface. E.g. the node resolver.
 */
public class FetchInterfaceResolverMethodGenerator extends ResolverMethodGenerator<ObjectField> {
    private final ObjectDefinition localObject;

    public FetchInterfaceResolverMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
        this.localObject = localObject;
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        InterfaceDefinition interfaceDefinition = processedSchema.getInterface(target);
        dependencySet.add(IdHandlerDependency.getInstance());

        var spec = getDefaultSpecBuilder(target.getName(), interfaceDefinition.getGraphClassName());

        if (!localObject.isOperationRoot()) {
            spec.addParameter(localObject.getGraphClassName(), uncapitalize(localObject.getName()));
        }

        var inputField = target.getArguments().get(0);
        String nodeId = inputField.getName();
        spec
                .addParameter(iterableWrapType(inputField), nodeId)
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, VARIABLE_ENV)
                .addStatement("$T $L = $N.getTable($N).getName()", STRING.className, TABLE_NAME, VariableNames.NODE_ID_HANDLER_NAME, nodeId)
                .addCode("\n");

        processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(it -> it.implementsInterface(interfaceDefinition.getName()))
                .sorted(Comparator.comparing(AbstractObjectDefinition::getName))
                .map(implementation -> codeForImplementation(target, implementation, nodeId))
                .forEach(spec::addCode);

        return spec.addStatement("throw new $T(\"Could not find dataloader for $N with name \" + $N)", ILLEGAL_ARGUMENT_EXCEPTION.className, nodeId, TABLE_NAME).build();
    }

    private CodeBlock codeForImplementation(ObjectField target, ObjectDefinition implementation, String inputFieldName) {
        var queryLocation = asQueryClass(implementation.getName());

        var queryClass = ClassName.get(GeneratorConfig.outputPackage() + "." + DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + FetchDBClassGenerator.SAVE_DIRECTORY_NAME, queryLocation);
        var dbFunction = CodeBlock.of(
                "($L, $L, $L) -> $T.load$LBy$LsAs$L($N, $N, $N)",
                CONTEXT_NAME,
                IDS_NAME,
                SELECTION_SET_NAME,
                queryClass,
                implementation.getName(),
                capitalize(inputFieldName),
                capitalize(target.getName()),
                CONTEXT_NAME,
                IDS_NAME,
                SELECTION_SET_NAME
        );

        return CodeBlock
                .builder()
                .beginControlFlow("if ($T.$N.getName().equals($N))", implementation.getTable().getTableClass(), implementation.getTable().getMappingName(), TABLE_NAME)
                .addStatement("return $L.$L($N, $N, $L)", newDataFetcher(), "loadInterface", TABLE_NAME, inputFieldName, dbFunction)
                .endControlFlow()
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        return localObject
                .getFields()
                .stream()
                .filter(ObjectField::isGeneratedWithResolver)
                .filter(it -> processedSchema.isInterface(it.getTypeName()))
                .map(this::generate)
                .filter(it -> !it.code.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        return localObject
                .getFields()
                .stream()
                .filter(it -> processedSchema.isInterface(it.getTypeName()))
                .allMatch(ObjectField::isGeneratedWithResolver);
    }
}
