package no.sikt.graphitron.reducedgenerators;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.generators.db.DBClassGenerator;
import no.sikt.graphitron.generators.db.FetchMultiTableDBMethodGenerator;
import no.sikt.graphitron.generators.db.FetchSingleTableInterfaceDBMethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.NameFormat.getFormatGeneratedName;

public class InterfaceOnlyFetchDBClassGenerator extends DBClassGenerator {
    public InterfaceOnlyFetchDBClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(ObjectField target) {
        TypeSpec typeSpec = getSpec(
                getFormatGeneratedName(target),
                List.of(
                        new FetchMultiTableDBMethodGenerator(target, processedSchema),
                        new FetchSingleTableInterfaceDBMethodGenerator(target, processedSchema)
                )
        ).build();
        warnOrCrashIfMethodsExceedsBounds(typeSpec);
        return typeSpec;
    }
}
