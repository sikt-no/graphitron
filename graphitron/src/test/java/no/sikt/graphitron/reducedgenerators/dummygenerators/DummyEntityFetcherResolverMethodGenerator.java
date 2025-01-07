package no.sikt.graphitron.reducedgenerators.dummygenerators;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.generators.datafetchers.resolvers.fetch.EntityFetcherResolverMethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;

/**
 * This class generates a dummy entity resolver.
 */
public class DummyEntityFetcherResolverMethodGenerator extends EntityFetcherResolverMethodGenerator {
    public DummyEntityFetcherResolverMethodGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        return getDefaultSpecBuilder(METHOD_NAME, DATA_FETCHER_TYPE)
                .addCode(returnWrap(CodeBlock.of("null")))
                .build();
    }
}
