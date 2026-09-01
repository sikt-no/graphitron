package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the classification walk bound each type's backing to: the SDL type name to the binary name
 * of the class the walk resolved for it. Test-source only, and deliberately: nothing the generator
 * emits reads this, and the one thing that does is {@link TypeBackingShadowTest}, which projects
 * the walk's side of the backing differential and compares it against the derivation's query in
 * this JVM.
 *
 * <p>Fidelity to the walk is evidence, not the specification: where the two disagree, each case is
 * adjudicated on its own, and the outcome is either a fix to the derivation or a recorded
 * behaviour change. The comparison keeps that shape by naming its fixtures and asserting each
 * departure's direction, never by asserting the two are equal over a corpus.
 */
public record TypeBackingClasses(Map<String, String> byTypeName) {

    public TypeBackingClasses {
        byTypeName = Map.copyOf(byTypeName);
    }

    /**
     * The walked model's backing resolution, reduced to the class each type was bound to. Reads
     * {@link CatalogBuilder#projectTypesByName} rather than switching over the classified types a
     * second time, so this and the LSP-facing projection cannot come to differ about what the walk
     * decided.
     */
    public static TypeBackingClasses of(GraphitronSchema schema) {
        var bound = new LinkedHashMap<String, String>();
        CatalogBuilder.projectTypesByName(schema).forEach((typeName, shape) -> {
            String className = classNameOf(shape);
            if (className != null) {
                bound.put(typeName, className);
            }
        });
        return new TypeBackingClasses(bound);
    }

    /**
     * The class a backing shape names, or {@code null} where it names none. The two null arms are
     * populations another relation owns: a table backing is {@code intent_bound_table}'s, and an
     * absent backing is the walk declining to bind. Exhaustive on the sealed permits, so a new
     * shape has to decide which of the three it is.
     */
    private static String classNameOf(TypeBackingShape shape) {
        return switch (shape) {
            case TypeBackingShape.RecordBacking r -> r.fqClassName();
            case TypeBackingShape.PojoBacking p -> p.fqClassName();
            case TypeBackingShape.JooqRecordBacking j -> j.fqClassName();
            case TypeBackingShape.TableBacking ignored -> null;
            case TypeBackingShape.NoBacking ignored -> null;
        };
    }
}
