package com.sistemaspalacios.billing.domain.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Configuración de retenciones propia de un cliente (configuracion_retencion_cliente).
 * Solo existe si el cliente autorizó expresamente reutilizarla en sus pagos futuros; sirve para
 * precargar el modo, los conceptos y los porcentajes, que el cliente puede modificar en cada pago.
 */
@Entity
@Table(name = "configuracion_retencion_cliente")
public class ClientRetentionConfiguration {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "cliente_corporativo_id", nullable = false, unique = true)
    private UUID clientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "modo_aplicacion", nullable = false, length = 20)
    private RetentionApplicationMode applicationMode;

    @Column(name = "autorizado_en", nullable = false)
    private Instant authorizedAt;

    @Column(name = "autorizado_por", length = 150)
    private String authorizedBy;

    @Column(name = "actualizado_en", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "configuration", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<ClientRetentionConfigurationItem> items = new ArrayList<>();

    protected ClientRetentionConfiguration() {
        // JPA
    }

    public ClientRetentionConfiguration(UUID clientId) {
        this.id = UUID.randomUUID();
        this.clientId = Objects.requireNonNull(clientId, "clientId cannot be null");
    }

    /** Reemplaza la configuración completa con una nueva autorización del cliente. */
    public void authorize(RetentionApplicationMode mode, Map<UUID, BigDecimal> percentagesByConcept, String authorizedBy) {
        this.applicationMode = Objects.requireNonNull(mode, "applicationMode cannot be null");
        // Se actualizan en sitio los conceptos que siguen: borrar y volver a insertar el mismo
        // concepto violaría la unicidad (configuracion_id, vigencia_concepto_id) en el flush.
        items.removeIf(item -> !percentagesByConcept.containsKey(item.getConceptId()));
        percentagesByConcept.forEach((conceptId, percentage) -> items.stream()
                .filter(item -> item.getConceptId().equals(conceptId)).findFirst()
                .ifPresentOrElse(item -> item.changePercentage(percentage),
                        () -> items.add(new ClientRetentionConfigurationItem(this, conceptId, percentage))));
        this.authorizedAt = Instant.now();
        this.updatedAt = this.authorizedAt;
        this.authorizedBy = authorizedBy != null && authorizedBy.length() > 150 ? authorizedBy.substring(0, 150) : authorizedBy;
    }

    public UUID getId() { return id; }
    public UUID getClientId() { return clientId; }
    public RetentionApplicationMode getApplicationMode() { return applicationMode; }
    public Instant getAuthorizedAt() { return authorizedAt; }
    public String getAuthorizedBy() { return authorizedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<ClientRetentionConfigurationItem> getItems() { return Collections.unmodifiableList(items); }
}
