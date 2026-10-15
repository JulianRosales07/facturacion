package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.ImportedFileLine;
import com.sistemaspalacios.billing.domain.model.ImportedLineStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for ImportedFileLine (archivo_importado_linea).
 */
@Repository
public interface RepositorioArchivoImportadoLinea extends JpaRepository<ImportedFileLine, UUID> {

    List<ImportedFileLine> findByImportedFileIdOrderByLineNumberAsc(UUID importedFileId);

    List<ImportedFileLine> findByImportedFileIdAndStatus(UUID importedFileId, ImportedLineStatus status);

    Optional<ImportedFileLine> findByImportedFileIdAndLineNumber(UUID importedFileId, int lineNumber);
}
