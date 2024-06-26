package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.Rating;
import fake.graphql.example.model.RatingFilterInput;
import fake.graphql.example.model.RatingReference;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import no.fellesstudentsystem.graphitron.enums.RatingListTest;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static List<Film> listArgumentNoConditionForQuery(DSLContext ctx, List<Rating> ratings,
            String releaseYear, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("rating", FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("G", Rating.G, "PG-13", Rating.PG_13, "R", Rating.R).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.G, "G", Rating.PG_13, "PG-13", Rating.R, "R").getOrDefault(s, null))).as("rating")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("listArgumentNoCondition")
                )
                .from(FILM)
                .where(ratings != null && ratings.size() > 0 ? FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("G", Rating.G, "PG-13", Rating.PG_13, "R", Rating.R).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.G, "G", Rating.PG_13, "PG-13", Rating.R, "R").getOrDefault(s, null)).in(ratings) : DSL.noCondition())
                .and(FILM.RELEASE_YEAR.eq(releaseYear))
                .orderBy(FILM.getIdFields())
                .fetch(it -> it.into(Film.class));
    }
    public static List<Film> listArgumentConditionForQuery(DSLContext ctx, List<Rating> ratings,
            String releaseYear, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("rating", FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("G", Rating.G, "PG-13", Rating.PG_13, "R", Rating.R).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.G, "G", Rating.PG_13, "PG-13", Rating.R, "R").getOrDefault(s, null))).as("rating")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("listArgumentCondition")
                )
                .from(FILM)
                .where(ratings != null && ratings.size() > 0 ? FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("G", Rating.G, "PG-13", Rating.PG_13, "R", Rating.R).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.G, "G", Rating.PG_13, "PG-13", Rating.R, "R").getOrDefault(s, null)).in(ratings) : DSL.noCondition())
                .and(no.fellesstudentsystem.graphitron.conditions.RatingTestConditions.ratingList(FILM, ratings == null ? null : ratings.stream().map(itRating -> Map.of(Rating.G, "G", Rating.PG_13, "PG-13", Rating.R, "R").getOrDefault(itRating, null)).collect(Collectors.toList())))
                .and(FILM.RELEASE_YEAR.eq(releaseYear))
                .orderBy(FILM.getIdFields())
                .fetch(it -> it.into(Film.class));
    }
    public static List<Film> listArgumentWithOverrideConditionForQuery(DSLContext ctx,
            List<Rating> ratings, String releaseYear, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("rating", FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("G", Rating.G, "PG-13", Rating.PG_13, "R", Rating.R).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.G, "G", Rating.PG_13, "PG-13", Rating.R, "R").getOrDefault(s, null))).as("rating")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("listArgumentWithOverrideCondition")
                )
                .from(FILM)
                .where(no.fellesstudentsystem.graphitron.conditions.RatingTestConditions.ratingList(FILM, ratings == null ? null : ratings.stream().map(itRating -> Map.of(Rating.G, "G", Rating.PG_13, "PG-13", Rating.R, "R").getOrDefault(itRating, null)).collect(Collectors.toList())))
                .and(FILM.RELEASE_YEAR.eq(releaseYear))
                .orderBy(FILM.getIdFields())
                .fetch(it -> it.into(Film.class));
    }
    public static List<Film> listArgumentAndFieldConditionForQuery(DSLContext ctx, List<Rating> ratings,
            String releaseYear, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("rating", FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("G", Rating.G, "PG-13", Rating.PG_13, "R", Rating.R).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.G, "G", Rating.PG_13, "PG-13", Rating.R, "R").getOrDefault(s, null))).as("rating")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("listArgumentAndFieldCondition")
                )
                .from(FILM)
                .where(ratings != null && ratings.size() > 0 ? FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("G", Rating.G, "PG-13", Rating.PG_13, "R", Rating.R).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.G, "G", Rating.PG_13, "PG-13", Rating.R, "R").getOrDefault(s, null)).in(ratings) : DSL.noCondition())
                .and(FILM.RELEASE_YEAR.eq(releaseYear))
                .and(no.fellesstudentsystem.graphitron.conditions.RatingTestConditions.ratingListAll(FILM, ratings == null ? null : ratings.stream().map(itRating -> Map.of(Rating.G, "G", Rating.PG_13, "PG-13", Rating.R, "R").getOrDefault(itRating, null)).collect(Collectors.toList()), releaseYear))
                .orderBy(FILM.getIdFields())
                .fetch(it -> it.into(Film.class));
    }
    public static List<Film> inputArgumentContainingListConditionForQuery(DSLContext ctx,
            RatingFilterInput filter, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("rating", FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("G", Rating.G, "PG-13", Rating.PG_13, "R", Rating.R).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.G, "G", Rating.PG_13, "PG-13", Rating.R, "R").getOrDefault(s, null))).as("rating")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("inputArgumentContainingListCondition")
                )
                .from(FILM)
                .where(no.fellesstudentsystem.graphitron.conditions.RatingTestConditions.ratingList(FILM, filter != null && filter.getRatings() != null && filter.getRatings().size() > 0 ? filter.getRatings() == null ? null : filter.getRatings().stream().map(itRating -> Map.of(Rating.G, "G", Rating.PG_13, "PG-13", Rating.R, "R").getOrDefault(itRating, null)).collect(Collectors.toList()) : null))
                .and(filter != null ? FILM.RELEASE_YEAR.eq(filter.getReleaseYear()) : DSL.noCondition())
                .orderBy(FILM.getIdFields())
                .fetch(it -> it.into(Film.class));
    }
    public static List<Film> listArgumentWithEnumDirectiveNoConditionForQuery(DSLContext ctx,
            List<RatingReference> ratings, String releaseYear, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("rating", FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("G", Rating.G, "PG-13", Rating.PG_13, "R", Rating.R).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.G, "G", Rating.PG_13, "PG-13", Rating.R, "R").getOrDefault(s, null))).as("rating")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("listArgumentWithEnumDirectiveNoCondition")
                )
                .from(FILM)
                .where(ratings != null && ratings.size() > 0 ? FILM.RATING.convert(RatingReference.class, s -> s == null ? null : Map.of(RatingListTest.G, RatingReference.G, RatingListTest.PG_13, RatingReference.PG_13, RatingListTest.R, RatingReference.R).getOrDefault(s, null), s -> s == null ? null : Map.of(RatingReference.G, RatingListTest.G, RatingReference.PG_13, RatingListTest.PG_13, RatingReference.R, RatingListTest.R).getOrDefault(s, null)).in(ratings) : DSL.noCondition())
                .and(releaseYear != null ? FILM.RELEASE_YEAR.eq(releaseYear) : DSL.noCondition())
                .orderBy(FILM.getIdFields())
                .fetch(it -> it.into(Film.class));
    }
    public static List<Film> listArgumentWithEnumDirectiveConditionForQuery(DSLContext ctx,
            List<RatingReference> ratings, String releaseYear, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("rating", FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("G", Rating.G, "PG-13", Rating.PG_13, "R", Rating.R).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.G, "G", Rating.PG_13, "PG-13", Rating.R, "R").getOrDefault(s, null))).as("rating")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("listArgumentWithEnumDirectiveCondition")
                )
                .from(FILM)
                .where(ratings != null && ratings.size() > 0 ? FILM.RATING.convert(RatingReference.class, s -> s == null ? null : Map.of(RatingListTest.G, RatingReference.G, RatingListTest.PG_13, RatingReference.PG_13, RatingListTest.R, RatingReference.R).getOrDefault(s, null), s -> s == null ? null : Map.of(RatingReference.G, RatingListTest.G, RatingReference.PG_13, RatingListTest.PG_13, RatingReference.R, RatingListTest.R).getOrDefault(s, null)).in(ratings) : DSL.noCondition())
                .and(no.fellesstudentsystem.graphitron.conditions.RatingTestConditions.ratingListEnumReference(FILM, ratings == null ? null : ratings.stream().map(itRatingReference -> Map.of(RatingReference.G, RatingListTest.G, RatingReference.PG_13, RatingListTest.PG_13, RatingReference.R, RatingListTest.R).getOrDefault(itRatingReference, null)).collect(Collectors.toList())))
                .and(releaseYear != null ? FILM.RELEASE_YEAR.eq(releaseYear) : DSL.noCondition())
                .orderBy(FILM.getIdFields())
                .fetch(it -> it.into(Film.class));
    }
}
