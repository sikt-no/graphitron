package no.sikt.graphitron.rewrite.generators.lookup;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.JooqCatalog;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * {@link no.sikt.graphitron.generators.abstractions.ClassGenerator} that produces one
 * {@code <TypeName>Lookup.java} per {@link no.sikt.graphitron.rewrite.field.QueryField.LookupQueryField}
 * whose argument type is a {@link no.sikt.graphitron.rewrite.type.GraphitronType.TableInputType}.
 *
 * <p>Each generated class contains a {@code toInputRows} method that maps a
 * {@code List<Map<String, Object>>} — the graphql-java representation of a list input argument —
 * into a {@code List<RecordN<Integer, T1, ...>>} for use in a jOOQ derived VALUES table.
 *
 * <p>Generated files are placed in the {@code rewrite.resolvers} sub-package of the configured
 * output package.
 */
public class LookupClassGenerator extends AbstractClassGenerator {

    static final String SAVE_DIRECTORY = "rewrite.resolvers";

    private final List<LookupSpec> specs;
    private final LookupCodeGenerator codeGenerator = new LookupCodeGenerator();
    private final Class<?> tablesClass;

    public LookupClassGenerator(GraphitronSchema schema) {
        var catalog = new JooqCatalog(GeneratorConfig.getGeneratedJooqPackage());
        this.specs = LookupSpecBuilder.build(schema, catalog);
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
    public String getFileNameSuffix() {
        return "";
    }

    @Override
    public void writeToFile(TypeSpec generatedClass, String path, String packagePath, String directoryOverride) {
        var fileBuilder = JavaFile
            .builder(packagePath + "." + directoryOverride, generatedClass)
            .indent("    ");

        addStaticImports(fileBuilder, packagePath);

        try {
            fileBuilder.build().writeTo(new File(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String writeToString(TypeSpec generatedClass) {
        var fileBuilder = JavaFile.builder("", generatedClass).indent("    ");
        addStaticImports(fileBuilder, "");
        return fileBuilder.build().toString();
    }

    private void addStaticImports(JavaFile.Builder fileBuilder, String packagePath) {
        if (tablesClass != null) {
            fileBuilder.addStaticImport(tablesClass, "*");
        }
        var graphitronValuesClass = ClassName.get(
            packagePath.isEmpty() ? "" : packagePath + ".rewrite",
            "GraphitronValues"
        );
        fileBuilder.addStaticImport(graphitronValuesClass, "GRAPHITRON_INPUT_IDX");
    }

    private Class<?> loadTablesClass() {
        try {
            return Class.forName(GeneratorConfig.getGeneratedJooqPackage() + ".Tables");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
