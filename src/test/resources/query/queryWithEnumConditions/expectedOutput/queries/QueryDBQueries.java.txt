package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.Film;
import fake.graphql.example.package.model.FilmInput;
import fake.graphql.example.package.model.Rating;
import java.lang.String;
import java.util.List;
import java.util.Map;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public List<Film> paramConditionForQuery(DSLContext ctx, Rating rating, String releaseYear,
            SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("rating", FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("R", Rating.R, "PG-13", Rating.PG_13, "G", Rating.G).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(s, null))).as("rating")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("paramCondition")
                )
                .from(FILM)
                .where(FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("R", Rating.R, "PG-13", Rating.PG_13, "G", Rating.G).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(s, null)).eq(rating))
                .and(no.fellesstudentsystem.graphitron.conditions.RatingTestConditions.rating(FILM, rating == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(rating, null)))
                .and(FILM.RELEASE_YEAR.eq(releaseYear))
                .orderBy(FILM.getIdFields())
                .fetch(0, Film.class);
    }

    public List<Film> paramConditionOverrideForQuery(DSLContext ctx, Rating rating,
            String releaseYear, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("rating", FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("R", Rating.R, "PG-13", Rating.PG_13, "G", Rating.G).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(s, null))).as("rating")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("paramConditionOverride")
                )
                .from(FILM)
                .where(no.fellesstudentsystem.graphitron.conditions.RatingTestConditions.rating(FILM, rating == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(rating, null)))
                .and(FILM.RELEASE_YEAR.eq(releaseYear))
                .orderBy(FILM.getIdFields())
                .fetch(0, Film.class);
    }

    public List<Film> fieldConditionForQuery(DSLContext ctx, Rating rating, String releaseYear,
            SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("rating", FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("R", Rating.R, "PG-13", Rating.PG_13, "G", Rating.G).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(s, null))).as("rating")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("fieldCondition")
                )
                .from(FILM)
                .where(FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("R", Rating.R, "PG-13", Rating.PG_13, "G", Rating.G).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(s, null)).eq(rating))
                .and(FILM.RELEASE_YEAR.eq(releaseYear))
                .and(no.fellesstudentsystem.graphitron.conditions.RatingTestConditions.ratingAll(FILM, rating == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(rating, null), releaseYear))
                .orderBy(FILM.getIdFields())
                .fetch(0, Film.class);
    }

    public List<Film> fieldConditionOverrideForQuery(DSLContext ctx, Rating rating,
            String releaseYear, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("rating", FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("R", Rating.R, "PG-13", Rating.PG_13, "G", Rating.G).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(s, null))).as("rating")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("fieldConditionOverride")
                )
                .from(FILM)
                .where(no.fellesstudentsystem.graphitron.conditions.RatingTestConditions.ratingAll(FILM, rating == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(rating, null), releaseYear))
                .orderBy(FILM.getIdFields())
                .fetch(0, Film.class);
    }

    public List<Film> fieldAndParamConditionForQuery(DSLContext ctx, Rating rating,
            String releaseYear, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("rating", FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("R", Rating.R, "PG-13", Rating.PG_13, "G", Rating.G).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(s, null))).as("rating")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("fieldAndParamCondition")
                )
                .from(FILM)
                .where(FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("R", Rating.R, "PG-13", Rating.PG_13, "G", Rating.G).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(s, null)).eq(rating))
                .and(no.fellesstudentsystem.graphitron.conditions.RatingTestConditions.rating(FILM, rating == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(rating, null)))
                .and(FILM.RELEASE_YEAR.eq(releaseYear))
                .and(no.fellesstudentsystem.graphitron.conditions.RatingTestConditions.ratingAll(FILM, rating == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(rating, null), releaseYear))
                .orderBy(FILM.getIdFields())
                .fetch(0, Film.class);
    }

    public List<Film> fieldAndParamConditionOverrideForQuery(DSLContext ctx, Rating rating,
            String releaseYear, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("rating", FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("R", Rating.R, "PG-13", Rating.PG_13, "G", Rating.G).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(s, null))).as("rating")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("fieldAndParamConditionOverride")
                )
                .from(FILM)
                .where(no.fellesstudentsystem.graphitron.conditions.RatingTestConditions.rating(FILM, rating == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(rating, null)))
                .and(no.fellesstudentsystem.graphitron.conditions.RatingTestConditions.ratingAll(FILM, rating == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(rating, null), releaseYear))
                .orderBy(FILM.getIdFields())
                .fetch(0, Film.class);
    }

    public List<Film> fieldAndParamConditionOverrideBothForQuery(DSLContext ctx, Rating rating,
            String releaseYear, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("rating", FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("R", Rating.R, "PG-13", Rating.PG_13, "G", Rating.G).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(s, null))).as("rating")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("fieldAndParamConditionOverrideBoth")
                )
                .from(FILM)
                .where(no.fellesstudentsystem.graphitron.conditions.RatingTestConditions.rating(FILM, rating == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(rating, null)))
                .and(no.fellesstudentsystem.graphitron.conditions.RatingTestConditions.ratingAll(FILM, rating == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(rating, null), releaseYear))
                .orderBy(FILM.getIdFields())
                .fetch(0, Film.class);
    }

    public List<Film> fieldInputConditionForQuery(DSLContext ctx, FilmInput ratingIn,
            String releaseYear, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("rating", FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("R", Rating.R, "PG-13", Rating.PG_13, "G", Rating.G).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(s, null))).as("rating")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("fieldInputCondition")
                )
                .from(FILM)
                .where(ratingIn != null ? FILM.RELEASE_YEAR.eq(ratingIn.getReleaseYear()) : DSL.noCondition())
                .and(ratingIn != null ? FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("R", Rating.R, "PG-13", Rating.PG_13, "G", Rating.G).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(s, null)).eq(ratingIn.getRating()) : DSL.noCondition())
                .and(FILM.RELEASE_YEAR.eq(releaseYear))
                .and(no.fellesstudentsystem.graphitron.conditions.RatingTestConditions.ratingInputAll(FILM, ratingIn != null ? ratingIn.getReleaseYear() : null, ratingIn != null ? ratingIn.getRating() == null ? null : Map.of(Rating.R, "R", Rating.PG_13, "PG-13", Rating.G, "G").getOrDefault(ratingIn.getRating(), null) : null, releaseYear))
                .orderBy(FILM.getIdFields())
                .fetch(0, Film.class);
    }
}