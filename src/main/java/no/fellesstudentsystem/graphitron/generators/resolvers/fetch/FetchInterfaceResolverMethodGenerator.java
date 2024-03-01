package no.fellesstudentsystem.graphitron.generators.resolvers.fetch;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.AbstractObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.InterfaceDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.ResolverMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames;
import no.fellesstudentsystem.graphitron.generators.dependencies.QueryDependency;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asQueryClass;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.*;
import static no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator.SAVE_DIRECTORY_NAME;
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

        var spec = getDefaultSpecBuilder(target.getName(), interfaceDefinition.getGraphClassName());

        if (!localObject.isRoot()) {
            spec.addParameter(localObject.getGraphClassName(), uncapitalize(localObject.getName()));
        }

        var inputField = target.getArguments().get(0);
        String inputFieldName = inputField.getName();
        spec
                .addParameter(inputIterableWrap(inputField), inputFieldName)
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, VARIABLE_ENV)
                .addStatement("$T $L = $T.getTablePartOf($N)", STRING.className, TABLE_OF_ID, FIELD_HELPERS.className, inputFieldName)
                .addCode("\n");

        processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(it -> it.implementsInterface(interfaceDefinition.getName()))
                .sorted(Comparator.comparing(AbstractObjectDefinition::getName))
                .map(implementation -> codeForImplementation(target, implementation, inputFieldName))
                .forEach(spec::addCode);

        return spec.addStatement("throw new $T(\"Could not find dataloader for $N with prefix \" + $N)", ILLEGAL_ARGUMENT_EXCEPTION.className, inputFieldName, TABLE_OF_ID).build();
    }

    private CodeBlock codeForImplementation(ObjectField target, ObjectDefinition implementation, String inputFieldName) {
        var queryLocation = asQueryClass(implementation.getName());
        dependencySet.add(new QueryDependency(queryLocation, SAVE_DIRECTORY_NAME));

        var dbFunction = CodeBlock.of(
                "($L, $L) -> $N.load$LBy$LsAs$L($N, $N, $N)",
                IDS_NAME,
                SELECTION_SET_NAME,
                uncapitalize(queryLocation),
                implementation.getName(),
                capitalize(inputFieldName),
                capitalize(target.getName()),
                VariableNames.CONTEXT_NAME,
                IDS_NAME,
                SELECTION_SET_NAME
        );

        return CodeBlock
                .builder()
                .beginControlFlow("if ($N.equals($T.$N.getViewId().toString()))", TABLE_OF_ID, TABLES.className, implementation.getTable().getMappingName())
                .addStatement("return $T.loadInterfaceData($N, $N, $N, $L)", DATA_LOADERS.className, VARIABLE_ENV, TABLE_OF_ID, inputFieldName, dbFunction)
                .endControlFlow()
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        return localObject
                .getFields()
                .stream()
                .filter(ObjectField::isGenerated)
                .filter(it -> processedSchema.isInterface(it.getTypeName()))
                .map(this::generate)
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        return localObject
                .getFields()
                .stream()
                .filter(it -> processedSchema.isInterface(it.getTypeName()))
                .allMatch(ObjectField::isGenerated);
    }
}
