-- A coordinate the arms state more than one way. At a type, the arms that coin it: two carriers
-- coining one name through one arm agree, and one carrier coining it through two arms does not,
-- whatever the docstrings say. At a field or an argument, its distinct payloads. Aggregates over
-- each candidate relation, no window, so a stage's graph filter reaches the grouping key.
SELECT graph_name, coordinate, 'NAMED_TYPE' AS element_kind, CAST(COUNT(*) AS INT) AS variants
  FROM (SELECT DISTINCT graph_name, coordinate, arm FROM graphitron_type_minted_candidate) r
 GROUP BY graph_name, coordinate
HAVING COUNT(*) > 1
 UNION ALL
SELECT graph_name, coordinate, 'FIELD', CAST(COUNT(*) AS INT)
  FROM (SELECT DISTINCT graph_name, coordinate, ordinal, type_sdl, named_type, non_null,
               list_depth, item_non_null, description, rewrite
          FROM graphitron_field_minted_candidate) r
 GROUP BY graph_name, coordinate
HAVING COUNT(*) > 1
 UNION ALL
SELECT graph_name, coordinate, 'FIELD_ARGUMENT', CAST(COUNT(*) AS INT)
  FROM (SELECT DISTINCT graph_name, coordinate, ordinal, type_sdl, named_type, non_null,
               list_depth, item_non_null, default_value_sdl
          FROM graphitron_argument_minted_candidate) r
 GROUP BY graph_name, coordinate
HAVING COUNT(*) > 1
