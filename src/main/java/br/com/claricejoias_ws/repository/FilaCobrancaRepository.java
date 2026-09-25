package br.com.claricejoias_ws.repository;

import br.com.claricejoias_ws.enums.StatusDisparo;
import br.com.claricejoias_ws.model.FilaCobranca;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FilaCobrancaRepository extends JpaRepository<FilaCobranca, Long> {
    boolean existsByClienteIdAndStatus(Long clienteId, StatusDisparo status);
    Optional<FilaCobranca> findFirstByStatusOrderByDataCriacaoAsc(StatusDisparo status);
    List<FilaCobranca> findByStatusOrderByDataCriacaoAsc(StatusDisparo status);
}
