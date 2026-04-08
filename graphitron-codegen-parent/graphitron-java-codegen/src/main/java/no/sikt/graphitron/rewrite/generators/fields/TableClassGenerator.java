package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.generators.AbstractRewriteClassGenerator;
import no.sikt.graphitron.rewrite.type.GraphitronType;
import no.sikt.graphitron.rewrite.type.TableRef;
import java.util.List;

/**
 * {@link AbstractRewriteClassGenerator} that produces one
 * table class per {@link GraphitronType.TableType} in the schema.
 *
 * <p>Class names come from {@link TableRef.ResolvedTable#javaClassName()}, the simple name of the
 * jOOQ-generated table class obtained at catalog resolution time via reflection. This respects any
 * custom jOOQ naming strategy. Only resolved tables are generated; unresolved entries (not found
 * in the jOOQ catalog) are skipped. The GraphQL type name may differ from the table class name.
 *
 * <p>Generated files are placed in the {@code rewrite.tables} sub-package of the configured
 * output package.
 */
public class TableClassGenerator extends AbstractRewriteClassGenerator {

    static final String SAVE_DIRECTORY = "rewrite.tables";

    private final List<String> tableNames;
    private final TableCodeGenerator codeGenerator = new TableCodeGenerator();

    public TableClassGenerator(GraphitronSchema schema) {
        this.tableNames = schema.types().values().stream()
            .filter(t -> t instanceof GraphitronType.TableType)
            .map(t -> ((GraphitronType.TableType) t).table())
            .filter(ref -> ref instanceof TableRef.ResolvedTable)
            .map(ref -> ((TableRef.ResolvedTable) ref).javaClassName())
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
