package no.sikt.graphitron.generators.resolvers.fetch;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.AbstractObjectDefinition;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.ResolverClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.empty;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.indentIfMultiline;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.NODE_MAP_NAME;
import static no.sikt.graphitron.mappings.JavaPoetClassName.MAP;
import static no.sikt.graphitron.mappings.JavaPoetClassName.STRING;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_MUTATION;

/**
 * Class generator for basic select resolver classes.
 */
public class FetchResolverClassGenerator extends ResolverClassGenerator<ObjectDefinition> {
    public final String SAVE_DIRECTORY_NAME = "query";

    public FetchResolverClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public List<TypeSpec> generateTypeSpecs() {
        return processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(ObjectDefinition::isGenerated)
                .filter(obj -> !obj.getName().equals(SCHEMA_MUTATION.getName()))
                .map(this::generate)
                .filter(it -> !it.methodSpecs().isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        var spec = getSpec(
                target.getName(),
                List.of(
                        new FetchResolverMethodGenerator(target, processedSchema),
                        new FetchNodeResolverMethodGenerator(target, processedSchema)
                )
        );
        target
                .getFields()
                .stream()
                .filter(it -> it.getTypeName().equals(NODE_TYPE.getName()))
                .findFirst()
                .flatMap(this::buildNodeMap)
                .ifPresent(spec::addField);
        return spec.build();
    }

    public Optional<FieldSpec> buildNodeMap(ObjectField target) {
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
                                return empty(); // Can not handle having duplicate tables for nodes.
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
                        .builder(ParameterizedTypeName.get(MAP.className, STRING.className, STRING.className), NODE_MAP_NAME)
                        .addModifiers(Modifier.PRIVATE, Modifier.FINAL, Modifier.STATIC)
                        .initializer(CodeBlock.of("$T.ofEntries($L)", MAP.className, indentIfMultiline(nodes)))
                        .build()
        );
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return ResolverClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + SAVE_DIRECTORY_NAME;
    }
}
