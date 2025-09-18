SELECT ROW(
    ROW("film_3747728953"."film_id"),
    ROW("film_3747728953"."film_id"),
    "film_3747728953"."title",
    "film_3747728953"."description",
    (
        SELECT ROW(
            ROW("filmLanguageIdFkey_2471369182"."language_id"),
            ROW("filmLanguageIdFkey_2471369182"."language_id"),
            "filmLanguageIdFkey_2471369182"."name"
        ) AS "nested"
        FROM "public"."language" AS "filmLanguageIdFkey_2471369182"
        WHERE "film_3747728953"."language_id" = "filmLanguageIdFkey_2471369182"."language_id"
    )
) AS "nested"
FROM "public"."film" AS "film_3747728953"
WHERE (TRUE, CAST("film_3747728953"."description" AS VARCHAR)) IN (
    (
        ("film_3747728953"."film_id" IN (1)),
        'A Epic Drama of a Feminist And a Mad Scientist who must Battle a Teacher in The Canadian Rockies'
    ),
    (
        ("film_3747728953"."film_id" IN (1)),
        CAST("film_3747728953"."description" AS VARCHAR)
    )
)
ORDER BY "film_3747728953"."film_id";