package no.sikt.graphitron.generators.datafetchers.operations;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.AbstractObjectDefinition;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DataFetcherClassGenerator;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.indentIfMultiline;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_NODE_MAP;
import static no.sikt.graphitron.mappings.JavaPoetClassName.MAP;
import static no.sikt.graphitron.mappings.JavaPoetClassName.STRING;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;

/**
 * Class generator for any data fetchers classes.
 */
public class OperationClassGenerator extends DataFetcherClassGenerator<ObjectDefinition> {
    public static final String SAVE_DIRECTORY_NAME = "operations";

    public OperationClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public List<TypeSpec> generateAll() {
        return processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(ObjectDefinition::isGenerated)
                .map(this::generate)
                .filter(it -> !it.methodSpecs().isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        var generators = List.of(
                new FetchEntitiesMethodGenerator(target, processedSchema),
                new OperationMethodGenerator(target, processedSchema),
                new FetchNodeMethodGenerator(target, processedSchema)
        );
        var spec = getSpec(target.getName(), generators);
        target
                .getFields()
                .stream()
                .filter(it -> it.getTypeName().equals(NODE_TYPE.getName()) && !GeneratorConfig.shouldMakeNodeStrategy())
                .findFirst()
                .flatMap(this::buildNodeMap)
                .ifPresent(spec::addField);
        var className = getGeneratedClassName(target.getName() + getFileNameSuffix());
        generators.forEach(it -> addFetchers(it.getDataFetcherWiring(), className));
        return spec.build();
    }

    protected Optional<FieldSpec> buildNodeMap(ObjectField target) {
        var interfaceDefinition = processedSchema.getInterface(target);
        var interfaceName = interfaceDefinition.getName();
        var seenTables = new HashSet<String>();
        var nodes = processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(it -> it.implementsInterface(interfaceName))
                .sorted(Comparator.comparing(AbstractObjectDefinition::getName))
                .map(it -> {
                            var table = it.getTable();
                            if (seenTables.contains(table.getMappingName())) {
                                return CodeBlock.empty(); // Can not handle having duplicate tables for nodes.
                            }

                            seenTables.add(table.getMappingName());
                            return CodeBlock.of(
                                    "$T.entry($T.$N.getName(), $S)",
                                    MAP.className,
                                    table.getTableClass(),
                                    table.getMappingName(),
                                    it.getName()
                            );
                        }
                )
                .filter(it -> !it.isEmpty())
                .collect(CodeBlock.joining(",\n"));

        if (nodes.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(
                FieldSpec
                        .builder(ParameterizedTypeName.get(MAP.className, STRING.className, STRING.className), VAR_NODE_MAP)
                        .addModifiers(Modifier.PRIVATE, Modifier.FINAL, Modifier.STATIC)
                        .initializer(CodeBlock.of("$T.ofEntries($L)", MAP.className, indentIfMultiline(nodes)))
                        .build()
        );
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DataFetcherClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + SAVE_DIRECTORY_NAME;
    }
}
