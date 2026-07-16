package no.sikt.graphitron.rewrite.catalog;

import java.util.List;

/**
 * LSP-facing projection of a {@link no.sikt.graphitron.rewrite.model.GraphitronType}'s
 * classified variant. The LSP's inlay-hint and hover arms consume this to render
 * classification information at SDL type declarations and at the {@code @table} directive
 * sites where the canonical {@code name:} argument was inferred. Carried alongside
 * {@link TypeBackingShape} and {@link FieldClassification} on
 * {@link LspSchemaSnapshot.Built}.
 *
 * <p>Same discipline as {@link FieldClassification}: payload-distinct records over the
 * {@code GraphitronType} permits, sized to LSP-renderable strings and primitives. The
 * type-side payload divergence is denser than the field side (record-backed, pojo-backed,
 * jOOQ-table-backed, etc. all carry different load-bearing payload), so most type permits
 * keep their own record; collapse where the hover-payload is genuinely identical.
 *
 * <p>The producer-side exhaustive switch in
 * {@link CatalogBuilder#projectTypeClassification} enforces coverage; the label switch in
 * {@code LspClassificationLabels} dispatches over the full generator-side permit set.
 *
 * <p>{@link TypeBackingShape} (which already lives on the snapshot) covers the
 * {@code @field(name:)} dispatch axis for the LSP — i.e. what's resolvable as a member
 * name inside a type. This projection covers the orthogonal classification axis: <em>what
 * kind of type</em> the SDL author declared. Both projections live next to each other on
 * the snapshot so the LSP arms can read either or both without re-running the classifier.
 *
 * <p><b>Projection-record simple names are also user-visible.</b> {@code
 * LspClassificationLabels.projectionTypeLabel} returns each permit's simple name
 * verbatim, {@code DeclarationHovers} prints {@code TypeClassification.<name>} in hover
 * headers, and {@code InlayHints}'s absent-{@code @table} arm anchors its synthetic
 * ghost on the {@code Table} / {@code Node} / {@code TableInterface} / {@code TableInput}
 * projection records. Renaming a permit (say, {@code TableInput} to
 * {@code TableBoundInput}) is therefore <em>also</em> a user-visible-string change
 * touching docs, screenshots, and tutorials, not a purely internal refactor.
 */
public sealed interface TypeClassification
    permits TypeClassification.Table,
            TypeClassification.Node,
            TypeClassification.TableInterface,
            TypeClassification.Interface,
            TypeClassification.Union,
            TypeClassification.JavaRecord,
            TypeClassification.JavaRecordInput,
            TypeClassification.JooqRecord,
            TypeClassification.JooqRecordInput,
            TypeClassification.JooqTableRecord,
            TypeClassification.JooqTableRecordInput,
            TypeClassification.PojoResult,
            TypeClassification.PojoInput,
            TypeClassification.TableInput,
            TypeClassification.Root,
            TypeClassification.Connection,
            TypeClassification.Edge,
            TypeClassification.PageInfo,
            TypeClassification.Error,
            TypeClassification.Enum,
            TypeClassification.Scalar,
            TypeClassification.PlainObject,
            TypeClassification.Unclassified {

    /**
     * A {@code @table}-annotated object type. Covers {@code GraphitronType.TableType}.
     */
    record Table(String tableName) implements TypeClassification {}

    /**
     * A {@code @table} + {@code @node} type. Covers {@code GraphitronType.NodeType}.
     * {@code typeId} is null when the directive argument was omitted (classifier-deduced).
     */
    record Node(
        String tableName, String typeId, List<String> keyColumnNames
    ) implements TypeClassification {

        public Node {
            keyColumnNames = List.copyOf(keyColumnNames);
        }
    }

    /**
     * A {@code @table} + {@code @discriminate} interface. Covers
     * {@code GraphitronType.TableInterfaceType}.
     */
    record TableInterface(
        String tableName, String discriminatorColumn, List<String> participantTypeNames
    ) implements TypeClassification {

        public TableInterface {
            participantTypeNames = List.copyOf(participantTypeNames);
        }
    }

    /**
     * A multi-table interface (no {@code @table} on the interface itself). Covers
     * {@code GraphitronType.InterfaceType}.
     */
    record Interface(List<String> participantTypeNames) implements TypeClassification {

        public Interface {
            participantTypeNames = List.copyOf(participantTypeNames);
        }
    }

    /**
     * A union of {@code @table}-bearing members. Covers {@code GraphitronType.UnionType}.
     */
    record Union(List<String> participantTypeNames) implements TypeClassification {

        public Union {
            participantTypeNames = List.copyOf(participantTypeNames);
        }
    }

    /**
     * A reflection-bound output type backed by a Java {@code record} class (its producer's
     * reflected return is a Java record). Covers {@code GraphitronType.JavaRecordType}.
     */
    record JavaRecord(String fqClassName) implements TypeClassification {}

    /**
     * A reflection-bound input type backed by a Java {@code record} class (the method
     * parameter it flows into is a Java record). Covers {@code GraphitronType.JavaRecordInputType}.
     */
    record JavaRecordInput(String fqClassName) implements TypeClassification {}

    /**
     * A reflection-bound output type backed by a jOOQ {@code Record<?>} subclass
     * (not table-bound; its producer's reflected return is such a record). Covers
     * {@code GraphitronType.JooqRecordType}.
     */
    record JooqRecord(String fqClassName) implements TypeClassification {}

    /**
     * A reflection-bound input type backed by a jOOQ {@code Record<?>} subclass
     * (not table-bound; the method parameter it flows into is such a record). Covers
     * {@code GraphitronType.JooqRecordInputType}.
     */
    record JooqRecordInput(String fqClassName) implements TypeClassification {}

    /**
     * A reflection-bound output type backed by a jOOQ {@code TableRecord<?>}
     * with a resolved table (its producer's reflected return is such a record). Covers
     * {@code GraphitronType.JooqTableRecordType}.
     * {@code tableName} may be null when the catalog is unavailable.
     */
    record JooqTableRecord(String fqClassName, String tableName) implements TypeClassification {}

    /**
     * A reflection-bound input type backed by a jOOQ {@code TableRecord<?>} (the method
     * parameter it flows into is such a record). Covers {@code GraphitronType.JooqTableRecordInputType}.
     */
    record JooqTableRecordInput(String fqClassName, String tableName) implements TypeClassification {}

    /**
     * A reflection-bound output type backed by a plain Java class (POJO; its producer's
     * reflected return is such a class). Covers {@code GraphitronType.PojoResultType.Backed}.
     */
    record PojoResult(String fqClassName) implements TypeClassification {}

    /**
     * A reflection-bound input type backed by a plain Java class (POJO; the method parameter
     * it flows into is such a class). Covers {@code GraphitronType.PojoInputType};
     * {@code fqClassName} is null when no backing class could be resolved.
     */
    record PojoInput(String fqClassName) implements TypeClassification {}

    /**
     * A {@code @table}-annotated input type. Covers {@code GraphitronType.TableInputType}.
     */
    record TableInput(String tableName) implements TypeClassification {}

    /**
     * The {@code Query} or {@code Mutation} root operation type. Covers
     * {@code GraphitronType.RootType}. The {@code operation} is the SDL name
     * ({@code "Query"}, {@code "Mutation"}, etc.).
     */
    record Root(String operation) implements TypeClassification {}

    /**
     * A Relay connection wrapper. Covers {@code GraphitronType.ConnectionType}.
     */
    record Connection(String elementTypeName, String edgeTypeName) implements TypeClassification {}

    /**
     * A Relay edge wrapper. Covers {@code GraphitronType.EdgeType}.
     */
    record Edge(String elementTypeName) implements TypeClassification {}

    /**
     * The Relay {@code PageInfo} type. Covers {@code GraphitronType.PageInfoType}.
     */
    record PageInfo() implements TypeClassification {}

    /**
     * An {@code @error}-annotated type with a sealed handler list. Covers
     * {@code GraphitronType.ErrorType}. {@code handlerKinds} lists the handler variants
     * ({@code "exception"}, {@code "sql-state"}, {@code "vendor-code"}, {@code "validation"})
     * in source order.
     */
    record Error(List<String> handlerKinds) implements TypeClassification {

        public Error {
            handlerKinds = List.copyOf(handlerKinds);
        }
    }

    /**
     * A GraphQL enum type. Covers {@code GraphitronType.EnumType}.
     */
    record Enum() implements TypeClassification {}

    /**
     * A GraphQL scalar type. Covers {@code GraphitronType.ScalarType}. {@code javaType} is
     * the resolved Java type name of the scalar's value (rendered display form).
     */
    record Scalar(String javaType) implements TypeClassification {}

    /**
     * A plain SDL object type with no domain directive. Covers
     * {@code GraphitronType.NestingType}.
     */
    record PlainObject() implements TypeClassification {}

    /**
     * A type the classifier could not assign a variant to. Covers
     * {@code GraphitronType.UnclassifiedType}. {@code reason} is the rejection message.
     */
    record Unclassified(String reason) implements TypeClassification {}
}
