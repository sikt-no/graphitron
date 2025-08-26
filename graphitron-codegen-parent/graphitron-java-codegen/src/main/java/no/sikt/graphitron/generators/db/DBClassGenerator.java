package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.configuration.CodeGenerationThresholds;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.AbstractSchemaClassGenerator;
import no.sikt.graphitron.generators.abstractions.MethodGenerator;
import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.mappings.TableReflection;
import no.sikt.graphql.schema.ProcessedSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;

/**
 * Class generator that keeps track of all method generators for DB queries.
 */
public class DBClassGenerator extends AbstractSchemaClassGenerator<ObjectDefinition> {
    public static final String DEFAULT_SAVE_DIRECTORY_NAME = "queries", FILE_NAME_SUFFIX = "DBQueries";
    protected final Set<ObjectField> objectFieldsReturningNode;
    private static final CodeGenerationThresholds CODE_GENERATION_THRESHOLDS = GeneratorConfig.getCodeGenerationThresholds();
    static final Logger LOGGER = LoggerFactory.getLogger(DBClassGenerator.class);

    public DBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);

        objectFieldsReturningNode = processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(ObjectDefinition::isGeneratedWithResolver)
                .map(ObjectDefinition::getFields)
                .flatMap(List::stream)
                .filter(ObjectField::isGenerated)
                .filter(processedSchema::isInterface)
                .filter(it -> processedSchema.getInterface(it).getName().equals(NODE_TYPE.getName()))
                .collect(Collectors.toSet());
    }

    @Override
    public List<TypeSpec> generateAll() {
        return processedSchema
                .getObjects()
                .values()
                .stream()
                .filter(Objects::nonNull)
                .filter(it -> it.isGeneratedWithResolver() || it.isEntity() || (!objectFieldsReturningNode.isEmpty()))
                .map(this::generate)
                .filter(it -> !it.methodSpecs().isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        TypeSpec typeSpec = getSpec(
                target.getName(),
                List.of(
                        new FetchMappedObjectDBMethodGenerator(target, processedSchema),
                        new FetchCountDBMethodGenerator(target, processedSchema),
                        new FetchNodeImplementationDBMethodGenerator(target, processedSchema, objectFieldsReturningNode),
                        new FetchMultiTableDBMethodGenerator(target, processedSchema),
                        new FetchSingleTableInterfaceDBMethodGenerator(target, processedSchema),
                        new UpdateDBMethodGenerator(target, processedSchema),
                        new EntityDBFetcherMethodGenerator(target, processedSchema)
                )
        ).build();
        warnOrCrashIfMethodsExceedsBounds(typeSpec.methodSpecs());
        return typeSpec;
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DEFAULT_SAVE_DIRECTORY_NAME;
    }

    @Override
    public TypeSpec.Builder getSpec(String className, List<? extends MethodGenerator> generators) {
        var spec = super.getSpec(className, generators);
        setDependencies(generators, spec);
        return spec;
    }

    protected Set<Class<?>> getStaticImports() {
        return new HashSet<>(TableReflection.getClassFromSchemas("Tables"));
    }

    @Override
    public void writeToFile(TypeSpec generatedClass, String path, String packagePath, String directoryOverride) {
        var fileBuilder = JavaFile
                .builder(packagePath + "." + directoryOverride, generatedClass)
                .indent("    ");

        getStaticImports().forEach(it -> fileBuilder.addStaticImport(it, "*"));

        var file = fileBuilder.build();
        try {
            file.writeTo(new File(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String writeToString(TypeSpec generatedClass) {
        var fileBuilder = JavaFile.builder("", generatedClass).indent("    ");
        getStaticImports().forEach(it -> fileBuilder.addStaticImport(it, "*"));
        return fileBuilder.build().toString();
    }

    @Override
    public String getFileNameSuffix() {
        return FILE_NAME_SUFFIX;
    }

    protected void warnOrCrashIfMethodsExceedsBounds(List<MethodSpec> methods) {
        if (methods.isEmpty()) {
            return;
        }

        var thresholds = new CodeGenerationThresholds(methods);
        var upperBoundMessages = thresholds.getUpperBoundMessages();
        var crashPointMessages = thresholds.getCrashPointMessages();

        if (!upperBoundMessages.isEmpty()) {
            LOGGER.warn(
                    "Code generation upper bound has exceeded for the following methods, this may cause performance issues:\n{}",
                    String.join("\n", upperBoundMessages)
            );
        }

        if (!crashPointMessages.isEmpty()) {
            throw new IllegalArgumentException(
                    "Code generation crash point has exceeded for the following methods:\n"
                            + String.join("\n", crashPointMessages)
            );
        }
    }
}
