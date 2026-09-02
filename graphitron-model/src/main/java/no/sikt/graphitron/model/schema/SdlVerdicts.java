package no.sikt.graphitron.model.schema;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the two stages ahead of assembly concluded about one pass's schema document: the sources the
 * parser refused, and the declarations the combining registry refused.
 *
 * <p>Reading a schema is a pipeline whose stages contribute to the store very differently. Parsing
 * is the stage that produces the per-site declaration facts: every source that parses contributes
 * its own, and that is where the transcription families come from. Combining produces no
 * declaration at all; its entire contribution is a verdict on what parsing produced, which is why
 * it shares a relation with the assembly verdict downstream of it.
 *
 * <p>Assembly's verdict is deliberately not a component here. The gatherer runs that stage itself,
 * because the stages after it read the schema it produces, and taking the transcription and the
 * verdict from one assembly is what keeps the store from judging a document it does not hold. A
 * caller hands over the assembly beside these verdicts instead.
 *
 * <p>A stage's refusals never stop the next stage from running. That is the property the whole
 * arrangement rests on: a parse refusal costs its own source and no other, a registry refusal costs
 * its own declaration and no other, and assembly runs over whatever survived both. The alternative,
 * aborting the pipeline at the first refusal, is what makes one freshly broken file able to blank
 * every fact about every other file, so that a workspace loses its answers exactly when the author
 * is mid-edit and needs them.
 *
 * @param syntaxFailures the sources the parser refused, in parse order
 * @param schemaErrors   the combining stage's verdicts, in the order the declarations were offered
 */
public record SdlVerdicts(List<SchemaLoader.SyntaxFailure> syntaxFailures,
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

    /** The verdicts of a read's first two stages, as the loader reported them. */
    public static SdlVerdicts of(SchemaLoader.PerSourceParse read) {
        return new SdlVerdicts(read.failures(), read.registryErrors());
    }

    /**
     * The sources the parser refused, by name. The source census reads this: a refused source is
     * one the run read, so the store owes it a source row, and it is the one source the walk over
     * the surviving declarations cannot find, having contributed none.
     */
    public Set<String> refusedSourceNames() {
        return syntaxFailures.stream()
            .map(SchemaLoader.SyntaxFailure::sourceName)
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Whether either stage here refused anything. A caller whose contract is to fail asks this and
     * the assembly separately, assembly being the gatherer's own stage rather than one of these.
     */
    public boolean anyRefusal() {
        return !syntaxFailures.isEmpty() || !schemaErrors.isEmpty();
    }
}
