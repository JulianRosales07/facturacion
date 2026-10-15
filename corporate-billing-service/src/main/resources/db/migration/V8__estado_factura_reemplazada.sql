-- ==============================================================================
-- Sprint 10: Migración V8 - Nuevo estado de factura 'REEMPLAZADA'
-- ==============================================================================
-- Esta migración contiene EXCLUSIVAMENTE el ALTER TYPE.
--
-- Motivo: PostgreSQL no permite usar un valor de enum recién agregado dentro de
-- la misma transacción que lo agrega ("unsafe use of new value of enum type").
-- Flyway ejecuta cada migración en su propia transacción, por lo que el DDL que
-- referencia 'REEMPLAZADA' vive en V9.
--
-- NO agregar nada más a este archivo.
-- ==============================================================================

ALTER TYPE estado_factura ADD VALUE IF NOT EXISTS 'REEMPLAZADA';
