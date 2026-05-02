package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.ArrayList;
import java.util.List;

/**
 * Projects {@code @lookupKey}-bearing scalar arguments and {@code @table}-input field bindings
 * into a {@link LookupMapping} for the target table. Sibling to {@link OrderByResolver}.
 *
 * <p>This resolver reads only the classified {@link ArgumentRef} variants — the classifier is the
 * single source of truth for which arguments contribute to a lookup mapping. The projection is
 * total: every input shape produces a {@link ColumnMapping}, possibly with an empty {@code args()}
 * list. The empty-mapping invariant ("@lookupKey declared but no argument resolved to a lookup
 * column") is enforced at the {@link FieldBuilder} call site, not here, because it consults
 * caller-side state (the field-level {@code @lookupKey} signal and the accumulating errors list).
 *
 * <p>Three {@code ColumnMapping.LookupArg} arms are produced:
 * <ul>
 *   <li>{@link ColumnMapping.LookupArg.ScalarLookupArg} — single-column scalar
 *       {@code @lookupKey} args ({@link ArgumentRef.ScalarArg.ColumnArg}).</li>
 *   <li>{@link ColumnMapping.LookupArg.DecodedRecord} — composite-PK NodeId scalar
 *       {@code @lookupKey} args ({@link ArgumentRef.ScalarArg.CompositeColumnArg}). Decode runs
 *       once per row at the arg layer; positional {@link InputColumnBinding.RecordBinding}s
 *       index the resulting {@code Record<N>}. Failure mode is
 *       {@link CallSiteExtraction.ThrowOnMismatch} — null returns surface as
 *       {@code GraphqlErrorException} via {@code LookupValuesJoinEmitter}'s per-row throw.</li>
 *   <li>{@link ColumnMapping.LookupArg.MapInput} — composite-key
 *       {@link ArgumentRef.InputTypeArg.TableInputArg} where each
 *       {@link InputColumnBinding.MapBinding} contributes one slot. List cardinality lives on
 *       the outer arg; individual input fields are guaranteed scalar by
 *       {@code FieldBuilder#buildLookupBindings}.</li>
 * </ul>
 *
 * <p>Non-lookup-key args are ignored. {@link ArgumentRef.InputTypeArg.TableInputArg} args with no
 * {@code @lookupKey}-bearing input fields ({@code fieldBindings().isEmpty()}) are ignored as well
 * since they don't contribute to the mapping; the field still validates and generates correctly
 * via {@link no.sikt.graphitron.rewrite.model.GeneratedConditionFilter} or the standard filter
 * path. {@link no.sikt.graphitron.rewrite.model.LookupField} variants must have at least one
 * column; that invariant is enforced by {@code projectForFilter} before the field is constructed
 * as a {@code LookupField} variant.
 */
final class LookupMappingResolver {

    LookupMappingResolver() {}

    /**
     * Projects the classified {@code refs} into a {@link LookupMapping} against {@code targetTable}.
     * Always returns a {@link ColumnMapping}; an empty {@code args()} list signals "no
     * {@code @lookupKey}-bearing argument found" and is the caller's responsibility to validate.
     */
    LookupMapping resolve(List<ArgumentRef> refs, TableRef targetTable) {
        var args = new ArrayList<ColumnMapping.LookupArg>();
        for (var ref : refs) {
            switch (ref) {
                case ArgumentRef.ScalarArg.ColumnArg ca when ca.isLookupKey() ->
                    args.add(new ColumnMapping.LookupArg.ScalarLookupArg(
                        ca.name(), ca.column(), ca.extraction(), ca.list()));
                case ArgumentRef.ScalarArg.CompositeColumnArg cca when cca.isLookupKey() -> {
                    var bindings = new ArrayList<InputColumnBinding.RecordBinding>();
                    for (int i = 0; i < cca.columns().size(); i++) {
                        bindings.add(new InputColumnBinding.RecordBinding(i, cca.columns().get(i)));
                    }
                    if (!(cca.extraction() instanceof CallSiteExtraction.NodeIdDecodeKeys nodeIdExtraction)) {
                        throw new IllegalStateException(
                            "CompositeColumnArg @lookupKey arg '" + cca.name() + "' must carry a"
                            + " NodeIdDecodeKeys extraction; got " + cca.extraction().getClass().getSimpleName());
                    }
                    args.add(new ColumnMapping.LookupArg.DecodedRecord(
                        cca.name(), cca.list(), nodeIdExtraction, bindings));
                }
                case ArgumentRef.InputTypeArg.TableInputArg tia -> {
                    if (!tia.fieldBindings().isEmpty()) {
                        args.add(new ColumnMapping.LookupArg.MapInput(
                            tia.name(), tia.list(), tia.fieldBindings()));
                    }
                }
                default -> {}
            }
        }
        return new ColumnMapping(args, targetTable);
    }
}
