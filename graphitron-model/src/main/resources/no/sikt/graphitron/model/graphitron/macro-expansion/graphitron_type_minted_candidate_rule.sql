-- What the arms of the type mint state, one row per arm and carrier, before any disagreement between
-- them is resolved. Stored because two rules build on it, the conflict count and the minted type set,
-- and the coinage is its projection. The arm is in the key because one carrier can coin one name
-- through two arms: connectionName: "PageInfo" names the connection after the page info the same
-- carrier states, under two descriptions.
SELECT DISTINCT graph_name, type_name, carrier_coordinate, arm, description
  FROM (SELECT graph_name, connection_name AS type_name, coordinate AS carrier_coordinate,
               'CONNECTION' AS arm, 'A connection to a list of items.' AS description
          FROM graphitron_connection_carrier
         UNION ALL
        SELECT graph_name, edge_name, coordinate, 'EDGE', 'An edge in a connection.'
          FROM graphitron_connection_carrier
         UNION ALL
        SELECT graph_name, 'PageInfo', coordinate, 'PAGE_INFO',
               'Information about pagination in a connection.'
          FROM graphitron_connection_carrier
         UNION ALL
        -- One row per facet, collapsed by the distinct to one per carrier.
        SELECT f.graph_name, c.facets_name, c.coordinate, 'FACETS',
               'Facet value counts for a connection.'
          FROM graphitron_connection_facet f
          JOIN graphitron_connection_carrier c
            ON c.graph_name = f.graph_name AND c.type_name = f.type_name
           AND c.field_name = f.field_name
         UNION ALL
        SELECT f.graph_name, f.bucket_type_name, c.coordinate, 'BUCKET',
               'One facet bucket: a filterable value and its count.'
          FROM graphitron_connection_facet f
          JOIN graphitron_connection_carrier c
            ON c.graph_name = f.graph_name AND c.type_name = f.type_name
           AND c.field_name = f.field_name) m
