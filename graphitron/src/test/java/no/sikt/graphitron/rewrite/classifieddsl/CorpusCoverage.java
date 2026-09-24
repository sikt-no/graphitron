package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.model.test.CorpusDocuments;

import java.util.HashSet;
import java.util.Set;

/**
 * What the corpus covers, classified.
 *
 * <p>Here rather than beside the corpus itself, which is {@code graphitron-model}'s and knows
 * nothing of classification. Loading a document is a question about a folder; which leaves a
 * document covers is a question only the generator can answer, and the two sat in one class until
 * the corpus moved to the module that owns the documents.
 */
public final class CorpusCoverage {

    private CorpusCoverage() {}

    /**
     * The set of sealed {@code GraphitronField} / {@code GraphitronType} leaves the corpus
     * demonstrates classification for, by classifying every document and collecting the leaf each
     * {@code @classified} / {@code @classifiedType} coordinate landed on, descending the ridden lists
     * a classified leaf carries ({@code NestingField.nestedFields()}, {@code PivotSpec.slots()}); a
     * pivot slot or a nesting child has no top-level coordinate of its own, so the descent is what
     * lets the corpus walk observe it. This set alone carries the output-field and type side of the
     * variant-coverage obligation ({@code ExemptionRegistry}): a leaf absent here fails coverage even
     * when an enum case still asserts it.
     *
     * <p>Synthesised type leaves join through {@code @synthesises} on a carrier coordinate: an arm
     * counts only when a declared mint agrees with the connection-synthesis relation's produced row
     * (same name, same arm, registry entry matching), never from the producer's output alone, so the
     * coverage stays author-checkable.
     */
    public static Set<Class<?>> coveredLeaves() {
        var leaves = new HashSet<Class<?>>();
        var mintedArmsBySimpleName = new java.util.HashMap<String, Class<?>>();
        for (var arm : no.sikt.graphitron.rewrite.model.ConnectionSynthesis.MINTED_ARM_VOCABULARY) {
            mintedArmsBySimpleName.put(arm.getSimpleName(), arm);
        }
        for (CorpusDocuments.Document document : CorpusDocuments.documents()) {
            var result = ClassifiedHarness.classify(document.sdl());
            for (var fc : result.fields()) {
                var field = result.schema().field(fc.parentType(), fc.fieldName());
                ClassifiedHarness.forEachWithRiddenFields(field, f -> leaves.add(f.getClass()));
            }
            for (var tc : result.types()) {
                if (tc.leaf() != null) {
                    leaves.add(tc.leaf());
                }
            }
            for (var sc : result.synthesises()) {
                for (var declared : sc.declared()) {
                    if (sc.produced().contains(declared)) {
                        leaves.add(mintedArmsBySimpleName.get(declared.arm()));
                    }
                }
            }
        }
        return leaves;
    }
}
