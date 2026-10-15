package com.sistemaspalacios.billing.service.siigo;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * Extractor y decodificador del código QR real impreso en facturas oficiales en PDF.
 * <p>
 * Estrategia de extracción:
 * 1. Inspecciona imágenes XObject embebidas en el PDF.
 * 2. Si el QR está dibujado por vectores o trazos directos (caso Siigo estándar),
 *    renderiza la primera página a alta resolución (300 DPI).
 * 3. Enfoca y recorta la región superior derecha donde Siigo ubica el código QR.
 * 4. Decodifica el payload real usando ZXing (sin OCR).
 * 5. Conserva el contenido exacto y genera/extrae la imagen PNG del QR decodificado.
 */
@Component
public class PdfQrCodeExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfQrCodeExtractor.class);

    public record ExtractedQr(String content, byte[] imageBytes, String status) {
        public boolean hasContent() {
            return content != null && !content.isBlank();
        }

        public String cufe() {
            if (content == null) return null;
            var matcher = java.util.regex.Pattern.compile("CUFE:\\s*([a-fA-F0-9]{64,128})").matcher(content);
            if (matcher.find()) return matcher.group(1);
            var urlMatcher = java.util.regex.Pattern.compile("documentkey=([a-fA-F0-9]{64,128})", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(content);
            if (urlMatcher.find()) return urlMatcher.group(1);
            return null;
        }

        public String invoiceNumber() {
            if (content == null) return null;
            var matcher = java.util.regex.Pattern.compile("NumFac:\\s*([A-Za-z0-9_-]+)").matcher(content);
            if (matcher.find()) return matcher.group(1);
            return null;
        }

        public static ExtractedQr notFound() {
            return new ExtractedQr(null, null, "NO_ENCONTRADO");
        }

        public static ExtractedQr found(String content, byte[] imageBytes) {
            return new ExtractedQr(content, imageBytes, "EXTRAIDO");
        }
    }

    public ExtractedQr extractQr(File pdfFile) {
        if (pdfFile == null || !pdfFile.exists() || !pdfFile.isFile()) {
            return ExtractedQr.notFound();
        }
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            return extractFromDocument(doc, pdfFile.getName());
        } catch (Exception e) {
            log.warn("No se pudo procesar PDF para extracción de QR {}: {}", pdfFile.getName(), e.getMessage());
            return ExtractedQr.notFound();
        }
    }

    public ExtractedQr extractQr(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return ExtractedQr.notFound();
        }
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            return extractFromDocument(doc, "in-memory");
        } catch (Exception e) {
            log.warn("No se pudo procesar stream PDF para extracción de QR: {}", e.getMessage());
            return ExtractedQr.notFound();
        }
    }

    public ExtractedQr extractQr(InputStream inputStream) {
        if (inputStream == null) return ExtractedQr.notFound();
        try {
            return extractQr(inputStream.readAllBytes());
        } catch (Exception e) {
            log.warn("Error leyendo stream para QR: {}", e.getMessage());
            return ExtractedQr.notFound();
        }
    }

    private ExtractedQr extractFromDocument(PDDocument doc, String sourceName) {
        if (doc.getNumberOfPages() == 0) {
            return ExtractedQr.notFound();
        }

        // Intento 1: Buscar imágenes embebidas en la primera página
        try {
            PDPage page0 = doc.getPage(0);
            PDResources resources = page0.getResources();
            if (resources != null) {
                for (COSName name : resources.getXObjectNames()) {
                    if (resources.isImageXObject(name)) {
                        PDImageXObject imgObj = (PDImageXObject) resources.getXObject(name);
                        BufferedImage bImg = imgObj.getImage();
                        if (bImg != null) {
                            String text = decodeBufferedImage(bImg);
                            if (text != null && !text.isBlank()) {
                                log.debug("QR encontrado como imagen embebida en {} [{}]", sourceName, name.getName());
                                byte[] pngBytes = toPngBytes(bImg);
                                return ExtractedQr.found(text, pngBytes);
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("No se pudieron leer imágenes embebidas en {}: {}", sourceName, ex.getMessage());
        }

        // Intento 2: Renderizar página a 300 DPI y analizar
        try {
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage pageImg = renderer.renderImageWithDPI(0, 300);

            // Sub-intento 2a: Región superior derecha de Siigo (x: 58% a 100%, y: 0 a 30%)
            int w = pageImg.getWidth();
            int h = pageImg.getHeight();
            int cropX = (int) (w * 0.58);
            int cropY = 0;
            int cropW = w - cropX;
            int cropH = (int) (h * 0.32);

            BufferedImage topQuadrant = pageImg.getSubimage(cropX, cropY, cropW, cropH);
            DecodeResult cropResult = decodeWithPoints(topQuadrant);
            if (cropResult != null && cropResult.text != null && !cropResult.text.isBlank()) {
                byte[] qrPng = extractCroppedSquare(topQuadrant, cropResult.points);
                if (qrPng == null) {
                    qrPng = generateQrPng(cropResult.text);
                }
                log.info("QR decodificado exitosamente de región superior derecha en {}", sourceName);
                return ExtractedQr.found(cropResult.text, qrPng);
            }

            // Sub-intento 2b: Mitad superior completa
            BufferedImage topHalf = pageImg.getSubimage(0, 0, w, (int) (h * 0.50));
            DecodeResult topHalfResult = decodeWithPoints(topHalf);
            if (topHalfResult != null && topHalfResult.text != null && !topHalfResult.text.isBlank()) {
                byte[] qrPng = extractCroppedSquare(topHalf, topHalfResult.points);
                if (qrPng == null) {
                    qrPng = generateQrPng(topHalfResult.text);
                }
                log.info("QR decodificado exitosamente de mitad superior en {}", sourceName);
                return ExtractedQr.found(topHalfResult.text, qrPng);
            }

            // Sub-intento 2c: Página completa
            DecodeResult fullResult = decodeWithPoints(pageImg);
            if (fullResult != null && fullResult.text != null && !fullResult.text.isBlank()) {
                byte[] qrPng = extractCroppedSquare(pageImg, fullResult.points);
                if (qrPng == null) {
                    qrPng = generateQrPng(fullResult.text);
                }
                log.info("QR decodificado exitosamente de página completa en {}", sourceName);
                return ExtractedQr.found(fullResult.text, qrPng);
            }
        } catch (Exception ex) {
            log.warn("Error renderizando página de {} para lectura de QR: {}", sourceName, ex.getMessage());
        }

        return ExtractedQr.notFound();
    }

    private record DecodeResult(String text, ResultPoint[] points) {}

    private DecodeResult decodeWithPoints(BufferedImage image) {
        try {
            LuminanceSource source = new BufferedImageLuminanceSource(image);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            Result result = new MultiFormatReader().decode(bitmap, hints);
            return new DecodeResult(result.getText(), result.getResultPoints());
        } catch (Exception e) {
            return null;
        }
    }

    private String decodeBufferedImage(BufferedImage image) {
        DecodeResult res = decodeWithPoints(image);
        return res != null ? res.text : null;
    }

    /**
     * Recorta el cuadrado original del QR aprovechando los puntos de anclaje detectados por ZXing.
     */
    private byte[] extractCroppedSquare(BufferedImage baseImage, ResultPoint[] points) {
        if (points == null || points.length < 3) return null;
        try {
            float minX = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE;
            float minY = Float.MAX_VALUE;
            float maxY = Float.MIN_VALUE;

            for (ResultPoint p : points) {
                if (p == null) continue;
                if (p.getX() < minX) minX = p.getX();
                if (p.getX() > maxX) maxX = p.getX();
                if (p.getY() < minY) minY = p.getY();
                if (p.getY() > maxY) maxY = p.getY();
            }

            int padding = 20;
            int x = Math.max(0, (int) minX - padding);
            int y = Math.max(0, (int) minY - padding);
            int w = Math.min(baseImage.getWidth() - x, (int) (maxX - minX) + padding * 2);
            int h = Math.min(baseImage.getHeight() - y, (int) (maxY - minY) + padding * 2);

            if (w <= 10 || h <= 10) return null;

            BufferedImage sub = baseImage.getSubimage(x, y, w, h);
            return toPngBytes(sub);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Regenera una imagen PNG nítida a partir del payload oficial exactamente extraído del QR.
     */
    public byte[] generateQrPng(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, 240, 240);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("No se pudo generar PNG del QR: {}", e.getMessage());
            return null;
        }
    }

    private byte[] toPngBytes(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
