package no.sikt.graphitron.rewrite.schema.input;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a list of {@link SchemaInput} entries into a source-name-keyed
 * map consumed by {@link TagApplier} and {@link DescriptionNoteApplier}.
 *
 * <p>The map is a {@link LinkedHashMap} so iteration order matches the
 * caller's input-list order; this keeps tag/note application reproducible
 * across builds and lets overlap error messages name entries
 * deterministically.
 *
 * <p>One fail-fast check: the same {@code sourceName} appearing in two
 * entries throws {@link SchemaInputException}, naming both offending
 * entries.
 */
public final class SchemaInputAttribution {

    private SchemaInputAttribution() {}

    public static Map<String, SchemaInput> build(List<SchemaInput> inputs) {
        var map = new LinkedHashMap<String, SchemaInput>();
        for (int i = 0; i < inputs.size(); i++) {
            var entry = inputs.get(i);
            var prior = map.putIfAbsent(entry.sourceName(), entry);
            if (prior != null) {
                int priorIdx = inputs.indexOf(prior);
                throw new SchemaInputException(
                    "source '" + entry.sourceName() + "' is declared in two SchemaInput entries: "
                        + "#" + priorIdx + " with " + describe(prior)
                        + " and #" + i + " with " + describe(entry)
                        + ". Each source must belong to exactly one entry."
                );
            }
        }
        return map;
    }

    private static String describe(SchemaInput input) {
        return "tag=" + input.tag().orElse("<none>")
            + "/note=" + input.descriptionNote().map(n -> "<present>").orElse("<none>");
    }
}
