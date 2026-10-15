package com.sistemaspalacios.billing.service;

import com.sistemaspalacios.billing.domain.model.BillingType;
import com.sistemaspalacios.billing.repository.RepositorioCliente;
import com.sistemaspalacios.billing.repository.RepositorioSede;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class ClientDirectoryService {
    private final RepositorioCliente clients;
    private final RepositorioSede sites;
    public ClientDirectoryService(RepositorioCliente clients, RepositorioSede sites) {
        this.clients = clients; this.sites = sites;
    }
    @Transactional(readOnly = true)
    public List<ClientEntry> list() {
        return clients.findAll().stream().map(c -> {
            var branches = sites.findByClientId(c.getId()).stream().map(s -> new SiteEntry(s.getId(),
                    s.getExternalOrderCode(), s.getSiteName(), s.getAddress(), s.getMonthlyValue().amount(),
                    s.getStatus().name(), s.getNic())).toList();
            return new ClientEntry(c.getId(), c.getNit(), c.getBusinessName(), branches.size(), c.getBillingType(),
                    c.getMunicipality(), c.getTaxAddress(), c.getBillingEmail(), c.getPhone(),
                    branches.stream().map(SiteEntry::monthlyValue).reduce(BigDecimal.ZERO, BigDecimal::add), branches);
        }).toList();
    }
    public record ClientEntry(UUID id, String nit, @com.fasterxml.jackson.annotation.JsonProperty("razonSocial") String businessName, int branchesCount, @com.fasterxml.jackson.annotation.JsonProperty("tipoFacturacion") BillingType billingType,
            @com.fasterxml.jackson.annotation.JsonProperty("municipio") String municipality, @com.fasterxml.jackson.annotation.JsonProperty("direccionFiscal") String taxAddress, @com.fasterxml.jackson.annotation.JsonProperty("emailFacturacion") String billingEmail, @com.fasterxml.jackson.annotation.JsonProperty("telefono") String phone,
            BigDecimal totalMonthlyValue, @com.fasterxml.jackson.annotation.JsonProperty("sedes") List<SiteEntry> sites) {}
    public record SiteEntry(UUID id, @com.fasterxml.jackson.annotation.JsonProperty("codigoOrden") String orderCode, @com.fasterxml.jackson.annotation.JsonProperty("nombreSede") String siteName, @com.fasterxml.jackson.annotation.JsonProperty("direccion") String address,
            @com.fasterxml.jackson.annotation.JsonProperty("valorMensual") BigDecimal monthlyValue, @com.fasterxml.jackson.annotation.JsonProperty("estado") String status, String nic) {}
}
