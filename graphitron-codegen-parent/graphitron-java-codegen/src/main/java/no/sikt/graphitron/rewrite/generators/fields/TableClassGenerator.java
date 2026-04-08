package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.type.GraphitronType;

import java.util.List;

/**
 * {@link no.sikt.graphitron.generators.abstractions.ClassGenerator} that produces one
 * table class per {@link GraphitronType.TableType} in the schema.
 *
 * <p>Classes are named after the SQL table in PascalCase (e.g. {@code Film} for table
 * {@code film}, {@code FilmActor} for table {@code film_actor}), not after the GraphQL type
 * name, which may differ.
 *
 * <p>Generated files are placed in the {@code rewrite.tables} sub-package of the configured
 * output package.
 */
public class TableClassGenerator extends AbstractClassGenerator {

    static final String SAVE_DIRECTORY = "rewrite.tables";

    private final List<String> tableNames;
    private final TableCodeGenerator codeGenerator = new TableCodeGenerator();

    public TableClassGenerator(GraphitronSchema schema) {
        this.tableNames = schema.types().values().stream()
            .filter(t -> t instanceof GraphitronType.TableType)
            .map(t -> TableCodeGenerator.toPascalCase(((GraphitronType.TableType) t).table().tableName()))
            .distinct()
            .sorted()
            .toList();
    }

    @Override
    public List<TypeSpec> generateAll() {
        return tableNames.stream()
            .map(codeGenerator::generate)
            .toList();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return SAVE_DIRECTORY;
    }

    @Override
    public String getFileNameSuffix() {
        return "";
    }
}
