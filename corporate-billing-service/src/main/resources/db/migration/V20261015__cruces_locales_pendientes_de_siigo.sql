-- Separa el saldo oficial del recaudo local todavía no reflejado en el corte.
ALTER TABLE factura ADD COLUMN cruce_local_pendiente_siigo NUMERIC(14,2) NOT NULL DEFAULT 0;
ALTER TABLE factura ADD COLUMN ultimo_cruce_local_en TIMESTAMPTZ;
ALTER TABLE factura ADD CONSTRAINT ck_factura_cruce_local_no_negativo CHECK (cruce_local_pendiente_siigo >= 0);
