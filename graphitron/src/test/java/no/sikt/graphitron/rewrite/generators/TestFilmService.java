package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.ArrayHolderRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmListRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord;
import org.jooq.Record1;
import org.jooq.Row1;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal service stub used by {@link FetcherPipelineTest} to verify that the
 * {@code @service} DataLoader code path can be triggered via reflection.
 *
 * <p>The {@code languageKeys} parameter uses {@code Row1<Integer>} matching the
 * {@code language_id} {@code serial} (Integer) column. Return type is the structurally-
 * required {@code List<List<FilmRecord>>} per the rows-method shape for a {@code [Film!]!}
 * list field with a positional {@code Row}-shaped batch key (the per-key value
 * {@code V} is {@code tb.table().recordClass()}, here {@code FilmRecord}).
 */
public class TestFilmService {

    public static List<List<FilmRecord>> getFilms(List<Row1<Integer>> languageKeys, String filter, String tenantId) {
        throw new UnsupportedOperationException();
    }

    /**
     * Mapped-container sibling of {@link #getFilms}: {@code Set} keys + {@code Map} return
     * classify the field's loader as {@code MAPPED_SET}. Used by the pipeline test to
     * assert that a mapped {@code ServiceTableField} routes through the lift (rows method returns
     * {@code Map<Row1<Integer>, List<Record>>} — the projected Record, not the developer-returned
     * {@code FilmRecord}).
     */
    public static Map<Row1<Integer>, List<FilmRecord>> getFilmsMapped(Set<Row1<Integer>> languageKeys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: a mapped child {@code @service} field whose GraphQL type is an enum. The
     * service maps each parent key to the enum's DB text (a {@code String} leaf), so the
     * structurally-required rows-method shape is the flat {@code Map<Row1<Integer>, String>} —
     * the per-key {@code V} is the {@code String} leaf, not the whole map. Sibling to
     * {@link #getRankMapped} (a built-in {@code Int} leaf) which already produced the flat shape;
     * the pair pins that the non-built-in scalar leaf no longer double-wraps the rows method.
     */
    public static Map<Row1<Integer>, String> getRatingMapped(Set<Row1<Integer>> languageKeys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: the built-in {@code Int}-leaf sibling of {@link #getRatingMapped}. Its leaf
     * resolves through the spec-built-in path ({@code strictPerKeyType} → {@code Integer}), so its
     * rows method was always the flat {@code Map<Row1<Integer>, Integer>}; the test asserts it
     * stays unchanged alongside the enum fix.
     */
    public static Map<Row1<Integer>, Integer> getRankMapped(Set<Row1<Integer>> languageKeys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: typed-{@code TableRecord} source-shape sibling of {@link #getRankMapped}.
     * {@code Set<LanguageRecord>} keys classify the wrap as {@code SourceKey.Wrap.TableRecord},
     * which obliges the parent {@code $project} SELECT to project the wrap's key columns, the
     * parent's primary key. The generated extraction copies exactly those onto a fresh typed
     * record by jOOQ field identity; a body needing any other column fetches it itself.
     */
    public static Map<LanguageRecord, Integer> getRankMappedByRecord(Set<LanguageRecord> languageKeys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: typed-{@code TableRecord} source-shape sibling of {@link #getFilmsMapped}
     * (a list-valued {@code ServiceTableField} rather than a scalar {@code ServiceRecordField}).
     */
    public static Map<LanguageRecord, List<FilmRecord>> getFilmsMappedByRecord(Set<LanguageRecord> languageKeys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Contrast fixture: {@code Record1}-keyed sibling of {@link #getRankMappedByRecord}. Both
     * wraps demand the same key columns and nothing wider, so the pair pins that the projection
     * does not vary with the wrap; only the shape of the emitted key read does.
     */
    public static Map<Record1<Integer>, Integer> getRankRecordWrap(Set<Record1<Integer>> languageKeys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Negative fixture: a mapped enum-leaf child {@code @service} whose method returns a bare
     * {@code List} where the mapped (Set-keyed) batch shape requires a {@code Map<K, V>}. The
     * non-built-in scalar leaf can no longer be named from the schema, so previously the validator
     * skipped this and the generated rows method miscompiled; now
     * {@code ServiceDirectiveResolver.validateChildServiceReturnType} peels and finds the outer
     * container unpeelable, rejecting at classify time.
     */
    public static List<String> getRatingWrongContainer(Set<Row1<Integer>> languageKeys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: typed-{@code TableRecord} source-shape keyed on {@code array_holder}, whose row
     * carries array-typed columns ({@code flags boolean[]}, {@code tags text[]}). {@code Set<
     * ArrayHolderRecord>} keys classify the wrap as {@code SourceKey.Wrap.TableRecord}, so the
     * generated fetcher's key extraction runs over that table.
     */
    public static Map<ArrayHolderRecord, Integer> getArrayHolderRankByRecord(Set<ArrayHolderRecord> holderKeys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: a SOURCES batch parameter keyed on {@code film_list}, a table with no primary key.
     * The batch key is the parent's PK, so this coordinate cannot be honoured; it is the
     * {@code SourcesOnPkLessParent} rejection's fixture.
     */
    public static Map<FilmListRecord, Integer> getFilmListRankByRecord(Set<FilmListRecord> listKeys) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: a child {@code @service} with no SOURCES batch parameter, a plain per-parent
     * delegation with no key read and so no need of a batch key. Paired with a PK-less parent it
     * is the over-fire guard for the {@code SourcesOnPkLessParent} rejection, which is keyed on
     * the parameter shape rather than on the parent lacking a primary key.
     */
    public static Integer getConstantRank(String filter) {
        throw new UnsupportedOperationException();
    }
}
