package no.sikt.graphitron.reducedgenerators.dummygenerators;

import com.palantir.javapoet.CodeBlock;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.generators.mapping.TransformerMethodGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;

public class DummyTransformerMethodGenerator extends TransformerMethodGenerator {
    public DummyTransformerMethodGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    protected CodeBlock getMethodContent(GenerationField target) {
        return returnWrap(target.isInput() ? CodeBlock.of("new $T()", processedSchema.getRecordType(target).getRecordClassName()) : CodeBlock.of("null"));
    }
}
