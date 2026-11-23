package no.sikt.graphitron.rewrite.model;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;

import java.util.List;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;

/**
 * How to extract one argument value from a GraphQL execution context at the fetcher call site.
 *
 * <p>Used in {@link CallParam} to tell the fetcher generator exactly what code to emit for each
 * argument passed to a condition or ordering method. The generator switches on this type once and
 * emits the corresponding expression, no other decisions are made at generation time.
 *
 * <p>Text-mapped enum args route through {@link Direct}: graphql-java carries the
 * {@code @field(name:)} runtime form on {@code GraphQLEnumValueDefinition.value(...)}, so the
 * wire-form to runtime-form translation happens at the graphql-java boundary.
 */
public sealed interface CallSiteExtraction
        permits CallSiteExtraction.Direct, CallSiteExtraction.EnumValueOf,
                CallSiteExtraction.ContextArg,
                CallSiteExtraction.SessionHandleRead, CallSiteExtraction.SessionMemberRead,
                CallSiteExtraction.JooqConvert, CallSiteExtraction.NestedInputField,
                CallSiteExtraction.NodeIdDecodeKeys, CallSiteExtraction.NodeIdDecodeRecord,
                CallSiteExtraction.NodeIdDecodePolymorphicRecord,
                CallSiteExtraction.InputBean, CallSiteExtraction.ElementRows {

    /** Pass the argument directly: {@code env.getArgument("name")}. */
    record Direct() implements CallSiteExtraction {}

    /**
     * Convert a GraphQL String argument to a jOOQ enum:
     * {@code EnumClass.valueOf(env.<String>getArgument("name"))}, null-guarded when the argument
     * is nullable.
     *
     * <p>{@code enumClassName} is the fully qualified Java enum class name
     * (e.g. {@code "no.example.jooq.enums.MpaaRating"}).
     */
    record EnumValueOf(String enumClassName) implements CallSiteExtraction {}

    /**
     * Retrieve a context argument: {@code graphitronContext(env).getContextArgument(env, "name")}.
     */
    record ContextArg() implements CallSiteExtraction {}

    /**
     * Read the whole session handle off the executing {@code DSLContext}'s own connection:
     * {@code OwnedConnections.sessionHandle(dsl, "<coordinate>")}. A {@code @condition} binding
     * ({@code argMapping: "x: $session"}); never reads the argument map or the request context,
     * so a glue holding one takes the statement's {@code dsl}.
     */
    record SessionHandleRead() implements CallSiteExtraction {}

    /**
     * Read one member of the session handle off the executing {@code DSLContext}'s own
     * connection ({@code argMapping: "x: $session.<member>"}); see {@link SessionHandleRead}.
     * {@code member} is the authored member name, which the connection runtime's guarded member
     * accessor is keyed by.
     */
    record SessionMemberRead(String member) implements CallSiteExtraction {}

    /**
     * Coerce a GraphQL {@code ID} scalar (delivered as {@code String} by GraphQL-Java) to the
     * column Java type by binding it through the column's {@code DataType} (and any registered
     * {@code Converter}) via {@code DSL.val(Object, DataType<T>).getValue()}
     * ({@code DataType.convert(Object)} is deprecated for removal in jOOQ 3.20).
     *
     * <p>{@code columnJavaName} is the jOOQ field constant name (e.g. {@code "FILM_ID"}) used to
     * reach the target {@code DataType} from the table alias:
     * <ul>
     *   <li>Scalar: {@code DSL.val(env.getArgument("film_id"), table.FILM_ID.getDataType()).getValue()}</li>
     *   <li>List (with {@link CallParam#list()} / {@link BodyParam#list()}): local variable
     *       {@code List<String> filmIdKeys = env.getArgument("film_id")} is declared before the
     *       condition call, then passed as
     *       {@code filmIdKeys.stream().map(k -> DSL.val(k, table.FILM_ID.getDataType()).getValue()).toList()}.</li>
     * </ul>
     */
    record JooqConvert(String columnJavaName) implements CallSiteExtraction {}

    /**
     * Traverse a nested input-object graph starting from the top-level argument named
     * {@code outerArgName} and descending through {@code path} keys to retrieve a value stored
     * inside the argument Map. Used by {@code @condition} on {@code INPUT_FIELD_DEFINITION}:
     * the condition method's parameter is the field value, but the field is not a top-level
     * argument, so the generator must emit Map traversal from the outer arg down to the leaf.
     *
     * <p>{@code path} is the ordered list of keys from the outer argument Map to the leaf value.
     * Non-empty; the last element is the SDL input-field name whose condition is being evaluated
     * (matches {@link CallParam#name()}).
     *
     * <p>{@code leaf} is the per-leaf transform applied to the wire-format value returned by the
     * Map traversal. {@link Direct} (the default for column-equality paths) leaves the value as
     * is. {@link NodeIdDecodeKeys} arms are used for {@code [ID!] @nodeId(typeName: T)} input
     * fields whose wire-format {@code List<String>} of base64 ids is decoded element-by-element
     * via the {@code decode<TypeName>} helper into typed key values before the predicate body
     * fires.
     *
     * <p>Traversal is null-safe: if any intermediate step is not a {@code Map} or is
     * {@code null}, the result is {@code null}. The condition method is still invoked; if it
     * receives {@code null}, it is responsible for returning a no-op filter.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code outerArgName="filter"}, {@code path=["filmId"]}, {@code leaf=Direct} for a
     *       direct {@code ColumnField.filmId} condition on a top-level input arg.</li>
     *   <li>{@code outerArgName="filter"}, {@code path=["where", "filmId"]}, {@code leaf=Direct}
     *       for a {@code ColumnField.filmId} inside a {@code NestingField.where}.</li>
     * </ul>
     */
    record NestedInputField(String outerArgName, List<String> path, CallSiteExtraction leaf)
            implements CallSiteExtraction {
        public NestedInputField {
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("NestedInputField path must be non-empty");
            }
            path = List.copyOf(path);
            if (leaf instanceof NestedInputField) {
                throw new IllegalArgumentException("NestedInputField leaf must not be another NestedInputField");
            }
        }

        /** Convenience constructor that defaults {@code leaf} to {@link Direct}. */
        public NestedInputField(String outerArgName, List<String> path) {
            this(outerArgName, path, new Direct());
        }
    }

    /**
     * Read a filter field typed as a list of input objects one element at a time. Each element is a
     * {@code Map} whose {@link #cells()} are the scalar leaves the element binds onto columns of the
     * resolving table; a row matches when it equals any one element on the cells that element
     * carries. When the list is the argument itself the arm is the extraction directly; when the
     * list sits inside an input object it is the leaf of a {@link NestedInputField} whose path is the
     * descent to the list.
     *
     * <p>Two shapes, decided once by the classifier from the cells' {@link Cell#required()} facts:
     * {@link Whole} when every cell is required, so every element is one whole row, and
     * {@link Partial} when at least one cell is optional, so each element compares only the cells it
     * carries. Every consumer forks on the arm rather than re-reading the cells.
     *
     * <p>Only the condition glue reads this arm. Every other exhaustive switch over
     * {@link CallSiteExtraction} treats it as unreachable by construction.
     */
    sealed interface ElementRows extends CallSiteExtraction permits ElementRows.Whole, ElementRows.Partial {

        /** The cells one element contributes, in element-field order. */
        List<Cell> cells();

        /** The cells' columns in cell order: the tuple the predicate compares. */
        default List<ColumnRef> columns() {
            return cells().stream().map(Cell::column).toList();
        }

        /**
         * True when {@code extraction} is an {@link ElementRows}, bare or as a
         * {@link NestedInputField}'s leaf, whose {@link #columns()} differ from {@code columns}.
         * The check the predicates reading element rows run on their own columns.
         */
        static boolean disagrees(CallSiteExtraction extraction, List<ColumnRef> columns) {
            ElementRows rows = of(extraction);
            return rows != null && !rows.columns().equals(columns);
        }

        /** The {@link ElementRows} {@code extraction} carries, bare or as a nested leaf; else null. */
        static ElementRows of(CallSiteExtraction extraction) {
            if (extraction instanceof ElementRows rows) {
                return rows;
            }
            if (extraction instanceof NestedInputField nif && nif.leaf() instanceof ElementRows rows) {
                return rows;
            }
            return null;
        }

        private static List<Cell> requireCells(List<Cell> cells) {
            if (cells == null || cells.isEmpty()) {
                throw new IllegalArgumentException("ElementRows requires at least one cell");
            }
            return List.copyOf(cells);
        }

        /**
         * Every cell required: every element carries every cell, so the list reads as one row per
         * element, {@code List<Row<N><T1, ..., TN>>} built by a per-class helper, or
         * {@code List<T>} for a one-cell element, and the
         * predicate is {@code DSL.row(c1, ..., cN).in(rows)} or {@code c1.in(values)}. Rides
         * {@link BodyParam.RowIn} at two or more cells and {@link BodyParam.In} at one.
         *
         * <p>A {@code null} element contributes a row of typed nulls, which no row matches; it is not
         * dropped, so {@code [null]} is a filter that matches nothing rather than an empty list that
         * filters nothing.
         */
        record Whole(List<Cell> cells) implements ElementRows {
            public Whole {
                cells = requireCells(cells);
                if (cells.stream().anyMatch(c -> !c.required())) {
                    throw new IllegalArgumentException(
                        "ElementRows.Whole requires every cell to be required; an optional cell makes it Partial");
                }
            }
        }

        /**
         * At least one cell optional: an element constrains only the cells it carries, so it reads as
         * the conjunction of those cells' comparisons and the list as the disjunction over its
         * elements. A left-out cell matches every row, including rows whose column is {@code NULL};
         * an element carrying no cell matches every row. A per-class match helper builds the
         * finished {@code Condition}. Rides
         * {@link BodyParam.PartialRowIn} at any arity.
         */
        record Partial(List<Cell> cells) implements ElementRows {
            public Partial {
                cells = requireCells(cells);
                if (cells.stream().allMatch(Cell::required)) {
                    throw new IllegalArgumentException(
                        "ElementRows.Partial requires an optional cell; every cell required makes it Whole");
                }
            }
        }
    }

    /**
     * One cell of an {@link ElementRows} element: the descent {@code path} from the element's own
     * {@code Map} down to a scalar leaf (longer than one segment when the leaf sits inside a
     * non-list nested input), the {@code column} the cell compares against, and the {@code leaf}
     * transform reading the wire value, which is the one a scalar field at that position takes:
     * {@link Direct}, {@link JooqConvert} for an {@code ID}, or {@link EnumValueOf}.
     *
     * <p>{@code required} is true when the cell's own field and every nested input field on the way
     * down to it from the element are non-null, so graphql-java's input coercion guarantees every
     * element carries it; the match helper compares a required cell without a null test.
     */
    record Cell(List<String> path, ColumnRef column, CallSiteExtraction leaf, boolean required) {
        public Cell {
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("ElementRows.Cell path must be non-empty");
            }
            path = List.copyOf(path);
            if (column == null) {
                throw new IllegalArgumentException("ElementRows.Cell column must be non-null");
            }
            if (!(leaf instanceof Direct || leaf instanceof JooqConvert || leaf instanceof EnumValueOf)) {
                throw new IllegalArgumentException(
                    "ElementRows.Cell leaf must be Direct, JooqConvert or EnumValueOf; got " + leaf);
            }
        }
    }

    /**
     * Decode a base64 NodeId at the call-site root into typed key values. Two arms, describing how
     * a {@code null} return from {@link HelperRef.Decode decode<TypeName>} (malformed input or
     * typeId mismatch) surfaces:
     *
     * <ul>
     *   <li>{@link ThrowOnMismatch}: every {@code @nodeId} argument or input-object-field filter
     *       ({@code [ID!] @nodeId(typeName: T)} and the scalar analogue), the implicit reading of a
     *       coordinate named for its target's {@code id}, plus {@code @nodeId} lookup / mutation
     *       keys. A {@code null} return is a client mistake and surfaces as a
     *       {@code GraphitronClientException} (a {@code GraphQLError}), not a silent drop; the decode
     *       helper's message distinguishes a structurally-malformed id from a well-formed wrong-type
     *       id. For filters this gives up the Relay heterogeneous-id-source pattern by design:
     *       one bad element fails the whole field rather than narrowing the set.</li>
     *   <li>{@link PruneOnMismatch}: one branch of a multitable polymorphic root whose participants
     *       resolve <em>different</em> node types for the same {@code @nodeId} argument. A
     *       {@code null} decode means the supplied id belongs to a sibling branch, so this branch
     *       cannot match and its filter renders {@code DSL.falseCondition()}. The client error still
     *       exists, one level up: the fetcher checks the id against every branch's decoder before
     *       stage 1 runs and throws when none of them accepts it, so the failure mode moves from
     *       branch granularity to field granularity rather than disappearing.</li>
     * </ul>
     *
     * <p>Pruning is not the retired silent-drop sibling. That arm existed for the legacy
     * metadata-driven carriers, which could not tell a client mistake from a schema the author never
     * wrote, and it hid the mistake. This arm prunes a branch that structurally cannot match, under
     * a field-level guard that keeps a genuinely bad id an error.
     *
     * <p>The third failure mode (NullOnMismatch) is dispatcher-driven (Query.node, Query.nodes,
     * federated _entities) and does not appear here as a carrier arm; see
     * {@code EntityFetcherDispatchClassGenerator}.
     *
     * <p>Each arm carries the pre-resolved {@link HelperRef.Decode} for the target NodeType, so
     * the call-site emitter reaches the per-Node {@code decode<TypeName>} helper through a typed
     * structural reference rather than reconstructing names from a typeId string.
     */
    sealed interface NodeIdDecodeKeys extends CallSiteExtraction
            permits ThrowOnMismatch, PruneOnMismatch {

        /** The pre-resolved {@code decode<TypeName>} helper reference. */
        HelperRef.Decode decodeMethod();
    }

    /**
     * Throw the generated {@code GraphitronClientException} (a {@code GraphQLError}) on a
     * {@code null} decode return, with a message distinguishing malformed input from a well-formed
     * wrong-type id. Used by every authored {@code @nodeId} filter (argument or
     * input-object-field) and by {@code @nodeId} lookup / mutation keys.
     */
    record ThrowOnMismatch(HelperRef.Decode decodeMethod) implements NodeIdDecodeKeys {}

    /**
     * Render {@code DSL.falseCondition()} instead of a comparison when the decode returns
     * {@code null}: the id decodes for a sibling branch of the same polymorphic field, so this
     * branch structurally cannot match and contributes no rows.
     *
     * <p>Produced only for a {@code @nodeId} argument of a multitable polymorphic root
     * ({@code QueryInterfaceField} / {@code QueryUnionField}) whose participants resolve pairwise
     * distinct node types for that argument, one arm per participant carrying that participant's own
     * decoder. The field carries the same per-participant decoders as its dispatch fact, and the
     * generated fetcher rejects an id no participant decodes before any branch runs, so
     * "no branch matched" stays a client error while "this branch did not match" stays a filter miss.
     *
     * <p>Distinguishing absent from mismatched is the renderer's obligation, not this arm's: the
     * glue guards a nullable scalar on wire presence and reads an empty (non-null) list return from
     * the prune-mode list helper as all-elements-mismatched.
     */
    record PruneOnMismatch(HelperRef.Decode decodeMethod) implements NodeIdDecodeKeys {}

    /**
     * Decode a base64 NodeId into a jOOQ {@code TableRecord} for a {@code @service} input-bean
     * member field typed as a generated {@code *Record}. Sibling to {@link NodeIdDecodeKeys}: same
     * NodeId wire decode at the root, opposite downstream projection. Where {@link NodeIdDecodeKeys}
     * decomposes the decoded key tuple into scalar column values for a predicate / SET body, this
     * arm materialises a {@link org.jooq.TableRecord}: it calls
     * {@code encoderClass.decodeColumns(typeId, nodeId, parseClasses, Tables.<T>.<col1>, ...)}, which answers
     * each key value as a value of its column (or {@code null} when one is not), and loads those
     * values positionally onto the target record's key columns with a single
     * {@code record.fromArray(values, Tables.<T>.<col1>, Tables.<T>.<col2>, ...)} call. The
     * {@code fromArray} call reaches the columns' {@code DataType} / registered
     * {@code Converter}, and the {@code Tables.<T>.<col>} field references must exist on the record
     * (the {@code graphitron-sakila-example} compile tier verifies this).
     *
     * <p>The leaf carries no {@code decode<Type>} method name, only the node type {@code typeName}
     * that name is minted from: {@code encoderClass} reaches {@code decodeColumns}, {@code typeId} is
     * its first argument, {@code keyColumns} names the {@code Tables.<T>.<col>} fields passed to
     * it and to {@code fromArray}, and {@code table} supplies the record class ({@code new <T>Record()}) and
     * the {@code Tables} constants class. {@code keyColumns} is kept separate from
     * {@code table.primaryKeyColumns()} because an {@code @node(keyColumns:)} key may differ from
     * the PK.
     *
     * <p>{@code typeName} is the decode's identity, as it is on {@link PolymorphicCandidate}: it
     * names the {@code @nodeId(typeName:)} target, a node type or a {@code @table} type over a table
     * with catalog node metadata, every other component is a function of that target, and two
     * types can share one table (a rename that keeps the old name exposed is exactly that shape), so the table cannot
     * tell two decodes apart. Collection and naming both key on it.
     *
     * <p>Produced only by {@code InputBeanResolver} when an input-bean field's loaded Java type is
     * assignable to {@code org.jooq.Record} and the SDL field carries {@code @nodeId(typeName:)};
     * consumed only by {@code InputBeanInstantiationEmitter}, which emits a per-target-type
     * {@code decode<TypeName>Record} helper (plus a {@code …RecordList} variant for list-valued
     * members) on the enclosing {@code *Fetchers} class. Any other exhaustive
     * {@link CallSiteExtraction} switch treats this arm as unreachable-by-construction.
     *
     * <p>Both arities (single-column and composite key) and both shapes (scalar and list-valued
     * member) flow through this one leaf: arity is {@code keyColumns.size()} (each column named as a
     * positional field in the one {@code fromArray} call), and list-ness is carried on the enclosing
     * {@link FieldBinding#list()}, not
     * duplicated here. {@code nonNull} reflects the SDL field's nullability ({@code ID!} vs
     * {@code ID}); a type-mismatch decode (the helper returns {@code null}) is always an
     * authored-input error and throws, while a {@code null}/absent wire value follows graphql-java's
     * non-null enforcement at the boundary, so a nullable field yields a {@code null} member.
     */
    record NodeIdDecodeRecord(ClassName encoderClass, String typeName, String typeId,
                              List<ColumnRef> keyColumns, TableRef table,
                              boolean nonNull)
            implements CallSiteExtraction {
        public NodeIdDecodeRecord {
            if (encoderClass == null) {
                throw new IllegalArgumentException("NodeIdDecodeRecord encoderClass must be non-null");
            }
            if (typeName == null || typeName.isEmpty()) {
                throw new IllegalArgumentException("NodeIdDecodeRecord typeName must be non-empty");
            }
            if (typeId == null || typeId.isEmpty()) {
                throw new IllegalArgumentException("NodeIdDecodeRecord typeId must be non-empty");
            }
            if (keyColumns == null || keyColumns.isEmpty()) {
                throw new IllegalArgumentException("NodeIdDecodeRecord keyColumns must be non-empty");
            }
            keyColumns = List.copyOf(keyColumns);
            if (table == null) {
                throw new IllegalArgumentException("NodeIdDecodeRecord table must be non-null");
            }
        }
    }

    /**
     * The Java type a polymorphic {@code @nodeId} slot declares, admitted: every candidate's
     * generated record is one of these, which is what the emitted helper returns.
     *
     * <p>A wrapper rather than a bare {@link TypeName} because it is an <em>acceptance</em> and not
     * a name. It is minted where the assignability check passes,
     * {@code BuildContext.admitPolymorphicSlotType}, and an emit site holding one therefore knows
     * the records it is about to return can land in this type; a {@code TypeName} field would be a
     * value any caller could supply from anywhere.
     *
     * <p>Parameterized where the admitted class is generic, each type argument an unbounded
     * wildcard: {@code org.jooq.UpdatableRecord<?>} and never the raw {@code UpdatableRecord}. The
     * raw form would compile but emit a raw type into the consumer's own sources, and the wildcard
     * form is also what the author wrote at the slot the value lands in.
     */
    record AdmittedSlotType(TypeName typeName) {
        public AdmittedSlotType {
            if (typeName == null) {
                throw new IllegalArgumentException("AdmittedSlotType typeName must be non-null");
            }
        }
    }

    /**
     * One candidate of a polymorphic {@code @nodeId} decode: exactly what {@link NodeIdDecodeRecord}
     * carries for one node type, plus the GraphQL type name the dispatch's failure message quotes
     * and the per-candidate helper is named from.
     */
    record PolymorphicCandidate(String typeName, String typeId, List<ColumnRef> keyColumns,
                                TableRef table) {
        public PolymorphicCandidate {
            if (typeName == null || typeName.isEmpty()) {
                throw new IllegalArgumentException("PolymorphicCandidate typeName must be non-empty");
            }
            if (typeId == null || typeId.isEmpty()) {
                throw new IllegalArgumentException("PolymorphicCandidate typeId must be non-empty");
            }
            if (keyColumns == null || keyColumns.isEmpty()) {
                throw new IllegalArgumentException(
                    "PolymorphicCandidate keyColumns must be non-empty");
            }
            keyColumns = List.copyOf(keyColumns);
            if (table == null) {
                throw new IllegalArgumentException("PolymorphicCandidate table must be non-null");
            }
        }
    }

    /**
     * Decode a wire node id into whichever candidate's generated record it belongs to, and hand that
     * record to a slot typed as something every candidate's record is. Produced where
     * {@code @nodeId(typeName:)} names a multitable interface or a union at a {@code @service} slot;
     * consumed by the three emit sites that host a {@code @service} slot's decode, each rendering one
     * {@code decode<Container>Record(Object wire)} helper on the enclosing {@code *Fetchers} class
     * (plus a {@code …RecordList} variant for a list-shaped slot).
     *
     * <p>A sibling of {@link NodeIdDecodeRecord} rather than a widening of it. Every consumer of that
     * leaf reads a single table off it and emits a single helper call, so a widened record would hand
     * each of them a list they must not receive, and the exhaustive switches over this interface would
     * lose the compiler's help telling the two apart.
     *
     * <p>{@code candidates} is a list and not a map keyed on the typeId each entry already holds: the
     * order is the emission order, and a list states it without spelling one value twice. At least
     * one of them, which the compact constructor holds; the count is not otherwise constrained. A
     * one-candidate leaf is a container whose one implementation is admissible, which is legal SDL
     * and dispatches through the same chain with one arm: the container's member count is the
     * schema's own and grows, so a leaf refusing it would make the slot's spelling depend on the
     * count of the day. {@link NodeIdDecodeRecord} remains the leaf for a slot naming <em>one node
     * type</em>, which is a different thing from a container that currently has one member.
     *
     * <p>{@code slotType} is the {@link AdmittedSlotType} the assignability check minted, which is
     * the helper's return type; {@code nonNull} reflects the SDL slot's nullability on
     * {@link NodeIdDecodeRecord}'s own terms.
     */
    record NodeIdDecodePolymorphicRecord(ClassName encoderClass, String containerName,
                                         List<PolymorphicCandidate> candidates,
                                         AdmittedSlotType slotType, boolean nonNull)
            implements CallSiteExtraction {
        public NodeIdDecodePolymorphicRecord {
            if (encoderClass == null) {
                throw new IllegalArgumentException(
                    "NodeIdDecodePolymorphicRecord encoderClass must be non-null");
            }
            if (containerName == null || containerName.isEmpty()) {
                throw new IllegalArgumentException(
                    "NodeIdDecodePolymorphicRecord containerName must be non-empty");
            }
            candidates = List.copyOf(candidates == null ? List.<PolymorphicCandidate>of() : candidates);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException(
                    "NodeIdDecodePolymorphicRecord needs at least one candidate; a container with no"
                    + " admissible member has nothing to decode an id into, which the walk refuses");
            }
            if (slotType == null) {
                throw new IllegalArgumentException(
                    "NodeIdDecodePolymorphicRecord slotType must be non-null");
            }
        }
    }

    /**
     * Instantiate a consumer-authored Java bean class from a GraphQL input-object Map. Used by
     * {@code @service} methods whose Java parameter (or list element type) is a class mirroring
     * an SDL {@code input} type living in the service package.
     *
     * <p>The call site emits {@code create<TypeName>(env.getArgument("name"))} for a singular bean
     * parameter and {@code create<TypeName>s(env.getArgument("name"))} for a {@code List<Bean>}
     * parameter. The emitted helper(s) live on the enclosing {@code *Fetchers} class; helpers are
     * deduplicated per bean class. The helper itself walks the Map field-by-field, populating a
     * fresh instance, either via the record canonical constructor (when {@code target} is
     * {@link Target#RECORD}) or no-arg constructor + JavaBean setters
     * (when {@code target} is {@link Target#JAVA_BEAN}).
     *
     * <p>{@code fields} is the per-SDL-field binding, in SDL declaration order. For record targets
     * this order is also the canonical-constructor argument order: the bindings must match the
     * record's component order. For JavaBean targets the order is irrelevant (each binding is
     * applied via its named setter independently).
     *
     * <p>Cycle-prevention invariant: the helper references only JDK types and service-layer
     * types. {@code beanClass} is a consumer-authored type, never a graphitron-emitted record.
     */
    record InputBean(ClassName beanClass, Target target, List<FieldBinding> fields)
            implements CallSiteExtraction {
        public InputBean {
            if (beanClass == null) {
                throw new IllegalArgumentException("InputBean beanClass must be non-null");
            }
            if (target == null) {
                throw new IllegalArgumentException("InputBean target must be non-null");
            }
            if (fields == null || fields.isEmpty()) {
                throw new IllegalArgumentException("InputBean fields must be non-empty");
            }
            fields = List.copyOf(fields);
        }

        /** Constructor shape the helper uses to instantiate the bean. */
        public enum Target {
            /** Java record: populate positionally via the canonical constructor. */
            RECORD,
            /** Plain class with a public no-arg constructor and JavaBean setters. */
            JAVA_BEAN
        }
    }

    /**
     * One field on an {@link InputBean}. {@code accessPath} is the ordered chain of GraphQL input
     * field names from the bean's own wire {@code Map} down to the leaf: the last element is the
     * {@code Map} key the helper reads the value from ({@link FieldBinding#mapKey()}), and any
     * earlier elements are the enclosing <em>grouping</em> input fields the leaf was flattened out
     * of. A field declared directly on the bean's input type is a one-element path. The path is
     * bean-local, relative to the {@code Map} the bean's own helper receives.
     *
     * <p>{@code javaFieldName} is the corresponding
     * member name on the consumer-authored bean: for a record target this is the canonical
     * component name; for a JavaBean target this is the property name (the setter is named
     * {@code set<Capitalised javaFieldName>}).
     *
     * <p>{@code leaf} is the per-field transform. {@link Direct} populates from the raw Map value
     * (typed via Java cast). {@link EnumValueOf} routes through the corresponding enum's
     * {@code valueOf}. {@link InputBean} recurses into a nested bean instantiation. The validator
     * rejects other arms on the leaf at classify time.
     *
     * <p>{@code list} indicates whether the field is list-shaped (Java type {@code List<X>} on the
     * bean). When {@code true} with a non-{@link Direct} leaf, the helper streams the inner list
     * through the leaf transform.
     *
     * <p>{@code javaElementTypeName} is the fully qualified name of the leaf scalar/enum Java type
     * (or the bean class name for nested {@link InputBean} leaves), used to emit casts. For lists
     * it is the element type, not the wrapping {@code List}. Invariant: always a real class name,
     * never a Java primitive literal: primitives are boxed to their wrapper FQN at the resolver
     * boundary so that the {@link com.palantir.javapoet.ClassName#bestGuess(String)} consumers
     * in {@code InputBeanInstantiationEmitter} can rely on the string being a class name.
     *
     * <p>{@code definition} is the schema coordinate ({@code Input.field}) of the SDL input field
     * the binding reads, which for a hoisted leaf is the field on the grouping input that declares
     * it. Stamped where the resolver holds the field definition; the access path is a use-site
     * path and does not name the declaring type.
     */
    record FieldBinding(List<String> accessPath, String javaFieldName,
                        CallSiteExtraction leaf, boolean list,
                        String javaElementTypeName, String definition) {
        public FieldBinding {
            if (definition == null || definition.isEmpty()) {
                throw new IllegalArgumentException("FieldBinding definition must be non-empty");
            }
            if (accessPath == null || accessPath.isEmpty()) {
                throw new IllegalArgumentException("FieldBinding accessPath must be non-empty");
            }
            for (var element : accessPath) {
                if (element == null || element.isEmpty()) {
                    throw new IllegalArgumentException("FieldBinding accessPath elements must be non-empty");
                }
            }
            accessPath = List.copyOf(accessPath);
            if (javaFieldName == null || javaFieldName.isEmpty()) {
                throw new IllegalArgumentException("FieldBinding javaFieldName must be non-empty");
            }
            if (leaf == null) {
                throw new IllegalArgumentException("FieldBinding leaf must be non-null");
            }
            if (javaElementTypeName == null || javaElementTypeName.isEmpty()) {
                throw new IllegalArgumentException("FieldBinding javaElementTypeName must be non-empty");
            }
        }

        /**
         * The wire {@code Map} key for this binding's value: the last {@link #accessPath()} element.
         * Deliberately not named {@code leaf()}, which on this carrier already names the
         * {@link #leaf()} extraction component.
         */
        public String mapKey() {
            return accessPath.get(accessPath.size() - 1);
        }
    }
}
