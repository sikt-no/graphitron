package no.sikt.graphitron.generators.abstractions;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.definitions.objects.AbstractObjectDefinition;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.empty;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.indentIfMultiline;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.NODE_MAP_NAME;
import static no.sikt.graphitron.mappings.JavaPoetClassName.MAP;
import static no.sikt.graphitron.mappings.JavaPoetClassName.STRING;

/**
 * Superclass for any select resolver generator classes.
 */
abstract public class ResolverClassGenerator<T extends GenerationTarget> extends AbstractSchemaClassGenerator<T> {
    public ResolverClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
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
}
