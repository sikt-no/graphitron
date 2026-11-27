-- What the arms of the argument mint state, on the type candidates' terms. Keyed by the argument's
-- own coordinate with no arm beside it: each arm adds its own argument to its own carrier, so the
-- argument name is the arm and the carrier the field it sits on.
-- Pagination, on every carrier. The page size the application asked for, or the default where it
-- asked for none, lowered to the build's maximum page size where that is smaller, so the default a
-- client is told never exceeds the page it can receive. The 100 is
-- ConnectionDefaults.DEFAULT_PAGE_SIZE, and the CASE its defaultPageSize (not LEAST, whose stored
-- form H2 writes with a RESPECT NULLS the definition walk cannot parse). An authored default passes
-- through as written; graphitron_entry_defect judges it.
SELECT c.graph_name, c.type_name, c.field_name, 'first' AS argument_name,
       c.argument_count AS ordinal, 'Int' AS type_sdl, 'Int' AS named_type,
       FALSE AS non_null, 0 AS list_depth, CAST(NULL AS BOOLEAN) AS item_non_null,
       CAST(COALESCE(c.default_first_value,
                     CASE WHEN mx.max_page_size < 100 THEN mx.max_page_size ELSE 100 END)
            AS VARCHAR) AS default_value_sdl
  FROM graphitron_connection_carrier c
  LEFT JOIN store_graph_max_allowed_page_size mx ON mx.graph_name = c.graph_name
 UNION ALL
SELECT graph_name, type_name, field_name, 'after',
       argument_count + 1, 'String', 'String',
       FALSE, 0, NULL, NULL
  FROM graphitron_connection_carrier
