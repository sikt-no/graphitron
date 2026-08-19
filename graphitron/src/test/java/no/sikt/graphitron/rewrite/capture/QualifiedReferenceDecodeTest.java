package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE_COLUMN_MAPPING_PAIR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_COLUMN_MATCH_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_SPELLED_TABLE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered anchor for the qualifier grammar: how a written reference to a catalog object is
 * partitioned into the namespace half and the name half capture stores beside it.
 *
 * <p>The rule is total and has no fallback arm, so the cases here are its partition rather than a
 * sample of well-formed inputs. Null and the empty string carry different meanings and both are
 * load-bearing: null says no period appeared, and the empty string says one did with nothing on
 * that side of it. The two boundary cases pinning that distinction are the ones worth reading
 * first, since every other row of the table follows from them.
 *
 * <p>One case reaches past capture into the resolution, because the arrangement is only worth
 * anything if a half-empty reference joins nothing rather than degrading into an unqualified one.
 * That failure is meant to be visible in the stored fact and produced by the join coming up empty,
 * not by a rule somewhere deciding what a malformed value falls back to.
 */
@PipelineTier
class QualifiedReferenceDecodeTest {

    /**
     * One reference of each qualification shape, spread across the directives that carry one:
     * {@code @table}, a path element's table and key, {@code @routine} and {@code @mutation}.
     */
    private static final String FIXTURE = """
        type Query {
          film: Film
          qualified: Qualified
          trailing: Trailing
          leading: Leading
          deep: Deep
          byRoutine: [Film!]! @routine(name: "public.films_for_actor")
        }
        type Mutation {
          dropFilm(id: Int!): Film @mutation(typeName: DELETE, table: "public.film")
        }
        type Film @table(name: "film") {
          title: String
          language: Language @reference(path: [{key: "public.film_language_id_fkey"}])
          categories: [Category!]! @reference(path: [{table: "public.film_category"}])
        }
        type Qualified @table(name: "public.film") { title: String }
        type Trailing @table(name: "film.") { title: String }
        type Leading @table(name: ".film") { title: String }
        type Deep @table(name: "a.b.c") { title: String }
        type Language @table(name: "language") { name: String }
        type Category @table(name: "category") { name: String }
        """;

    // ===== The partition, one case per arm =====

    /** No period appeared, so the namespace half is null and the name half is the whole value. */
    @Test
    @DisplayName("an unqualified name partitions to a null namespace and itself")
    void anUnqualifiedNamePartitionsToANullNamespace(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertThat(tableRefParts(store.dsl(), "Film"))
                .containsExactly(null, "film");
        }
    }

    @Test
    @DisplayName("a qualified name partitions on its period")
    void aQualifiedNamePartitionsOnItsPeriod(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertThat(tableRefParts(store.dsl(), "Qualified"))
                .containsExactly("public", "film");
        }
    }

    /**
     * The first of the two cases the whole arrangement rests on. A period was written with nothing
     * after it, so the name half is the empty string and not null: null would say no period
     * appeared, which is a different fact about what the author typed.
     */
    @Test
    @DisplayName("a trailing period leaves the name half empty rather than absent")
    void aTrailingPeriodLeavesTheNameHalfEmpty(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertThat(tableRefParts(store.dsl(), "Trailing"))
                .containsExactly("film", "");
        }
    }

    /** The second: a qualifier position written empty is the empty string, not an absent one. */
    @Test
    @DisplayName("a leading period leaves the namespace half empty rather than absent")
    void aLeadingPeriodLeavesTheNamespaceHalfEmpty(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertThat(tableRefParts(store.dsl(), "Leading"))
                .containsExactly("", "film");
        }
    }

    /**
     * The split is on the <em>first</em> period and everything after it is the name half, which is
     * then not a legal identifier and matches nothing. Recording it that way keeps the partition a
     * total function; deciding that a second period means something else would make it a parser.
     */
    @Test
    @DisplayName("a second period rides into the name half")
    void aSecondPeriodRidesIntoTheNameHalf(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertThat(tableRefParts(store.dsl(), "Deep"))
                .containsExactly("a", "b.c");
        }
    }

    // ===== The same rule, at the other sites that carry a qualifier =====

    /**
     * A path element's key partitions by the same rule, and the meaning of the halves is what
     * differs: the qualifier names which schema's table holds the constraint rather than the
     * constraint's own schema, which the store's column comments carry and the split does not know.
     */
    @Test
    @DisplayName("a path element's key and table partition by the same rule")
    void aPathElementPartitionsByTheSameRule(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            var s = GRAPHITRON_FIELD_REFERENCE_STEP;
            assertThat(halves(store.dsl()
                .select(s.KEY_REF_NAMESPACE_PART, s.KEY_REF_NAME_PART)
                .from(s).where(s.FIELD_NAME.eq("language")).fetchOne()))
                .containsExactly("public", "film_language_id_fkey");
            assertThat(halves(store.dsl()
                .select(s.TABLE_REF_NAMESPACE_PART, s.TABLE_REF_NAME_PART)
                .from(s).where(s.FIELD_NAME.eq("categories")).fetchOne()))
                .containsExactly("public", "film_category");
        }
    }

    @Test
    @DisplayName("a routine name and a mutation's write target partition by the same rule")
    void aRoutineAndAMutationPartitionByTheSameRule(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FIXTURE)) {
            assertThat(halves(store.dsl()
                .select(GRAPHITRON_ROUTINE.ROUTINE_REF_NAMESPACE_PART,
                        GRAPHITRON_ROUTINE.ROUTINE_REF_NAME_PART)
                .from(GRAPHITRON_ROUTINE).fetchOne()))
                .containsExactly("public", "films_for_actor");
            assertThat(halves(store.dsl()
                .select(GRAPHITRON_MUTATION.TABLE_REF_NAMESPACE_PART,
                        GRAPHITRON_MUTATION.TABLE_REF_NAME_PART)
                .from(GRAPHITRON_MUTATION).fetchOne()))
                .containsExactly("public", "film");
        }
    }

    // ===== What an empty half does downstream =====

    /**
     * The point of storing the empty string rather than repairing it: the join finds nothing, so
     * the failure is a non-match a reader can see the shape of. A fallback treating a blank half as
     * unqualified would have resolved this spelling as though the author had written {@code film}.
     */
    @Test
    @DisplayName("a half-empty reference resolves to no table rather than to the other half")
    void aHalfEmptyReferenceResolvesToNoTable(@TempDir Path tmp) {
        String spellings = """
            type Query { a: Plain, b: Trailing, c: Leading, d: Deep }
            type Plain    @table(name: "film")  { title: String }
            type Trailing @table(name: "film.") { title: String }
            type Leading  @table(name: ".film") { title: String }
            type Deep     @table(name: "a.b.c") { title: String }
            """;
        withCatalog(tmp, spellings, dsl -> {
            assertThat(dsl.fetchCount(INTENT_SPELLED_TABLE,
                INTENT_SPELLED_TABLE.SPELLING.eq("film")))
                .as("the fixture has to resolve the plain spelling, or the pins below say nothing")
                .isPositive();
            assertThat(dsl.fetchCount(INTENT_SPELLED_TABLE,
                INTENT_SPELLED_TABLE.SPELLING.eq("film."))).isZero();
            assertThat(dsl.fetchCount(INTENT_SPELLED_TABLE,
                INTENT_SPELLED_TABLE.SPELLING.eq(".film"))).isZero();
            assertThat(dsl.fetchCount(INTENT_SPELLED_TABLE,
                INTENT_SPELLED_TABLE.SPELLING.eq("a.b.c"))).isZero();
        });
    }

    // ===== The folds on the authored side =====

    /**
     * A type name is a GraphQL identifier and a table name is a SQL one, and where the author
     * omitted {@code @table(name:)} the first stands in as a spelling of the second. Both sides of
     * that comparison are folded columns now rather than one folded column and one per-row
     * {@code UPPER}, so this pins behaviour the change had to preserve rather than behaviour it
     * adds: it passed before the fold and has to pass after it.
     */
    @Test
    @DisplayName("a type name binds its table across a difference in case")
    void aTypeNameBindsItsTableAcrossCase(@TempDir Path tmp) {
        String byTypeName = """
            type Query { film: Film }
            type Film @table { title: String }
            """;
        withCatalog(tmp, byTypeName, dsl ->
            assertThat(dsl.select(INTENT_SPELLED_TABLE.TABLE_NAME)
                .from(INTENT_SPELLED_TABLE)
                .where(INTENT_SPELLED_TABLE.SPELLING.eq("Film"))
                .fetch(INTENT_SPELLED_TABLE.TABLE_NAME))
                .containsExactly("film"));
    }

    /** The same, on the other left side: an effective column name matched case-insensitively. */
    @Test
    @DisplayName("a bound name claims its column across a difference in case")
    void aBoundNameClaimsItsColumnAcrossCase(@TempDir Path tmp) {
        String byFieldName = """
            type Query { film: Film }
            type Film @table(name: "film") { heading: String @field(name: "TITLE") }
            """;
        withCatalog(tmp, byFieldName, dsl ->
            assertThat(dsl.select(INTENT_COLUMN_MATCH_CLAIM.COLUMN_NAME)
                .from(INTENT_COLUMN_MATCH_CLAIM)
                .where(INTENT_COLUMN_MATCH_CLAIM.FIELD_NAME.eq("heading"))
                .fetch(INTENT_COLUMN_MATCH_CLAIM.COLUMN_NAME))
                .containsExactly("title"));
    }

    /**
     * The excluded reference stays whole. A dotted {@code columnMapping} right side is an author
     * error the store holds so a detection can report it, so splitting it would delete the case it
     * exists for; this pins that the partition did not spread there.
     */
    @Test
    @DisplayName("a dotted columnMapping right side is still stored whole")
    void aDottedColumnMappingRightSideIsStoredWhole(@TempDir Path tmp) {
        String dotted = """
            type Query {
              films: [Film!]! @routine(name: "films_for_actor",
                                       columnMapping: "title: film.title")
            }
            type Film @table(name: "film") { title: String }
            """;
        try (var store = CapturedStore.of(tmp, dotted)) {
            assertThat(store.dsl()
                .select(GRAPHITRON_ROUTINE_COLUMN_MAPPING_PAIR.COLUMN_REF)
                .from(GRAPHITRON_ROUTINE_COLUMN_MAPPING_PAIR)
                .fetch(GRAPHITRON_ROUTINE_COLUMN_MAPPING_PAIR.COLUMN_REF))
                .containsExactly("film.title");
        }
    }

    // ===== Helpers =====

    /** A capture with the fixture catalog beside it, for the cases whose subject is a resolution. */
    private static void withCatalog(Path directory, String sdl,
                                    java.util.function.Consumer<DSLContext> body) {
        var ctx = testContext();
        try (var store = CapturedStore.ofCatalog(directory, sdl,
                new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()))) {
            body.accept(store.dsl());
        }
    }

    /** One type's {@code @table} reference as its two halves, either of which may be null. */
    private static List<String> tableRefParts(DSLContext dsl, String typeName) {
        return halves(dsl.select(GRAPHITRON_TABLE.TABLE_REF_NAMESPACE_PART,
                                 GRAPHITRON_TABLE.TABLE_REF_NAME_PART)
            .from(GRAPHITRON_TABLE)
            .where(GRAPHITRON_TABLE.TYPE_NAME.eq(typeName))
            .fetchOne());
    }

    /** A two-column row as a list, which unlike a tuple carries a null half. */
    private static List<String> halves(Record2<String, String> row) {
        return Arrays.asList(row.value1(), row.value2());
    }
}
