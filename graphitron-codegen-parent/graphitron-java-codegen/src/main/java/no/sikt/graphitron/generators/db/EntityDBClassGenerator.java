package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.NameFormat.getFormatGeneratedName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.ENTITY_CLASS;

/**
 * Class generator that keeps track of all method generators for entity DB queries.
 */
public class EntityDBClassGenerator extends DBClassGenerator {
    public EntityDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public List<TypeSpec> generateAll() {
        var query = processedSchema.getQueryType();
        return processedSchema
                .getEntities()
                .values()
                .stream()
                .filter(RecordObjectSpecification::isEntity)
                .map(it -> new VirtualSourceField(it, query.getName()))
                .map(this::generate)
                .filter(it -> !it.methodSpecs().isEmpty())
                .toList();
    }

    @Override
    public TypeSpec generate(ObjectField target) {
        var typeSpec = getSpec(
                getFormatGeneratedName(ENTITY_CLASS, target.getTypeName()),
                List.of(new EntityDBFetcherMethodGenerator(target, processedSchema))
        ).build();
        warnOrCrashIfMethodsExceedsBounds(typeSpec);
        return typeSpec;
    }
}
