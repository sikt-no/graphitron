package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.List;

/**
 * Projects a {@link BatchKeyField} into a {@link SourceKey}: the new singular per-field
 * source-side metadata that R38 introduces. Sibling to {@link OrderByResolver},
 * {@link PaginationResolver}, {@link LookupKeyDirectiveResolver},
 * {@link SourceRowDirectiveResolver}, {@link ClassAccessorResolver}.
 *
 * <p>Single-concern projection: reads today's {@link BatchKey} (already classified upstream
 * in {@code FieldBuilder}) plus the field's typed {@link ReturnTypeRef} and produces the
 * new {@link SourceKey}. R38 Phase 2 wires the resolver into the field-classifier sites so
 * both values populate alongside today's {@code BatchKey}; Phase 3 deletes {@code BatchKey}
 * and re-grounds the resolver against the upstream classification primitives directly
 * (catalog FK columns, lifter / accessor reflection result, service-return classification).
 *
 * <p>The projection rules (Field permit × BatchKey permit → {@link SourceKey.Reader}) are
 * pinned in the spec's "{@code BatchKey} → {@code SourceKey} projection" subsection
 * ({@code graphitron-rewrite/roadmap/unify-rowsmethodname.md}).
 */
public final class SourceKeyResolver {

    public sealed interface Resolved {
        record Ok(SourceKey key) implements Resolved {}
        record Rejected(Rejection rejection) implements Resolved {
            public String message() { return rejection.message(); }
        }
    }

    private SourceKeyResolver() {}

    /**
     * Projects the field into a {@link SourceKey}. Returns {@link Resolved.Rejected} when the
     * field's shape isn't covered by a projection rule (e.g. a future {@link BatchKeyField}
     * permit added without a corresponding row in the projection table).
     */
    public static Resolved resolve(BatchKeyField field) {
        if (field instanceof ChildField.SplitTableField stf) return resolveSplitTable(stf);
        if (field instanceof ChildField.SplitLookupTableField slf) return resolveSplitLookupTable(slf);
        if (field instanceof ChildField.RecordTableField rtf) return resolveRecordTable(rtf);
        if (field instanceof ChildField.RecordLookupTableField rltf) return resolveRecordLookupTable(rltf);
        if (field instanceof ChildField.ServiceTableField stf) return resolveServiceTable(stf);
        if (field instanceof ChildField.ServiceRecordField srf) return resolveServiceRecord(srf);
        return rejected("SourceKeyResolver does not handle BatchKeyField permit '"
            + field.getClass().getSimpleName() + "' (added without a projection rule)");
    }

    private static Resolved resolveSplitTable(ChildField.SplitTableField stf) {
        // SplitTableField narrows BatchKey to RowKeyed (catalog-FK only). Reader.ColumnRead.
        return ok(new SourceKey(
            stf.returnType().table(),
            stf.batchKey().parentKeyColumns(),
            List.of(),
            SourceKey.Wrap.ROW,
            cardinalityFor(stf.returnType()),
            new SourceKey.Reader.ColumnRead()));
    }

    private static Resolved resolveSplitLookupTable(ChildField.SplitLookupTableField slf) {
        // SplitLookupTableField narrows BatchKey to RowKeyed too; same shape as SplitTableField
        // (the @lookupKey wrapper lives outside SourceKey, on the field's LookupMapping).
        return ok(new SourceKey(
            slf.returnType().table(),
            slf.batchKey().parentKeyColumns(),
            List.of(),
            SourceKey.Wrap.ROW,
            cardinalityFor(slf.returnType()),
            new SourceKey.Reader.ColumnRead()));
    }

    private static Resolved resolveRecordTable(ChildField.RecordTableField rtf) {
        return resolveRecordParent(
            rtf.returnType().table(),
            rtf.returnType(),
            rtf.batchKey());
    }

    private static Resolved resolveRecordLookupTable(ChildField.RecordLookupTableField rltf) {
        return resolveRecordParent(
            rltf.returnType().table(),
            rltf.returnType(),
            rltf.batchKey());
    }

    private static Resolved resolveRecordParent(
            TableRef target,
            ReturnTypeRef.TableBoundReturnType returnType,
            BatchKey.RecordParentBatchKey bk) {

        SourceKey.Cardinality cardinality = cardinalityFor(returnType);
        if (bk instanceof BatchKey.RowKeyed rk) {
            // Catalog FK on @record-parent's TableRecord backing class.
            return ok(new SourceKey(
                target, rk.parentKeyColumns(), List.of(),
                SourceKey.Wrap.ROW, cardinality,
                new SourceKey.Reader.ColumnRead()));
        }
        if (bk instanceof BatchKey.LifterLeafKeyed llk) {
            // @sourceRows static lifter, leaf-PK shape (single LiftedHop).
            return ok(new SourceKey(
                target, llk.parentSideColumns(), List.of(llk.hop()),
                SourceKey.Wrap.ROW, cardinality,
                new SourceKey.Reader.SourceRowsCall(llk.lifter())));
        }
        if (bk instanceof BatchKey.LifterPathKeyed lpk) {
            // @sourceRows + @reference: the FK chain is the path, the lifter produces the
            // first-hop source-side tuple.
            return ok(new SourceKey(
                target, lpk.parentSideColumns(), lpk.path(),
                SourceKey.Wrap.ROW, cardinality,
                new SourceKey.Reader.SourceRowsCall(lpk.lifter())));
        }
        if (bk instanceof BatchKey.AccessorKeyedSingle aks) {
            // Typed accessor on the @record parent's backing class returning a single
            // TableRecord. Cardinality forced to ONE; the field's wrapper might still be
            // single (consumed by RowsMethodShape) but the source-side per-key is one record.
            return ok(new SourceKey(
                target, aks.targetKeyColumns(), List.of(aks.hop()),
                SourceKey.Wrap.RECORD, SourceKey.Cardinality.ONE,
                new SourceKey.Reader.AccessorCall(aks.accessor())));
        }
        if (bk instanceof BatchKey.AccessorKeyedMany akm) {
            // Typed accessor returning List<X> / Set<X>. The loader.loadMany contract emits
            // one record per element-PK key; cardinality forced to MANY (per-element walk).
            return ok(new SourceKey(
                target, akm.targetKeyColumns(), List.of(akm.hop()),
                SourceKey.Wrap.RECORD, SourceKey.Cardinality.MANY,
                new SourceKey.Reader.AccessorCall(akm.accessor())));
        }
        return rejected("SourceKeyResolver: RecordParentBatchKey permit '"
            + bk.getClass().getSimpleName() + "' has no projection rule");
    }

    private static Resolved resolveServiceTable(ChildField.ServiceTableField stf) {
        // ServiceTableField's ReturnTypeRef is TableBoundReturnType; the rows-method body calls
        // the @service method whose return is a typed TableRecord (e.g. Result<FilmRecord>).
        // recordType for the Reader = target's recordClass.
        BatchKey.ParentKeyed bk = stf.batchKey();
        return ok(new SourceKey(
            stf.returnType().table(),
            bk.parentKeyColumns(),
            List.of(),
            wrapForServiceParentKeyed(bk),
            cardinalityFor(stf.returnType()),
            new SourceKey.Reader.ServiceTableRecord(stf.returnType().table().recordClass())));
    }

    private static Resolved resolveServiceRecord(ChildField.ServiceRecordField srf) {
        // ServiceRecordField's ReturnTypeRef is ResultReturnType / ScalarReturnType — no typed
        // table-bound return. Target derives from joinPath's last hop when present (the
        // service-reconnect path); otherwise null (scalar-returning service with no
        // reconnect).
        TableRef target = serviceRecordTarget(srf.joinPath());
        BatchKey.ParentKeyed bk = srf.batchKey();
        SourceKey.Cardinality cardinality = cardinalityForReturn(srf.returnType());
        return ok(new SourceKey(
            target,
            bk.parentKeyColumns(),
            List.of(),
            wrapForServiceParentKeyed(bk),
            cardinality,
            new SourceKey.Reader.ServiceUntypedRecord()));
    }

    /**
     * Maps a {@link BatchKey.ParentKeyed} permit (six arms today) to the corresponding
     * {@link SourceKey.Wrap}. Driven by the developer's source-shape declaration on the
     * {@code @service} method:
     * <ul>
     *   <li>{@code RowN<...>} sources ({@link BatchKey.RowKeyed},
     *       {@link BatchKey.MappedRowKeyed}) → {@link SourceKey.Wrap#ROW}</li>
     *   <li>{@code RecordN<...>} sources ({@link BatchKey.RecordKeyed},
     *       {@link BatchKey.MappedRecordKeyed}) → {@link SourceKey.Wrap#RECORD}</li>
     *   <li>Typed {@code TableRecord} sources ({@link BatchKey.TableRecordKeyed},
     *       {@link BatchKey.MappedTableRecordKeyed}) →
     *       {@link SourceKey.Wrap#TABLE_RECORD}</li>
     * </ul>
     */
    private static SourceKey.Wrap wrapForServiceParentKeyed(BatchKey.ParentKeyed bk) {
        if (bk instanceof BatchKey.TableRecordKeyed
                || bk instanceof BatchKey.MappedTableRecordKeyed) {
            return SourceKey.Wrap.TABLE_RECORD;
        }
        if (bk instanceof BatchKey.RecordKeyed
                || bk instanceof BatchKey.MappedRecordKeyed) {
            return SourceKey.Wrap.RECORD;
        }
        // RowKeyed + MappedRowKeyed
        return SourceKey.Wrap.ROW;
    }

    /**
     * Cardinality for a table-bound return type — driven by the field's wrapper. List or
     * connection wrappers project to {@link SourceKey.Cardinality#MANY}; single to
     * {@link SourceKey.Cardinality#ONE}.
     */
    private static SourceKey.Cardinality cardinalityFor(ReturnTypeRef.TableBoundReturnType rt) {
        return rt.wrapper().isList()
            ? SourceKey.Cardinality.MANY
            : SourceKey.Cardinality.ONE;
    }

    /**
     * Cardinality for a non-table-bound return type. ServiceRecordField's return is
     * {@link ReturnTypeRef.ResultReturnType} or {@link ReturnTypeRef.ScalarReturnType}; both
     * carry a {@code wrapper()} accessor.
     */
    private static SourceKey.Cardinality cardinalityForReturn(ReturnTypeRef rt) {
        return rt.wrapper().isList()
            ? SourceKey.Cardinality.MANY
            : SourceKey.Cardinality.ONE;
    }

    /**
     * Last hop's target table when the joinPath is non-empty and ends at a
     * {@link JoinStep.WithTarget} hop. Returns {@code null} for the scalar-service-with-no-
     * reconnect case.
     */
    private static TableRef serviceRecordTarget(List<JoinStep> joinPath) {
        if (joinPath.isEmpty()) return null;
        JoinStep last = joinPath.get(joinPath.size() - 1);
        if (last instanceof JoinStep.WithTarget wt) return wt.targetTable();
        return null;
    }

    private static Resolved ok(SourceKey key) { return new Resolved.Ok(key); }
    private static Resolved rejected(String message) {
        return new Resolved.Rejected(Rejection.structural(message));
    }
}
