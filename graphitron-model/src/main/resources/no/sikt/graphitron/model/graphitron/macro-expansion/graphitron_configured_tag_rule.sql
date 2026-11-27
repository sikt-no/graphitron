WITH applied (graph_name, coordinate, site_order, tag) AS (
  -- A field or an input field, declared at one site, takes the tag of its file unless it carries one.
  SELECT f.graph_name, f.type_name || '.' || f.field_name, 0, s.tag
    FROM graphql_field f
    JOIN store_graph_source_input s ON s.graph_name = f.graph_name AND s.source_name = f.source_name
   WHERE s.tag IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM graphql_directive_application d
                      WHERE d.graph_name = f.graph_name
                        AND d.coordinate = f.type_name || '.' || f.field_name
                        AND d.directive_name = 'tag')
  UNION ALL
  SELECT a.graph_name, a.type_name || '.' || a.field_name || '(' || a.argument_name || ':)', 0, s.tag
    FROM graphql_argument a
    JOIN store_graph_source_input s ON s.graph_name = a.graph_name AND s.source_name = a.source_name
   WHERE s.tag IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM graphql_directive_application d
                      WHERE d.graph_name = a.graph_name
                        AND d.coordinate = a.type_name || '.' || a.field_name
                                           || '(' || a.argument_name || ':)'
                        AND d.directive_name = 'tag')
  UNION ALL
  SELECT v.graph_name, v.type_name || '.' || v.value_name, 0, s.tag
    FROM graphql_enum_value v
    JOIN store_graph_source_input s ON s.graph_name = v.graph_name AND s.source_name = v.source_name
   WHERE s.tag IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM graphql_directive_application d
                      WHERE d.graph_name = v.graph_name
                        AND d.coordinate = v.type_name || '.' || v.value_name
                        AND d.directive_name = 'tag')
  UNION ALL
  -- A union is tagged per declaration site, each site taking the tag of its own file unless the site
  -- carries one, so a union declared in two tagged files carries both.
  SELECT t.graph_name, t.type_name, t.merge_ordinal, s.tag
    FROM graphql_type_declaration t
    JOIN store_graph_source_input s ON s.graph_name = t.graph_name AND s.source_name = t.source_name
   WHERE t.kind = 'UNION' AND s.tag IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM graphql_ast_entry e
                       JOIN graphql_ast_directive_application_entry a
                         ON a.graph_name = e.graph_name AND a.source_name = e.source_name
                        AND a.source_line = e.source_line AND a.source_column = e.source_column
                      WHERE e.graph_name = t.graph_name AND e.source_name = t.source_name
                        AND e.parent_line = t.source_line AND e.parent_column = t.source_column
                        AND a.name = 'tag'))
SELECT p.graph_name, p.coordinate,
       CAST((SELECT COUNT(*) FROM graphql_directive_application d
              WHERE d.graph_name = p.graph_name AND d.coordinate = p.coordinate
                AND d.directive_name = 'tag') AS INT)
         -- After every tag the author wrote, in the order of the sites the tags come from: one site
         -- at its own position, a union's sites at their merge ordinals.
         + p.site_order AS ordinal,
       '"' || p.tag || '"' AS value_sdl
  FROM applied p
  JOIN graphitron_element e ON e.graph_name = p.graph_name AND e.coordinate = p.coordinate
