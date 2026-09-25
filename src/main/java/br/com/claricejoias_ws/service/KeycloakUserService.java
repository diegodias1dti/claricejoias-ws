package br.com.claricejoias_ws.service;

import br.com.claricejoias_ws.exceptions.RegraNegocioException;
import br.com.claricejoias_ws.model.Cliente;
import br.com.claricejoias_ws.model.Lead;
import br.com.claricejoias_ws.model.Revendedor;
import br.com.claricejoias_ws.repository.ClienteRepository;
import br.com.claricejoias_ws.repository.LeadRepository;
import br.com.claricejoias_ws.repository.RevendedorRepository;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakUserService {

    private final Keycloak keycloak;
    private final ClienteRepository clienteRepository;
    private final LeadRepository leadRepository;
    private final EvolutionApiService evolutionApiService;
    private final RevendedorRepository revendedorRepository;
    private final WhatsAppService whatsAppService;

    private final String REALM_NAME = "claricejoias";

    /**
     * Fluxo para Clientes: Cadastro direto com senha definida no modal da loja.
     * Agora recebe o visitorId para aproveitar os dados do Lead!
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    // Garante que se o banco falhar, o processo reverta com segurança
    public String criarUsuarioCliente(String email, String senha, String nomeCompleto, String whatsapp, String revendedorId) {
        String whatsappLimpo = (whatsapp != null) ? whatsapp.replaceAll("[^0-9]", "") : null;
        boolean isLojaMatriz = (revendedorId == null || revendedorId.trim().isEmpty());

        // 1. Verifica se já existe o cliente NESTA loja específica
        boolean existeNaLoja = isLojaMatriz
                ? clienteRepository.existsByWhatsappAndRevendedorIsNull(whatsappLimpo)
                : clienteRepository.existsByWhatsappAndRevendedorId(whatsappLimpo, revendedorId);

        if (existeNaLoja) {
            throw new RegraNegocioException("Este número de WhatsApp já possui cadastro nesta loja. Por favor, faça login.");
        }

        String userId;

        // 2. Verifica globalmente se o usuário JÁ TEM uma identidade no Keycloak (já comprou em outra filial)
        boolean existeEmOutraLoja = clienteRepository.existsByWhatsapp(whatsappLimpo);

        if (existeEmOutraLoja) {
            // Se ele já existe no ecossistema, nós reaproveitamos a identidade do Keycloak!
            // (Você não tenta criar no Keycloak de novo, só pega o ID que já está no seu banco)
            Cliente clienteAntigo = clienteRepository.findFirstByWhatsapp(whatsappLimpo)
                    .orElseThrow(() -> new RuntimeException("Inconsistência de dados."));
            userId = clienteAntigo.getUsuarioId();

            log.info("Cliente {} já tinha login. Reaproveitando ID: {}", nomeCompleto, userId);
        } else {
            // Se é totalmente novo, cria no Keycloak
            UserRepresentation user = criarRepresentacaoBasica(email, nomeCompleto, whatsappLimpo);

            // Opcional: Adicionar o atributo da loja no Keycloak como discutimos na etapa anterior
            if (!isLojaMatriz) {
                Map<String, List<String>> attrs = user.getAttributes();
                if (attrs == null) attrs = new HashMap<>();
                attrs.put("revendedor_id", Collections.singletonList(revendedorId));
                user.setAttributes(attrs);
            }

            Response response = keycloak.realm(REALM_NAME).users().create(user);
            userId = processarResposta(response, senha, "cliente");
        }

        // 3. Sempre cria um NOVO PERFIL DE CLIENTE no PostgreSQL isolado para esta loja
        Cliente novoClienteDaLoja = new Cliente();
        novoClienteDaLoja.setUsuarioId(userId);
        novoClienteDaLoja.setEmail(email);
        novoClienteDaLoja.setWhatsapp(whatsappLimpo);
        novoClienteDaLoja.setNome(nomeCompleto);

        if (!isLojaMatriz) {
            Revendedor revendedor = new Revendedor();
            revendedor.setId(revendedorId);
            novoClienteDaLoja.setRevendedor(revendedor);
        }

        clienteRepository.save(novoClienteDaLoja);

        return userId;
    }


    /**
     * Fluxo para Revendedoras: cria a conta no Keycloak (login por e-mail, senha
     * provisória obrigando troca no 1º acesso) e atribui a role REVENDEDORA.
     * Retorna o ID gerado, que vira o ID do registro local em Revendedor.
     * Sem @Transactional aqui de propósito: não toca no banco local, só na API do Keycloak.
     */
    public String criarUsuarioRevendedora(String email, String senha, String nomeCompleto) {
        UserRepresentation user = new UserRepresentation();

        // Diferente do cliente (username = whatsapp): a revendedora loga com e-mail/senha.
        user.setUsername(email);
        user.setEmail(email);
        user.setEnabled(true);
        user.setEmailVerified(false);
        user.setRequiredActions(Collections.singletonList("UPDATE_PASSWORD"));

        String[] nomes = nomeCompleto.trim().split(" ", 2);
        user.setFirstName(nomes[0]);
        if (nomes.length > 1) {
            user.setLastName(nomes[1]);
        }

        Response response = keycloak.realm(REALM_NAME).users().create(user);
        return processarResposta(response, senha, "REVENDEDORA");
    }

    public void vincularVisitanteAoNovoUsuario(String visitorId, String userId, Cliente cliente) {
        if (visitorId == null) return;

        leadRepository.findFirstByVisitorIdOrderByIdDesc(visitorId)
                .ifPresent(lead -> {
                    // Garante que o PC não pertence a outra conta logada
                    if (lead.getUsuarioId() == null) {
                        lead.setUsuarioId(userId);
                        lead.setNome(cliente.getNome());
                        lead.setWhatsapp(cliente.getWhatsapp());
                        lead.setEmail(cliente.getEmail());
                        leadRepository.save(lead);
                    }
                });
    }


    public void redefinirSenhaTemporaria(String userId, String novaSenha) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(novaSenha);
        credential.setTemporary(true); // OBRIGA a trocar no login

        keycloak.realm(REALM_NAME).users().get(userId).resetPassword(credential);
    }

    /**
     * Fluxo para Funcionários (ADM): Cadastro sem senha.
     */
//    public void criarUsuarioFuncionario(String email, String nomeCompleto) {
//        UserRepresentation user = criarRepresentacaoBasica(email, nomeCompleto);
//        user.setRequiredActions(Collections.singletonList("UPDATE_PASSWORD"));
//        Response response = keycloak.realm(REALM_NAME).users().create(user);
//        processarResposta(response, null, "ADMIN");
//    }
    public void recuperarSenhaViaWhatsApp(String whatsapp, String revendedorId) {
        String whatsappLimpo = whatsapp.replaceAll("[^0-9]", "");

        Revendedor revendedor = revendedorRepository.findById(revendedorId)
                .orElseThrow(() -> new RegraNegocioException("Revendedor não encontrado."));

        // 1. Verifica se o cliente existe
        Cliente cliente = clienteRepository.findByWhatsappAndRevendedorId(whatsappLimpo, revendedor.getId())
                .orElseThrow(() -> new RegraNegocioException("Número de WhatsApp não encontrado no sistema."));

        // 2. Gera a nova senha provisória de 6 dígitos
        String pinProvisorio = String.format("%06d", new Random().nextInt(999999));

        // 3. Atualiza a senha direto no Keycloak (como Temporária)
        // O userId do Keycloak você salvou na entidade Cliente!
        redefinirSenhaTemporaria(cliente.getUsuarioId(), pinProvisorio);

        // 4. Monta a mensagem
        String mensagem = String.format(
                "Olá *%s*! 🔒\n\nVocê solicitou a recuperação de senha na Clarice Joias.\n" +
                        "Sua nova senha de acesso provisória é: *%s*\n\n" +
                        "Acesse o site e faça login com ela. O sistema pedirá para você criar uma nova senha definitiva logo em seguida.",
                cliente.getNome(), pinProvisorio
        );

        // 5. JOGA PARA A FILA! (Retorno instantâneo para o Front-End)
        whatsAppService.enfileirarMensagemSistema(whatsappLimpo, mensagem, revendedor);
    }

    public void recuperarSenhaViaWhatsAppMatriz(String whatsapp) {
        String whatsappLimpo = whatsapp.replaceAll("[^0-9]", "");


        // 1. Verifica se o cliente existe
        Cliente cliente = clienteRepository.findByWhatsappAndRevendedorIsNull(whatsappLimpo)
                .orElseThrow(() -> new RegraNegocioException("Número de WhatsApp não encontrado no sistema."));

        // 2. Gera a nova senha provisória de 6 dígitos
        String pinProvisorio = String.format("%06d", new Random().nextInt(999999));

        // 3. Atualiza a senha direto no Keycloak (como Temporária)
        // O userId do Keycloak você salvou na entidade Cliente!
        redefinirSenhaTemporaria(cliente.getUsuarioId(), pinProvisorio);

        // 4. Monta a mensagem
        String mensagem = String.format(
                "Olá *%s*! 🔒\n\nVocê solicitou a recuperação de senha na Clarice Joias.\n" +
                        "Sua nova senha de acesso provisória é: *%s*\n\n" +
                        "Acesse o site e faça login com ela. O sistema pedirá para você criar uma nova senha definitiva logo em seguida.",
                cliente.getNome(), pinProvisorio
        );

        // 5. JOGA PARA A FILA! (Retorno instantâneo para o Front-End)
        whatsAppService.enfileirarMensagemSistema(whatsappLimpo, mensagem, null);
    }


    private UserRepresentation criarRepresentacaoBasica(String email, String nomeCompleto, String whatsapp) {
        UserRepresentation user = new UserRepresentation();

        // Username agora é o WhatsApp (melhor para o login via Evolution API)
        user.setUsername(whatsapp);
        user.setEmail(email);
        user.setEnabled(true);
        user.setEmailVerified(false);

        // A MÁGICA: Obriga o usuário a mudar a senha no primeiro login
        user.setRequiredActions(java.util.Collections.singletonList("UPDATE_PASSWORD"));

        // Separação do nome
        String[] nomes = nomeCompleto.trim().split(" ", 2);
        user.setFirstName(nomes[0]);
        if (nomes.length > 1) {
            user.setLastName(nomes[1]);
        }

        // Adiciona o WhatsApp nos atributos para consulta posterior
        Map<String, List<String>> attributes = new HashMap<>();
        attributes.put("whatsapp", java.util.Collections.singletonList(whatsapp));
        user.setAttributes(attributes);

        return user;
    }

    /**
     * Agora este método retorna o ID do Keycloak criado (String)
     */
    private String processarResposta(Response response, String senha, String roleName) {
        if (response.getStatus() == 201) {
            String userId = response.getLocation().getPath().replaceAll(".*/([^/]+)$", "$1");

            // Valida se a senha não é nula E não está em branco/vazia
            if (senha != null && !senha.trim().isEmpty()) {
                definirSenha(userId, senha);
            }

            // Boa prática: validar a role também para evitar erros no Keycloak
            if (roleName != null && !roleName.trim().isEmpty()) {
                atribuirRole(userId, roleName);
            }

            // Retorna o ID gerado para ser usado na criação do Cliente local
            return userId;

        } else if (response.getStatus() == 409) {
            throw new RegraNegocioException("Este e-mail já está cadastrado.");
        } else {
            throw new RegraNegocioException("Falha ao criar usuário no Keycloak. Tente novamente mais tarde.");
        }
    }

    private void definirSenha(String userId, String senha) {
        CredentialRepresentation credential = new CredentialRepresentation();

        // Mudamos para TRUE: Isso indica que a senha é apenas um PIN provisório
        credential.setTemporary(true);

        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(senha);

        keycloak.realm(REALM_NAME).users().get(userId).resetPassword(credential);
    }

    private void atribuirRole(String userId, String roleName) {
        try {
            RoleRepresentation role = keycloak.realm(REALM_NAME).roles().get(roleName).toRepresentation();
            keycloak.realm(REALM_NAME).users().get(userId).roles().realmLevel().add(Collections.singletonList(role));
        } catch (Exception e) {
            log.warn("Erro ao atribuir role {}. Certifique-se que ela existe no Keycloak.", roleName, e);
        }
    }
}