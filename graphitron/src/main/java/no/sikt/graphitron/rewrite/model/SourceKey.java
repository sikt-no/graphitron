package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;
import java.util.Objects;

/**
 * The batch key extracted from a source-bearing field's parent: the key column tuple plus the
 * Java shape one emitted key row takes. Nothing about where the key points (the leaf's
 * {@code returnType.table()} / {@link ParentCorrelation}), how it is lifted off the parent
 * ({@link KeyLift} on the record-parent leaves), or what envelope the parent arrived in
 * ({@link SourceEnvelope} on the carrier leaves). R431 decomposed the former six-component
 * record onto those facts; this residue is what remains — and it is exactly the
 * {@code (wrap, columns)} pair the partial carriers ({@link MethodRef.Param.Sourced},
 * {@link ParamSource.Sources}, {@code ServiceCatalog.SourcesShape}) always held.
 *
 * <p>Pairs with {@link LoaderRegistration} (DataLoader container kind + dispatch shape) at the
 * field-classifier site: one {@code SourceKey} per {@link BatchKeyField}; the
 * {@link LoaderRegistration} is a separate value because the same key shape can be loaded into
 * either a positional or mapped DataLoader container.
 *
 * <h2>Components</h2>
 *
 * <ul>
 *   <li>{@link #columns()} — entry-point columns for the rows-method's parent-input VALUES
 *       table: target-side columns for the catalog-FK / accessor arms, first-hop source-side
 *       columns for the {@code @sourceRow + @reference} chain.</li>
 *   <li>{@link #wrap()} — the Java shape of one key row: {@link Wrap.Row} ({@code RowN<...>}),
 *       {@link Wrap.Record} ({@code RecordN<...>}), or {@link Wrap.TableRecord} (the typed jOOQ
 *       {@code TableRecord} subclass, with the {@link ClassName} payload). Stored where the
 *       shape is authored (the {@code @splitQuery} source-shape choice, the {@code @service}
 *       {@code Sources} signature); derived from the lift arm ({@link KeyLift#wrap()}) where it
 *       is inferred (the record-parent leaves, whose constructors pin the derivation via
 *       {@link KeyLift#checkResidueAgreement}).</li>
 * </ul>
 */
public record SourceKey(
    List<ColumnRef> columns,
    Wrap wrap
) {

    /**
     * The Java shape of one key row. Sealed so the {@link TableRecord} arm can carry the
     * developer-declared {@code TableRecord} subclass payload that the column-tuple arms have
     * no use for; {@link #keyElementType()} is total without an extra nullable field on
     * {@link SourceKey}.
     */
    public sealed interface Wrap {
        /** {@code RowN<...>} — values only, no value-N accessors. */
        record Row() implements Wrap {}
        /** {@code RecordN<...>} — values + value1()..valueN() accessors. */
        record Record() implements Wrap {}
        /**
         * A typed jOOQ {@code TableRecord} subclass (e.g. {@code FilmRecord}); {@code className}
         * is the developer-declared subtype that propagates to the rows-method's parameter
         * shape and the loader-key element type.
         */
        record TableRecord(ClassName className) implements Wrap {
            public TableRecord {
                Objects.requireNonNull(className, "className");
            }
        }
    }

    public SourceKey {
        Objects.requireNonNull(wrap, "wrap");
        columns = List.copyOf(columns);
    }

    /**
     * The DataLoader key element type — {@code RowN<...>}, {@code RecordN<...>}, or the
     * developer-declared {@code TableRecord} subclass — derived from {@link #wrap()} and
     * {@link #columns()}.
     *
     * <p>For {@link Wrap.Row}: {@code Row<n>} parameterised by each column's
     * {@link ColumnRef#columnClass()}.
     * For {@link Wrap.Record}: {@code Record<n>} parameterised by each column's
     * {@code columnClass}. For {@link Wrap.TableRecord}: the captured
     * {@link Wrap.TableRecord#className()}.
     */
    public TypeName keyElementType() {
        return keyElementType(wrap, columns);
    }

    /**
     * Static derivation of the DataLoader key element type from the {@code (wrap, columns)}
     * pair alone. Used by {@link MethodRef.Param.Sourced} and {@link ParamSource.Sources}
     * consumers that hold the pair {@code (wrap, columns[, container])} directly without a
     * full {@link SourceKey}.
     */
    public static TypeName keyElementType(Wrap wrap, List<ColumnRef> columns) {
        return switch (wrap) {
            case Wrap.Row r            -> jooqShape("Row", columns);
            case Wrap.Record r         -> jooqShape("Record", columns);
            case Wrap.TableRecord tr   -> tr.className();
        };
    }

    private static TypeName jooqShape(String shape, List<ColumnRef> cols) {
        ClassName container = ClassName.get("org.jooq", shape + cols.size());
        TypeName[] args = cols.stream()
            .map(ColumnRef::columnType)
            .toArray(TypeName[]::new);
        return ParameterizedTypeName.get(container, args);
    }
}
