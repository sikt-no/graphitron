package no.sikt.graphitron.rewrite.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything the SDL toolchain concluded about one pass's schema document: the sources the parser
 * refused, and the verdicts the two document-wide stages returned.
 *
 * <p>Reading a schema is a pipeline of three stages, and they contribute to the store very
 * differently. Parsing is the stage that produces facts: every source that parses contributes its
 * declarations, and that is where the transcription families come from. Combining and assembling
 * produce no declarations at all; their entire contribution is a verdict on what parsing produced,
 * which is why one carrier holds the refusals of all three and why the two later stages share a
 * single relation.
 *
 * <p>A stage's refusals never stop the next stage from running. That is the property the whole
 * arrangement rests on: a parse refusal costs its own source and no other, a registry refusal costs
 * its own declaration and no other, and assembly runs over whatever survived both. The alternative,
 * aborting the pipeline at the first refusal, is what makes one freshly broken file able to blank
 * every fact about every other file, so that a workspace loses its answers exactly when the author
 * is mid-edit and needs them.
 *
 * @param syntaxFailures the sources the parser refused, in parse order
 * @param schemaErrors   the document-wide verdicts, in the order their stages ran, so every
 *                       registry verdict precedes every assembly verdict
 */
public record SdlVerdicts(List<RewriteSchemaLoader.SyntaxFailure> syntaxFailures,
                          List<SchemaError> schemaErrors) {

    public SdlVerdicts {
        syntaxFailures = List.copyOf(syntaxFailures);
        schemaErrors = List.copyOf(schemaErrors);
    }

    /**
     * The verdicts of a read where nothing refused anything. What a caller that built a registry
     * itself should hand over: no stage ran, so no stage refused, and the store records the same
     * emptiness it would record for a document read clean.
     */
    public static SdlVerdicts none() {
        return new SdlVerdicts(List.of(), List.of());
    }

    /**
     * The verdicts of a full read: the parse and registry stages from {@code read}, the assembly
     * stage from {@code assembly}. Concatenates the two document-wide stages in the order they ran,
     * which is the order the store keys them in.
     */
    public static SdlVerdicts of(RewriteSchemaLoader.PerSourceParse read, SchemaAssembly assembly) {
        var schemaErrors = new ArrayList<>(read.registryErrors());
        schemaErrors.addAll(assembly.errors());
        return new SdlVerdicts(read.failures(), schemaErrors);
    }

    /**
     * The sources the parser refused, by name. The source census reads this: a refused source is
     * one the run read, so the store owes it a source row, and it is the one source the walk over
     * the surviving declarations cannot find, having contributed none.
     */
    public Set<String> refusedSourceNames() {
        return syntaxFailures.stream()
            .map(RewriteSchemaLoader.SyntaxFailure::sourceName)
            .collect(Collectors.toUnmodifiableSet());
    }

    /** Whether any stage refused anything, so a caller whose contract is to fail knows to. */
    public boolean anyRefusal() {
        return !syntaxFailures.isEmpty() || !schemaErrors.isEmpty();
    }
}
