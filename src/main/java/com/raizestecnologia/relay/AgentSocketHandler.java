package com.raizestecnologia.relay;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Recebe as conexoes de SAIDA dos agentes das lojas.
 * Protocolo: agente envia {type:register,cnpj,nome,key} e depois {type:res,id,status,body}.
 * Autorizacao: se AGENT_KEY estiver setado, so agentes com essa chave sao "confiaveis".
 * Em transicao (AGENT_AUTH_ENFORCE=false) aceita sem chave mas avisa no log; com enforce=true rejeita.
 */
@Component
public class AgentSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentSocketHandler.class);

    private final AgentHub hub;
    private final com.raizestecnologia.relay.loja.LojaService lojas;
    private final com.raizestecnologia.relay.loja.PasseLocalService passes;
    @org.springframework.beans.factory.annotation.Autowired
    private com.raizestecnologia.relay.saude.SaudeService saude;
    @org.springframework.beans.factory.annotation.Autowired
    private com.raizestecnologia.relay.cancelamento.CancelamentoHostAviso cancelHost;
    private final String agentKey; // chave esperada dos agentes (env AGENT_KEY); vazio = auth desligada
    private final boolean enforce; // true = rejeita agente sem chave; false = so avisa (transicao)

    public AgentSocketHandler(AgentHub hub, com.raizestecnologia.relay.loja.LojaService lojas,
                              com.raizestecnologia.relay.loja.PasseLocalService passes,
                              @Value("${AGENT_KEY:}") String agentKey,
                              @Value("${AGENT_AUTH_ENFORCE:false}") boolean enforce) {
        this.hub = hub;
        this.lojas = lojas;
        this.passes = passes;
        this.agentKey = agentKey == null ? "" : agentKey;
        this.enforce = enforce;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node = hub.parse(message.getPayload());
        String type = node.path("type").asText("");
        switch (type) {
            case "register" -> {
                String cnpj = node.path("cnpj").asText("");
                String nome = node.path("nome").asText("");
                String key = node.path("key").asText("");
                String revenda = node.path("revenda").asText("");
                boolean authOn = !agentKey.isBlank();
                boolean chaveInvalida = authOn && !key.equals(agentKey);
                if (cnpj.isBlank()) {
                    /* sem cnpj, ignora */
                } else if (chaveInvalida && enforce) {
                    log.warn("[agent-auth] REJEITADO: agente sem chave valida (cnpj {}).", cnpj);
                    session.close(CloseStatus.NOT_ACCEPTABLE);
                } else {
                    if (chaveInvalida) {
                        log.warn("[agent-auth] TRANSICAO: agente sem chave valida (cnpj {}) - aceito por enquanto.", cnpj);
                    }
                    // mesmo CNPJ em outro PC/sistema = outra loja (chave própria)
                    String chave = lojas.resolverChave(cnpj, nome, node.path("instalacao").asText(""));
                    hub.register(chave, nome, revenda, session);
                    session.getAttributes().put("loja", chave);
                    session.sendMessage(new TextMessage("{\"type\":\"registered\"}"));
                    enviarPasses(session); // modo local: chave publica + revogados
                }
            }
            case "res" -> hub.completeResponse(
                    node.path("id").asText(""),
                    node.path("status").asInt(200),
                    node.path("body").asText("{}"));
            case "ping" -> session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
            case "passes" -> enviarPasses(session); // agente pede de tempos em tempos (revogados atualizados)
            case "evento" -> { // saúde da loja: algo aconteceu na rede (IP, impressora, conflito)
                Object loja = session.getAttributes().get("loja");
                if (loja != null) saude.registrar(String.valueOf(loja), node);
            }
            case "cancelamento_host" -> { // Host: venda cancelada com liberacao de outro usuario
                Object loja = session.getAttributes().get("loja");
                if (loja != null) cancelHost.avisar(String.valueOf(loja), node);
            }
            default -> { /* ignora */ }
        }
    }

    /** Modo local do agente: chave publica dos passes, a loja dele e quem foi revogado. */
    private void enviarPasses(WebSocketSession session) {
        Object loja = session.getAttributes().get("loja");
        if (loja == null) return;
        try {
            String msg = passes.mensagemAgente(String.valueOf(loja));
            synchronized (session) {
                session.sendMessage(new TextMessage(msg));
            }
        } catch (Exception e) {
            log.warn("[passes] nao enviou pra loja {}: {}", loja, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hub.remove(session);
    }
}
