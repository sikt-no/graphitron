package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.List;

/**
 * Produces one table class per {@link GraphitronType.TableType} in the schema.
 *
 * <p>Class names come from {@link TableRef#javaClassName()}, the simple name of the
 * jOOQ-generated table class obtained at catalog resolution time via reflection. This respects any
 * custom jOOQ naming strategy. The GraphQL type name may differ from the table class name.
 *
 * <p>Generated files are placed in the {@code rewrite.tables} sub-package of the configured
 * output package.
 */
public class TableClassGenerator {

    public static List<TypeSpec> generate(GraphitronSchema schema) {
        var codeGenerator = new TableCodeGenerator();
        return schema.types().values().stream()
            .filter(t -> t instanceof GraphitronType.TableType)
            .map(t -> ((GraphitronType.TableType) t).table().javaClassName())
            .distinct()
            .sorted()
            .map(codeGenerator::generate)
            .toList();
    }
}
