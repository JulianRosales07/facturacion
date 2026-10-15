package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.TaxConcept;
import com.sistemaspalacios.billing.domain.model.TaxConceptCode;
import com.sistemaspalacios.billing.domain.model.TaxConceptValidity;
import com.sistemaspalacios.billing.domain.vo.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for Tax Configuration management (HU-25, HU-28).
 * Manages tax concepts, dated concept validities, and corporate client tax profiles.
 */
public interface TaxConfigurationService {

    // --- Conceptos Tributarios ---
    List<TaxConcept> listConcepts();
    Optional<TaxConcept> getConceptByCode(String code);

    // --- Vigencias de Conceptos (fechadas y versionadas - HU-50) ---
    TaxConceptValidity createValidity(TaxConceptValidity validity);
    TaxConceptValidity updateValidity(UUID id, TaxConceptValidity updated);
    TaxConceptValidity createNewVersion(UUID previousValidityId, Money newMinimumBase, BigDecimal newPercentage, LocalDate effectiveFrom);
    void deactivateValidity(UUID id);
    List<TaxConceptValidity> findActiveValiditiesAtDate(LocalDate date);
    List<TaxConceptValidity> findValiditiesByConceptAndDate(TaxConceptCode code, LocalDate date);
    List<TaxConceptValidity> listAllValidities();
}
