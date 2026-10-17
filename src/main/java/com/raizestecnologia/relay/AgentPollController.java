package com.raizestecnologia.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modo HTTPS do agente: em redes que derrubam o WebSocket com a central (caía e voltava a cada
 * poucos segundos), o agente pergunta aqui se tem pedido (POST /agent/poll, espera até 5s) e
 * devolve a resposta em POST /agent/res. Mesma identificação e chave do "register" do WebSocket.
 */
@RestController
@RequestMapping("/agent")
public class AgentPollController {

    private static final Logger log = LoggerFactory.getLogger(AgentPollController.class);
    private static final long ESPERA_MS = 5000;

    private final AgentHub hub;
    private final com.raizestecnologia.relay.loja.LojaService lojas;
    private final com.raizestecnologia.relay.loja.PasseLocalService passes;
    private final String agentKey;
    private final boolean enforce;
    private final ObjectMapper mapper = new ObjectMapper();
    // cnpj|instalacao -> chave da loja (não consulta o banco a cada pergunta)
    private final Map<String, String> chaves = new ConcurrentHashMap<>();

    public AgentPollController(AgentHub hub, com.raizestecnologia.relay.loja.LojaService lojas,
                               com.raizestecnologia.relay.loja.PasseLocalService passes,
                               @Value("${AGENT_KEY:}") String agentKey,
                               @Value("${AGENT_AUTH_ENFORCE:false}") boolean enforce) {
        this.hub = hub;
        this.lojas = lojas;
        this.passes = passes;
        this.agentKey = agentKey == null ? "" : agentKey;
        this.enforce = enforce;
    }

    private boolean recusado(JsonNode n) {
        return !agentKey.isBlank() && enforce && !agentKey.equals(n.path("key").asText(""));
    }

    @PostMapping("/poll")
    public ResponseEntity<String> poll(@RequestBody String corpo) throws Exception {
        JsonNode n = mapper.readTree(corpo);
        String cnpj = n.path("cnpj").asText("");
        if (cnpj.isBlank()) return ResponseEntity.badRequest().body("{\"success\":false}");
        if (recusado(n)) {
            log.warn("[agent-auth] REJEITADO (https): agente sem chave valida (cnpj {}).", cnpj);
            return ResponseEntity.status(401).body("{\"success\":false}");
        }
        String nome = n.path("nome").asText("");
        String inst = n.path("instalacao").asText("");
        String chave = chaves.computeIfAbsent(cnpj + "|" + inst, k -> lojas.resolverChave(cnpj, nome, inst));
        ObjectNode out = mapper.createObjectNode();
        out.put("loja", chave);
        if (n.path("passes").asBoolean(false)) out.set("passes", mapper.readTree(passes.mensagemAgente(chave)));
        ArrayNode reqs = out.putArray("reqs");
        for (String r : hub.poll(chave, nome, n.path("revenda").asText(""), ESPERA_MS)) reqs.add(mapper.readTree(r));
        return ResponseEntity.ok(mapper.writeValueAsString(out));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.raizestecnologia.relay.saude.SaudeService saude;

    /** POST /agent/evento — saúde da loja pelo modo HTTPS (mesma identificação do poll). */
    @PostMapping("/evento")
    public ResponseEntity<String> evento(@RequestBody String corpo) throws Exception {
        JsonNode n = mapper.readTree(corpo);
        String cnpj = n.path("cnpj").asText("");
        if (cnpj.isBlank() || recusado(n)) return ResponseEntity.status(401).body("{\"success\":false}");
        String inst = n.path("instalacao").asText("");
        String chave = chaves.computeIfAbsent(cnpj + "|" + inst, k -> lojas.resolverChave(cnpj, n.path("nome").asText(""), inst));
        saude.registrar(chave, n);
        return ResponseEntity.ok("{\"success\":true}");
    }

    @PostMapping("/res")
    public ResponseEntity<String> res(@RequestBody String corpo) throws Exception {
        JsonNode n = mapper.readTree(corpo);
        if (recusado(n)) return ResponseEntity.status(401).body("{\"success\":false}");
        hub.completeResponse(n.path("id").asText(""), n.path("status").asInt(200), n.path("body").asText("{}"));
        return ResponseEntity.ok("{\"success\":true}");
    }
}
