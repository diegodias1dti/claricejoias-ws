package br.com.claricejoias_ws.repository;

import br.com.claricejoias_ws.model.WhatsappInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WhatsappInstanceRepository extends JpaRepository<WhatsappInstance,String> {
    Optional<WhatsappInstance> findByUsuarioId(String usuarioId);

    Optional<WhatsappInstance> findByInstanceName(String instanceName);

    Optional<WhatsappInstance> findByRevendedorIsNull();

    boolean existsByRevendedorIsNull();

    boolean existsByUsuarioId(String usuarioId);
}
