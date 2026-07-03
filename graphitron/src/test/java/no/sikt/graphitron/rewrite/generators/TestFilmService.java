package no.sikt.graphitron.rewrite.generators;

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
 * list field with a positional {@code Row}-shaped batch key (post-R177: V = {@code
 * tb.table().recordClass()}, here {@code FilmRecord}).
 */
public class TestFilmService {

    public static List<List<FilmRecord>> getFilms(List<Row1<Integer>> languageKeys, String filter, String tenantId) {
        throw new UnsupportedOperationException();
    }

    /**
     * Mapped-container sibling of {@link #getFilms}: {@code Set} keys + {@code Map} return
     * classify the field's loader as {@code MAPPED_SET}. Used by the R285 pipeline test to
     * assert that a mapped {@code ServiceTableField} routes through the lift (rows method returns
     * {@code Map<Row1<Integer>, List<Record>>} — the projected Record, not the developer-returned
     * {@code FilmRecord}).
     */
    public static Map<Row1<Integer>, List<FilmRecord>> getFilmsMapped(Set<Row1<Integer>> languageKeys) {
        throw new UnsupportedOperationException();
    }

    /**
     * R364 fixture: a mapped child {@code @service} field whose GraphQL type is an enum. The
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
     * R364 fixture: the built-in {@code Int}-leaf sibling of {@link #getRatingMapped}. Its leaf
     * resolves through the spec-built-in path ({@code strictPerKeyType} → {@code Integer}), so its
     * rows method was always the flat {@code Map<Row1<Integer>, Integer>}; the test asserts it
     * stays unchanged alongside the enum fix.
     */
    public static Map<Row1<Integer>, Integer> getRankMapped(Set<Row1<Integer>> languageKeys) {
        throw new UnsupportedOperationException();
    }

    /**
     * R426 fixture: typed-{@code TableRecord} source-shape sibling of {@link #getRankMapped}.
     * {@code Set<LanguageRecord>} keys classify the wrap as {@code SourceKey.Wrap.TableRecord},
     * which obliges the parent {@code $fields} SELECT to project the full parent row (the
     * documented contract of the typed-record arm; the generated key extraction is
     * {@code env.getSource().into(Tables.LANGUAGE)} and the body may read any parent column).
     */
    public static Map<LanguageRecord, Integer> getRankMappedByRecord(Set<LanguageRecord> languageKeys) {
        throw new UnsupportedOperationException();
    }

    /**
     * R426 fixture: typed-{@code TableRecord} source-shape sibling of {@link #getFilmsMapped}
     * (a list-valued {@code ServiceTableField} rather than a scalar {@code ServiceRecordField}).
     */
    public static Map<LanguageRecord, List<FilmRecord>> getFilmsMappedByRecord(Set<LanguageRecord> languageKeys) {
        throw new UnsupportedOperationException();
    }

    /**
     * R426 contrast fixture: {@code Record1}-keyed sibling of {@link #getRankMappedByRecord}.
     * {@code Wrap.Record} keys carry exactly the key columns, so the parent projection stays
     * key-columns-only — pins that the R426 full-row widening is wrap-gated.
     */
    public static Map<Record1<Integer>, Integer> getRankMappedRecordKeyed(Set<Record1<Integer>> languageKeys) {
        throw new UnsupportedOperationException();
    }

    /**
     * R364 negative fixture: a mapped enum-leaf child {@code @service} whose method returns a bare
     * {@code List} where the mapped (Set-keyed) batch shape requires a {@code Map<K, V>}. The
     * non-built-in scalar leaf can no longer be named from the schema, so before R364 the validator
     * skipped this and the generated rows method miscompiled; now
     * {@code ServiceDirectiveResolver.validateChildServiceReturnType} peels and finds the outer
     * container unpeelable, rejecting at classify time.
     */
    public static List<String> getRatingWrongContainer(Set<Row1<Integer>> languageKeys) {
        throw new UnsupportedOperationException();
    }
}
