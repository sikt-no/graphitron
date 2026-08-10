package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.LookupResolution;
import no.sikt.graphitron.rewrite.model.RoutineResolution;
import no.sikt.graphitron.rewrite.model.QueryField.QueryTableField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.rewrite.TestFixtures;

@UnitTier
class RootLookupValidationTest {

    private static final ColumnRef FILM_ID_COL = new ColumnRef("film_id", "FILM_ID", "java.lang.Integer");
    private static final ColumnRef TITLE_COL = new ColumnRef("title", "TITLE", "java.lang.String");
    private static final TableRef FILM_TABLE = TestFixtures.tableRef("film", "FILM", "Film", List.of());
    // A minimal one-arg mapping: these cases pin wrapper/ordering verdicts, not the key
    // payload, and ColumnMapping rejects an empty arg list (the vacuous mapping is
    // LookupResolution.None, which never reaches a lookup leaf).
    private static final LookupMapping SCALAR_LOOKUP = new LookupMapping.ColumnMapping(
        List.of(new LookupMapping.ColumnMapping.LookupArg.ScalarLookupArg(
            "film_id", new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"),
            new CallSiteExtraction.Direct(), false)),
        FILM_TABLE);
    private static final LookupResolution KEYED_LOOKUP = new LookupResolution.Keyed(SCALAR_LOOKUP);
    // The same mapping with a list-typed key arg. Key list-ness is the whole cardinality fact,
    // so the two mappings are the axis the cube below varies against the return wrapper.
    private static final LookupMapping LIST_LOOKUP = new LookupMapping.ColumnMapping(
        List.of(new LookupMapping.ColumnMapping.LookupArg.ScalarLookupArg(
            "film_id", FILM_ID_COL, new CallSiteExtraction.Direct(), true)),
        FILM_TABLE);
    private static final LookupResolution LIST_KEYED_LOOKUP = new LookupResolution.Keyed(LIST_LOOKUP);
    private static final OrderBySpec.Fixed PK_ORDER = new OrderBySpec.Fixed(
        List.of(new OrderBySpec.ColumnOrderEntry(FILM_ID_COL, null, OrderBySpec.SortDirection.ASC)), true);
    private static final String CARDINALITY_MISMATCH =
        "Field 'Query.filmById': result type does not match input cardinality";
    private static final String NONNULL_ITEM =
        "Field 'Query.filmById': a lookup field's list elements must be nullable, since an "
            + "unmatched key yields null at its output position; declare the element type without '!'";

    /** One cube cell: a lookup field varying key list-ness, return list-ness and filter list-ness. */
    private static QueryTableField cell(LookupResolution keyed, boolean listReturn, List<WhereFilter> filters) {
        return new QueryTableField("Query", "filmById", null,
            new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE,
                listReturn ? new FieldWrapper.List(true, true) : new FieldWrapper.Single(true)),
            filters, listReturn ? PK_ORDER : new OrderBySpec.None(), null, keyed,
            RoutineResolution.None.INSTANCE);
    }

    /**
     * A list-key lookup varying the return wrapper's two nullability slots independently, which
     * the cardinality cube's {@link #cell} cannot do (it fixes both to nullable). Argument order
     * mirrors {@link FieldWrapper.List}: outer list first, element second.
     */
    private static QueryTableField listReturn(boolean listNullable, boolean itemNullable,
            List<WhereFilter> filters) {
        return new QueryTableField("Query", "filmById", null,
            new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE,
                new FieldWrapper.List(listNullable, itemNullable)),
            filters, PK_ORDER, null, LIST_KEYED_LOOKUP, RoutineResolution.None.INSTANCE);
    }

    /**
     * A generated filter over a column that is not the lookup key, which is the only shape that
     * reaches a lookup coordinate: the key argument is excluded from the generated filter
     * upstream ({@code FieldBuilder}'s {@code !ca.isLookupKey()} branch), so a filter here can
     * only ever be a non-key sibling argument.
     */
    private static GeneratedConditionFilter columnFilter(String name, boolean nonNull, boolean list) {
        BodyParam bodyParam = list
            ? new BodyParam.In(name, TITLE_COL, "java.lang.String", nonNull, new CallSiteExtraction.Direct())
            : new BodyParam.Eq(name, TITLE_COL, "java.lang.String", nonNull, new CallSiteExtraction.Direct());
        var callParam = new CallParam(name, new CallSiteExtraction.Direct(), list, TITLE_COL.columnClass());
        return new GeneratedConditionFilter(FILM_TABLE,
            List.of(callParam), List.of(bodyParam));
    }

    private static QueryTableField singleReturn(List<WhereFilter> filters, OrderBySpec orderBy) {
        return new QueryTableField("Query", "filmById", null,
            new ReturnTypeRef.TableBoundReturnType("Film", TestFixtures.tableRef("film", "FILM", "Film", List.of()), new FieldWrapper.Single(true)),
            filters, orderBy, null, KEYED_LOOKUP, RoutineResolution.None.INSTANCE);
    }

    enum Case implements ValidatorCase {

        // The cardinality cube, exhaustive over key list-ness x return list-ness x non-key-filter
        // list-ness. A generated column filter beside the lookup keys is legal at every cell: the
        // keys ride the VALUES join and the filter composes in the WHERE beside it. The verdict is
        // the key axis against the return wrapper alone, so the filter column below never changes
        // it — which is exactly what the four LIST_FILTER cells pin, since the cardinality read
        // used to OR the filter's list-ness in and made two of them disagree with their siblings.

        SCALAR_KEY_SINGLE_RETURN_NO_FILTER("scalar key, single return, no filter — valid",
            cell(KEYED_LOOKUP, false, List.of()),
            List.of()),

        SCALAR_KEY_SINGLE_RETURN_SCALAR_FILTER("scalar key, single return, scalar filter — valid",
            cell(KEYED_LOOKUP, false, List.of(columnFilter("title", false, false))),
            List.of()),

        SCALAR_KEY_SINGLE_RETURN_LIST_FILTER("scalar key, single return, list filter — valid",
            cell(KEYED_LOOKUP, false, List.of(columnFilter("title", false, true))),
            List.of()),

        SCALAR_KEY_LIST_RETURN_NO_FILTER("scalar key, list return, no filter — cardinality mismatch",
            cell(KEYED_LOOKUP, true, List.of()),
            List.of(CARDINALITY_MISMATCH)),

        SCALAR_KEY_LIST_RETURN_SCALAR_FILTER("scalar key, list return, scalar filter — cardinality mismatch",
            cell(KEYED_LOOKUP, true, List.of(columnFilter("title", false, false))),
            List.of(CARDINALITY_MISMATCH)),

        SCALAR_KEY_LIST_RETURN_LIST_FILTER("scalar key, list return, list filter — cardinality mismatch",
            cell(KEYED_LOOKUP, true, List.of(columnFilter("title", false, true))),
            List.of(CARDINALITY_MISMATCH)),

        LIST_KEY_SINGLE_RETURN_NO_FILTER("list key, single return, no filter — cardinality mismatch",
            cell(LIST_KEYED_LOOKUP, false, List.of()),
            List.of(CARDINALITY_MISMATCH)),

        LIST_KEY_SINGLE_RETURN_SCALAR_FILTER("list key, single return, scalar filter — cardinality mismatch",
            cell(LIST_KEYED_LOOKUP, false, List.of(columnFilter("title", false, false))),
            List.of(CARDINALITY_MISMATCH)),

        LIST_KEY_SINGLE_RETURN_LIST_FILTER("list key, single return, list filter — cardinality mismatch",
            cell(LIST_KEYED_LOOKUP, false, List.of(columnFilter("title", false, true))),
            List.of(CARDINALITY_MISMATCH)),

        LIST_KEY_LIST_RETURN_NO_FILTER("list key, list return, no filter — valid",
            cell(LIST_KEYED_LOOKUP, true, List.of()),
            List.of()),

        LIST_KEY_LIST_RETURN_SCALAR_FILTER("list key, list return, scalar filter — valid",
            cell(LIST_KEYED_LOOKUP, true, List.of(columnFilter("title", false, false))),
            List.of()),

        LIST_KEY_LIST_RETURN_LIST_FILTER("list key, list return, list filter — valid",
            cell(LIST_KEYED_LOOKUP, true, List.of(columnFilter("title", false, true))),
            List.of()),

        // The item-nullability axis, orthogonal to the cardinality cube above (whose cells all
        // hold itemNullable true). A list lookup answers one slot per key and a missed key holds
        // null in its slot, so non-null elements make the contract unrepresentable: GraphQL
        // propagates the null out of the list and one miss discards every hit.
        // `[Film!]!` — the shape every lookup coordinate in the example schema used to declare.
        LIST_KEY_LIST_RETURN_NONNULL_ITEM("list key, `[Film!]!` — unrepresentable slot",
            listReturn(false, false, List.of()),
            List.of(NONNULL_ITEM)),

        // `[Film!]` — a nullable list does not help; it is the *element* that has to carry null.
        LIST_KEY_NULLABLE_LIST_NONNULL_ITEM("list key, `[Film!]` — still unrepresentable",
            listReturn(true, false, List.of()),
            List.of(NONNULL_ITEM)),

        // `[Film]` — the remaining accepted cell beside the cube's `[Film]!`.
        LIST_KEY_NULLABLE_LIST_NULLABLE_ITEM("list key, `[Film]` — valid",
            listReturn(true, true, List.of()),
            List.of()),

        // The rejection is about the element, not the filter: a non-key filter beside the keys
        // neither causes nor excuses it.
        LIST_KEY_LIST_RETURN_NONNULL_ITEM_WITH_FILTER("list key, `[Film!]!`, non-key filter — still one error",
            listReturn(false, false, List.of(columnFilter("title", false, false))),
            List.of(NONNULL_ITEM)),

        // Both wrapper rules fire independently: the cardinality mismatch does not mask the
        // element rule, nor the reverse.
        SCALAR_KEY_LIST_RETURN_NONNULL_ITEM("scalar key, `[Film!]!` return — cardinality AND element errors",
            new QueryTableField("Query", "filmById", null,
                new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, new FieldWrapper.List(false, false)),
                List.of(), PK_ORDER, null, KEYED_LOOKUP, RoutineResolution.None.INSTANCE),
            List.of(CARDINALITY_MISMATCH, NONNULL_ITEM)),

        // The single arm has one slot by construction and fetchOne already answers null in it,
        // so nothing about element nullability applies; a non-null single return stays valid.
        SCALAR_KEY_NONNULL_SINGLE_RETURN("scalar key, non-null single return — element rule does not reach the single arm",
            new QueryTableField("Query", "filmById", null,
                new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, new FieldWrapper.Single(false)),
                List.of(), new OrderBySpec.None(), null, KEYED_LOOKUP, RoutineResolution.None.INSTANCE),
            List.of()),

        VALID_WITH_TABLE_INPUT_TYPE_ARG("table-bound input type arg — skipped, empty filters, valid with single return",
            singleReturn(List.of(), new OrderBySpec.None()),
            List.of()),

        CONNECTION_RETURN("connection return — never valid on lookup",
            new QueryTableField("Query", "filmById", null,
                new ReturnTypeRef.TableBoundReturnType("Film", TestFixtures.tableRef("film", "FILM", "Film", List.of()), new FieldWrapper.Connection(true, 100)),
                List.of(), new OrderBySpec.None(), null, KEYED_LOOKUP, RoutineResolution.None.INSTANCE),
            List.of("Field 'Query.filmById': lookup fields must not return a connection")),

        ORDERBY_ARG("@orderBy on a lookup field — not valid on lookup",
            singleReturn(List.of(), new OrderBySpec.Argument("order", "FilmOrder", false, false, "sortField", "direction", List.of(), new OrderBySpec.None())),
            List.of("Field 'Query.filmById': @orderBy is not valid on a lookup field")),

        CONNECTION_AND_ORDERBY("connection return AND @orderBy — two independent errors",
            new QueryTableField("Query", "filmById", null,
                new ReturnTypeRef.TableBoundReturnType("Film", TestFixtures.tableRef("film", "FILM", "Film", List.of()), new FieldWrapper.Connection(true, 100)),
                List.of(), new OrderBySpec.Argument("order", "FilmOrder", false, false, "sortField", "direction", List.of(), new OrderBySpec.None()), null, KEYED_LOOKUP, RoutineResolution.None.INSTANCE),
            List.of(
                "Field 'Query.filmById': lookup fields must not return a connection",
                "Field 'Query.filmById': @orderBy is not valid on a lookup field"));

        private final String description;
        private final GraphitronField field;
        private final List<String> errors;

        Case(String description, GraphitronField field, List<String> errors) {
            this.description = description;
            this.field = field;
            this.errors = errors;
        }

        @Override public GraphitronField field() { return field; }
        @Override public List<String> errors() { return errors; }
        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void lookupQueryFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
