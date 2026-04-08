package no.sikt.graphitron.rewrite.generators.lookup;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.generators.AbstractRewriteClassGenerator;

import java.util.List;

/**
 * Generates one {@code <TypeName>Lookup.java} per
 * {@link no.sikt.graphitron.rewrite.field.QueryField.LookupQueryField} whose argument type is a
 * {@link no.sikt.graphitron.rewrite.type.GraphitronType.TableInputType}.
 *
 * <p>Each generated class contains a {@code toInputRows} method that maps a
 * {@code List<Map<String, Object>>} — the graphql-java representation of a list input argument —
 * into a {@code List<RecordN<Integer, T1, ...>>} for use in a jOOQ derived VALUES table.
 *
 * <p>Generated files are placed in the {@code rewrite.resolvers} sub-package of the configured
 * output package.
 */
public class LookupClassGenerator extends AbstractRewriteClassGenerator {

    static final String SAVE_DIRECTORY = "rewrite.resolvers";

    private final List<LookupSpec> specs;
    private final LookupCodeGenerator codeGenerator = new LookupCodeGenerator();

    public LookupClassGenerator(GraphitronSchema schema) {
        this.specs = LookupSpecBuilder.build(schema);
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
