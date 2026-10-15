package com.sistemaspalacios.billing.repository;

import com.sistemaspalacios.billing.domain.model.CarteraSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository para el corte de cartera de Siigo (cartera_snapshot).
 */
@Repository
public interface RepositorioCarteraSnapshot extends JpaRepository<CarteraSnapshot, UUID> {

    /** Permite saltar un archivo ya ingerido en un ciclo anterior. */
    Optional<CarteraSnapshot> findBySha256Hash(String sha256Hash);

    boolean existsBySha256Hash(String sha256Hash);

    Optional<CarteraSnapshot> findTopByOrderByGeneratedAtDesc();

    List<CarteraSnapshot> findTop20ByOrderByGeneratedAtDesc();
}
