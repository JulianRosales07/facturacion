package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.TaxConcept;
import com.sistemaspalacios.billing.domain.model.TaxConceptValidity;

import java.time.LocalDate;
import java.util.List;

/**
 * Interfaz en español para ServicioConfiguracionTributaria.
 * Especificada en PROMPT_corporate-billing-service.md.
 */
public interface ServicioConfiguracionTributaria extends TaxConfigurationService {

    default List<TaxConcept> listarConceptos() {
        return listConcepts();
    }

    default List<TaxConceptValidity> obtenerVigenciasActivas(LocalDate fecha) {
        return findActiveValiditiesAtDate(fecha);
    }
}
