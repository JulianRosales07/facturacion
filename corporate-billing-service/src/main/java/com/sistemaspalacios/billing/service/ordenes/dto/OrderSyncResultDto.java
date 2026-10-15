package com.sistemaspalacios.billing.service.ordenes.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Result summary of an Order synchronization execution.
 */
public class OrderSyncResultDto {

    private final String periodCode;
    private int clientsProcessed;
    private int clientsCreated;
    private int sitesProcessed;
    private int sitesCreated;
    private int updatesProcessed;
    private int installationFeesCreated;
    private int errorCount;
    private final List<String> messages = new ArrayList<>();

    public OrderSyncResultDto(String periodCode) {
        this.periodCode = periodCode;
    }

    public void incrementClientsProcessed() { this.clientsProcessed++; }
    public void incrementClientsCreated() { this.clientsCreated++; }
    public void incrementSitesProcessed() { this.sitesProcessed++; }
    public void incrementSitesCreated() { this.sitesCreated++; }
    public void incrementUpdatesProcessed() { this.updatesProcessed++; }
    public void incrementInstallationFeesCreated() { this.installationFeesCreated++; }
    public void incrementErrorCount() { this.errorCount++; }
    public void addMessage(String msg) { this.messages.add(msg); }

    public String getPeriodCode() { return periodCode; }
    public int getClientsProcessed() { return clientsProcessed; }
    public int getClientsCreated() { return clientsCreated; }
    public int getSitesProcessed() { return sitesProcessed; }
    public int getSitesCreated() { return sitesCreated; }
    public int getUpdatesProcessed() { return updatesProcessed; }
    public int getInstallationFeesCreated() { return installationFeesCreated; }
    public int getErrorCount() { return errorCount; }
    public List<String> getMessages() { return messages; }

    @Override
    public String toString() {
        return "OrderSyncResultDto{" +
                "periodCode='" + periodCode + '\'' +
                ", clientsProcessed=" + clientsProcessed +
                ", clientsCreated=" + clientsCreated +
                ", sitesProcessed=" + sitesProcessed +
                ", sitesCreated=" + sitesCreated +
                ", updatesProcessed=" + updatesProcessed +
                ", installationFeesCreated=" + installationFeesCreated +
                ", errorCount=" + errorCount +
                '}';
    }
}
