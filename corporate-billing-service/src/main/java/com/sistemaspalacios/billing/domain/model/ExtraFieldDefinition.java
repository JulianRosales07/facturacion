package com.sistemaspalacios.billing.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;

/**
 * Metadata definition for dynamic extra invoice fields (EAV: definicion_campo_extra).
 */
@Entity
@Table(name = "definicion_campo_extra")
public class ExtraFieldDefinition {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "clave_campo", nullable = false, unique = true, length = 50)
    private String fieldKey;

    @Column(name = "etiqueta", nullable = false, length = 100)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_dato", nullable = false, length = 20)
    private ExtraFieldDataType dataType = ExtraFieldDataType.TEXTO;

    @Column(name = "obligatorio", nullable = false)
    private boolean mandatory = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private RecordStatus status = RecordStatus.ACTIVO;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant createdAt;

    protected ExtraFieldDefinition() {
        // JPA
    }

    public ExtraFieldDefinition(UUID id, String fieldKey, String label, ExtraFieldDataType dataType, boolean mandatory) {
        this.id = Objects.requireNonNull(id, "ID must not be null");
        this.fieldKey = Objects.requireNonNull(fieldKey, "fieldKey must not be null").trim().toLowerCase();
        this.label = Objects.requireNonNull(label, "label must not be null").trim();
        this.dataType = dataType != null ? dataType : ExtraFieldDataType.TEXTO;
        this.mandatory = mandatory;
        this.status = RecordStatus.ACTIVO;
    }

    public void validateValue(String value) {
        if (mandatory && (value == null || value.trim().isEmpty())) {
            throw new IllegalArgumentException("El campo extra '" + fieldKey + "' es obligatorio.");
        }
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        switch (dataType) {
            case NUMERO -> {
                try {
                    Double.parseDouble(value.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("El valor '" + value + "' para el campo extra '" + fieldKey + "' no es un número válido.");
                }
            }
            case FECHA -> {
                try {
                    LocalDate.parse(value.trim());
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("El valor '" + value + "' para el campo extra '" + fieldKey + "' no es una fecha válida (YYYY-MM-DD).");
                }
            }
            case BOOLEANO -> {
                String v = value.trim().toLowerCase();
                if (!v.equals("true") && !v.equals("false") && !v.equals("si") && !v.equals("no") && !v.equals("1") && !v.equals("0")) {
                    throw new IllegalArgumentException("El valor '" + value + "' para el campo extra '" + fieldKey + "' no es un booleano válido.");
                }
            }
            case TEXTO -> {
                // Cualquier texto es válido
            }
        }
    }

    public UUID getId() {
        return id;
    }

    public String getFieldKey() {
        return fieldKey;
    }

    public String getLabel() {
        return label;
    }

    public ExtraFieldDataType getDataType() {
        return dataType;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public RecordStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void deactivate() {
        this.status = RecordStatus.INACTIVO;
    }

    public void activate() {
        this.status = RecordStatus.ACTIVO;
    }
}
