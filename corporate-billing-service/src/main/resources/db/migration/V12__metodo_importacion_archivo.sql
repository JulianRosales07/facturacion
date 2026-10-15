-- Migration V12: Agregar columna metodo_importacion a archivo_importado
ALTER TABLE archivo_importado ADD COLUMN IF NOT EXISTS metodo_importacion VARCHAR(50) DEFAULT 'EXCEL';
