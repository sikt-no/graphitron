SELECT DISTINCT o.graph_name, o.coordinate, 'argumentPath' AS rule, 'TABLE' AS kind, j.target_source_name AS table_source_name, j.target_schema AS table_schema, j.target_table AS table_name
  FROM graphitron_occurrence o
  JOIN graphitron_occurrence p ON p.graph_name = o.graph_name AND p.coordinate = o.parent_coordinate
  JOIN graphitron_field pf ON pf.graph_name = p.graph_name AND pf.coordinate = p.element_coordinate
  JOIN graphitron_join_path j ON j.graph_name = p.graph_name AND j.coordinate = p.coordinate
 WHERE o.kind = 'FIELD_ARGUMENT' AND EXISTS (SELECT 1 FROM graphitron_join_path_claim wpc
                 WHERE wpc.graph_name = pf.graph_name AND wpc.element_coordinate = pf.coordinate
                   AND wpc.claim_kind = 'FIELD' AND wpc.last_place >= wpc.first_place)
