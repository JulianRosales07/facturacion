package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.domain.model.AdjustmentDocument;
import com.sistemaspalacios.billing.service.CreditNoteService;
import com.sistemaspalacios.billing.web.dto.NotaCreditoResponseDto;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * REST controller for direct credit note / adjustment document queries (Sprint 5, Sprint 8).
 */
@RestController
@RequestMapping({"/api/v1/notas-credito", "/notas-credito"})
@PreAuthorize("hasAuthority('INVOICES_VIEW')")
public class CreditNoteController {

    private final CreditNoteService creditNoteService;

    public CreditNoteController(CreditNoteService creditNoteService) {
        this.creditNoteService = Objects.requireNonNull(creditNoteService, "creditNoteService cannot be null");
    }

    /**
     * Obtener el detalle de una nota de crédito por su ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<NotaCreditoResponseDto> getCreditNoteById(@PathVariable("id") UUID id) {
        AdjustmentDocument doc = creditNoteService.getCreditNoteById(id);
        return ResponseEntity.ok(NotaCreditoResponseDto.fromDomain(doc));
    }
}
