package no.sikt.graphitron.reducedgenerators.dummygenerators;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.generators.mapping.TransformerListMethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.listOf;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;

public class DummyTransformerListMethodGenerator extends TransformerListMethodGenerator {
    public DummyTransformerListMethodGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    protected CodeBlock getMethodContent(GenerationField target) {
        return returnWrap(listOf());
    }
}
