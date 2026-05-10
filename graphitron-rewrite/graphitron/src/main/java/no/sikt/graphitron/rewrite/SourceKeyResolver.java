package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.List;

/**
 * Projects a {@link BatchKey} + field-shape inputs into a {@link SourceKey}: the new singular
 * per-field source-side metadata that R38 introduces. Sibling to {@link OrderByResolver},
 * {@link PaginationResolver}, {@link LookupKeyDirectiveResolver},
 * {@link SourceRowDirectiveResolver}, {@link ClassAccessorResolver}.
 *
 * <p>Single-concern projection: takes today's {@link BatchKey} (classified upstream in
 * {@code FieldBuilder} / {@code ServiceCatalog} / {@code SourceRowDirectiveResolver}) plus the
 * field's typed {@link ReturnTypeRef} and produces the new {@link SourceKey}. Producers call
 * the matching per-shape entry point at field-construction time and pass the resulting
 * {@code SourceKey} to the field's record constructor.
 *
 * <p>The projection rules (Field permit × BatchKey permit → {@link SourceKey.Reader}) are
 * pinned in the spec's "{@code BatchKey} → {@code SourceKey} projection" subsection
 * ({@code graphitron-rewrite/roadmap/unify-rowsmethodname.md}).
 *
 * <p>Phase 3 (in progress) is folding consumer reads off the legacy {@link BatchKey} and onto
 * the field's {@code sourceKey()} record component, which the producers populate via these
 * entry points. The end-state is that the resolvers' contents inline into the producers
 * (FieldBuilder / ServiceCatalog / SourceRowDirectiveResolver) and the projection table
 * disappears as a separate helper.
 */
public final class SourceKeyResolver {

    private SourceKeyResolver() {}

    /**
     * Projects a {@link BatchKey.RowKeyed} (catalog-FK only) plus the field's table-bound
     * return type into a {@link SourceKey}. Used by {@link no.sikt.graphitron.rewrite.model.ChildField.SplitTableField}
     * and {@link no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField} (the
     * {@code @lookupKey} wrapper lives outside {@link SourceKey} on the field's
     * {@link no.sikt.graphitron.rewrite.model.LookupMapping}).
     */
    public static SourceKey resolveSplit(BatchKey.RowKeyed bk, ReturnTypeRef.TableBoundReturnType rt) {
        return new SourceKey(
            rt.table(),
            bk.parentKeyColumns(),
            List.of(),
            new SourceKey.Wrap.Row(),
            cardinalityFor(rt),
            new SourceKey.Reader.ColumnRead());
    }

    /**
     * Projects a {@link BatchKey.RecordParentBatchKey} (one of five permits) plus the field's
     * table-bound return type into a {@link SourceKey}. Used by
     * {@link no.sikt.graphitron.rewrite.model.ChildField.RecordTableField} and
     * {@link no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField}; reader-axis
     * dispatch is on the {@link BatchKey.RecordParentBatchKey} permit identity.
     */
    public static SourceKey resolveRecordParent(BatchKey.RecordParentBatchKey bk,
                                                 ReturnTypeRef.TableBoundReturnType rt) {
        SourceKey.Cardinality cardinality = cardinalityFor(rt);
        TableRef target = rt.table();
        if (bk instanceof BatchKey.RowKeyed rk) {
            return new SourceKey(
                target, rk.parentKeyColumns(), List.of(),
                new SourceKey.Wrap.Row(), cardinality,
                new SourceKey.Reader.ColumnRead());
        }
        if (bk instanceof BatchKey.LifterLeafKeyed llk) {
            return new SourceKey(
                target, llk.parentSideColumns(), List.of(llk.hop()),
                new SourceKey.Wrap.Row(), cardinality,
                new SourceKey.Reader.SourceRowsCall(llk.lifter()));
        }
        if (bk instanceof BatchKey.LifterPathKeyed lpk) {
            return new SourceKey(
                target, lpk.parentSideColumns(), lpk.path(),
                new SourceKey.Wrap.Row(), cardinality,
                new SourceKey.Reader.SourceRowsCall(lpk.lifter()));
        }
        if (bk instanceof BatchKey.AccessorKeyedSingle aks) {
            return new SourceKey(
                target, aks.targetKeyColumns(), List.of(aks.hop()),
                new SourceKey.Wrap.Record(), SourceKey.Cardinality.ONE,
                new SourceKey.Reader.AccessorCall(aks.accessor()));
        }
        if (bk instanceof BatchKey.AccessorKeyedMany akm) {
            return new SourceKey(
                target, akm.targetKeyColumns(), List.of(akm.hop()),
                new SourceKey.Wrap.Record(), SourceKey.Cardinality.MANY,
                new SourceKey.Reader.AccessorCall(akm.accessor()));
        }
        throw new IllegalArgumentException(
            "SourceKeyResolver.resolveRecordParent: RecordParentBatchKey permit '"
                + bk.getClass().getSimpleName() + "' has no projection rule");
    }

    /**
     * Polymorphic-parent projection: a parent-side {@link SourceKey} for an
     * {@link no.sikt.graphitron.rewrite.model.ChildField.InterfaceField} or
     * {@link no.sikt.graphitron.rewrite.model.ChildField.UnionField}. The polymorphic field's
     * own return type is {@link ReturnTypeRef.PolymorphicReturnType} (no table target), so this
     * variant takes only the {@link BatchKey} and produces a {@link SourceKey} suitable for
     * extracting the parent's identity from {@code env.getSource()}.
     *
     * <p>Cardinality is variant-derived rather than field-cardinality-derived: each parent is
     * one entity (so {@link BatchKey.RowKeyed}, both lifter arms, and
     * {@link BatchKey.AccessorKeyedSingle} land on {@link SourceKey.Cardinality#ONE}); the
     * {@link BatchKey.AccessorKeyedMany} arm preserves {@link SourceKey.Cardinality#MANY} for
     * the per-element walk through the parent record's typed list-accessor.
     *
     * <p>{@code target} is {@code null} for the {@code ColumnRead}/{@code SourceRowsCall} arms
     * (the parent doesn't have a target — it IS the source); the accessor arms set
     * {@code target} from the {@code LiftedHop}'s target table because that's where the
     * accessor's typed return lives.
     */
    public static SourceKey resolveRecordParentForPolymorphic(BatchKey.RecordParentBatchKey bk) {
        if (bk instanceof BatchKey.RowKeyed rk) {
            return new SourceKey(
                null, rk.parentKeyColumns(), List.of(),
                new SourceKey.Wrap.Row(), SourceKey.Cardinality.ONE,
                new SourceKey.Reader.ColumnRead());
        }
        if (bk instanceof BatchKey.LifterLeafKeyed llk) {
            return new SourceKey(
                null, llk.parentSideColumns(), List.of(llk.hop()),
                new SourceKey.Wrap.Row(), SourceKey.Cardinality.ONE,
                new SourceKey.Reader.SourceRowsCall(llk.lifter()));
        }
        if (bk instanceof BatchKey.LifterPathKeyed lpk) {
            return new SourceKey(
                null, lpk.parentSideColumns(), lpk.path(),
                new SourceKey.Wrap.Row(), SourceKey.Cardinality.ONE,
                new SourceKey.Reader.SourceRowsCall(lpk.lifter()));
        }
        if (bk instanceof BatchKey.AccessorKeyedSingle aks) {
            return new SourceKey(
                aks.hop().targetTable(), aks.targetKeyColumns(), List.of(aks.hop()),
                new SourceKey.Wrap.Record(), SourceKey.Cardinality.ONE,
                new SourceKey.Reader.AccessorCall(aks.accessor()));
        }
        if (bk instanceof BatchKey.AccessorKeyedMany akm) {
            return new SourceKey(
                akm.hop().targetTable(), akm.targetKeyColumns(), List.of(akm.hop()),
                new SourceKey.Wrap.Record(), SourceKey.Cardinality.MANY,
                new SourceKey.Reader.AccessorCall(akm.accessor()));
        }
        throw new IllegalArgumentException(
            "SourceKeyResolver.resolveRecordParentForPolymorphic: RecordParentBatchKey permit '"
                + bk.getClass().getSimpleName() + "' has no projection rule");
    }

    /**
     * Projects a service-side {@link BatchKey.ParentKeyed} (six permits) plus the
     * {@code ServiceTableField}'s table-bound return type into a {@link SourceKey}.
     * {@code Wrap} comes from the developer's source-shape declaration on the
     * {@code @service} method (carried on the {@code BatchKey} permit); {@code Reader} is
     * {@code ServiceTableRecord(target.recordClass())} for the typed-table-return service.
     */
    public static SourceKey resolveServiceTable(BatchKey.ParentKeyed bk,
                                                 ReturnTypeRef.TableBoundReturnType rt) {
        return new SourceKey(
            rt.table(),
            bk.parentKeyColumns(),
            List.of(),
            wrapForServiceParentKeyed(bk),
            cardinalityFor(rt),
            new SourceKey.Reader.ServiceTableRecord(rt.table().recordClass()));
    }

    /**
     * Projects a service-side {@link BatchKey.ParentKeyed} (six permits) plus the
     * {@code ServiceRecordField}'s untyped return ({@link ReturnTypeRef.ResultReturnType} or
     * {@link ReturnTypeRef.ScalarReturnType}) and {@code joinPath} into a {@link SourceKey}.
     * Target derives from the {@code joinPath}'s last hop on the service-reconnect path;
     * {@code null} for scalar-returning services with no reconnect. {@code Reader} is
     * {@code ServiceUntypedRecord}.
     */
    public static SourceKey resolveServiceRecord(BatchKey.ParentKeyed bk,
                                                  ReturnTypeRef rt,
                                                  List<JoinStep> joinPath) {
        return new SourceKey(
            serviceRecordTarget(joinPath),
            bk.parentKeyColumns(),
            List.of(),
            wrapForServiceParentKeyed(bk),
            cardinalityForReturn(rt),
            new SourceKey.Reader.ServiceUntypedRecord());
    }

    /**
     * Maps a {@link BatchKey.ParentKeyed} permit (six arms today) to the corresponding
     * {@link SourceKey.Wrap}. Driven by the developer's source-shape declaration on the
     * {@code @service} method:
     * <ul>
     *   <li>{@code RowN<...>} sources ({@link BatchKey.RowKeyed},
     *       {@link BatchKey.MappedRowKeyed}) → {@link SourceKey.Wrap.Row}</li>
     *   <li>{@code RecordN<...>} sources ({@link BatchKey.RecordKeyed},
     *       {@link BatchKey.MappedRecordKeyed}) → {@link SourceKey.Wrap.Record}</li>
     *   <li>Typed {@code TableRecord} sources ({@link BatchKey.TableRecordKeyed},
     *       {@link BatchKey.MappedTableRecordKeyed}) →
     *       {@link SourceKey.Wrap.TableRecord} carrying the developer's typed
     *       {@code TableRecord} subtype.</li>
     * </ul>
     */
    private static SourceKey.Wrap wrapForServiceParentKeyed(BatchKey.ParentKeyed bk) {
        if (bk instanceof BatchKey.TableRecordKeyed trk) {
            return new SourceKey.Wrap.TableRecord(ClassName.get(trk.elementClass()));
        }
        if (bk instanceof BatchKey.MappedTableRecordKeyed mtrk) {
            return new SourceKey.Wrap.TableRecord(ClassName.get(mtrk.elementClass()));
        }
        if (bk instanceof BatchKey.RecordKeyed
                || bk instanceof BatchKey.MappedRecordKeyed) {
            return new SourceKey.Wrap.Record();
        }
        // RowKeyed + MappedRowKeyed
        return new SourceKey.Wrap.Row();
    }

    private static SourceKey.Cardinality cardinalityFor(ReturnTypeRef.TableBoundReturnType rt) {
        return rt.wrapper().isList()
            ? SourceKey.Cardinality.MANY
            : SourceKey.Cardinality.ONE;
    }

    private static SourceKey.Cardinality cardinalityForReturn(ReturnTypeRef rt) {
        return rt.wrapper().isList()
            ? SourceKey.Cardinality.MANY
            : SourceKey.Cardinality.ONE;
    }

    private static TableRef serviceRecordTarget(List<JoinStep> joinPath) {
        if (joinPath.isEmpty()) return null;
        JoinStep last = joinPath.get(joinPath.size() - 1);
        if (last instanceof JoinStep.WithTarget wt) return wt.targetTable();
        return null;
    }
}
