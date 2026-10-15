package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.ReceivedPdf;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReceivedPdfRepository extends JpaRepository<ReceivedPdf, UUID> {

    Optional<ReceivedPdf> findBySha256(String sha256);

    Optional<ReceivedPdf> findByOriginalNameAndSizeBytesAndModifiedAtSource(
            String originalName,
            long sizeBytes,
            Instant modifiedAtSource
    );

    List<ReceivedPdf> findByStatus(String status);

    Page<ReceivedPdf> findByStatus(String status, Pageable pageable);

    long countByStatus(String status);
}
