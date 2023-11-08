package no.fellesstudentsystem.graphitron.generators.resolvers;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.AbstractObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.InterfaceDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.ResolverMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.dependencies.Dependency;
import no.fellesstudentsystem.graphitron.generators.dependencies.QueryDependency;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBClassGenerator;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.generators.db.FetchDBClassGenerator.SAVE_DIRECTORY_NAME;
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
        TypeName returnClassName = interfaceDefinition.getGraphClassName();

        var spec = getDefaultSpecBuilder(target.getName(), returnClassName);

        if (!localObject.isRoot()) {
            spec.addParameter(localObject.getGraphClassName(), uncapitalize(localObject.getName()));
        }

        var inputField = target.getInputFields().get(0);
        String inputFieldName = inputField.getName();
        spec
                .addParameter(inputIterableWrap(inputField), inputFieldName)
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, ENV_NAME)
                .addStatement("String tablePartOfId = $T.getTablePartOf($N)", FIELD_HELPERS.className, inputFieldName)
                .addCode("\n");

        processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(it -> it.implementsInterface(interfaceDefinition.getName()))
                .sorted(Comparator.comparing(AbstractObjectDefinition::getName))
                .forEach(implementation -> {
                    var queryLocation = implementation.getName() + DBClassGenerator.FILE_NAME_SUFFIX;
                    var map = Map.ofEntries(
                            Map.entry("env", ENV_NAME),
                            Map.entry("string", STRING.className),
                            Map.entry("returnType", returnClassName),
                            Map.entry("dataloader", DATA_LOADER_FACTORY.className),
                            Map.entry("batchLoader", MAPPED_BATCH_LOADER_WITH_CONTEXT.className),
                            Map.entry("implementationClass", implementation.getGraphClassName()),
                            Map.entry("future", COMPLETABLE_FUTURE.className),
                            Map.entry("queryInstanceField", uncapitalize(queryLocation)),
                            Map.entry("implName", implementation.getName()),
                            Map.entry("inputFieldName", inputFieldName),
                            Map.entry("inputFieldNameCap", capitalize(inputFieldName)),
                            Map.entry("selectionSets", SELECTION_SET.className),
                            Map.entry("environmentUtils", ENVIRONMENT_UTILS.className),
                            Map.entry("referenceFieldName", capitalize(target.getName())),
                            Map.entry("context", Dependency.CONTEXT_NAME)
                    );
                    dependencySet.add(new QueryDependency(queryLocation, SAVE_DIRECTORY_NAME));

                    spec.beginControlFlow("if (tablePartOfId.equals($T.$N.getViewId().toString()))", TABLES.className, implementation.getTable().getMappingName());
                    spec.addStatement(CodeBlock.builder().addNamed(
                                    "return $env:N.getDataLoaderRegistry().<$string:T, $returnType:T>computeIfAbsent(tablePartOfId, name ->$W" +
                                            "$dataloader:T.newMappedDataLoader(($batchLoader:T<$string:T, $implementationClass:T>) (keys, loaderEnvironment) ->$>$W" +
                                            "$future:T.completedFuture($queryInstanceField:N.load$implName:NBy$inputFieldNameCap:NsAs$referenceFieldName:N" +
                                            "($context:N, keys, new $selectionSets:T($environmentUtils:T.getSelectionSetsFromEnvironment(loaderEnvironment))))))" +
                                            "$<$W.load($inputFieldName:N, $env:N)", map)
                            .build());
                    spec.endControlFlow();
                });

        spec.addStatement("throw new $T(\"could not find dataloader for $N with prefix \" + tablePartOfId)", ILLEGAL_ARGUMENT_EXCEPTION.className, inputFieldName);

        return spec.build();
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
