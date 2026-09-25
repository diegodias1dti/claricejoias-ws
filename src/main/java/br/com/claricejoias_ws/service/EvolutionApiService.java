package br.com.claricejoias_ws.service;

import br.com.claricejoias_ws.dto.InstanceCreateRequest;
import br.com.claricejoias_ws.exceptions.RegraNegocioException;
import br.com.claricejoias_ws.model.Revendedor;
import br.com.claricejoias_ws.model.WhatsappInstance;
import br.com.claricejoias_ws.repository.RevendedorRepository;
import br.com.claricejoias_ws.repository.WhatsappInstanceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvolutionApiService {

    @Value("${evolution.api.url}")
    private String evolutionUrl;

    @Value("${evolution.api.key}")
    private String apikey;

    @Value("${evolution.api.instance}")
    private String instanciaGlobal;

    // Base pública onde este backend está acessível, usada para montar a URL
    // do endpoint /api/arquivos/view/{objectName} que a Evolution API busca.
    @Value("${app.public.url:http://localhost:8080}")
    private String appPublicUrl;

    // Timeouts explícitos: sem isso, uma Evolution API travada prende para sempre
    // a thread do @RabbitListener que consome a fila de disparos (WhatsAppWorker).
    private final RestTemplate restTemplate = criarRestTemplateComTimeout();

    private static RestTemplate criarRestTemplateComTimeout() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }
    private final WhatsappInstanceRepository whatsappInstanceRepository;
    private final ObjectMapper objectMapper;
    private final RevendedorRepository revendedorRepository;

    // ========================================================================
    // ENVIO DE MENSAGENS (USADOS PELO RABBITMQ WORKER)
    // ========================================================================

    public boolean enviarTexto(String numeroDestino, String mensagem, String instanciaUso) {
        String url = evolutionUrl + "/message/sendText/" + instanciaUso;
        String numeroFormatado = formatarNumero(numeroDestino);

        Map<String, Object> body = Map.of(
                "number", numeroFormatado,
                "textMessage", Map.of("text", mensagem),
                "options", Map.of("delay", 1200) // Simula digitação humana
        );

        try {
            log.info("Enviando texto via Evolution API para {} usando a instância: {}", numeroFormatado, instanciaUso);
            restTemplate.postForObject(url, new HttpEntity<>(body, getHeaders()), String.class);

            // Se a requisição HTTP retornar sucesso (2xx), retorna true
            return true;
        } catch (Exception e) {
            log.error("Falha ao enviar texto para {}: {}", numeroFormatado, e.getMessage());

            // Repassa a exceção para quem chamou poder tratar (Essencial para o RabbitMQ)
            throw e;
        }
    }

    public void enviarMediaBase64(String numeroDestino, String legenda, String mediaBase64OuUrl, String instanciaUso) {
        String url = evolutionUrl + "/message/sendMedia/" + instanciaUso;
        String numeroFormatado = formatarNumero(numeroDestino);

        Map<String, Object> body = Map.of(
                "number", numeroFormatado,
                "mediaMessage", Map.of(
                        "mediatype", "image",
                        "caption", legenda != null ? legenda : "",
                        // A Evolution API aceita tanto base64 quanto uma URL http neste campo
                        "media", mediaBase64OuUrl
                ),
                "options", Map.of("delay", 1200)
        );

        log.info("Enviando imagem via Evolution API para {} usando a instância: {}", numeroFormatado, instanciaUso);
        restTemplate.postForObject(url, new HttpEntity<>(body, getHeaders()), String.class);
    }

    // Usado pelo WhatsAppWorker: recebe o objectName gravado no MinIO (não a imagem em si)
    // e monta a URL pública de /api/arquivos/view/{objectName} para a Evolution API baixar.
    public boolean enviarImagem(String numeroDestino, String legenda, String objectName, String instanciaUso) {
        try {
            String mediaUrl = appPublicUrl + "/api/arquivos/view/" + objectName;
            enviarMediaBase64(numeroDestino, legenda, mediaUrl, instanciaUso);
            return true;
        } catch (Exception e) {
            log.error("Erro ao enviar imagem via Evolution API para {}: {}", numeroDestino, e.getMessage());
            return false;
        }
    }

    // Mantido para compatibilidade com partes antigas do sistema que não passam a instância
    public boolean enviarMensagemTexto(String numeroDestino, String mensagem, String instancia) {
        try {
            // Retorna o resultado (true) do método enviarTexto
            return this.enviarTexto(numeroDestino, mensagem, instancia);
        } catch (Exception e) {
            log.error("Erro ao enviar WhatsApp pelo Evolution API para {}: {}", numeroDestino, e.getMessage());
            // Aqui você pode retornar false para não quebrar a aplicação onde esse método é chamado
            return false;

            // Ou, se esse método for chamado direto pelo Worker do RabbitMQ,
            // o ideal é manter o "throw e;" para que a fila saiba do erro:
            // throw e;
        }
    }

    // ========================================================================
    // GERENCIAMENTO DE INSTÂNCIAS (REVENDEDORES)
    // ========================================================================

    public ResponseEntity<String> createInstanceForUser(String usuarioId, String username, boolean isAdmin) {

        // 1. O usuário já possui um vínculo local de instância? Antes de bloquear a criação,
        // confirmamos que essa instância ainda existe DE VERDADE na Evolution API — senão um
        // vínculo órfão (ex: a Evolution API reiniciou/perdeu estado) trava pra sempre a
        // criação de uma instância nova, e ela nem aparece na lista pra você apagar (a lista
        // também é montada consultando a Evolution API).
        Optional<WhatsappInstance> minhaInstancia = whatsappInstanceRepository.findByUsuarioId(usuarioId);
        if (minhaInstancia.isPresent()) {
            if (instanciaAindaExisteNaEvolution(minhaInstancia.get().getInstanceName())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("{\"message\": \"Usuário já possui uma instância ativa.\"}");
            }
            log.warn("Vínculo local da instância {} está órfão (não existe mais na Evolution API). Removendo antes de criar uma nova.",
                    minhaInstancia.get().getInstanceName());
            whatsappInstanceRepository.delete(minhaInstancia.get());
        }

        // 2. Regra de Negócio: Garantir apenas UMA instância sem revendedor (Se for Admin) —
        // mesma checagem de órfão antes de bloquear.
        if (isAdmin) {
            Optional<WhatsappInstance> instanciaGlobal = whatsappInstanceRepository.findByRevendedorIsNull();
            if (instanciaGlobal.isPresent()) {
                if (instanciaAindaExisteNaEvolution(instanciaGlobal.get().getInstanceName())) {
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body("{\"message\": \"Já existe uma instância global ativa no sistema.\"}");
                }
                log.warn("Vínculo local da instância global {} está órfão. Removendo antes de criar uma nova.",
                        instanciaGlobal.get().getInstanceName());
                whatsappInstanceRepository.delete(instanciaGlobal.get());
            }
        }

        // 3. Preparação dos dados (Com proteção de tamanho de String)
        String cleanUsername = username.replaceAll("[^a-zA-Z0-9]", "");
        int idLength = Math.min(5, usuarioId.length()); // Evita StringIndexOutOfBoundsException
        String instanceName = "rev_" + cleanUsername + "_" + usuarioId.substring(0, idLength);
        String uniqueToken = java.util.UUID.randomUUID().toString();

        Map<String, Object> payload = Map.of(
                "instanceName", instanceName,
                "token", uniqueToken,
                "qrcode", true,
                "integration", "WHATSAPP-BAILEYS"
        );

        String url = evolutionUrl + "/instance/create";

        // 4. Chamada à Evolution API e Persistência
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(payload, getHeaders()), String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                WhatsappInstance novaInstancia = new WhatsappInstance();
                novaInstancia.setUsuarioId(usuarioId);
                novaInstancia.setInstanceName(instanceName);
                novaInstancia.setUniqueToken(uniqueToken);

                // ATENÇÃO: Se NÃO for admin, precisamos vincular o revendedor!
                 if (!isAdmin) {
                     Revendedor revendedor = revendedorRepository.findById(usuarioId).orElseThrow(()-> new RegraNegocioException(""));
                     novaInstancia.setRevendedor(revendedor);
                 }

                whatsappInstanceRepository.save(novaInstancia);
                log.info("Instância {} criada com sucesso para o usuário {}", instanceName, usuarioId);
            }

            return response;

        } catch (RestClientException e) {
            log.error("Erro na comunicação com a Evolution API para o usuário {}: {}", usuarioId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"message\": \"Erro de comunicação com o servidor do WhatsApp.\"}");
        }
    }

    /**
     * Confirma na própria Evolution API se uma instância com esse nome ainda existe lá —
     * usado antes de bloquear a criação de uma nova instância por causa de um vínculo local
     * que pode estar órfão (a Evolution API perdeu o estado, por exemplo após um restart).
     * Em caso de erro de comunicação, assume que NÃO existe: é melhor deixar a usuária tentar
     * criar de novo do que travá-la pra sempre num vínculo que talvez nem exista mais.
     */
    private boolean instanciaAindaExisteNaEvolution(String instanceName) {
        try {
            String baseUrl = evolutionUrl.endsWith("/") ? evolutionUrl.substring(0, evolutionUrl.length() - 1) : evolutionUrl;
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/instance/fetchInstances", HttpMethod.GET, new HttpEntity<>(getHeaders()), String.class
            );

            String body = response.getBody();
            if (body == null || body.isBlank()) return false;

            JsonNode allInstancesNode = objectMapper.readTree(body);
            if (!allInstancesNode.isArray()) return false;

            for (JsonNode node : allInstancesNode) {
                JsonNode nameNode = node.path("instance").path("instanceName");
                if (nameNode.isMissingNode()) {
                    nameNode = node.path("instanceName");
                }
                if (!nameNode.isMissingNode() && instanceName.equals(nameNode.asText())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("Não foi possível confirmar na Evolution API se a instância {} ainda existe ({}). Assumindo que não existe.",
                    instanceName, e.getMessage());
            return false;
        }
    }

    public ResponseEntity<String> connectInstanceByUser(String usuarioId) {
        Optional<WhatsappInstance> instanceOpt = whatsappInstanceRepository.findByUsuarioId(usuarioId);

        if (instanceOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("{\"message\": \"Nenhuma instância encontrada para este usuário.\"}");
        }

        String instanceName = instanceOpt.get().getInstanceName();
        String url = evolutionUrl + "/instance/connect/" + instanceName;

        try {
            return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(getHeaders()), String.class);
        } catch (Exception e) {
            log.error("Erro ao buscar QR Code para instância {}: {}", instanceName, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"message\": \"Erro ao buscar QR Code.\"}");
        }
    }

    public ResponseEntity<String> deleteInstanceByUser(String usuarioId) {
        Optional<WhatsappInstance> instanceOpt = whatsappInstanceRepository.findByUsuarioId(usuarioId);

        if (instanceOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("{\"message\": \"Nenhuma instância encontrada para este usuário.\"}");
        }

        WhatsappInstance instance = instanceOpt.get();
        String instanceName = instance.getInstanceName();
        String url = evolutionUrl + "/instance/delete/" + instanceName;

        // O vínculo local (usuarioId -> instanceName) é só um registro interno nosso —
        // não é a fonte da verdade sobre o estado real do WhatsApp. Clicou em apagar, ele
        // some do nosso banco sempre, não importa o que a Evolution API responder (sucesso,
        // 404 porque já não existe mais lá, erro de configuração, ou até fora do ar).
        // Deixar essa decisão condicionada à resposta da Evolution API foi exatamente o que
        // travava pra sempre a criação de uma instância nova ("Já existe uma instância global
        // ativa no sistema"), porque o vínculo local nunca era liberado.
        whatsappInstanceRepository.delete(instance);

        try {
            ResponseEntity<String> respostaEvolution = restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(getHeaders()), String.class);
            log.info("Instância {} removida do vínculo local (status Evolution API: {})", instanceName, respostaEvolution.getStatusCode());
            return ResponseEntity.ok("{\"message\": \"Instância removida com sucesso.\"}");
        } catch (Exception e) {
            log.warn("Vínculo local da instância {} removido, mas não foi possível confirmar a exclusão na Evolution API ({}). Verifique manualmente se necessário.", instanceName, e.getMessage());
            return ResponseEntity.ok("{\"message\": \"Vínculo local removido. Não foi possível confirmar a exclusão na Evolution API — verifique manualmente se necessário.\"}");
        }
    }

    public ResponseEntity<String> logoutInstanceByUser(String usuarioId) {
        Optional<WhatsappInstance> instanceOpt = whatsappInstanceRepository.findByUsuarioId(usuarioId);

        if (instanceOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("{\"message\": \"Nenhuma instância encontrada para este usuário.\"}");
        }

        String instanceName = instanceOpt.get().getInstanceName();
        String url = evolutionUrl + "/instance/logout/" + instanceName;

        try {
            log.info("Desconectando instância: {}", instanceName);
            return restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(getHeaders()), String.class);
        } catch (Exception e) {
            log.error("Erro ao desconectar instância {}: {}", instanceName, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"message\": \"Erro ao desconectar a instância no servidor.\"}");
        }
    }

    public ResponseEntity<String> fetchInstances(String usuarioId) {
        Optional<WhatsappInstance> instanceOpt = whatsappInstanceRepository.findByUsuarioId(usuarioId);

        if (instanceOpt.isEmpty()) {
            return ResponseEntity.ok("[]");
        }

        String userInstanceName = instanceOpt.get().getInstanceName();
        String baseUrl = evolutionUrl.endsWith("/") ? evolutionUrl.substring(0, evolutionUrl.length() - 1) : evolutionUrl;
        String url = baseUrl + "/instance/fetchInstances";

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(getHeaders()), String.class);
            String responseBody = response.getBody();

            if (responseBody == null || responseBody.trim().isEmpty()) {
                return ResponseEntity.ok("[]");
            }

            JsonNode allInstancesNode = objectMapper.readTree(responseBody);
            ArrayNode filteredInstances = objectMapper.createArrayNode();

            if (allInstancesNode.isArray()) {
                for (JsonNode node : allInstancesNode) {
                    JsonNode nameNode = node.path("instance").path("instanceName");
                    if (nameNode.isMissingNode()) {
                        nameNode = node.path("instanceName");
                    }

                    if (!nameNode.isMissingNode() && userInstanceName.equals(nameNode.asText())) {
                        filteredInstances.add(node);
                        break;
                    }
                }
            }

            return ResponseEntity.ok(objectMapper.writeValueAsString(filteredInstances));

        } catch (Exception e) {
            log.error("Erro ao buscar a instância do usuário na API: {}", e.getMessage());
            throw new RuntimeException("Erro ao buscar a instância", e);
        }
    }

    // Listagem SEM filtro, para o painel do ADMIN gerenciar a instância de qualquer revendedora.
    // A autorização (ser ADMIN) é checada no controller antes de chamar este método.
    public ResponseEntity<String> fetchAllInstancesForAdmin() {
        String baseUrl = evolutionUrl.endsWith("/") ? evolutionUrl.substring(0, evolutionUrl.length() - 1) : evolutionUrl;
        String url = baseUrl + "/instance/fetchInstances";

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(getHeaders()), String.class);
            String responseBody = response.getBody();
            return ResponseEntity.ok(responseBody == null || responseBody.trim().isEmpty() ? "[]" : responseBody);
        } catch (Exception e) {
            log.error("Erro ao buscar todas as instâncias na API: {}", e.getMessage());
            throw new RuntimeException("Erro ao buscar as instâncias", e);
        }
    }

    // ========================================================================
    // GERENCIAMENTO DE INSTÂNCIAS GENÉRICAS (MÉTODOS ANTIGOS/ADMIN)
    // ========================================================================

    public ResponseEntity<String> createInstance(InstanceCreateRequest request, String usuarioId) {
        String url = evolutionUrl + "/instance/create";
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(request, getHeaders()), String.class);
    }

    public ResponseEntity<String> connectInstance(String instanceName) {
        String url = evolutionUrl + "/instance/connect/" + instanceName;
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(getHeaders()), String.class);
    }

    public ResponseEntity<String> logoutInstance(String instanceName) {
        String url = evolutionUrl + "/instance/logout/" + instanceName;
        return restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(getHeaders()), String.class);
    }

    public ResponseEntity<String> deleteInstance(String instanceName) {
        String url = evolutionUrl + "/instance/delete/" + instanceName;

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(getHeaders()), String.class);

            // Sem isto, a linha em WhatsappInstance ficava órfã: a instância sumia da Evolution
            // API mas o banco continuava achando que a revendedora tem uma instância ativa.
            if (response.getStatusCode().is2xxSuccessful()) {
                whatsappInstanceRepository.findByInstanceName(instanceName).ifPresent(whatsappInstanceRepository::delete);
                log.info("Instância {} deletada com sucesso no banco e na API", instanceName);
            }

            return response;
        } catch (Exception e) {
            log.error("Erro ao deletar instância {}: {}", instanceName, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"message\": \"Erro ao deletar instância.\"}");
        }
    }

    public ResponseEntity<String> setWebhook(String instanceName, Map<String, Object> webhookConfig) {
        String url = evolutionUrl + "/webhook/set/" + instanceName;
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(webhookConfig, getHeaders()), String.class);
    }

    // ========================================================================
    // MÉTODOS AUXILIARES
    // ========================================================================

    private HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("apikey", apikey);
        return headers;
    }

    private String formatarNumero(String numero) {
        if (numero == null) return "";
        String limpo = numero.replaceAll("\\D", "");
        if (!limpo.startsWith("55")) {
            return "55" + limpo;
        }
        return limpo;
    }
}