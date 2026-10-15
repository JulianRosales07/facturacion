package com.sistemaspalacios.billing.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * Tax concept catalog entity (concepto_tributario).
 */
@Entity
@Table(name = "concepto_tributario")
public class TaxConcept {

    @Id
    @Column(name = "id")
    private Short id;

    @Column(name = "codigo", nullable = false, unique = true, length = 20)
    private String code;

    @Column(name = "nombre", nullable = false, length = 80)
    private String name;

    @Column(name = "territorial", nullable = false)
    private boolean territorial;

    protected TaxConcept() {
        // JPA
    }

    public TaxConcept(Short id, String code, String name, boolean territorial) {
        this.id = id;
        this.code = Objects.requireNonNull(code, "code cannot be null");
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.territorial = territorial;
    }

    public Short getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public boolean isTerritorial() { return territorial; }
}
