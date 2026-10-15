package com.sistemaspalacios.billing.domain.vo;

import com.sistemaspalacios.billing.domain.model.SourceChannel;

import java.util.Objects;
import java.util.UUID;

/**
 * Quién diligenció una selección de retenciones y desde dónde. La hora NO viaja aquí: la fija el
 * servidor al registrar. En la pasarela no hay usuario: se identifica al cliente por su NIT y la
 * sesión del navegador, sin inventar un usuario.
 *
 * @param sessionId identificador de la sesión de selección (lo genera el frontend; agrupa el historial)
 * @param ipAddress IP de origen (dato de trazabilidad, nunca fuente de identidad)
 */
public record SelectionAudit(
        SourceChannel channel,
        UUID userId,
        String username,
        String userFullName,
        String clientNit,
        String sessionId,
        String ipAddress,
        String userAgent
) {
    public SelectionAudit {
        Objects.requireNonNull(channel, "channel cannot be null");
        username = clip(username, 150);
        userFullName = clip(userFullName, 200);
        clientNit = clip(clientNit, 30);
        sessionId = clip(sessionId, 64);
        ipAddress = clip(ipAddress, 45);
        userAgent = clip(userAgent, 500);
    }

    /** Sin contexto de la petición (llamadas internas): solo se sabe que llegó por API. */
    public static SelectionAudit unknown() {
        return new SelectionAudit(SourceChannel.API, null, null, null, null, null, null, null);
    }

    public SelectionAudit withClientNit(String nit) {
        return new SelectionAudit(channel, userId, username, userFullName, nit, sessionId, ipAddress, userAgent);
    }

    public SelectionAudit withSession(String session) {
        return new SelectionAudit(channel, userId, username, userFullName, clientNit, session, ipAddress, userAgent);
    }

    /** Actor legible: el usuario del panel, o el NIT del cliente en la pasarela. */
    public String actorLabel() {
        if (username != null) return username;
        if (clientNit != null) return "NIT " + clientNit;
        return "No identificado";
    }

    private static String clip(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
