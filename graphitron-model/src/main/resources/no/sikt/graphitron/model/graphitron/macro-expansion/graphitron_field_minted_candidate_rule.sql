-- What the arms of the field mint state, one row per arm and carrier, on the type candidates'
-- terms; the arm here is the type of the carrier's the field sits on, or CARRIER for the rewrite.
-- The arms of its union are bracketed as a balanced tree, about log2 n deep rather than one level
-- an arm: one SELECT DISTINCT reads every mint arm, and H2 keeps a parsed copy of a union at every level of its nesting.
SELECT DISTINCT graph_name, type_name, field_name, carrier_coordinate, arm, position, ordinal,
       type_sdl, named_type, non_null, list_depth, item_non_null, description
  FROM (
        (
        (
        -- The connection's own machinery.
        SELECT graph_name, connection_name AS type_name, 'edges' AS field_name,
               coordinate AS carrier_coordinate, 'CONNECTION' AS arm, 'EDGES' AS position, 0 AS ordinal,
               '[' || edge_name || '!]!' AS type_sdl, edge_name AS named_type,
               TRUE AS non_null, 1 AS list_depth, TRUE AS item_non_null,
               'A list of edges.' AS description
          FROM graphitron_connection_carrier
        UNION ALL
        (
        SELECT graph_name, connection_name, 'nodes', coordinate, 'CONNECTION', 'NODES', 1,
               '[' || item_sdl || ']!', element_type, TRUE, 1, item_non_null,
               'A list of nodes.'
          FROM graphitron_connection_carrier
        UNION ALL
        SELECT graph_name, connection_name, 'pageInfo', coordinate, 'CONNECTION', 'PAGE_INFO', 2,
               'PageInfo!', 'PageInfo', TRUE, 0, NULL,
               'Information to aid in pagination.'
          FROM graphitron_connection_carrier
        )
        )
        UNION ALL
        (
        (
        -- Nullable like the connection's other aggregate: a skipped count degrades to null rather
        -- than bubbling a failure through the connection.
        SELECT graph_name, connection_name, 'totalCount', coordinate, 'CONNECTION', 'TOTAL_COUNT', 3,
               'Int', 'Int', FALSE, 0, NULL,
               'Identifies the total count of items in the connection.'
          FROM graphitron_connection_carrier
        UNION ALL
        -- The edge's.
        SELECT graph_name, edge_name, 'cursor', coordinate, 'EDGE', 'CURSOR', 0,
               'String!', 'String', TRUE, 0, NULL,
               'A cursor for use in pagination.'
          FROM graphitron_connection_carrier
        )
        UNION ALL
        (
        SELECT graph_name, edge_name, 'node', coordinate, 'EDGE', 'NODE', 1,
               item_sdl, element_type, item_non_null, 0, NULL,
               'The item at the end of the edge.'
          FROM graphitron_connection_carrier
        UNION ALL
        -- The page info every carrier states whole, which is why the set collapses them.
        SELECT graph_name, 'PageInfo', 'hasNextPage', coordinate, 'PAGE_INFO', 'HAS_NEXT_PAGE', 0,
               'Boolean!', 'Boolean', TRUE, 0, NULL,
               'When paginating forwards, are there more items?'
          FROM graphitron_connection_carrier
        )
        )
        )
        UNION ALL
        (
        (
        (
        SELECT graph_name, 'PageInfo', 'hasPreviousPage', coordinate, 'PAGE_INFO', 'HAS_PREVIOUS_PAGE', 1,
               'Boolean!', 'Boolean', TRUE, 0, NULL,
               'When paginating backwards, are there more items?'
          FROM graphitron_connection_carrier
        UNION ALL
        SELECT graph_name, 'PageInfo', 'startCursor', coordinate, 'PAGE_INFO', 'START_CURSOR', 2,
               'String', 'String', FALSE, 0, NULL,
               'When paginating backwards, the cursor to continue.'
          FROM graphitron_connection_carrier
        )
        UNION ALL
        (
        SELECT graph_name, 'PageInfo', 'endCursor', coordinate, 'PAGE_INFO', 'END_CURSOR', 3,
               'String', 'String', FALSE, 0, NULL,
               'When paginating forwards, the cursor to continue.'
          FROM graphitron_connection_carrier
        UNION ALL
        -- The facets the carrier surfaces: the connection's own field reaching the container, one
        -- row per facet collapsed by the distinct, the container's field per facet, and each
        -- bucket's value and count.
        SELECT f.graph_name, c.connection_name, 'facets', c.coordinate, 'CONNECTION', 'FACETS', 4,
               c.facets_name, c.facets_name, FALSE, 0, NULL,
               'Per-facet value counts for the items in the connection.'
          FROM graphitron_connection_facet f
          JOIN graphitron_connection_carrier c
            ON c.graph_name = f.graph_name AND c.type_name = f.type_name
           AND c.field_name = f.field_name
        )
        )
        UNION ALL
        (
        (
        SELECT f.graph_name, c.facets_name, f.facet_field_name, c.coordinate, 'FACETS', 'FACET', f.position,
               '[' || f.bucket_type_name || '!]', f.bucket_type_name, FALSE, 1, TRUE,
               'Value counts for this facet, under the connection''s filter minus this facet''s own predicate.'
          FROM graphitron_connection_facet f
          JOIN graphitron_connection_carrier c
            ON c.graph_name = f.graph_name AND c.type_name = f.type_name
           AND c.field_name = f.field_name
        UNION ALL
        SELECT f.graph_name, f.bucket_type_name, 'value', c.coordinate, 'BUCKET', 'VALUE', 0,
               f.value_type_name || CASE WHEN f.value_nullable THEN '' ELSE '!' END, f.value_type_name,
               NOT f.value_nullable, 0, NULL,
               'The facet value; feed it back into the filter to select this bucket.'
          FROM graphitron_connection_facet f
          JOIN graphitron_connection_carrier c
            ON c.graph_name = f.graph_name AND c.type_name = f.type_name
           AND c.field_name = f.field_name
        )
        UNION ALL
        (
        SELECT f.graph_name, f.bucket_type_name, 'count', c.coordinate, 'BUCKET', 'COUNT', 1,
               'Int!', 'Int', TRUE, 0, NULL,
               'The number of items in this bucket.'
          FROM graphitron_connection_facet f
          JOIN graphitron_connection_carrier c
            ON c.graph_name = f.graph_name AND c.type_name = f.type_name
           AND c.field_name = f.field_name
        UNION ALL
        -- The rewrite: the carrier keeps its own coordinate, ordinal and docstring, and returns
        -- the connection under the nullability its author wrote.
        SELECT graph_name, type_name, field_name, coordinate, 'CARRIER', CAST(NULL AS VARCHAR), ordinal,
               connection_name || CASE WHEN non_null THEN '!' ELSE '' END,
               connection_name, non_null, 0, NULL,
               description
          FROM graphitron_connection_carrier
        )
        )
        )) m
