package com.sistemaspalacios.billing.repository;
import com.sistemaspalacios.billing.domain.model.IncomingFileHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface IncomingFileHistoryRepository extends JpaRepository<IncomingFileHistory, UUID> {
    boolean existsBySha256AndStatusIn(String sha256, Collection<String> statuses);
    Optional<IncomingFileHistory> findFirstBySha256OrderByProcessedAtDesc(String sha256);
}
