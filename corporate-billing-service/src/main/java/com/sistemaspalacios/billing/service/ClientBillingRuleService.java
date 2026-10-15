package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.ClientBillingRule;
import com.sistemaspalacios.billing.domain.model.CorporateClient;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Regla de facturación (ordinaria o especial corporativa) y día de corte de cada cliente. */
@Service
public class ClientBillingRuleService {

    private final RepositorioCliente repositorioCliente;

    public ClientBillingRuleService(RepositorioCliente repositorioCliente) {
        this.repositorioCliente = repositorioCliente;
    }

    @Transactional(readOnly = true)
    public List<CorporateClient> listClients() {
        return repositorioCliente.findAll();
    }

    @Transactional
    public CorporateClient updateRule(UUID clientId, ClientBillingRule billingRule, Integer corporateCutoffDay,
                                      String corporateRuleDetails) {
        CorporateClient client = repositorioCliente.findById(clientId)
                .orElseThrow(() -> new NoSuchElementException("Cliente no encontrado con ID: " + clientId));

        client.setBillingRule(billingRule);

        if (billingRule == ClientBillingRule.ESPECIAL_CORPORATIVA) {
            if (corporateCutoffDay != null) {
                client.setCorporateCutoffDay(corporateCutoffDay);
            } else if (client.getCorporateCutoffDay() == null) {
                // Si aún no está configurado, se permite null pero se reporta como incompleta
                client.setCorporateCutoffDay(null);
            }
        } else {
            // Regla ordinaria usa corte 2; se mantiene el día especial o se deja opcional
            if (corporateCutoffDay != null) {
                client.setCorporateCutoffDay(corporateCutoffDay);
            }
        }

        if (corporateRuleDetails != null) {
            client.setCorporateRuleDetails(corporateRuleDetails);
        }

        return repositorioCliente.saveAndFlush(client);
    }
}
