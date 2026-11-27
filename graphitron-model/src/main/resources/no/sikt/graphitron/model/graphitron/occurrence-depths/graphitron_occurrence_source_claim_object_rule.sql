SELECT DISTINCT o.graph_name, o.coordinate, 'object' AS rule, 'OBJECT' AS kind, hc.class_name
  FROM graphitron_occurrence o
  JOIN graphitron_occurrence p ON p.graph_name = o.graph_name AND p.coordinate = o.parent_coordinate
  JOIN graphitron_field pf ON pf.graph_name = p.graph_name AND pf.coordinate = p.element_coordinate
  JOIN graphitron_field_handed_class hc
    ON hc.graph_name = pf.graph_name AND hc.type_name = pf.type_name AND hc.field_name = pf.field_name
  JOIN graphitron_field cf ON cf.graph_name = o.graph_name AND cf.coordinate = o.element_coordinate
 WHERE o.kind = 'FIELD' AND p.kind = 'FIELD'
   AND NOT EXISTS (SELECT 1 FROM graphitron_occurrence_source po
                     WHERE po.graph_name = p.graph_name AND po.coordinate = p.coordinate AND po.kind = 'OBJECT')
   AND NOT EXISTS (SELECT 1 FROM graphitron_errors_field ef
                     WHERE ef.graph_name = cf.graph_name AND ef.type_name = cf.type_name
                       AND ef.field_name = cf.field_name)
