package no.sikt.graphitron.rewrite.generators.splitquery;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.generators.AbstractRewriteClassGenerator;

import java.util.List;

/**
 * Generates one {@code <ParentTypeName><FieldName>DerivedSource.java} per
 * {@link no.sikt.graphitron.rewrite.field.ChildField.TableField} annotated with
 * {@code @splitQuery}.
 *
 * <p>Each generated class contains a {@code rows} method that maps a
 * {@code List<Record>} — the parent records supplied by the DataLoader — into a
 * {@code List<RowN<Integer, T1, ...>>} for use in a jOOQ derived VALUES table. The first
 * column is always {@code GRAPHITRON_INPUT_IDX} (1-based row position), preserving input-to-output
 * ordering when JOINed against the child table.
 *
 * <p>Generated files are placed in the {@code rewrite.resolvers} sub-package of the configured
 * output package.
 */
public class SplitSourceClassGenerator extends AbstractRewriteClassGenerator {

    static final String SAVE_DIRECTORY = "rewrite.resolvers";

    private final List<SplitSourceSpec> specs;
    private final SplitSourceCodeGenerator codeGenerator;

    public SplitSourceClassGenerator(GraphitronSchema schema) {
        this.specs = SplitSourceSpecBuilder.build(schema);
        var tablesClass = ClassName.get(GeneratorConfig.getGeneratedJooqPackage(), "Tables");
        this.codeGenerator = new SplitSourceCodeGenerator(tablesClass);
    }

    @Override
    public List<TypeSpec> generateAll() {
        return specs.stream()
            .map(codeGenerator::generate)
            .toList();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return SAVE_DIRECTORY;
    }
}
