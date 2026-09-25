package br.com.claricejoias_ws.service;

import br.com.claricejoias_ws.dto.RevendedorCadastroDTO;
import br.com.claricejoias_ws.exceptions.RegraNegocioException;
import br.com.claricejoias_ws.model.Revendedor;
import br.com.claricejoias_ws.repository.RevendedorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RevendedorService {

    private final RevendedorRepository revendedorRepository;
    private final KeycloakUserService keycloakUserService;

    public Optional<Revendedor> findById(String usuarioId){
       return revendedorRepository.findById(usuarioId);
    }

    /**
     * Cria a conta da revendedora no Keycloak e o vínculo local numa tacada só —
     * substitui o fluxo antigo de "criar no Keycloak na mão + colar o UUID aqui".
     */
    @Transactional
    public Revendedor cadastrarComConta(RevendedorCadastroDTO dto) {
        if (revendedorRepository.findBySlug(dto.getSlug()).isPresent()) {
            throw new RegraNegocioException("Este slug já está em uso por outra revendedora.");
        }

        // Cria no Keycloak primeiro: se falhar aqui (ex: e-mail duplicado), nada é
        // gravado no banco local.
        String usuarioId = keycloakUserService.criarUsuarioRevendedora(dto.getEmail(), dto.getSenha(), dto.getNome());

        Revendedor revendedor = new Revendedor();
        revendedor.setId(usuarioId);
        revendedor.setNome(dto.getNome());
        revendedor.setEmail(dto.getEmail());
        revendedor.setSlug(dto.getSlug());
        revendedor.setWhatsappContato(dto.getWhatsappContato());
        if (dto.getPercentualComissao() != null) {
            revendedor.setPercentualComissao(dto.getPercentualComissao());
        }

        return revendedorRepository.save(revendedor);
    }
}
