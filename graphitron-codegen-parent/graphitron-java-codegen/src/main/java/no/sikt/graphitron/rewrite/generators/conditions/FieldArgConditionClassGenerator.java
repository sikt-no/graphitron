package no.sikt.graphitron.rewrite.generators.conditions;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.JooqCatalog;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * {@link no.sikt.graphitron.generators.abstractions.ClassGenerator} that produces one
 * {@code <TypeName>Conditions.java} per table-mapped return type that has at least one
 * {@code @condition}-annotated query argument.
 *
 * <p>The generated class contains a single {@code public static Condition conditions(...)} method
 * whose parameters correspond to the filterable arguments. Null-safe: passing {@code null} for any
 * argument omits that condition entirely.
 *
 * <p>Generated files are placed in the {@code rewrite.resolvers} sub-package of the configured
 * output package.
 */
public class FieldArgConditionClassGenerator extends AbstractClassGenerator {

    static final String SAVE_DIRECTORY = "rewrite.resolvers";

    private final List<FieldArgConditionSpec> specs;
    private final FieldArgConditionCodeGenerator codeGenerator = new FieldArgConditionCodeGenerator();
    private final Class<?> tablesClass;

    public FieldArgConditionClassGenerator(GraphitronSchema schema) {
        var catalog = new JooqCatalog(GeneratorConfig.getGeneratedJooqPackage());
        this.specs = FieldArgConditionSpecBuilder.build(schema, catalog);
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

        if (tablesClass != null) {
            fileBuilder.addStaticImport(tablesClass, "*");
        }

        try {
            fileBuilder.build().writeTo(new File(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String writeToString(TypeSpec generatedClass) {
        var fileBuilder = JavaFile.builder("", generatedClass).indent("    ");
        if (tablesClass != null) {
            fileBuilder.addStaticImport(tablesClass, "*");
        }
        return fileBuilder.build().toString();
    }

    private Class<?> loadTablesClass() {
        try {
            return Class.forName(GeneratorConfig.getGeneratedJooqPackage() + ".Tables");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
