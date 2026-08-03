package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputColumnBindingGroup;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping;
import no.sikt.graphitron.rewrite.model.LookupResolution;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.ArrayList;
import java.util.List;

/**
 * Projects {@code @lookupKey}-bearing scalar arguments and {@code @table}-input field bindings
 * into a {@link LookupResolution} for the target table. Sibling to {@link OrderByResolver}.
 *
 * <p>This resolver reads only the classified {@link ArgumentRef} variants; the classifier is the
 * single source of truth for which arguments contribute to a lookup mapping. The projection is
 * total and sealed: an argument surface resolving no lookup keys is
 * {@link LookupResolution.None}, and {@link LookupResolution.Keyed} always wraps a
 * {@link ColumnMapping} with at least one arg. The mismatch rejection ("@lookupKey declared but
 * no argument resolved to a lookup column") is enforced at the {@link FieldBuilder} call site,
 * not here, because it consults caller-side state (the field-level {@code @lookupKey} signal
 * and the accumulating errors list).
 *
 * <p>Downstream facts not visible here: a {@link ColumnMapping.LookupArg.DecodedRecord} decodes
 * once per row at the arg layer, with positional {@link InputColumnBinding.RecordBinding}s
 * indexing the resulting {@code Record<N>}; a null decode surfaces as
 * {@code GraphqlErrorException} via {@code LookupValuesJoinEmitter}'s per-row throw
 * ({@link CallSiteExtraction.ThrowOnMismatch}). For a
 * {@link ColumnMapping.LookupArg.MapInput}, list cardinality lives on the outer arg and
 * individual input fields are guaranteed scalar by {@code FieldBuilder#buildLookupBindings}.
 *
 * <p>A {@link ArgumentRef.InputTypeArg.TableInputArg} with no {@code @lookupKey}-bearing input
 * fields contributes nothing here, but the field still validates and generates correctly via
 * {@link no.sikt.graphitron.rewrite.model.GeneratedConditionFilter} or the standard filter
 * path.
 */
final class LookupMappingResolver {

    LookupMappingResolver() {}

    /**
     * Projects the classified {@code refs} into a {@link LookupResolution} against
     * {@code targetTable}: {@link LookupResolution.None} when no {@code @lookupKey}-bearing
     * argument contributed a key slot, {@link LookupResolution.Keyed} otherwise.
     */
    LookupResolution resolve(List<ArgumentRef> refs, TableRef targetTable) {
        var args = new ArrayList<ColumnMapping.LookupArg>();
        for (var ref : refs) {
            switch (ref) {
                case ArgumentRef.ScalarArg.ColumnBackedArg ca when ca.isLookupKey() && !ca.isComposite() ->
                    args.add(new ColumnMapping.LookupArg.ScalarLookupArg(
                        ca.name(), ca.columns().get(0), ca.extraction(), ca.list()));
                case ArgumentRef.ScalarArg.ColumnBackedArg cca when cca.isLookupKey() -> {
                    // The cast is safe: the carrier's constructor guarantees composite
                    // ColumnBackedArgs carry a NodeIdDecodeKeys extraction.
                    var bindings = new ArrayList<InputColumnBinding.RecordBinding>();
                    for (int i = 0; i < cca.columns().size(); i++) {
                        bindings.add(new InputColumnBinding.RecordBinding(i, cca.columns().get(i)));
                    }
                    args.add(new ColumnMapping.LookupArg.DecodedRecord(
                        cca.name(), cca.list(),
                        (CallSiteExtraction.NodeIdDecodeKeys) cca.extraction(), bindings));
                }
                case ArgumentRef.InputTypeArg.TableInputArg tia -> {
                    // MapInput slot order is a contract: group order, then binding order
                    // within each group.
                    var mapBindings = new ArrayList<InputColumnBinding.MapBinding>();
                    for (var g : tia.fieldBindings()) {
                        switch (g) {
                            case InputColumnBindingGroup.MapGroup mg ->
                                mapBindings.addAll(mg.bindings());
                            case InputColumnBindingGroup.DecodedRecordGroup drg ->
                                args.add(new ColumnMapping.LookupArg.DecodedRecord(
                                    tia.name(), tia.list(), drg.extraction(), drg.bindings()));
                        }
                    }
                    if (!mapBindings.isEmpty()) {
                        args.add(new ColumnMapping.LookupArg.MapInput(
                            tia.name(), tia.list(), mapBindings));
                    }
                }
                default -> {}
            }
        }
        return args.isEmpty()
            ? LookupResolution.None.INSTANCE
            : new LookupResolution.Keyed(new ColumnMapping(args, targetTable));
    }
}
