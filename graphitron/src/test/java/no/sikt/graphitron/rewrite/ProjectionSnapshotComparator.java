package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.catalog.DirectiveShape;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot.Built.Current;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The differential bisect aid. Diffs two {@link Current} projection snapshots
 * ({@code CatalogBuilder.buildSnapshot} output) key-by-key and returns a human-readable list of
 * differences, empty when the two snapshots are value-equal.
 *
 * <p>This is a <em>development bisect aid only</em>, never a merge gate. The behavioural proof for
 * the classifier inversion is the {@code GraphitronSchemaBuilderTest} truth table plus the sakila
 * pipeline tiers; the snapshot is a lossy shadow of the classified model (it flattens
 * assembled-schema identity, {@code ErrorType} handler aggregation, and raw graphql-java node
 * references), so elevating it above those tiers would pin the shadow rather than the behaviour.
 * Its job is narrow: when a later slice's output drifts, run old-vs-new through this comparator to
 * localise <em>which</em> type, field, or directive moved, then write or fix the pipeline assertion
 * that actually owns the invariant.
 */
public final class ProjectionSnapshotComparator {

    private ProjectionSnapshotComparator() {}

    /** Returns the differences from {@code before} to {@code after}, or an empty list if equal. */
    public static List<String> diff(Current before, Current after) {
        var out = new ArrayList<String>();
        diffMaps("directive", indexByName(before.directives()), indexByName(after.directives()), out);
        diffMaps("type-backing", before.typesByName(), after.typesByName(), out);
        diffMaps("payload-data-field", before.payloadDataFieldByType(), after.payloadDataFieldByType(), out);
        diffMaps("field-classification", before.fieldClassificationsByCoord(), after.fieldClassificationsByCoord(), out);
        diffMaps("type-classification", before.typeClassificationsByName(), after.typeClassificationsByName(), out);
        return out;
    }

    private static Map<String, DirectiveShape> indexByName(List<DirectiveShape> directives) {
        var byName = new LinkedHashMap<String, DirectiveShape>();
        for (var directive : directives) {
            byName.put(directive.name(), directive);
        }
        return byName;
    }

    private static <V> void diffMaps(String kind, Map<String, V> before, Map<String, V> after, List<String> out) {
        var keys = new TreeSet<String>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (var key : keys) {
            V b = before.get(key);
            V a = after.get(key);
            if (b == null) {
                out.add("+ " + kind + " '" + key + "' added: " + a);
            } else if (a == null) {
                out.add("- " + kind + " '" + key + "' removed: " + b);
            } else if (!b.equals(a)) {
                out.add("~ " + kind + " '" + key + "' changed: " + b + " -> " + a);
            }
        }
    }
}
