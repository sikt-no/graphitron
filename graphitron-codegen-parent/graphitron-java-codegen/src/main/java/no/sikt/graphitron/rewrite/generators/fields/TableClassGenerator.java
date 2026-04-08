package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.type.TableRef;

import no.sikt.graphitron.rewrite.type.GraphitronType;
import java.util.List;

/**
 * {@link no.sikt.graphitron.generators.abstractions.ClassGenerator} that produces one
 * table class per {@link GraphitronType.TableType} in the schema.
 *
 * <p>Class names are derived from the jOOQ field name in the {@code Tables} class
 * (e.g. {@code FILM} → {@code Film}, {@code FILM_ACTOR} → {@code FilmActor}), which respects
 * any custom jOOQ naming strategy. Only {@link TableRef.ResolvedTable} entries are generated;
 * unresolved tables (not found in the jOOQ catalog) are skipped. The GraphQL type name may
 * differ from the table name.
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
            .map(t -> ((GraphitronType.TableType) t).table())
            .filter(ref -> ref instanceof TableRef.ResolvedTable)
            .map(ref -> TableCodeGenerator.toPascalCase(((TableRef.ResolvedTable) ref).javaFieldName()))
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
