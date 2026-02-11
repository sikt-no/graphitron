package no.sikt.graphitron.configuration;

import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.configuration.externalreferences.ExternalReferences;
import no.sikt.graphitron.configuration.externalreferences.GlobalTransform;
import no.sikt.graphitron.configuration.externalreferences.TransformScope;
import no.sikt.graphitron.generate.Generator;
import no.sikt.graphitron.generate.Validator;

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Static generator configuration. Mostly dependent on the settings set through the POM XML.
 */
public class GeneratorConfig {
    private static final String
            PLUGIN_OUTPUT_PATH = "graphitron",
            TEMP_GRAPHQL_GENERATED_PACKAGE = "fake.graphql.example", // Once codegen is fully contained in this module, this will be redundant.
            DEFAULT_API_SUFFIX = ".api",
            DEFAULT_MODEL_SUFFIX = ".model";

    private static final URL GENERATOR_DIRECTIVES_PATH = GeneratorConfig.class.getResource("schema/directives.graphqls");

    private static Set<String> generatorSchemaFiles, userSchema;

    private static String
            outputDirectory,
            outputPackage,
            generatedSchemaResolversPackage,
            generatedSchemaModelsPackage,
            generatedJooqPackage;

    private static boolean makeNodeStrategy = false;
    private static boolean nodeExists = false;
    private static boolean alwaysUsePrimaryKeyInSplitQueries = true;
    private static boolean useJdbcBatchingForDeletes = true;
    private static boolean useJdbcBatchingForInserts = true;
    private static boolean requireTypeIdOnNode = false;
    private static boolean validateOverlappingInputFields = true;

    private static int maxAllowedPageSize;

    private static ExternalReferences externalReferences;
    private static Set<String> externalReferenceImports;
    private static List<GlobalTransform> globalTransforms;

    private static RecordValidation recordValidation;

    private static CodeGenerationThresholds codeGenerationThresholds;

    private static OptionalSelect optionalSelect = new OptionalSelect();

    /**
     * Set the generator properties from code. Intended for tests.
     */
    public static void setProperties(
            Set<String> files,
            String outputDir,
            String outputPkg,
            String jooqPkg,
            List<ExternalReference> references,
            Set<String> imports,
            List<GlobalTransform> globalTransforms
    ) {
        generatorSchemaFiles = files;
        userSchema = files;
        outputDirectory = outputDir;
        outputPackage = outputPkg;

        generatedSchemaResolversPackage = TEMP_GRAPHQL_GENERATED_PACKAGE + DEFAULT_API_SUFFIX;
        generatedSchemaModelsPackage = TEMP_GRAPHQL_GENERATED_PACKAGE + DEFAULT_MODEL_SUFFIX;
        generatedJooqPackage = jooqPkg;
        maxAllowedPageSize = 1000;

        externalReferences = new ExternalReferences(references);
        externalReferenceImports = imports;

        GeneratorConfig.globalTransforms = globalTransforms;
    }

    /**
     * Read all the configurations set in the XML from the provided MOJO.
     */
    public static void loadProperties(Generator mojo) {
        var files = mojo.getSchemaFiles();
        Set<String> inputFiles = Set.of();
        if (files != null) {
            inputFiles = new HashSet<>(files);
            if (GENERATOR_DIRECTIVES_PATH != null) {
                inputFiles.add(GENERATOR_DIRECTIVES_PATH.getPath());
            }
        }

        generatorSchemaFiles = inputFiles;
        userSchema = mojo.getUserSchemaFiles();
        outputDirectory = mojo.getOutputPath() + "/" + PLUGIN_OUTPUT_PATH;
        outputPackage = mojo.getOutputPackage();

        generatedSchemaResolversPackage = mojo.getApiPackageName();
        generatedSchemaModelsPackage = mojo.getModelPackageName();
        generatedJooqPackage = mojo.getJooqGeneratedPackage();
        maxAllowedPageSize = mojo.getMaxAllowedPageSize();

        externalReferences = new ExternalReferences(mojo.getExternalReferences());
        externalReferenceImports = mojo.getExternalReferenceImports();

        globalTransforms = mojo.getGlobalTransforms();
        recordValidation = mojo.getRecordValidation();
        makeNodeStrategy = mojo.makeNodeStrategy();
        useJdbcBatchingForDeletes = mojo.useJdbcBatchingForDeletes();
        useJdbcBatchingForInserts = mojo.useJdbcBatchingForInserts();
        alwaysUsePrimaryKeyInSplitQueries = true;
        codeGenerationThresholds = mojo.getCodeGenerationThresholds();
        requireTypeIdOnNode = mojo.requireTypeIdOnNode();
        optionalSelect = mojo.getOptionalSelect();
        validateOverlappingInputFields = mojo.validateOverlappingInputFields();
    }

    /**
     * Load minimal properties needed for validation only.
     * This is a subset of {@link #loadProperties(Generator)} for the validate goal.
     */
    public static void loadValidatorProperties(Validator validator) {
        var files = validator.getSchemaFiles();
        Set<String> inputFiles = Set.of();
        if (files != null) {
            inputFiles = new HashSet<>(files);
            if (GENERATOR_DIRECTIVES_PATH != null) {
                inputFiles.add(GENERATOR_DIRECTIVES_PATH.getPath());
            }
        }

        generatorSchemaFiles = inputFiles;
        userSchema = files;
        generatedJooqPackage = validator.getJooqGeneratedPackage();
        makeNodeStrategy = validator.makeNodeStrategy();
        requireTypeIdOnNode = validator.requireTypeIdOnNode();

        externalReferences = new ExternalReferences(validator.getExternalReferences());
        externalReferenceImports = validator.getExternalReferenceImports();

        // Set defaults for unused fields to prevent NPEs
        // Note: These packages are needed by ProcessedSchema during construction,
        // even though code generation won't actually use them.
        outputDirectory = null;
        outputPackage = "validation.unused";
        generatedSchemaResolversPackage = "validation.unused.api";
        generatedSchemaModelsPackage = "validation.unused.model";
        maxAllowedPageSize = 1000;
        globalTransforms = List.of();
        recordValidation = new RecordValidation();
        codeGenerationThresholds = new CodeGenerationThresholds();
    }

    /**
     * Clear all configurations. Intended for tests.
     */
    public static void clear() {
        generatorSchemaFiles = null;
        userSchema = null;
        outputDirectory = null;
        outputPackage = null;
        generatedSchemaResolversPackage = null;
        generatedSchemaModelsPackage = null;
        generatedJooqPackage = null;
        externalReferences = new ExternalReferences(List.of());
        externalReferenceImports = Set.of();
        globalTransforms = List.of();
        recordValidation = new RecordValidation();
        codeGenerationThresholds = new CodeGenerationThresholds();
    }

    public static Set<String> generatorSchemaFiles() {
        return generatorSchemaFiles;
    }

    public static Set<String> schemaFiles() {
        return userSchema;
    }

    public static String outputDirectory() {
        return outputDirectory;
    }

    public static String outputPackage() {
        return outputPackage;
    }

    public static String generatedResolversPackage() {
        return generatedSchemaResolversPackage;
    }

    public static String generatedModelsPackage() {
        return generatedSchemaModelsPackage;
    }

    public static String getGeneratedJooqPackage() {
        return generatedJooqPackage;
    }

    public static ExternalReferences getExternalReferences() {
        return externalReferences;
    }

    public static Set<String> getExternalReferenceImports() {
        return externalReferenceImports;
    }

    public static List<GlobalTransform> getGlobalTransforms(TransformScope scope) {
        return globalTransforms
                .stream()
                .filter(it -> it.getScope().equals(scope))
                .collect(Collectors.toList());
    }

    public static RecordValidation getRecordValidation() {
        return recordValidation;
    }

    public static boolean recordValidationEnabled() {
        return recordValidation != null && recordValidation.isEnabled();
    }

    public static boolean shouldMakeNodeStrategy() {
        return makeNodeStrategy && nodeExists;
    }

    public static void setNodeStrategy(boolean shouldUseNodeStrategy) {
        makeNodeStrategy = shouldUseNodeStrategy;
    }

    public static void setNodeExists(boolean exists) {
        nodeExists = exists;
    }

    public static boolean alwaysUsePrimaryKeyInSplitQueries() {
        return alwaysUsePrimaryKeyInSplitQueries;
    }

    public static void setAlwaysUsePrimaryKeyInSplitQueries(boolean usePrimaryKey) {
        alwaysUsePrimaryKeyInSplitQueries = usePrimaryKey;
    }

    public static boolean useJdbcBatchingForDeletes() {
        return useJdbcBatchingForDeletes;
    }

    public static void setUseJdbcBatchingForDeletes(boolean useJdbcBatching) {
        useJdbcBatchingForDeletes = useJdbcBatching;
    }

    public static void setUseJdbcBatchingForAllMutations(boolean useJdbcBatching) {
        setUseJdbcBatchingForDeletes(useJdbcBatching);
        setUseJdbcBatchingForInserts(useJdbcBatching);
    }

    public static boolean useJdbcBatchingForInserts() {
        return useJdbcBatchingForInserts;
    }

    public static void setUseJdbcBatchingForInserts(boolean useJdbcBatching) {
        useJdbcBatchingForInserts = useJdbcBatching;
    }

    public static void setRecordValidation(RecordValidation recordValidation) {
        GeneratorConfig.recordValidation = recordValidation;
    }

    public static void setGeneratorSchemaFiles(String... files) {
        generatorSchemaFiles = Arrays.stream(files).filter(it -> new File(it).exists()).collect(Collectors.toSet());
    }

    public static void setGeneratorSchemaFiles(Set<String> files) {
        generatorSchemaFiles = files;
    }

    public static void setUserSchemaFiles(Set<String> files) {
        userSchema = files;
    }

    public static void setOutputDirectory(String path) {
        outputDirectory = path + "/" + PLUGIN_OUTPUT_PATH;
    }

    public static int getMaxAllowedPageSize() {
        return maxAllowedPageSize;
    }

    public static CodeGenerationThresholds getCodeGenerationThresholds() {
        return codeGenerationThresholds;
    }

    public static void setCodeGenerationThresholds(CodeGenerationThresholds thresholds) {
        codeGenerationThresholds = thresholds;
    }

    public static boolean requireTypeIdOnNode() {
        return requireTypeIdOnNode;
    }

    public static void setRequireTypeIdOnNode(boolean require) {
        requireTypeIdOnNode = require;
    }

    public static boolean validateOverlappingInputFields() {
        return validateOverlappingInputFields;
    }

    public static void setValidateOverlappingInputFields(boolean validate) {
        validateOverlappingInputFields = validate;
    }

    public static void setUseOptionalSelects(boolean enable) {
        optionalSelect.setOnExternalFields(enable);
        optionalSelect.setOnSubqueryReferences(enable);
    }

    public static void setOptionalSelectOnExternalFields(boolean enable) {
        optionalSelect.setOnExternalFields(enable);
    }

    public static void setOptionalSelectOnSubqueries(boolean enable) {
        optionalSelect.setOnSubqueryReferences(enable);
    }

    public static boolean optionalSelectIsEnabled() {
        return optionalSelect.onSubqueryReferences() || optionalSelect.onExternalFields();
    }

    public static boolean optionalSelectOnExternalFieldsIsEnabled() {
        return optionalSelect.onExternalFields();
    }

    public static boolean optionalSelectOnSubqueriesIsEnabled() {
        return optionalSelect.onSubqueryReferences();
    }
}
