package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.TaxConcept;
import com.sistemaspalacios.billing.domain.model.TaxConceptCode;
import com.sistemaspalacios.billing.domain.model.TaxConceptValidity;
import com.sistemaspalacios.billing.domain.vo.Money;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioConceptoTributario;
import com.sistemaspalacios.billing.repository.RepositorioVigenciaConcepto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of TaxConfigurationService and ServicioConfiguracionTributaria.
 * Manages versioned tax concepts and dated concept validities (Sprint 9, HU-50).
 */
@Service
public class TaxConfigurationServiceImpl implements ServicioConfiguracionTributaria {

    private static final Logger log = LoggerFactory.getLogger(TaxConfigurationServiceImpl.class);

    private final RepositorioConceptoTributario repositorioConceptoTributario;
    private final RepositorioVigenciaConcepto repositorioVigenciaConcepto;
    private final RepositorioCliente repositorioCliente;
    private final com.sistemaspalacios.billing.service.security.CurrentUserProvider currentUserProvider;

    @org.springframework.beans.factory.annotation.Autowired
    public TaxConfigurationServiceImpl(
            RepositorioConceptoTributario repositorioConceptoTributario,
            RepositorioVigenciaConcepto repositorioVigenciaConcepto,
            RepositorioCliente repositorioCliente,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.sistemaspalacios.billing.service.security.CurrentUserProvider currentUserProvider
    ) {
        this.repositorioConceptoTributario = repositorioConceptoTributario;
        this.repositorioVigenciaConcepto = repositorioVigenciaConcepto;
        this.repositorioCliente = repositorioCliente;
        this.currentUserProvider = currentUserProvider;
    }

    public TaxConfigurationServiceImpl(
            RepositorioConceptoTributario repositorioConceptoTributario,
            RepositorioVigenciaConcepto repositorioVigenciaConcepto,
            RepositorioCliente repositorioCliente
    ) {
        this(repositorioConceptoTributario, repositorioVigenciaConcepto, repositorioCliente, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxConcept> listConcepts() {
        return repositorioConceptoTributario.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaxConcept> getConceptByCode(String code) {
        return repositorioConceptoTributario.findByCode(code);
    }

    @Override
    @Transactional
    public TaxConceptValidity createValidity(TaxConceptValidity validity) {
        Objects.requireNonNull(validity, "validity cannot be null");
        validate(validity.getName(), validity.getMaxPercentage(), validity.getMinimumBase(), validity.getValidFrom(), validity.getValidTo());
        String currentUser = currentUserProvider != null ? currentUserProvider.getCurrentUsername() : "SISTEMA";
        validity.setCreatedBy(currentUser);
        log.info("Creating tax concept validity '{}' (concept: '{}', rate: {}%) from {} by user '{}'",
                validity.getName(), validity.getConceptCode(), validity.getMaxPercentage(), validity.getValidFrom(), currentUser);
        return repositorioVigenciaConcepto.save(validity);
    }

    @Override
    @Transactional
    public TaxConceptValidity updateValidity(UUID id, TaxConceptValidity updated) {
        TaxConceptValidity existing = repositorioVigenciaConcepto.findById(id)
                .orElseThrow(() -> new NoSuchElementException("TaxConceptValidity not found with id: " + id));

        boolean isActive = updated.getStatus() != null ? updated.isActive() : existing.isActive();
        String name = updated.getName() != null && !updated.getName().isBlank() ? updated.getName() : existing.getName();
        // Editar corrige la vigencia en sitio: el tipo de impuesto y el número de versión no
        // cambian (la versión solo avanza con createNewVersion). Los pagos ya registrados no se
        // ven afectados porque conservan su propio snapshot de porcentaje, base y versión.
        if (updated.getConceptCode() != null && updated.getConceptCode() != existing.getConceptCode()) {
            throw new IllegalArgumentException("No se puede cambiar el tipo de impuesto de una vigencia; cree un concepto nuevo");
        }
        validate(name,
                updated.getMaxPercentage() != null ? updated.getMaxPercentage() : existing.getMaxPercentage(),
                updated.getMinimumBase() != null ? updated.getMinimumBase() : existing.getMinimumBase(),
                updated.getValidFrom() != null ? updated.getValidFrom() : existing.getValidFrom(),
                updated.getValidTo());

        TaxConceptValidity saved = new TaxConceptValidity(
                existing.getId(),
                existing.getConceptCode(),
                name,
                existing.getVersionNumber(),
                updated.getClassification() != null ? updated.getClassification() : existing.getClassification(),
                updated.getMunicipality() != null ? updated.getMunicipality() : existing.getMunicipality(),
                updated.getMinimumBase() != null ? updated.getMinimumBase() : existing.getMinimumBase(),
                updated.getMaxPercentage() != null ? updated.getMaxPercentage() : existing.getMaxPercentage(),
                updated.getValidFrom() != null ? updated.getValidFrom() : existing.getValidFrom(),
                updated.getValidTo(),
                isActive
        );
        saved.setCreatedBy(existing.getCreatedBy());
        String currentUser = currentUserProvider != null ? currentUserProvider.getCurrentUsername() : "SISTEMA";
        saved.setUpdatedBy(currentUser);
        log.info("Updated tax concept validity ID {} ('{}') by user '{}'", id, name, currentUser);

        return repositorioVigenciaConcepto.save(saved);
    }

    @Override
    @Transactional
    public TaxConceptValidity createNewVersion(
            UUID previousValidityId,
            Money newMinimumBase,
            BigDecimal newPercentage,
            LocalDate effectiveFrom
    ) {
        TaxConceptValidity previous = repositorioVigenciaConcepto.findById(previousValidityId)
                .orElseThrow(() -> new NoSuchElementException("TaxConceptValidity not found with id: " + previousValidityId));

        LocalDate validFromDate = effectiveFrom != null ? effectiveFrom : LocalDate.now();
        if (!validFromDate.isAfter(previous.getValidFrom())) {
            throw new IllegalArgumentException("La nueva versión debe empezar después del " + previous.getValidFrom()
                    + "; para corregir la vigencia actual use Editar");
        }
        validate(previous.getName(), newPercentage != null ? newPercentage : previous.getMaxPercentage(),
                newMinimumBase != null ? newMinimumBase : previous.getMinimumBase(), validFromDate, null);
        LocalDate closeDate = validFromDate.minusDays(1);
        previous.closeValidity(closeDate);
        repositorioVigenciaConcepto.save(previous);

        String currentUser = currentUserProvider != null ? currentUserProvider.getCurrentUsername() : "SISTEMA";

        TaxConceptValidity newVersion = new TaxConceptValidity(
                UUID.randomUUID(),
                previous.getConceptCode(),
                previous.getName(),
                previous.getVersionNumber() + 1,
                previous.getClassification(),
                previous.getMunicipality(),
                newMinimumBase != null ? newMinimumBase : previous.getMinimumBase(),
                newPercentage != null ? newPercentage : previous.getMaxPercentage(),
                validFromDate,
                null,
                true
        );
        newVersion.setCreatedBy(currentUser);
        log.info("Created new version {} for tax concept validity '{}' (base: {}, rate: {}%) effective from {} by user '{}'",
                newVersion.getVersionNumber(), newVersion.getName(), newVersion.getMinimumBase(),
                newVersion.getMaxPercentage(), validFromDate, currentUser);

        return repositorioVigenciaConcepto.save(newVersion);
    }

    /** Reglas comunes de creación, edición y versionado. vigencia_concepto.porcentaje_maximo es NUMERIC(7,4). */
    private static void validate(String name, BigDecimal percentage, Money minimumBase, LocalDate validFrom, LocalDate validTo) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException("El nombre del concepto es obligatorio (máximo 100 caracteres)");
        }
        if (percentage == null || percentage.signum() <= 0 || percentage.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("El porcentaje máximo debe ser mayor que 0 y como máximo 100");
        }
        if (percentage.stripTrailingZeros().scale() > com.sistemaspalacios.billing.domain.vo.SelectedWithholding.MAX_PERCENTAGE_SCALE) {
            throw new IllegalArgumentException("El porcentaje máximo admite hasta "
                    + com.sistemaspalacios.billing.domain.vo.SelectedWithholding.MAX_PERCENTAGE_SCALE + " decimales");
        }
        if (minimumBase == null || minimumBase.isNegative()) {
            throw new IllegalArgumentException("La base mínima no puede ser negativa");
        }
        if (validFrom == null) {
            throw new IllegalArgumentException("La fecha de inicio de vigencia es obligatoria");
        }
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("La vigencia no puede terminar antes de empezar");
        }
    }

    @Override
    @Transactional
    public void deactivateValidity(UUID id) {
        TaxConceptValidity validity = repositorioVigenciaConcepto.findById(id)
                .orElseThrow(() -> new NoSuchElementException("TaxConceptValidity not found with id: " + id));
        validity.deactivate();
        repositorioVigenciaConcepto.save(validity);
        log.info("Deactivated tax concept validity {}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxConceptValidity> findActiveValiditiesAtDate(LocalDate date) {
        return repositorioVigenciaConcepto.findActiveAtDate(date);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxConceptValidity> findValiditiesByConceptAndDate(TaxConceptCode code, LocalDate date) {
        return repositorioVigenciaConcepto.findByConceptCodeAndDate(code, date);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxConceptValidity> listAllValidities() {
        return repositorioVigenciaConcepto.findAll();
    }
}
