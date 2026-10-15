package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.FileStatus;
import com.sistemaspalacios.billing.domain.model.ImportedFile;
import com.sistemaspalacios.billing.domain.model.OrderOrigin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA Repository for ImportedFile (archivo_importado).
 */
@Repository
public interface RepositorioArchivoImportado extends JpaRepository<ImportedFile, UUID> {

    List<ImportedFile> findByStatus(FileStatus status);

    List<ImportedFile> findByOrigin(OrderOrigin origin);

    List<ImportedFile> findAllByOrderByReceivedAtDesc();
}
