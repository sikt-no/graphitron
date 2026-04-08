package no.sikt.graphitron.rewrite.generators.lookup;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.JavaFile;
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
    private final Class<?> tablesClass;

    public LookupClassGenerator(GraphitronSchema schema) {
        this.specs = LookupSpecBuilder.build(schema);
        this.tablesClass = loadTablesClass();
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

    @Override
    protected JavaFile.Builder buildFile(TypeSpec spec, String packageName) {
        var builder = super.buildFile(spec, packageName);
        if (tablesClass != null) {
            builder.addStaticImport(tablesClass, "*");
        }
        // packageName is either "" (rendering to string) or the full output package
        // (e.g. "com.example.rewrite.resolvers"). GraphitronValues lives at {basePkg}.rewrite.
        var basePkg = basePkg(packageName);
        var graphitronValuesClass = ClassName.get(
            basePkg.isEmpty() ? "" : basePkg + ".rewrite",
            "GraphitronValues"
        );
        builder.addStaticImport(graphitronValuesClass, "GRAPHITRON_INPUT_IDX");
        return builder;
    }

    private static String basePkg(String packageName) {
        if (packageName.isEmpty()) return "";
        var suffix = "." + SAVE_DIRECTORY;
        return packageName.endsWith(suffix)
            ? packageName.substring(0, packageName.length() - suffix.length())
            : packageName;
    }

    private Class<?> loadTablesClass() {
        try {
            return Class.forName(GeneratorConfig.getGeneratedJooqPackage() + ".Tables");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
