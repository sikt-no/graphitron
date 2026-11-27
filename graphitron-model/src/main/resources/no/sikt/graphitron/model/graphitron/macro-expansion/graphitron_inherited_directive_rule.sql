-- Each tag the first carrier carries, the carrier of least coordinate, that every carrier carries.
WITH placed (graph_name, type_name, value_sdl, first_carrier, first_ordinal) AS (
  SELECT f.graph_name, f.type_name, f.value_sdl, f.coordinate, MIN(f.ordinal)
    FROM graphitron_carrier_directive f
   WHERE f.directive_name = 'tag' AND f.value_sdl IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM graphitron_minted_coinage e
                      WHERE e.graph_name = f.graph_name AND e.type_name = f.type_name
                        AND e.coordinate < f.coordinate)
     AND NOT EXISTS (SELECT 1 FROM graphitron_minted_coinage mc
                      WHERE mc.graph_name = f.graph_name AND mc.type_name = f.type_name
                        AND NOT EXISTS (SELECT 1 FROM graphitron_carrier_directive d
                                         WHERE d.graph_name = mc.graph_name AND d.type_name = mc.type_name
                                           AND d.coordinate = mc.coordinate AND d.directive_name = 'tag'
                                           AND d.value_sdl = f.value_sdl))
   GROUP BY f.graph_name, f.type_name, f.value_sdl, f.coordinate)
-- A tag every carrier carries, in the first carrier's order: placed by how many other tags the first
-- carrier carries before it that every carrier carries too, dense from zero.
SELECT p.graph_name, p.type_name, 'tag' AS directive_name,
       CAST((SELECT COUNT(DISTINCT b.value_sdl) FROM graphitron_carrier_directive b
              WHERE b.graph_name = p.graph_name AND b.type_name = p.type_name
                AND b.coordinate = p.first_carrier AND b.directive_name = 'tag'
                AND b.ordinal < p.first_ordinal AND b.value_sdl IS NOT NULL
                AND b.value_sdl <> p.value_sdl
                AND NOT EXISTS (SELECT 1 FROM graphitron_minted_coinage mc
                                 WHERE mc.graph_name = b.graph_name AND mc.type_name = b.type_name
                                   AND NOT EXISTS (SELECT 1 FROM graphitron_carrier_directive d
                                                    WHERE d.graph_name = mc.graph_name
                                                      AND d.type_name = mc.type_name
                                                      AND d.coordinate = mc.coordinate
                                                      AND d.directive_name = 'tag'
                                                      AND d.value_sdl = b.value_sdl))) AS INT) AS ordinal,
       p.value_sdl
  FROM placed p
UNION ALL
-- @shareable where any carrier is.
SELECT DISTINCT d.graph_name, d.type_name, 'shareable', 0, CAST(NULL AS VARCHAR)
  FROM graphitron_carrier_directive d
 WHERE d.directive_name = 'shareable'
