package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;

/**
 * Projects a {@link BatchKey} plus field-shape inputs into a {@link LoaderRegistration}:
 * container kind + dispatch shape + valueIsList. Sibling of {@link SourceKeyResolver}; both
 * project from today's {@link BatchKey} (already classified upstream) onto the new R38 model
 * surface, and the producers call the matching entry point at field-construction time.
 *
 * <p>Container projection is mechanical:
 *
 * <ul>
 *   <li>{@code Mapped*Keyed} permits → {@link LoaderRegistration.Container#MAPPED_SET}
 *       (drives {@code DataLoaderFactory.newMappedDataLoader}).</li>
 *   <li>All other permits, including {@link BatchKey.AccessorKeyedMany}, →
 *       {@link LoaderRegistration.Container#POSITIONAL_LIST}
 *       (drives {@code DataLoaderFactory.newDataLoader}).</li>
 * </ul>
 *
 * <p>Dispatch is orthogonal to container: today only {@link BatchKey.AccessorKeyedMany}
 * reaches {@link LoaderRegistration.Dispatch#LOAD_MANY} (its {@code @record} parent's
 * typed list-accessor fans out per-element-PK), and it does so on a positional
 * DataLoader. Every other permit takes {@link LoaderRegistration.Dispatch#LOAD_ONE}.
 *
 * <p>{@code valueIsList} follows the field's wrapper: {@code true} when the loader returns
 * a list per key (list / connection field cardinality), {@code false} for single-cardinality
 * fields. The single-record-per-key arms (single-cardinality + {@code AccessorKeyedMany})
 * read {@code loader.load(key) -> Record}; list-cardinality arms read
 * {@code loader.load(key) -> List<Record>}.
 */
public final class LoaderRegistrationResolver {

    private LoaderRegistrationResolver() {}

    /**
     * Projects a {@link BatchKey} plus the field's return-type wrapper into a
     * {@link LoaderRegistration}. The {@code returnType} parameter supplies the field's
     * cardinality via {@link ReturnTypeRef#wrapper()}; service-side and record-parent permits
     * both flow through the same projection.
     */
    public static LoaderRegistration resolve(BatchKey bk, ReturnTypeRef returnType) {
        return new LoaderRegistration(
            valueIsList(bk, returnType),
            container(bk),
            dispatch(bk));
    }

    @LoadBearingClassifierCheck(
        key = "loader-registration.container-axis-independent-of-dispatch",
        description = "container() projects only the three Mapped* permits to MAPPED_SET; every "
            + "other permit (including AccessorKeyedMany, whose dispatch is LOAD_MANY) lands on "
            + "POSITIONAL_LIST. The container axis is therefore independent of the dispatch axis: "
            + "AccessorKeyedMany pairs POSITIONAL_LIST + LOAD_MANY (newDataLoader takes List<K>; "
            + "loader.loadMany emits one Record per element-PK), and Mapped* permits pair "
            + "MAPPED_SET + LOAD_ONE. The independence is load-bearing for "
            + "TypeFetcherGenerator.buildRecordBasedDataFetcher's `valueType = Record` choice on "
            + "AccessorKeyedMany: routing it to MAPPED_SET would break the generated newDataLoader "
            + "type signature (Set<Record1<Integer>> cannot satisfy newDataLoader's List<K> "
            + "parameter), as Phase 2 caught at compile time when the spec table mis-routed it.")
    private static LoaderRegistration.Container container(BatchKey bk) {
        if (bk instanceof BatchKey.MappedRowKeyed
                || bk instanceof BatchKey.MappedRecordKeyed
                || bk instanceof BatchKey.MappedTableRecordKeyed) {
            return LoaderRegistration.Container.MAPPED_SET;
        }
        return LoaderRegistration.Container.POSITIONAL_LIST;
    }

    private static LoaderRegistration.Dispatch dispatch(BatchKey bk) {
        return bk instanceof BatchKey.AccessorKeyedMany
            ? LoaderRegistration.Dispatch.LOAD_MANY
            : LoaderRegistration.Dispatch.LOAD_ONE;
    }

    /**
     * The DataLoader's per-key value is a list iff the field is list-cardinality AND the
     * loader.load contract returns a list per key. List-cardinality fields with
     * {@link BatchKey.AccessorKeyedMany} use the {@code loader.loadMany} contract which emits
     * one record per key, so their {@code valueIsList} is false.
     */
    private static boolean valueIsList(BatchKey bk, ReturnTypeRef rt) {
        if (!rt.wrapper().isList()) return false;
        return !(bk instanceof BatchKey.AccessorKeyedMany);
    }
}
