package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.InterfaceOnlyFetchDBClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Query inputs - Equality, list and null checks for fields")
public class InputTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/inputs/required";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_TABLE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema), new InterfaceOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("No input")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("String field")
    void string() {
        assertGeneratedContentMatches("string"); // Check the placement, but just this once.
    }

    @Test
    @DisplayName("Scalar field found in extended scalars")
    void scalar() {
        assertGeneratedContentContains("scalar", ", LocalDate createdDate,", "customer.CREATE_DATE.eq(createdDate)");
    }

    @Test
    @DisplayName("ID field")
    void id() {
        assertGeneratedContentContains("id", ", String id,", "customer.hasId(id)");
    }

    @Test
    @DisplayName("ID field that is not the primary ID")
    void idOther() {
        assertGeneratedContentContains("idOther", "customer.hasAddressId(id)");
    }

    @Test
    @DisplayName("Boolean field")
    void booleanCase() {
        assertGeneratedContentContains("boolean", ", Boolean bool,", "customer.ACTIVE.eq(bool)");
    }

    @Test
    @DisplayName("Integer field")
    void integer() {
        assertGeneratedContentContains("integer", ", Integer length,", "film.LENGTH.eq(length)");
    }

    @Test
    @DisplayName("Field with @field directive")
    void fieldOverride() {
        assertGeneratedContentContains("fieldOverride", ", String name,", "customer.FIRST_NAME.eq(name)");
    }

    @Test
    @DisplayName("Two string fields")
    void twoFields() {
        assertGeneratedContentContains(
                "twoFields",
                ", String firstName, String lastName,",
                "customer.FIRST_NAME.eq(firstName)",
                "customer.LAST_NAME.eq(lastName)"
        );
    }

    @Test
    @DisplayName("Listed field")
    void list() {
        assertGeneratedContentContains(
                "list",
                ", List<String> email,",
                "email.size() > 0 ? _a_customer.EMAIL.in(email) : DSL.noCondition()"
        );
    }

    @Test  // Special case methods for IDs.
    @DisplayName("ID list field")
    void idList() {
        assertGeneratedContentContains("idList", ", List<String> id,", "customer.hasIds(id.stream().collect(Collectors.toSet()))");
    }

    @Test
    @DisplayName("ID list field that is not the primary ID")
    void idOtherList() {
        assertGeneratedContentContains("idOtherList", "customer.hasAddressIds(id.stream().collect(Collectors.toSet()))");
    }

    @Test
    @DisplayName("Input type field")
    void input() {
        assertGeneratedContentContains(
                "input",
                Set.of(DUMMY_INPUT),
                ", DummyInput in,",
                "in.getId() != null ? _a_customer.hasId(in.getId()) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Nested input field")
    void nestedInput() {
        assertGeneratedContentContains(
                "nestedInput",
                Set.of(DUMMY_INPUT),
                ", Wrapper in,",
                "in.getIn().getId() != null ? _a_customer.hasId(in.getIn().getId()) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Nested and then listed input field with two inner fields")
    void nestedListedInputTwoFields() {
        assertGeneratedContentContains(
                "nestedListedInputTwoFields",
                "customer.FIRST_NAME, _a_customer.LAST_NAME",
                "DSL.val(in.getIn().get(_iv_it).getFirst()), DSL.val(in.getIn().get(_iv_it).getLast())"
        );
    }

    @Test
    @DisplayName("Three-level input type containing two other input types on the same level")
    void multiLevelInput() {
        assertGeneratedContentContains(
                "multiLevelInput", Set.of(STAFF, NAME_INPUT),
                ".where(_a_staff.FIRST_NAME.eq(staff.getInfo().getName().getFirstname()))" +
                        ".and(_a_staff.LAST_NAME.eq(staff.getInfo().getName().getLastname()))" +
                        ".and(staff.getInfo().getJobEmail().getEmail() != null ? _a_staff.EMAIL.eq(staff.getInfo().getJobEmail().getEmail()) : DSL.noCondition())" +
                        ".and(_a_staff.ACTIVE.eq(staff.getActive()))" +
                        ".orderBy"
        );
    }

    @Test
    @DisplayName("On field returning single table interface")
    void onSingleTableInterface() {
        assertGeneratedContentContains("onSingleTableInterface",
                ", AddressInput filter",
                ".and(_a_address.POSTAL_CODE.eq(filter.getPostalCode()))");
    }

    @Test
    @DisplayName("SplitQuery field")
    void onSplitQueryField() {
        assertGeneratedContentContains("onSplitQueryField",
                ".from(_a_address_223244161_customer).where(_a_address_223244161_customer.EMAIL.eq(email))",
                ".from(_a_address).where(DSL.row(_a_address.ADDRESS_ID).in(_rk_address)).fetch" // Make sure conditon is not applied on outer query
        );
    }

    @Test
    @DisplayName("Container type with input field parameter") //TODO delete? Should be covered elsewhere. Failed due to checking if ANY field on the root Query had input table arguments.
    void filmWrappedWithInputFieldAndTableField() {
        assertGeneratedContentContains(
                "containerSimple",
                """
                            public static FilmContainer filmWrappedForQuery(DSLContext _iv_ctx,
                                    String filmId, SelectionSet _iv_select) {
                                var _a_film = FILM.as("film_2185543202");
                                return _iv_ctx
                                        .select(filmWrappedForQuery_filmContainer(filmId))
                                        .fetchOne(_iv_it -> _iv_it.into(FilmContainer.class));
                            }""",
                            """
                            private static SelectField<FilmContainer> filmWrappedForQuery_filmContainer(String filmId) {
                                var _a_film = FILM.as("film_2185543202");
                                return DSL.row(
                                        DSL.field(
                                                DSL.select(filmWrappedForQuery_filmContainer_films(_a_film))
                                                .from(_a_film)
                                                .where(filmId != null ? _a_film.FILM_ID.eq(filmId) : DSL.noCondition())
                                        )
                                ).mapping(Functions.nullOnAllNull(FilmContainer::new));
                            }"""
                           ,"""
                            private static SelectField<Film> filmWrappedForQuery_filmContainer_films(
                                    no.sikt.graphitron.jooq.generated.testdata.public_.tables.Film _a_film) {
                                return DSL.row(_a_film.getId()).mapping(Functions.nullOnAllNull(Film::new));
                            }"""
        );
    }

    @Test
    @DisplayName("Container type with input table parameter")
    void filmWrappedWithInputTableAndTableField() {
        assertGeneratedContentContains(
                "container",
                """
                        public class QueryDBQueries {
                            public static FilmContainer filmWrappedWithInputTableAndTableFieldForQuery(DSLContext _iv_ctx,
                                    FilmRecord inputRecord, SelectionSet _iv_select) {
                                var _a_film = FILM.as("film_2185543202");
                                var _a_film_2185543202_film = _a_film.film().as("film_3535906766");
                                var _a_film_3535906766_filmlanguageidfkey = _a_film_2185543202_film.filmLanguageIdFkey().as("language_716544853");
                                return _iv_ctx
                                        .select(filmWrappedWithInputTableAndTableFieldForQuery_filmContainer(inputRecord))
                                        .from(_a_film)
                                        .where(_a_film.DESCRIPTION.eq(inputRecord.getDescription()))
                                        .fetchOne(_iv_it -> _iv_it.into(FilmContainer.class));
                            }
                            private static SelectField<FilmContainer> filmWrappedWithInputTableAndTableFieldForQuery_filmContainer(
                                    FilmRecord inputRecord) {
                                var _a_film = FILM.as("film_2185543202");
                                var _a_film_2185543202_film = _a_film.film().as("film_3535906766");
                                var _a_film_3535906766_filmlanguageidfkey = _a_film_2185543202_film.filmLanguageIdFkey().as("language_716544853");
                                return DSL.row(
                                        DSL.row(
                                                _a_film_2185543202_film.getId(),
                                                DSL.field(
                                                        DSL.select(filmWrappedWithInputTableAndTableFieldForQuery_filmContainer_language(_a_film_3535906766_filmlanguageidfkey))
                                                        .from(_a_film_3535906766_filmlanguageidfkey)
                                                )
                                        ).mapping(Functions.nullOnAllNull(Film::new))
                                ).mapping(Functions.nullOnAllNull(FilmContainer::new));
                            }

                            private static SelectField<Language> filmWrappedWithInputTableAndTableFieldForQuery_filmContainer_language(
                                    no.sikt.graphitron.jooq.generated.testdata.public_.tables.Language _a_language) {
                                return DSL.row(_a_language.NAME).mapping(Functions.nullOnAllNull(Language::new));
                            }
                        }"""
        );
    }
}
