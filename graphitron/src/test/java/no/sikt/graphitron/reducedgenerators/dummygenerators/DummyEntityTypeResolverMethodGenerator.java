package no.sikt.graphitron.reducedgenerators.dummygenerators;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.objects.UnionDefinition;
import no.sikt.graphitron.generators.datafetchers.resolvers.fetch.EntityTypeResolverMethodGenerator;
import no.sikt.graphitron.generators.datafetchers.wiring.WiringContainer;
import no.sikt.graphql.schema.ProcessedSchema;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asTypeResolverMethodName;

/**
 * This class generates the entity type resolver.
 */
public class DummyEntityTypeResolverMethodGenerator extends EntityTypeResolverMethodGenerator {
    public DummyEntityTypeResolverMethodGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public MethodSpec generate(UnionDefinition target) {
        typeResolverWiring.add(new WiringContainer(asTypeResolverMethodName(METHOD_NAME), target.getName(), null));
        return getDefaultSpecBuilder(target.getName())
                .addCode(returnWrap(CodeBlock.of("null")))
                .build();
    }
}
