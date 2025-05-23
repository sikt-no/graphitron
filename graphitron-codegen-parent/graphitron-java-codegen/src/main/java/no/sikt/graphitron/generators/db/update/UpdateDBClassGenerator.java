package no.sikt.graphitron.generators.db.update;

import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.generators.abstractions.DBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static org.apache.commons.lang3.StringUtils.capitalize;

/**
 * Class generator for basic mutation classes.
 */
public class UpdateDBClassGenerator extends DBClassGenerator<ObjectDefinition> {
    public UpdateDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public List<TypeSpec> generateAll() {
        var mutation = processedSchema.getMutationType();
        if (mutation == null || mutation.isExplicitlyNotGenerated()) {
            return List.of();
        }

        var spec = generate(mutation);
        return !spec.methodSpecs().isEmpty() ? List.of(spec) : List.of();
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(capitalize(target.getName()), new UpdateDBMethodGenerator(target, processedSchema)).build();
    }
}
