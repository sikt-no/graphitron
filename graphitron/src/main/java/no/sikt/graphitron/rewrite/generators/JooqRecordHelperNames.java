package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-{@code <Type>Fetchers}-class resolver for the shape-aware naming of the {@code create<Record>} /
 * {@code create<Record>List} helpers a jOOQ {@code TableRecord} {@code @service} param emits (R311).
 *
 * <p>The original R311 dedup keyed the helper by record class alone ({@code putIfAbsent(recordClass, jr)}),
 * so two {@code @service} fields taking the same record through <em>different</em> input types (different
 * {@code @field} column sets) collapsed onto the first-seen shape's single helper and every call site routed
 * to it, silently dropping the columns unique to the other input (R437). This resolver keys instead by the
 * full binding <em>shape</em>: the record class plus the ordered {@link CallSiteExtraction.ColumnBinding}s and
 * {@link CallSiteExtraction.RecordKeyDecode}s that drive the emitted body. Identical shapes still collapse to
 * one helper (so two fields with the same input shape share it, and the common single-shape case is
 * byte-identical to before); distinct shapes on one record class each get their own helper and each call site
 * routes to the helper matching its own carrier's shape.
 *
 * <h3>Naming</h3>
 * A record class reached by exactly one distinct shape keeps the bare {@code create<Record>} /
 * {@code create<Record>List} name — no churn for the overwhelmingly common single-shape case, and existing
 * generated output / snapshots are unaffected. A record class reached by more than one distinct shape is
 * <em>contended</em>: its shapes are ordered by their (deterministic) signature string and each gets a stable
 * 1-based ordinal suffix ({@code create<Record>1}, {@code create<Record>2}, ...), so the disambiguation is
 * visible in the generated names and there is no hidden "primary" shape.
 *
 * <h3>Determinism</h3>
 * The signature is a content string built from column / decode names (SQL column names, decode paths, type
 * ids, encoder class, target columns, nullability) in emission order, never from live-object identity or
 * {@link Object#hashCode()}, so the same schema produces the same names on every run.
 */
final class JooqRecordHelperNames {

    /** signature -> helper stem (the {@code create<Record>[ordinal]} base, without the {@code List} suffix). */
    private final Map<String, String> stemBySignature;

    /** One carrier per distinct signature, in first-seen (encounter) order — the emission work-list. */
    private final List<CallSiteExtraction.JooqRecord> distinctShapes;

    private JooqRecordHelperNames(Map<String, String> stemBySignature,
            List<CallSiteExtraction.JooqRecord> distinctShapes) {
        this.stemBySignature = stemBySignature;
        this.distinctShapes = distinctShapes;
    }

    /**
     * The pre-R437 bare naming: every record class resolves to {@code create<Record>} with no
     * shape-awareness. Used by schema-free / unit callers ({@code ServiceMethodCallEmitter} unit tests,
     * out-of-band emission) that hold no collected carrier set; the single-shape path this produces is
     * exactly what those contexts had before.
     */
    static JooqRecordHelperNames bare() {
        return new JooqRecordHelperNames(Map.of(), List.of());
    }

    /**
     * Build the resolver from every jOOQ-record carrier collected on one {@code <Type>Fetchers} class
     * (both the child-coordinate {@code MethodBackedField.callParams()} walk and the root-coordinate
     * {@code ServiceField} carrier walk feed this). Carriers are deduped by signature preserving
     * encounter order; distinct shapes are then grouped by record class to detect contention and assign
     * names.
     */
    static JooqRecordHelperNames from(Collection<CallSiteExtraction.JooqRecord> carriers) {
        // Dedup by signature (encounter order): identical shapes share one helper, distinct ones split.
        LinkedHashMap<String, CallSiteExtraction.JooqRecord> bySignature = new LinkedHashMap<>();
        for (var jr : carriers) {
            bySignature.putIfAbsent(signature(jr), jr);
        }

        // Group the distinct shapes' signatures by record class to find the contended classes.
        LinkedHashMap<ClassName, List<String>> signaturesByClass = new LinkedHashMap<>();
        for (var entry : bySignature.entrySet()) {
            signaturesByClass
                .computeIfAbsent(entry.getValue().table().recordClass(), k -> new ArrayList<>())
                .add(entry.getKey());
        }

        Map<String, String> stems = new HashMap<>();
        for (var entry : signaturesByClass.entrySet()) {
            String simple = entry.getKey().simpleName();
            List<String> signatures = entry.getValue();
            if (signatures.size() == 1) {
                // Sole shape for this record class: bare create<Record>, byte-identical to pre-R437.
                stems.put(signatures.get(0), "create" + simple);
            } else {
                // Contended: order by signature so the ordinal is stable across runs, then suffix every
                // shape (no hidden primary — the split is explicit in the emitted names).
                List<String> sorted = new ArrayList<>(signatures);
                Collections.sort(sorted);
                for (int i = 0; i < sorted.size(); i++) {
                    stems.put(sorted.get(i), "create" + simple + (i + 1));
                }
            }
        }
        return new JooqRecordHelperNames(stems, new ArrayList<>(bySignature.values()));
    }

    /** The singular {@code create<Record>[ordinal]} helper name for a carrier's shape. */
    String singular(CallSiteExtraction.JooqRecord jr) {
        String stem = stemBySignature.get(signature(jr));
        // Fallback to the bare name for a carrier the resolver never saw (schema-free / bare() contexts):
        // identical to the pre-R437 single-shape behaviour.
        return stem != null ? stem : "create" + jr.table().recordClass().simpleName();
    }

    /** The plural {@code create<Record>[ordinal]List} helper name, sharing the singular's shape-derived stem. */
    String plural(CallSiteExtraction.JooqRecord jr) {
        return singular(jr) + "List";
    }

    /** One carrier per distinct shape, in emission order — the helper-emission work-list. */
    List<CallSiteExtraction.JooqRecord> distinctShapes() {
        return distinctShapes;
    }

    /**
     * A stable content signature of a carrier's binding shape: the record class plus its ordered column
     * bindings and key decodes, rendered from names only (SQL column names, decode paths / type id /
     * encoder / target columns / nullability). Two carriers share a signature iff they would emit an
     * identical helper body, so it is exactly the dedup and naming key. Built in emission order and from
     * stable string content, never {@link Object#hashCode()}, so it is deterministic across runs.
     */
    static String signature(CallSiteExtraction.JooqRecord jr) {
        StringBuilder sb = new StringBuilder(jr.table().recordClass().toString());
        sb.append("|cols:");
        for (var cb : jr.columnBindings()) {
            sb.append(String.join(".", cb.path())).append('>').append(cb.column().sqlName()).append(',');
        }
        sb.append("|keys:");
        for (var kd : jr.keyDecodes()) {
            sb.append(String.join(".", kd.path())).append('>')
                .append(kd.typeId()).append(':')
                .append(kd.encoderClass().toString()).append(':');
            for (var tc : kd.targetColumns()) {
                sb.append(tc.sqlName()).append('+');
            }
            sb.append(':').append(kd.nonNull()).append(',');
        }
        return sb.toString();
    }
}
