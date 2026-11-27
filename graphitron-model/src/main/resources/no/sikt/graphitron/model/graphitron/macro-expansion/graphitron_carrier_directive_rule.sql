WITH carried (graph_name, coordinate, directive_name, ordinal, value_sdl) AS (
  SELECT d.graph_name, d.coordinate, d.directive_name, d.ordinal, a.value_sdl
    FROM graphql_directive_application d
    LEFT JOIN graphql_directive_application_arg a
      ON a.graph_name = d.graph_name AND a.coordinate = d.coordinate
     AND a.directive_name = d.directive_name AND a.ordinal = d.ordinal
     AND a.directive_argument_name = 'name'
   WHERE d.directive_name IN ('tag', 'shareable')
  UNION ALL
  SELECT c.graph_name, c.coordinate, 'tag', c.ordinal, c.value_sdl
    FROM graphitron_configured_tag c)
SELECT m.graph_name, m.type_name, m.coordinate, c.directive_name, c.ordinal, c.value_sdl
  FROM graphitron_minted_coinage m
  -- The minted types as this stage stored them before running the rule, so a type an author
  -- declared, or one the applications disagree about, inherits nothing.
  JOIN graphitron_type t ON t.graph_name = m.graph_name AND t.type_name = m.type_name
   AND t.origin = 'MINTED'
  JOIN carried c ON c.graph_name = m.graph_name AND c.coordinate = m.coordinate
