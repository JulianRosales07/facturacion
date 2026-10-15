package com.sistemaspalacios.billing.service.siigo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sistemaspalacios.billing.domain.model.BillingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Robust parser for Siigo confirmation flat files and structured returns.
 * Supports semicolon (;), pipe (|), comma (,) separated lines or JSON payloads.
 */
@Component
public class SiigoConfirmationParser {

    private static final Logger log = LoggerFactory.getLogger(SiigoConfirmationParser.class);
    private final ObjectMapper objectMapper;

    public SiigoConfirmationParser() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
    }

    public SiigoConfirmationParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
    }

    /**
     * Parses the raw content of a Siigo confirmation file.
     *
     * @param content Raw file string content
     * @return List of parsed confirmation records
     */
    public List<SiigoConfirmationRecord> parse(String content) {
        List<SiigoConfirmationRecord> records = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return records;
        }

        String trimmed = content.trim();

        // Check if payload is JSON
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            try {
                return parseJson(trimmed);
            } catch (Exception e) {
                log.debug("Content started with JSON chars but failed JSON parsing, trying line parser: {}", e.getMessage());
            }
        }

        // Line by line delimited parser
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmedLine = line.trim();
                if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                    continue; // Skip comments and empty lines
                }

                // Skip header line if detected
                if (lineNumber == 1 && (trimmedLine.toUpperCase().contains("NUMERO") || trimmedLine.toUpperCase().contains("CONSECUTIVO"))) {
                    continue;
                }

                SiigoConfirmationRecord record = parseDelimitedLine(trimmedLine);
                if (record != null) {
                    records.add(record);
                }
            }
        } catch (Exception e) {
            log.error("Error reading delimited lines from Siigo confirmation file: {}", e.getMessage(), e);
        }

        return records;
    }

    private List<SiigoConfirmationRecord> parseJson(String json) throws Exception {
        List<SiigoConfirmationRecord> list = new ArrayList<>();
        if (json.startsWith("[")) {
            List<Map<String, Object>> rawList = objectMapper.readValue(json, new TypeReference<>() {});
            for (Map<String, Object> map : rawList) {
                list.add(fromMap(map));
            }
        } else {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            list.add(fromMap(map));
        }
        return list;
    }

    private SiigoConfirmationRecord fromMap(Map<String, Object> map) {
        String num = getString(map, "numeroFactura", "numero", "documentNumber", "consecutivo");
        String nit = getString(map, "nitCliente", "nit", "clientNit");
        String cufe = getString(map, "cufe", "cude");
        String status = getString(map, "estadoEnvio", "estado", "deliveryStatus");
        String typeStr = getString(map, "tipoFacturacion", "tipo", "billingType");
        BigDecimal total = getBigDecimal(map, "valorTotal", "total", "valor");

        BillingType type = null;
        if (typeStr != null && !typeStr.isBlank()) {
            try {
                type = BillingType.valueOf(typeStr.toUpperCase());
            } catch (Exception ignored) {}
        }

        return new SiigoConfirmationRecord(num, type, nit, cufe, status != null ? status : "Envío exitoso", total);
    }

    /**
     * Parses standard delimiters: semicolon (;), pipe (|), or tab (\t).
     * Expected layout variants:
     * 1) NUMERO;VALOR;ESTADO;CUFE;NIT
     * 2) NUMERO;VALOR;ESTADO;CUFE
     * 3) NUMERO;CUFE;NIT;ESTADO;VALOR
     */
    private SiigoConfirmationRecord parseDelimitedLine(String line) {
        String delimiter = ";";
        if (line.contains(";")) {
            delimiter = ";";
        } else if (line.contains("|")) {
            delimiter = "\\|";
        } else if (line.contains("\t")) {
            delimiter = "\t";
        } else if (line.contains(",")) {
            delimiter = ",";
        }

        String[] tokens = line.split(delimiter, -1);
        for (int i = 0; i < tokens.length; i++) {
            tokens[i] = tokens[i].trim();
        }

        if (tokens.length < 2) {
            log.warn("Line does not have enough columns to parse: '{}'", line);
            return null;
        }

        String docNumber = tokens[0];
        BigDecimal total = null;
        String status = "Envío exitoso";
        String cufe = null;
        String nit = null;
        BillingType billingType = null;

        // Detect prefix if docNumber contains FESP or FEMG
        if (docNumber.toUpperCase().contains("FESP") || docNumber.toUpperCase().startsWith("F1")) {
            billingType = BillingType.F1;
        } else if (docNumber.toUpperCase().contains("FEMG") || docNumber.toUpperCase().startsWith("F20")) {
            billingType = BillingType.F20;
        }

        // Parse remaining columns by heuristic
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isEmpty()) continue;

            // CUFE detection (hex or alphanumeric with optional hyphens >= 24 chars)
            if (token.length() >= 24 && token.matches("^[a-zA-Z0-9-]{24,}$")) {
                cufe = token;
            } else if (token.equalsIgnoreCase("Envío exitoso") || token.equalsIgnoreCase("Exitoso")
                    || token.equalsIgnoreCase("ENVIADO") || token.equalsIgnoreCase("RECHAZADO")
                    || token.toLowerCase().contains("envio")) {
                status = token;
            } else if (token.matches("^\\d{7,10}(-\\d)?$")) { // NIT pattern
                nit = token;
            } else if (total == null && token.matches("^\\d+(\\.\\d+)?$")) {
                try {
                    total = new BigDecimal(token);
                } catch (Exception ignored) {}
            }
        }

        return new SiigoConfirmationRecord(docNumber, billingType, nit, cufe, status, total);
    }

    private String getString(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            if (map.containsKey(k) && map.get(k) != null) {
                return map.get(k).toString();
            }
        }
        return null;
    }

    private BigDecimal getBigDecimal(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            if (map.containsKey(k) && map.get(k) != null) {
                try {
                    return new BigDecimal(map.get(k).toString());
                } catch (Exception ignored) {}
            }
        }
        return null;
    }
}
