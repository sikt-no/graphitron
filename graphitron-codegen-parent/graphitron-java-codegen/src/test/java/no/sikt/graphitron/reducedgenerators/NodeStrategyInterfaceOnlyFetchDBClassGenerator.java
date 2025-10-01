package no.sikt.graphitron.reducedgenerators;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.generators.db.*;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.NameFormat.getFormatGeneratedName;

public class NodeStrategyInterfaceOnlyFetchDBClassGenerator extends DBClassGenerator {
    public NodeStrategyInterfaceOnlyFetchDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectField target) {
        return getSpec(
                getFormatGeneratedName(target),
                List.of(
                        new FetchMappedObjectDBMethodGenerator(target, processedSchema),
                        new FetchCountDBMethodGenerator(target, processedSchema),
                        new FetchMultiTableDBMethodGenerator(target, processedSchema)
                )
        ).build();
    }
}
