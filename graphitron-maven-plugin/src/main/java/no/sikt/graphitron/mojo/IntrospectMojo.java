package no.sikt.graphitron.mojo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.helpers.ScalarUtils;
import no.sikt.graphitron.generate.Introspector;
import no.sikt.graphitron.mappings.TableReflection;
import no.sikt.graphitron.mojo.lsp.LspConfig;
import no.sikt.graphitron.mojo.lsp.LspConfig.ExternalReferenceConfig;
import no.sikt.graphitron.mojo.lsp.LspConfig.FieldConfig;
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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

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

    // Mapping from Java class canonical name to GraphQL type name (inverted from ScalarUtils)
    private Map<String, String> javaToGraphQLTypeMapping;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            ValidationHandler.resetErrorMessages();
            ValidationHandler.resetWarningMessages();
            GeneratorConfig.loadIntrospectorProperties(this);

            initializeTypeMapping();
            var config = buildLspConfig();
            writeConfig(config);

            getLog().info("LSP configuration written to: " + outputFile);
        } catch (Exception e) {
            ValidationHandler.logWarnings();
            throw new MojoExecutionException("\n" + e.getMessage(), e);
        }
    }

    /**
     * Initialize the Java class to GraphQL type mapping by inverting the ScalarUtils mapping.
     * ScalarUtils maps GraphQL type name -> Java class name, we need the reverse.
     */
    private void initializeTypeMapping() {
        ScalarUtils.initialize(Set.of());
        var graphqlToJavaMapping = ScalarUtils.getInstance().getScalarTypeNameMapping();

        // Invert the mapping: Java class name -> GraphQL type name
        // Note: Multiple GraphQL types might map to the same Java type (e.g., ID and String both map to java.lang.String)
        // We prefer the base type (String over ID) by processing in order
        javaToGraphQLTypeMapping = graphqlToJavaMapping.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getValue,
                        Map.Entry::getKey,
                        (existing, replacement) -> existing // Keep first occurrence
                ));
    }

    private LspConfig buildLspConfig() {
        var tableNames = TableReflection.getTableNames();
        var tables = new ArrayList<TableConfig>();

        for (var tableName : tableNames) {
            var references = buildReferences(tableName);
            var fields = buildFields(tableName);
            var tableConfig = new TableConfig(
                    tableName,
                    "",
                    new TableDefinition("/tables/" + tableName, 1, 1),
                    references,
                    fields
            );
            tables.add(tableConfig);
        }

        return new LspConfig(tables, buildScalarTypes(), buildExternalReferences());
    }

    private List<TableReference> buildReferences(String tableName) {
        var references = new ArrayList<TableReference>();
        var table = TableReflection.getTableByJavaFieldName(tableName).orElse(null);
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
            var otherTable = TableReflection.getTableByJavaFieldName(otherTableName).orElse(null);
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

    private List<FieldConfig> buildFields(String tableName) {
        var fields = new ArrayList<FieldConfig>();
        var table = TableReflection.getTableByJavaFieldName(tableName).orElse(null);
        if (table == null) {
            return fields;
        }

        for (var field : table.fields()) {
            var fieldConfig = new FieldConfig(
                    field.getName(),
                    getGraphQLTypeName(field.getDataType().getType()),
                    field.getDataType().nullable()
            );
            fields.add(fieldConfig);
        }

        return fields;
    }

    /**
     * Convert a Java class to its corresponding GraphQL type name.
     * Falls back to the simple class name if no mapping is found.
     */
    private String getGraphQLTypeName(Class<?> javaType) {
        var canonicalName = javaType.getCanonicalName();
        return javaToGraphQLTypeMapping.getOrDefault(canonicalName, javaType.getSimpleName());
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

    private List<ExternalReferenceConfig> buildExternalReferences() {
        var result = new ArrayList<ExternalReferenceConfig>();

        // Add explicitly configured external references
        var refs = getExternalReferences();
        if (refs != null) {
            for (var ref : refs) {
                result.add(buildExternalReferenceConfig(ref.name(), ref.classReference()));
            }
        }

        // Scan import packages for classes
        var imports = getExternalReferenceImports();
        if (imports != null) {
            for (var packageName : imports) {
                for (var clazz : scanPackage(packageName)) {
                    result.add(buildExternalReferenceConfig(clazz.getSimpleName(), clazz));
                }
            }
        }

        return result;
    }

    private ExternalReferenceConfig buildExternalReferenceConfig(String name, Class<?> clazz) {
        var methods = Arrays.stream(clazz.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(java.lang.reflect.Method::getName)
                .distinct()
                .sorted()
                .toList();
        return new ExternalReferenceConfig(name, clazz.getCanonicalName(), methods);
    }

    private List<Class<?>> scanPackage(String packageName) {
        var path = packageName.replace('.', '/');
        var classLoader = Thread.currentThread().getContextClassLoader();
        var classes = new ArrayList<Class<?>>();

        try {
            Enumeration<URL> resources = classLoader.getResources(path);
            while (resources.hasMoreElements()) {
                var resource = resources.nextElement();
                var protocol = resource.getProtocol();

                if ("file".equals(protocol)) {
                    var directory = new File(resource.toURI());
                    scanDirectory(directory, packageName, classes);
                } else if ("jar".equals(protocol)) {
                    var jarPath = resource.getPath().substring(5, resource.getPath().indexOf("!"));
                    scanJar(jarPath, path, packageName, classes);
                }
            }
        } catch (Exception e) {
            getLog().warn("Failed to scan package " + packageName + ": " + e.getMessage());
        }

        return classes;
    }

    private void scanDirectory(File directory, String packageName, List<Class<?>> classes) {
        var files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (var file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + "." + file.getName(), classes);
            } else if (file.isFile() && file.getName().endsWith(".class")) {
                var className = packageName + "." + file.getName().replace(".class", "");
                loadClass(className, classes);
            }
        }
    }

    private void scanJar(String jarPath, String packagePath, String packageName, List<Class<?>> classes) throws IOException {
        try (var jar = new JarFile(jarPath)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var entryName = entry.getName();
                if (entryName.startsWith(packagePath + "/") && entryName.endsWith(".class") && !entryName.contains("$")) {
                    var relativeName = entryName.substring(packagePath.length() + 1);
                    var className = packageName + "." + relativeName.replace("/", ".").replace(".class", "");
                    loadClass(className, classes);
                }
            }
        }
    }

    private void loadClass(String className, List<Class<?>> classes) {
        try {
            classes.add(Class.forName(className, false, Thread.currentThread().getContextClassLoader()));
        } catch (ClassNotFoundException e) {
            getLog().warn("Could not load class " + className + ": " + e.getMessage());
        }
    }

    @Override
    public String getOutputFile() {
        return outputFile;
    }
}
