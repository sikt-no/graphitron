package no.sikt.graphitron.generators.abstractions;

import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

abstract public class AbstractSchemaClassGenerator<T extends GenerationTarget> extends AbstractClassGenerator {
    protected final ProcessedSchema processedSchema;

    public AbstractSchemaClassGenerator(ProcessedSchema processedSchema) {
        this.processedSchema = processedSchema;
    }

    /**
     * @param target A {@link GenerationTarget} object representing a source from which a class should be generated.
     * @return A complete class in the form of a javapoet {@link TypeSpec}.
     */
    abstract public TypeSpec generate(T target);
}
