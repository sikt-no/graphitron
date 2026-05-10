package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;

/**
 * Projects a {@link BatchKeyField} into a {@link LoaderRegistration}: DataLoader identity +
 * container kind. Sibling of {@link SourceKeyResolver}; both project from today's
 * {@link BatchKey} (already classified upstream) onto the new R38 model surface.
 *
 * <p>Container projection is mechanical:
 *
 * <ul>
 *   <li>{@code Mapped*Keyed} permits → {@link LoaderRegistration.Container#MAPPED_SET}
 *       (drives {@code DataLoaderFactory.newMappedDataLoader}).</li>
 *   <li>{@link BatchKey.AccessorKeyedMany} → {@link LoaderRegistration.Container#MAPPED_SET}
 *       too: the {@code loader.loadMany} contract emits one record per element-PK key, and
 *       the resulting per-key value type folds onto the same mapped container.</li>
 *   <li>All other permits → {@link LoaderRegistration.Container#POSITIONAL_LIST}
 *       (drives {@code DataLoaderFactory.newDataLoader}).</li>
 * </ul>
 *
 * <p>{@code valueIsList} follows the field's wrapper: {@code true} when the loader returns
 * a list per key (list / connection field cardinality), {@code false} for single-cardinality
 * fields. The single-record-per-key arms (single-cardinality + {@code AccessorKeyedMany})
 * read {@code loader.load(key) -> Record}; list-cardinality arms read
 * {@code loader.load(key) -> List<Record>}.
 *
 * <p>{@code loaderName} is the field's qualified name (parent type + "." + name), used as a
 * stable logical identifier at the classifier site. The actual runtime path-scoped name is
 * still computed at emit time via {@code GeneratorUtils.buildDataLoaderName}; this string is
 * carried for diagnostics and for any future name-pinning.
 */
public final class LoaderRegistrationResolver {

    private LoaderRegistrationResolver() {}

    /**
     * Pure projection: every {@link BatchKeyField} produces a {@link LoaderRegistration}, no
     * rejection arm needed (the field-permit / batchkey-permit cross product is fully covered
     * by the rules above).
     */
    public static LoaderRegistration resolve(BatchKeyField field) {
        return new LoaderRegistration(
            qualifiedName(field),
            valueIsList(field),
            container(field.batchKey()));
    }

    private static LoaderRegistration.Container container(BatchKey bk) {
        if (bk instanceof BatchKey.MappedRowKeyed
                || bk instanceof BatchKey.MappedRecordKeyed
                || bk instanceof BatchKey.MappedTableRecordKeyed
                || bk instanceof BatchKey.AccessorKeyedMany) {
            return LoaderRegistration.Container.MAPPED_SET;
        }
        return LoaderRegistration.Container.POSITIONAL_LIST;
    }

    /**
     * The DataLoader's per-key value is a list iff the field is list-cardinality AND the
     * loader.load contract returns a list per key. Single-cardinality fields and
     * {@link BatchKey.AccessorKeyedMany}'s loadMany contract both emit one record per key,
     * so their {@code valueIsList} is false.
     */
    private static boolean valueIsList(BatchKeyField field) {
        if (field instanceof ChildField.SplitTableField stf) {
            return stf.returnType().wrapper().isList();
        }
        if (field instanceof ChildField.SplitLookupTableField slf) {
            return slf.returnType().wrapper().isList();
        }
        if (field instanceof ChildField.RecordTableField rtf) {
            return rtf.returnType().wrapper().isList()
                && !(rtf.batchKey() instanceof BatchKey.AccessorKeyedMany);
        }
        if (field instanceof ChildField.RecordLookupTableField rltf) {
            return rltf.returnType().wrapper().isList()
                && !(rltf.batchKey() instanceof BatchKey.AccessorKeyedMany);
        }
        if (field instanceof ChildField.ServiceTableField stf) {
            return stf.returnType().wrapper().isList();
        }
        if (field instanceof ChildField.ServiceRecordField srf) {
            return wrapperIsList(srf.returnType());
        }
        return false;
    }

    private static boolean wrapperIsList(ReturnTypeRef rt) {
        return rt.wrapper().isList();
    }

    private static String qualifiedName(BatchKeyField field) {
        if (field instanceof ChildField.SplitTableField stf) return stf.parentTypeName() + "." + stf.name();
        if (field instanceof ChildField.SplitLookupTableField slf) return slf.parentTypeName() + "." + slf.name();
        if (field instanceof ChildField.RecordTableField rtf) return rtf.parentTypeName() + "." + rtf.name();
        if (field instanceof ChildField.RecordLookupTableField rltf) return rltf.parentTypeName() + "." + rltf.name();
        if (field instanceof ChildField.ServiceTableField stf) return stf.parentTypeName() + "." + stf.name();
        if (field instanceof ChildField.ServiceRecordField srf) return srf.parentTypeName() + "." + srf.name();
        return field.name();
    }
}
