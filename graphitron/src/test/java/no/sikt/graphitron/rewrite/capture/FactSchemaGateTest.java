package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.table;

/**
 * The fact schema's gate family: the invariants the DDL itself cannot state, each as its own
 * named query against a bootstrapped store.
 *
 * <p>These are siblings of the comment-coverage gate rather than detections. Every one of them
 * ranges over something capture controls, so a failure here is a capture bug or a DDL defect,
 * never an author error. The most-cited counterexample is deliberately absent: a repeated
 * application of a non-repeatable directive is author-reachable under registry capture, so it is
 * a detection and has no business failing a build.
 */
@UnitTier
class FactSchemaGateTest {

    /**
     * A slice broad enough for the structural gates: extended types, both field families, a
     * repeatable federation application with its verbatim twin, and applications at all five
     * element sites.
     */
    private static final String FIXTURE = """
        directive @audit(note: String) repeatable on OBJECT | FIELD_DEFINITION | ARGUMENT_DEFINITION | ENUM_VALUE
        directive @key(fields: String!, resolvable: Boolean) repeatable on OBJECT

        type Query {
          films(first: Int = 10, title: String @audit(note: "filter")): [Film!]!
          node: Node
        }

        interface Node { id: ID! }

        type Film implements Node @table(name: "film") @audit(note: "one") @audit(note: "two")
                                  @key(fields: "filmId") @key(fields: "title year") {
          id: ID!
          filmId: ID! @field(name: "film_id")
          title: String @audit
          year: Int
        }

        extend type Film {
          rating: Rating
        }

        enum Rating {
          G @audit(note: "general")
          PG @field(name: "pg")
        }

        input FilmFilter {
          title: String = "any"
          year: Int
        }

        union Searchable = Film
        """;

    @Test
    @DisplayName("every table and every column carries a COMMENT ON")
    void commentCoverageIsTotal() {
        try (var store = GraphitronModelStore.open()) {
            var tables = store.dsl()
                .select(field(name("TABLE_NAME"), String.class))
                .from(table(name("INFORMATION_SCHEMA", "TABLES")))
                .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
                .and(field(name("REMARKS"), String.class).isNull())
                .fetch(0, String.class);
            assertThat(tables).as("uncommented relations").isEmpty();

            var columns = store.dsl()
                .select(field(name("TABLE_NAME"), String.class)
                    .concat(".").concat(field(name("COLUMN_NAME"), String.class)))
                .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
                .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
                .and(field(name("REMARKS"), String.class).isNull())
                .fetch(0, String.class);
            assertThat(columns).as("uncommented columns").isEmpty();
        }
    }

    @Test
    @DisplayName("merge ordinals are dense from zero within each type")
    void mergeOrdinalsAreDense(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var gaps = store.dsl()
                .select(GRAPHQL_TYPE_DECLARATION.TYPE_NAME)
                .from(GRAPHQL_TYPE_DECLARATION)
                .groupBy(GRAPHQL_TYPE_DECLARATION.TYPE_NAME)
                .having(max(GRAPHQL_TYPE_DECLARATION.MERGE_ORDINAL)
                    .ne(count().minus(1)))
                .fetch(0, String.class);
            assertThat(gaps).as("types whose declaration sites do not number densely from 0").isEmpty();
        }
    }

    @Test
    @DisplayName("application ordinals are dense from zero within each coordinate")
    void applicationOrdinalsAreDense(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertThat(store.dsl()
                .select(GRAPHQL_TYPE_DIRECTIVE.TYPE_NAME, GRAPHQL_TYPE_DIRECTIVE.DIRECTIVE_NAME)
                .from(GRAPHQL_TYPE_DIRECTIVE)
                .groupBy(GRAPHQL_TYPE_DIRECTIVE.TYPE_NAME, GRAPHQL_TYPE_DIRECTIVE.DIRECTIVE_NAME)
                .having(max(GRAPHQL_TYPE_DIRECTIVE.ORDINAL).ne(count().minus(1)))
                .fetch()).as("type-level application ordinals").isEmpty();

            assertThat(store.dsl()
                .select(GRAPHQL_FIELD_DIRECTIVE.TYPE_NAME, GRAPHQL_FIELD_DIRECTIVE.FIELD_NAME,
                    GRAPHQL_FIELD_DIRECTIVE.DIRECTIVE_NAME)
                .from(GRAPHQL_FIELD_DIRECTIVE)
                .groupBy(GRAPHQL_FIELD_DIRECTIVE.TYPE_NAME, GRAPHQL_FIELD_DIRECTIVE.FIELD_NAME,
                    GRAPHQL_FIELD_DIRECTIVE.DIRECTIVE_NAME)
                .having(max(GRAPHQL_FIELD_DIRECTIVE.ORDINAL).ne(count().minus(1)))
                .fetch()).as("field-level application ordinals").isEmpty();
        }
    }

    @Test
    @DisplayName("the wrapping decode agrees with the captured type expression")
    void wrappingDecodeAgreesWithTypeSdl(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            // The correspondences SQL can express: an outermost '!' is non_null, a leading '['
            // is is_list, and the named type is a substring of the expression. Deeper interior
            // structure is out of a LIKE's reach and stays the decode's own business.
            var disagreements = store.dsl()
                .select(GRAPHQL_FIELD.TYPE_NAME, GRAPHQL_FIELD.FIELD_NAME, GRAPHQL_FIELD.TYPE_SDL)
                .from(GRAPHQL_FIELD)
                .where(GRAPHQL_FIELD.NON_NULL.ne(GRAPHQL_FIELD.TYPE_SDL.like("%!"))
                    .or(GRAPHQL_FIELD.IS_LIST.ne(GRAPHQL_FIELD.TYPE_SDL.like("[%")))
                    .or(GRAPHQL_FIELD.TYPE_SDL.contains(GRAPHQL_FIELD.NAMED_TYPE).isFalse()))
                .fetch();
            assertThat(disagreements).as("fields whose decode contradicts type_sdl").isEmpty();
        }
    }

    @Test
    @DisplayName("default values appear only under INPUT_OBJECT parents")
    void defaultValuesOnlyOnInputFields(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var offenders = store.dsl()
                .select(GRAPHQL_FIELD.TYPE_NAME, GRAPHQL_FIELD.FIELD_NAME)
                .from(GRAPHQL_FIELD)
                .join(GRAPHQL_TYPE).on(GRAPHQL_TYPE.TYPE_NAME.eq(GRAPHQL_FIELD.TYPE_NAME))
                .where(GRAPHQL_FIELD.DEFAULT_VALUE_SDL.isNotNull())
                .and(GRAPHQL_TYPE.KIND.ne("INPUT_OBJECT"))
                .fetch();
            assertThat(offenders).as("output fields carrying a default value").isEmpty();
        }
    }

    /**
     * The transcription is total, so an application's name always resolves to a definition. This is
     * the invariant that replaced an exclusion: the graphitron namespace used to be withheld from
     * these relations, which made the family a transcription with a hole in it and pushed a
     * question about {@code source_name} into the choice of table.
     */
    @Test
    @DisplayName("every directive application resolves to a captured definition")
    void everyApplicationResolvesToItsDefinition(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var defined = store.dsl().select(GRAPHQL_DIRECTIVE.DIRECTIVE_NAME).from(GRAPHQL_DIRECTIVE);
            assertThat(store.dsl().fetchCount(GRAPHQL_SCHEMA_DIRECTIVE,
                GRAPHQL_SCHEMA_DIRECTIVE.DIRECTIVE_NAME.notIn(defined))).isZero();
            assertThat(store.dsl().fetchCount(GRAPHQL_TYPE_DIRECTIVE,
                GRAPHQL_TYPE_DIRECTIVE.DIRECTIVE_NAME.notIn(defined))).isZero();
            assertThat(store.dsl().fetchCount(GRAPHQL_FIELD_DIRECTIVE,
                GRAPHQL_FIELD_DIRECTIVE.DIRECTIVE_NAME.notIn(defined))).isZero();
            assertThat(store.dsl().fetchCount(GRAPHQL_ARGUMENT_DIRECTIVE,
                GRAPHQL_ARGUMENT_DIRECTIVE.DIRECTIVE_NAME.notIn(defined))).isZero();
            assertThat(store.dsl().fetchCount(GRAPHQL_ENUM_VALUE_DIRECTIVE,
                GRAPHQL_ENUM_VALUE_DIRECTIVE.DIRECTIVE_NAME.notIn(defined))).isZero();
        }
    }

    /**
     * The decode is an addition to the transcription, never a substitute for it, so a graphitron
     * application is reachable from both families and a consumer never has to know which one holds
     * a given directive.
     */
    @Test
    @DisplayName("a decoded graphitron application keeps its verbatim row too")
    void theDecodeDoesNotReplaceTheTranscription(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var decoded = store.dsl()
                .select(GRAPHITRON_TABLE.TYPE_NAME)
                .from(GRAPHITRON_TABLE)
                .fetch(GRAPHITRON_TABLE.TYPE_NAME);
            assertThat(decoded).as("the fixture applies @table, so the gate has something to pin")
                .isNotEmpty();
            var verbatim = store.dsl()
                .select(GRAPHQL_TYPE_DIRECTIVE.TYPE_NAME)
                .from(GRAPHQL_TYPE_DIRECTIVE)
                .where(GRAPHQL_TYPE_DIRECTIVE.DIRECTIVE_NAME.eq("table"))
                .fetch(GRAPHQL_TYPE_DIRECTIVE.TYPE_NAME);
            assertThat(verbatim).containsExactlyInAnyOrderElementsOf(decoded);
        }
    }

    @Test
    @DisplayName("the federation dual projection agrees with its verbatim twin")
    void federationKeyProjectionsAgree(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var decoded = store.dsl()
                .select(GRAPHITRON_FEDERATION_KEY.TYPE_NAME, GRAPHITRON_FEDERATION_KEY.ORDINAL)
                .from(GRAPHITRON_FEDERATION_KEY)
                .fetch();
            assertThat(decoded).as("the fixture applies @key twice, so the gate has something to pin")
                .hasSize(2);
            var verbatim = store.dsl()
                .select(GRAPHQL_TYPE_DIRECTIVE.TYPE_NAME, GRAPHQL_TYPE_DIRECTIVE.ORDINAL)
                .from(GRAPHQL_TYPE_DIRECTIVE)
                .where(GRAPHQL_TYPE_DIRECTIVE.DIRECTIVE_NAME.eq("key"))
                .fetch();
            assertThat(verbatim).containsExactlyInAnyOrderElementsOf(decoded);
        }
    }

    @Test
    @DisplayName("every element hangs off a declaration site of its own type")
    void elementSiteReferencesAreTotal(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            // The FK already guarantees this; the gate exists because a NULL source_name would
            // silently disable it under MATCH SIMPLE, which is the reason that column is NOT NULL.
            var orphans = store.dsl()
                .select(GRAPHQL_FIELD.TYPE_NAME, GRAPHQL_FIELD.FIELD_NAME)
                .from(GRAPHQL_FIELD)
                .whereNotExists(select(GRAPHQL_TYPE_DECLARATION.TYPE_NAME)
                    .from(GRAPHQL_TYPE_DECLARATION)
                    .where(GRAPHQL_TYPE_DECLARATION.TYPE_NAME.eq(GRAPHQL_FIELD.TYPE_NAME))
                    .and(GRAPHQL_TYPE_DECLARATION.SOURCE_NAME.eq(GRAPHQL_FIELD.SOURCE_NAME))
                    .and(GRAPHQL_TYPE_DECLARATION.SOURCE_LINE.eq(GRAPHQL_FIELD.DECLARATION_LINE))
                    .and(GRAPHQL_TYPE_DECLARATION.SOURCE_COLUMN.eq(GRAPHQL_FIELD.DECLARATION_COLUMN)))
                .fetch();
            assertThat(orphans).as("fields with no declaration site").isEmpty();
        }
    }
}
