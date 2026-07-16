package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * One {@code @lookupKey}-bearing input field's contribution to a {@code TableInputArg}'s
 * column bindings. Sibling sealed root to {@link LookupMapping.ColumnMapping.LookupArg}: shares
 * the binding-arity axis (scalar vs decoded-record) but is rooted at an input-field cluster
 * inside a {@code TableInputArg} rather than an outer GraphQL argument.
 *
 * <p>One group per {@code @lookupKey}-bearing input field on a {@code TableInputArg}'s
 * {@link GraphitronType.TableInputType}: {@link InputField.ColumnField} (whether
 * {@link CallSiteExtraction.Direct} or {@link CallSiteExtraction.NodeIdDecodeKeys}) produces a
 * {@link MapGroup} with one {@link InputColumnBinding.MapBinding}; {@link InputField.CompositeColumnField}
 * produces a {@link DecodedRecordGroup} with N {@link InputColumnBinding.RecordBinding} slots
 * indexed positionally into the decoded {@code Record<N>}.
 *
 * <p>The split is type-locked rather than carrier-flag-driven: {@code MapGroup}'s bindings are
 * all {@link InputColumnBinding.MapBinding}; {@code DecodedRecordGroup}'s bindings are all
 * {@link InputColumnBinding.RecordBinding} and the per-NodeType decode helper lives once on
 * {@code extraction.decodeMethod()}.
 */
public sealed interface InputColumnBindingGroup permits InputColumnBindingGroup.MapGroup, InputColumnBindingGroup.DecodedRecordGroup {

    /**
     * Flat list of target columns in slot order. {@link MapGroup} contributes one column per
     * {@link InputColumnBinding.MapBinding}; {@link DecodedRecordGroup} contributes
     * {@code bindings.size()} columns (one per {@link InputColumnBinding.RecordBinding}).
     */
    List<ColumnRef> targetColumns();

    /**
     * Scalar Map-keyed group: today's standard {@code @lookupKey}-on-scalar-input-field shape,
     * widened to carry the per-binding {@link CallSiteExtraction} (including
     * {@link CallSiteExtraction.NodeIdDecodeKeys} for an arity-1 NodeId-decoded carrier).
     *
     * <p>Single-binding lists are the canonical shape: one input field, one
     * {@link InputColumnBinding.MapBinding}. Multi-binding lists exist when an input type
     * carries multiple separate {@code @lookupKey} fields; each contributes one binding.
     */
    record MapGroup(List<InputColumnBinding.MapBinding> bindings) implements InputColumnBindingGroup {
        public MapGroup {
            if (bindings.isEmpty()) {
                throw new IllegalArgumentException("MapGroup must carry at least one binding");
            }
            bindings = List.copyOf(bindings);
        }

        @Override public List<ColumnRef> targetColumns() {
            return bindings.stream().map(InputColumnBinding.MapBinding::targetColumn).toList();
        }
    }

    /**
     * Composite-PK NodeId group: one input field whose wire value decodes once into a
     * {@code Record<N>} via {@code extraction.decodeMethod()}, with N positional
     * {@link InputColumnBinding.RecordBinding} slots indexed {@code 0..N-1}.
     *
     * <p>{@code sourceFieldName} is the GraphQL input-field name carrying the encoded id
     * (e.g. {@code "id"}); the emitter reads the wire value once per row and passes the result
     * through {@code decodeMethod} to obtain the typed record.
     *
 * <p>{@code accessPath} is the SDL key chain from the argument-value root map to the
     * source field: {@code [sourceFieldName]} for a top-level composite key (the emit reads
     * {@code map.get(sourceFieldName)}, byte-identical to before nested grouping inputs existed), or a multi-segment path
     * for a composite key buried in a nested grouping input (the emit descends the wire map). The
     * path always ends in {@code sourceFieldName}.
     */
    record DecodedRecordGroup(
        String sourceFieldName,
        CallSiteExtraction.NodeIdDecodeKeys extraction,
        List<InputColumnBinding.RecordBinding> bindings,
        List<String> accessPath
    ) implements InputColumnBindingGroup {
        public DecodedRecordGroup {
            if (bindings.isEmpty()) {
                throw new IllegalArgumentException(
                    "DecodedRecordGroup '" + sourceFieldName + "' must carry at least one binding");
            }
            bindings = List.copyOf(bindings);
            accessPath = accessPath == null || accessPath.isEmpty()
                ? List.of(sourceFieldName) : List.copyOf(accessPath);
        }

        /** Top-level convenience: the access path is just {@code [sourceFieldName]}. */
        public DecodedRecordGroup(String sourceFieldName,
                                  CallSiteExtraction.NodeIdDecodeKeys extraction,
                                  List<InputColumnBinding.RecordBinding> bindings) {
            this(sourceFieldName, extraction, bindings, List.of(sourceFieldName));
        }

        @Override public List<ColumnRef> targetColumns() {
            return bindings.stream().map(InputColumnBinding.RecordBinding::targetColumn).toList();
        }
    }
}
