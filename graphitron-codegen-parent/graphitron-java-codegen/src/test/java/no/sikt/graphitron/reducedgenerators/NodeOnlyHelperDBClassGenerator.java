package no.sikt.graphitron.reducedgenerators;

import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.db.DBClassGenerator;
import no.sikt.graphitron.generators.db.NodeSelectHelperDBMethodGenerator;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

public class NodeOnlyHelperDBClassGenerator extends DBClassGenerator {
    public NodeOnlyHelperDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectDefinition target) {
        return getSpec(target.getName(), new NodeSelectHelperDBMethodGenerator(target, processedSchema, objectFieldsReturningNode)).build();
    }
}
