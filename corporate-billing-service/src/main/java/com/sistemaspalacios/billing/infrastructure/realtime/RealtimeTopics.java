package com.sistemaspalacios.billing.infrastructure.realtime;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canales segmentados: cada NIT tiene el suyo y el panel administrativo uno propio. No existe un
 * canal global con la información de todos los clientes; el de seguridad solo lleva identificadores.
 */
public final class RealtimeTopics {

    public static final String ADMIN_INVOICES = "/topic/admin/invoices";
    /** Cambios en usuarios, roles y permisos: el frontend vuelve a consultar /api/v1/auth/me. */
    public static final String SECURITY = "/topic/security";
    public static final String INVOICES_PREFIX = "/topic/invoices/";

    private static final Pattern NIT_TOPIC = Pattern.compile("^/topic/invoices/([A-Za-z0-9-]{3,40})$");

    private RealtimeTopics() {}

    /** NIT tal como lo usa el portal (sin espacios); solo letras, dígitos y guiones. */
    public static String normalizeNit(String nit) {
        return nit == null ? "" : nit.trim().replaceAll("[^A-Za-z0-9-]", "");
    }

    public static String invoicesOf(String nit) {
        return INVOICES_PREFIX + normalizeNit(nit);
    }

    /** NIT del destino /topic/invoices/{nit}, o vacío si el destino no tiene ese formato. */
    public static Optional<String> nitFromDestination(String destination) {
        if (destination == null) return Optional.empty();
        Matcher matcher = NIT_TOPIC.matcher(destination);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
