SELECT b.graph_name, b.type_name, b.field_name, b.coordinate, b.connection_name,
       b.connection_name || 'Edge' AS edge_name, b.element_type, b.item_sdl, b.item_non_null, b.non_null, b.ordinal,
       b.description, b.default_first_value, b.argument_count,
       b.source_name, b.source_line, b.source_column
  FROM (SELECT ca.graph_name, fe.type_name, fe.field_name, ca.coordinate,
               COALESCE(NULLIF(c.connection_name, ''),
                        fe.type_name || UPPER(SUBSTRING(fe.field_name FROM 1 FOR 1))
                                     || SUBSTRING(fe.field_name FROM 2) || 'Connection')
                 AS connection_name,
               f.named_type AS element_type,
               f.named_type || CASE WHEN f.item_non_null THEN '!' ELSE '' END AS item_sdl,
               COALESCE(f.item_non_null, FALSE) AS item_non_null,
               f.non_null, f.ordinal, f.description, c.default_first_value,
               CAST((SELECT COUNT(*) FROM graphql_argument g
                      WHERE g.graph_name = ca.graph_name AND g.type_name = fe.type_name
                        AND g.field_name = fe.field_name) AS INT) AS argument_count,
               ca.source_name, ca.source_line, ca.source_column
          -- The field's first @asConnection, what it wrote joined on where it wrote anything.
          FROM graphql_directive_application ca
          JOIN graphql_field_element fe
            ON fe.graph_name = ca.graph_name AND fe.coordinate = ca.coordinate
          LEFT JOIN graphitron_ast_connection_entry c
            ON c.graph_name = ca.graph_name AND c.source_name = ca.source_name
           AND c.source_line = ca.source_line AND c.source_column = ca.source_column
          JOIN graphql_field f
            ON f.graph_name = fe.graph_name
           AND f.type_name = fe.type_name
           AND f.field_name = fe.field_name
         -- A bare list of a named type and nothing deeper. list_depth answers this; the expansion
         -- used to read it off type_sdl by hand because the anchor did not carry the column.
         WHERE ca.directive_name = 'asConnection' AND ca.ordinal = 0
           AND f.is_list AND f.list_depth = 1) b
