package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves the {@code create<Record>} / {@code create<Record>List} helper names for the jOOQ
 * {@code TableRecord} {@code @service} params on one {@code <Type>Fetchers} class, keyed by the full
 * binding <em>shape</em> rather than the record class. This is the single home for the dedup,
 * naming, and contention decisions the three sites that name a jOOQ-record helper must agree on: the
 * helper-emission drain in {@link TypeFetcherGenerator}, the child-coordinate call site in
 * {@link ArgCallEmitter}, and the root-coordinate call site in {@link ServiceMethodCallEmitter}.
 *
 * <h3>Why shape, not record class (D1)</h3>
 *
 * <p>The pre-R437 dedup keyed on {@code jr.table().recordClass()}, so two {@code @service} fields
 * binding one jOOQ record through two different input types (different {@code @field} column sets)
 * collapsed to the first-seen shape's helper; every call site then routed to that survivor and the
 * second mutation silently dropped the columns unique to its input. A
 * {@link CallSiteExtraction.JooqRecord} is a record whose every component is a value type with
 * structural {@code equals} ({@code TableRef}, {@code ColumnRef}, {@code ClassName}, element-wise
 * {@code List}), so {@code jr1.equals(jr2)} is <em>already</em> exactly "these two carriers emit an
 * identical helper body". The dedup therefore keys on the carrier itself, not a hand-built signature
 * string a later body-affecting component would silently desync from.
 *
 * <h3>Naming (D2)</h3>
 *
 * <p>A record class reached by exactly one distinct shape keeps the bare {@code create<Record>} /
 * {@code create<Record>List} name (the overwhelmingly common case, byte-identical to pre-R437). A
 * record class reached by more than one distinct shape is <em>contended</em>: its shapes are ordered
 * by {@link #canonicalRender} (a names-only render, stable across runs where the record's own
 * {@code hashCode} is not) and each gets a 1-based ordinal suffix ({@code create<Record>1},
 * {@code create<Record>2}, ...). Each contended helper also carries a one-line javadoc naming the
 * columns it binds, so a reader of the generated {@code MutationFetchers} can map helper to mutation
 * without reverse-engineering the sort.
 *
 * <h3>The populated / default split (D3)</h3>
 *
 * <p>{@link #of} builds a <em>populated</em> resolver from a class's collected carriers;
 * {@link #bare} returns the <em>default</em> resolver of schema-free / unit / out-of-band contexts.
 * A populated resolver throws when asked to name a carrier it never collected rather than silently
 * falling back to the bare name: a silent bare name on an unrouted path would be this exact bug
 * re-buried, while a generation-time failure surfaces the routing hole. The default resolver, which by
 * construction only serves contexts carrying at most one shape per record class, answers bare
 * unconditionally, preserving today's behaviour.
 */
final class JooqRecordHelperNames {

    private final boolean populated;
    /** Carrier shape → helper stem: the record's {@code simpleName}, or {@code simpleName + ordinal}
     *  when the record class is contended. Singular helper = {@code "create" + stem}, plural =
     *  {@code "create" + stem + "List"}. */
    private final Map<CallSiteExtraction.JooqRecord, String> stems;
    /** Carrier shape → contended-helper legibility javadoc, or {@code null} for an uncontended helper. */
    private final Map<CallSiteExtraction.JooqRecord, String> javadocs;
    /** Distinct shapes in first-encounter order: the helper-emission work-list. For the common
     *  uncontended case this is exactly the pre-R437 emission order, so no generated output churns. */
    private final List<CallSiteExtraction.JooqRecord> distinctShapes;

    private JooqRecordHelperNames(boolean populated,
            Map<CallSiteExtraction.JooqRecord, String> stems,
            Map<CallSiteExtraction.JooqRecord, String> javadocs,
            List<CallSiteExtraction.JooqRecord> distinctShapes) {
        this.populated = populated;
        this.stems = stems;
        this.javadocs = javadocs;
        this.distinctShapes = distinctShapes;
    }

    /**
     * The default (never-populated) resolver: answers the bare {@code create<Record>} name for any
     * carrier and has an empty work-list. Used by schema-free / unit / out-of-band emission contexts,
     * which by construction carry at most one shape per record class.
     */
    static JooqRecordHelperNames bare() {
        return new JooqRecordHelperNames(false, Map.of(), Map.of(), List.of());
    }

    /**
     * Build the populated resolver for one {@code <Type>Fetchers} class from every jOOQ-record carrier
     * collected on it (both coordinates). Dedups by structural equality, groups distinct shapes by
     * record class, and assigns bare names to uncontended classes and ordinal-suffixed names (ordered
     * by {@link #canonicalRender}) to contended ones.
     */
    static JooqRecordHelperNames of(Collection<CallSiteExtraction.JooqRecord> carriers) {
        // Dedup by structural equality; LinkedHashSet preserves first-encounter order.
        var distinct = new LinkedHashSet<>(carriers);

        // Group distinct shapes by record class to find contention.
        var byClass = new LinkedHashMap<ClassName, List<CallSiteExtraction.JooqRecord>>();
        for (var jr : distinct) {
            byClass.computeIfAbsent(jr.table().recordClass(), k -> new ArrayList<>()).add(jr);
        }

        var stems = new LinkedHashMap<CallSiteExtraction.JooqRecord, String>();
        var javadocs = new LinkedHashMap<CallSiteExtraction.JooqRecord, String>();
        for (var group : byClass.entrySet()) {
            String simpleName = group.getKey().simpleName();
            var shapes = group.getValue();
            if (shapes.size() == 1) {
                // Uncontended: bare stem, no javadoc. Byte-identical to pre-R437.
                stems.put(shapes.get(0), simpleName);
            } else {
                // Contended: order by canonical render (stable), 1-based ordinal suffix, legibility javadoc.
                var ordered = new ArrayList<>(shapes);
                ordered.sort(Comparator.comparing(JooqRecordHelperNames::canonicalRender));
                for (int i = 0; i < ordered.size(); i++) {
                    var jr = ordered.get(i);
                    stems.put(jr, simpleName + (i + 1));
                    javadocs.put(jr, bindingSummary(jr));
                }
            }
        }

        return new JooqRecordHelperNames(true, stems, javadocs, new ArrayList<>(distinct));
    }

    /** The {@code create<Record>} singular helper name for {@code jr}'s shape. */
    String singularName(CallSiteExtraction.JooqRecord jr) {
        return "create" + stem(jr);
    }

    /** The {@code create<Record>List} plural helper name for {@code jr}'s shape. */
    String pluralName(CallSiteExtraction.JooqRecord jr) {
        return "create" + stem(jr) + "List";
    }

    /**
     * The one-line legibility javadoc for a contended helper (e.g. {@code "Binds ADDRESS, DISTRICT,
     * DATO_FRA."}), or {@code null} for an uncontended helper — the byte-identical, javadoc-free
     * common case. Always {@code null} for the default resolver.
     */
    String javadocFor(CallSiteExtraction.JooqRecord jr) {
        return populated ? javadocs.get(jr) : null;
    }

    /** The distinct shapes to emit one {@code create<Record>} / {@code create<Record>List} pair for. */
    List<CallSiteExtraction.JooqRecord> distinctShapes() {
        return distinctShapes;
    }

    private String stem(CallSiteExtraction.JooqRecord jr) {
        if (!populated) {
            // Default resolver: bare name unconditionally (single-shape-per-class contexts only).
            return jr.table().recordClass().simpleName();
        }
        String stem = stems.get(jr);
        if (stem == null) {
            // A populated resolver asked to name a carrier it never collected is a routing hole: a
            // silent bare-name fallback here would re-bury R437 (a call site routing to a helper that
            // was never emitted, or to the wrong shape's helper). Fail at generation time instead.
            throw new IllegalStateException(
                "JooqRecordHelperNames was asked to name a jOOQ-record carrier it never collected: "
                + jr.table().recordClass() + " [" + canonicalRender(jr) + "]. Every call site must "
                + "route through the resolver built from this <Type>Fetchers class's carriers.");
        }
        return stem;
    }

    /**
     * A deterministic, names-only render of a carrier's shape, used solely to <em>order</em> a record
     * class's contended shapes (the record's own {@code hashCode} is not stable across runs). Never the
     * identity — that is the carrier's structural {@code equals} (D1) — so it need only be injective
     * enough to give a stable total order over the body-affecting components: the record class, each
     * column binding's path + resolved column, and each key decode's path + type id + encoder + target
     * columns + nullability.
     */
    private static String canonicalRender(CallSiteExtraction.JooqRecord jr) {
        var sb = new StringBuilder(jr.table().recordClass().toString());
        sb.append("|cols:");
        for (var cb : jr.columnBindings()) {
            sb.append(String.join(".", cb.path())).append('=').append(cb.column().javaName()).append(';');
        }
        sb.append("|keys:");
        for (var kd : jr.keyDecodes()) {
            sb.append(String.join(".", kd.path())).append('=').append(kd.typeId())
              .append('@').append(kd.encoderClass().toString()).append("->")
              .append(kd.targetColumns().stream().map(ColumnRef::javaName).collect(Collectors.joining(",")))
              .append(':').append(kd.nonNull()).append(';');
        }
        return sb.toString();
    }

    /**
     * The columns a contended helper binds, in binding order (plain {@code @field} columns first, then
     * {@code @nodeId} decode target columns), deduplicated, rendered as the one-line contended-helper
     * javadoc body.
     */
    private static String bindingSummary(CallSiteExtraction.JooqRecord jr) {
        var columns = new LinkedHashSet<String>();
        for (var cb : jr.columnBindings()) {
            columns.add(cb.column().javaName());
        }
        for (var kd : jr.keyDecodes()) {
            for (var col : kd.targetColumns()) {
                columns.add(col.javaName());
            }
        }
        return "Binds " + String.join(", ", columns) + ".";
    }
}
