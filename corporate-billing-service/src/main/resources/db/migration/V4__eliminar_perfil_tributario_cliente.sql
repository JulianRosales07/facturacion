-- ==============================================================================
-- Sprint 9: Migración V4 - Eliminación de perfil tributario por cliente (HU-50)
-- El catálogo de conceptos tributarios pasa a ser general, versionado y fechado.
-- ==============================================================================

-- 1. Agregar atributos de versionamiento y etiqueta descriptiva en vigencia_concepto
ALTER TABLE vigencia_concepto ADD COLUMN IF NOT EXISTS nombre VARCHAR(100);
ALTER TABLE vigencia_concepto ADD COLUMN IF NOT EXISTS numero_version INT NOT NULL DEFAULT 1;

-- Actualizar nombres iniciales en vigencias existentes si las hay
UPDATE vigencia_concepto vc
SET nombre = ct.nombre
FROM concepto_tributario ct
WHERE vc.concepto_tributario_id = ct.id AND vc.nombre IS NULL;

-- 2. Respaldo de seguridad y eliminación de la tabla perfil_tributario_cliente
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'perfil_tributario_cliente') THEN
        CREATE TABLE IF NOT EXISTS bkp_perfil_tributario_cliente AS SELECT * FROM perfil_tributario_cliente;
        DROP TABLE IF EXISTS perfil_tributario_cliente CASCADE;
    END IF;
END $$;
