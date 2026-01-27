package no.sikt.graphitron.mojo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generate.Introspector;
import no.sikt.graphitron.mappings.TableReflection;
import no.sikt.graphitron.mojo.lsp.LspConfig;
import no.sikt.graphitron.mojo.lsp.LspConfig.TableConfig;
import no.sikt.graphitron.mojo.lsp.LspConfig.TableDefinition;
import no.sikt.graphitron.mojo.lsp.LspConfig.TableReference;
import no.sikt.graphitron.mojo.lsp.LspConfig.TypeConfig;
import no.sikt.graphitron.validation.ValidationHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.jooq.ForeignKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Mojo for generating LSP configuration from jOOQ introspection.
 * Produces a JSON file containing table metadata and foreign key relationships.
 * <p>
 * Inherits shared configuration from AbstractGraphitronMojo:
 * - jooqGeneratedPackage: The jOOQ generated classes package
 * - schemaFiles, makeNodeStrategy, requireTypeIdOnNode (inherited but not used)
 */
@Mojo(name = "introspect", requiresDependencyResolution = ResolutionScope.COMPILE)
@Execute(phase = LifecyclePhase.GENERATE_RESOURCES)
public class IntrospectMojo extends AbstractGraphitronMojo implements Introspector {

    /**
     * Output path for the LSP configuration JSON file.
     */
    @Parameter(property = "graphitron.introspect.outputFile", defaultValue = "${project.build.directory}/graphitron-lsp-config.json")
    private String outputFile;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public void execute() throws MojoExecutionException {
        try {
            ValidationHandler.resetErrorMessages();
            ValidationHandler.resetWarningMessages();
            GeneratorConfig.loadIntrospectorProperties(this);

            var config = buildLspConfig();
            writeConfig(config);

            getLog().info("LSP configuration written to: " + outputFile);
        } catch (Exception e) {
            ValidationHandler.logWarnings();
            throw new MojoExecutionException("\n" + e.getMessage(), e);
        }
    }

    private LspConfig buildLspConfig() {
        var tableNames = TableReflection.getTableNames();
        var tables = new ArrayList<TableConfig>();

        for (var tableName : tableNames) {
            var references = buildReferences(tableName);
            var tableConfig = new TableConfig(
                    tableName,
                    "",
                    new TableDefinition("/tables/" + tableName, 1, 1),
                    references,
                    List.of() // Empty functions array
            );
            tables.add(tableConfig);
        }

        return new LspConfig(tables, buildScalarTypes());
    }

    private List<TableReference> buildReferences(String tableName) {
        var references = new ArrayList<TableReference>();
        var table = TableReflection.getTable(tableName).orElse(null);
        if (table == null) {
            return references;
        }

        // Get outgoing foreign keys (this table references others)
        for (var fk : table.getReferences()) {
            var targetTable = fk.getKey().getTable().getName().toUpperCase();
            references.add(new TableReference(
                    targetTable,
                    getKeyFieldName(fk),
                    false // Not inverse - this table owns the FK
            ));
        }

        // Get incoming foreign keys (other tables reference this one)
        for (var otherTableName : TableReflection.getTableNames()) {
            if (otherTableName.equals(tableName)) {
                continue;
            }
            var otherTable = TableReflection.getTable(otherTableName).orElse(null);
            if (otherTable == null) {
                continue;
            }
            for (var fk : otherTable.getReferencesTo(table)) {
                references.add(new TableReference(
                        otherTableName,
                        getKeyFieldName(fk),
                        true // Inverse - other table owns the FK
                ));
            }
        }

        return references;
    }

    private String getKeyFieldName(ForeignKey<?, ?> fk) {
        // Convert FK name to Java field name format used in jOOQ Keys class: TABLENAME__FKNAME
        return fk.getTable().getName().toUpperCase() + "__" + fk.getName().toUpperCase();
    }

    private List<TypeConfig> buildScalarTypes() {
        return List.of(
                new TypeConfig("Int", List.of(), ""),
                new TypeConfig("Float", List.of(), ""),
                new TypeConfig("String", List.of(), ""),
                new TypeConfig("Boolean", List.of(), ""),
                new TypeConfig("ID", List.of(), "")
        );
    }

    private void writeConfig(LspConfig config) throws IOException {
        var path = Path.of(outputFile);
        Files.createDirectories(path.getParent());
        MAPPER.writeValue(path.toFile(), config);
    }

    // Introspector-specific method (jooqGeneratedPackage inherited from AbstractGraphitronMojo)

    @Override
    public String getOutputFile() {
        return outputFile;
    }
}
