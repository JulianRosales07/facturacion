package com.sistemaspalacios.billing.web.controller;

import com.sistemaspalacios.billing.service.FacturaActivaYaExisteException;
import com.sistemaspalacios.billing.service.FacturaNoRegenerableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Global exception handler for REST controllers (Sprint 4).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(com.sistemaspalacios.billing.service.InvoicePdfStorageException.class)
    public ResponseEntity<Map<String, Object>> handleInvoicePdfStorage(com.sistemaspalacios.billing.service.InvoicePdfStorageException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "timestamp", Instant.now(),
                "status", HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "error", "Internal Server Error",
                "codigo", "PDF_ALMACENAMIENTO_ILEGIBLE",
                "mensaje", ex.getMessage()
        ));
    }

    @ExceptionHandler({com.sistemaspalacios.billing.service.PaymentConflictException.class,
            org.springframework.dao.DataIntegrityViolationException.class})
    public ResponseEntity<Map<String, Object>> handleConflict(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", 409,
                "codigo", "CONFLICTO", "mensaje", ex instanceof com.sistemaspalacios.billing.service.PaymentConflictException
                        ? ex.getMessage() : "El registro ya existe o entra en conflicto con datos existentes"));
    }

    @ExceptionHandler(com.sistemaspalacios.billing.service.access.AccessConflictException.class)
    public ResponseEntity<Map<String, Object>> handleAccessConflict(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", 409,
                "codigo", "CONFLICTO", "mensaje", ex.getMessage()));
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", 409,
                "codigo", "MODIFICADO_POR_OTRO_USUARIO",
                "mensaje", "El registro fue modificado por otro administrador. Recarga la información e intenta de nuevo."));
    }

    @ExceptionHandler(FacturaActivaYaExisteException.class)
    public ResponseEntity<Map<String, Object>> handleFacturaActivaYaExiste(FacturaActivaYaExisteException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "timestamp", Instant.now(),
                "status", HttpStatus.CONFLICT.value(),
                "error", "Conflict",
                "codigo", "FACTURA_ACTIVA_YA_EXISTE",
                "mensaje", ex.getMessage(),
                "nit", ex.getClientNit(),
                "periodo", ex.getPeriodCode(),
                "facturaExistenteId", ex.getExistingInvoiceId()
        ));
    }

    /**
     * Regeneración rechazada (Sprint 10): el estado de la factura no la admite, o ya tiene
     * CUFE transmitido a la DIAN y falta la confirmación explícita del operador.
     */
    @ExceptionHandler(FacturaNoRegenerableException.class)
    public ResponseEntity<Map<String, Object>> handleFacturaNoRegenerable(FacturaNoRegenerableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "timestamp", Instant.now(),
                "status", HttpStatus.CONFLICT.value(),
                "error", "Conflict",
                "codigo", ex.getReasonCode(),
                "mensaje", ex.getMessage(),
                "facturaId", ex.getInvoiceId(),
                "requiereConfirmacion",
                FacturaNoRegenerableException.REASON_CUFE_TRANSMITIDO.equals(ex.getReasonCode())
        ));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "timestamp", Instant.now(),
                "status", HttpStatus.NOT_FOUND.value(),
                "error", "Not Found",
                "codigo", "RECURSO_NO_ENCONTRADO",
                "mensaje", ex.getMessage()
        ));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "timestamp", Instant.now(),
                "status", HttpStatus.BAD_REQUEST.value(),
                "error", "Bad Request",
                "codigo", "SOLICITUD_INVALIDA",
                "mensaje", ex.getMessage()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .findFirst()
                .orElse("Error de validación en la solicitud");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "timestamp", Instant.now(),
                "status", HttpStatus.BAD_REQUEST.value(),
                "error", "Validation Error",
                "codigo", "DATOS_INVALIDOS",
                "mensaje", msg
        ));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "timestamp", Instant.now(),
                "status", HttpStatus.FORBIDDEN.value(),
                "error", "Forbidden",
                "codigo", "ACCESO_DENEGADO",
                "mensaje", "No tiene permisos suficientes para ejecutar esta operación",
                "detalles", ex.getMessage() != null ? ex.getMessage() : "Permiso insuficiente"
        ));
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(org.springframework.security.core.AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "timestamp", Instant.now(),
                "status", HttpStatus.UNAUTHORIZED.value(),
                "error", "Unauthorized",
                "codigo", "NO_AUTORIZADO",
                "mensaje", "Token de autenticación ausente, expirado o inválido",
                "detalles", ex.getMessage() != null ? ex.getMessage() : "Autenticación requerida"
        ));
    }

    /** Cuerpo ilegible o con tipos inválidos (p. ej. un ID que no es UUID): error del cliente, no del servidor. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "timestamp", Instant.now(),
                "status", HttpStatus.BAD_REQUEST.value(),
                "error", "Bad Request",
                "codigo", "SOLICITUD_INVALIDA",
                "mensaje", "El cuerpo de la solicitud no es válido: verifique el formato de los identificadores y valores enviados"
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "timestamp", Instant.now(),
                "status", HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "error", "Internal Server Error",
                "codigo", "ERROR_INTERNO",
                "mensaje", ex.getMessage() != null ? ex.getMessage() : "Error inesperado en el servidor"
        ));
    }
}
