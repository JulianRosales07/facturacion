package com.sistemaspalacios.billing.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sistemaspalacios.billing.domain.model.ClientRetentionConfiguration;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.domain.model.RetentionApplicationMode;
import com.sistemaspalacios.billing.domain.model.TaxConceptValidity;
import com.sistemaspalacios.billing.domain.vo.SelectedWithholding;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioConfiguracionRetencionCliente;
import com.sistemaspalacios.billing.repository.RepositorioVigenciaConcepto;
import com.sistemaspalacios.billing.service.security.CurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Configuración de retenciones propia de cada cliente (HU-51). Se guarda únicamente con la
 * autorización expresa del cliente y solo precarga sus pagos futuros: cada pago vuelve a
 * validar conceptos, vigencias y porcentajes máximos con la liquidación normal.
 */
@Service
public class ClientRetentionConfigurationService {

    private static final Logger log = LoggerFactory.getLogger(ClientRetentionConfigurationService.class);

    private final RepositorioConfiguracionRetencionCliente configurations;
    private final RepositorioCliente clients;
    private final RepositorioVigenciaConcepto concepts;
    private final CurrentUserProvider currentUserProvider;

    @Autowired
    public ClientRetentionConfigurationService(RepositorioConfiguracionRetencionCliente configurations,
                                               RepositorioCliente clients,
                                               RepositorioVigenciaConcepto concepts,
                                               @Autowired(required = false) CurrentUserProvider currentUserProvider) {
        this.configurations = configurations;
        this.clients = clients;
        this.concepts = concepts;
        this.currentUserProvider = currentUserProvider;
    }

    @Transactional(readOnly = true)
    public Optional<ClientRetentionConfigurationView> find(String nit) {
        return findClient(nit).flatMap(client -> configurations.findByClientId(client.getId()).map(c -> toView(client, c)));
    }

    /**
     * Guarda (o reemplaza) la configuración del cliente. {@code authorized} debe ser verdadero:
     * sin la aceptación expresa del cliente no se almacena nada.
     */
    @Transactional
    public ClientRetentionConfigurationView save(String nit, boolean authorized, RetentionApplicationMode mode,
                                                 List<PaymentService.ConceptSelection> selections) {
        if (!authorized) {
            throw new IllegalArgumentException("El cliente no autorizó guardar la configuración de retenciones");
        }
        if (mode == null) {
            throw new IllegalArgumentException("Debe indicar applicationMode: PER_INVOICE o CONSOLIDATED");
        }
        if (selections == null || selections.isEmpty()) {
            throw new IllegalArgumentException("La configuración debe incluir al menos una retención con su porcentaje");
        }
        CorporateClient client = findClient(nit)
                .orElseThrow(() -> new NoSuchElementException("Cliente no encontrado con NIT: " + nit));

        // Mismas reglas que al pagar: concepto vigente hoy, porcentaje > 0 y dentro del máximo, un concepto por tipo.
        List<SelectedWithholding> resolved = new ArrayList<>();
        Map<UUID, BigDecimal> byConcept = new LinkedHashMap<>();
        for (PaymentService.ConceptSelection selection : selections) {
            if (selection == null || selection.conceptId() == null) throw new IllegalArgumentException("Concepto tributario inválido");
            TaxConceptValidity concept = concepts.findById(selection.conceptId())
                    .orElseThrow(() -> new IllegalArgumentException("Concepto tributario inexistente: " + selection.conceptId()));
            if (!concept.isApplicable(LocalDate.now(), null, null)) {
                throw new IllegalArgumentException("Concepto tributario fuera de vigencia: " + concept.getName());
            }
            if (byConcept.put(concept.getId(), selection.percentage()) != null) throw new IllegalArgumentException("Conceptos repetidos");
            resolved.add(new SelectedWithholding(concept, selection.percentage()));
        }
        SelectedWithholding.requireOnePerType(resolved);

        ClientRetentionConfiguration configuration = configurations.findByClientId(client.getId())
                .orElseGet(() -> new ClientRetentionConfiguration(client.getId()));
        String user = currentUserProvider != null ? currentUserProvider.getCurrentUsername() : "PASARELA";
        configuration.authorize(mode, byConcept, user);
        ClientRetentionConfiguration saved = configurations.save(configuration);
        log.info("Configuración de retenciones autorizada para NIT {} (modo {}, {} conceptos) por '{}'",
                client.getNit(), mode, byConcept.size(), user);
        return toView(client, saved);
    }

    /** El cliente retira su autorización: los próximos pagos vuelven a pedir los porcentajes. */
    @Transactional
    public void delete(String nit) {
        findClient(nit).flatMap(client -> configurations.findByClientId(client.getId())).ifPresent(c -> {
            configurations.delete(c);
            log.info("Configuración de retenciones eliminada para el cliente {}", c.getClientId());
        });
    }

    private Optional<CorporateClient> findClient(String nit) {
        if (nit == null || nit.isBlank()) return Optional.empty();
        return clients.findByNit(nit.trim().replaceAll("\\s+", ""));
    }

    private ClientRetentionConfigurationView toView(CorporateClient client, ClientRetentionConfiguration configuration) {
        LocalDate today = LocalDate.now();
        List<ConfiguredConcept> items = configuration.getItems().stream().map(item -> {
            TaxConceptValidity concept = concepts.findById(item.getConceptId()).orElse(null);
            boolean usable = concept != null && concept.isApplicable(today, null, null)
                    && item.getPercentage().compareTo(concept.getMaxPercentage()) <= 0;
            return new ConfiguredConcept(
                    item.getConceptId(),
                    concept != null ? concept.getName() : null,
                    concept != null ? concept.getConceptCode().name() : null,
                    item.getPercentage(),
                    concept != null ? concept.getMaxPercentage() : null,
                    usable);
        }).toList();
        return new ClientRetentionConfigurationView(client.getNit(), configuration.getApplicationMode(),
                configuration.getAuthorizedAt(), configuration.getAuthorizedBy(), items);
    }

    public record ClientRetentionConfigurationView(
            @JsonProperty("nit") String nit,
            @JsonProperty("applicationMode") RetentionApplicationMode applicationMode,
            @JsonProperty("autorizadoEn") Instant authorizedAt,
            @JsonProperty("autorizadoPor") String authorizedBy,
            @JsonProperty("conceptos") List<ConfiguredConcept> concepts
    ) {}

    /**
     * {@code utilizable} es falso si el concepto ya no está vigente o su máximo bajó por debajo del
     * porcentaje guardado; la pasarela no lo precarga y el cliente debe volver a ingresarlo.
     */
    public record ConfiguredConcept(
            @JsonProperty("conceptId") UUID conceptId,
            @JsonProperty("nombre") String name,
            @JsonProperty("tipo") String type,
            @JsonProperty("percentage") BigDecimal percentage,
            @JsonProperty("porcentajeMaximo") BigDecimal maxPercentage,
            @JsonProperty("utilizable") boolean usable
    ) {}
}
