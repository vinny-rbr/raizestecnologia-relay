package com.raizestecnologia.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.raizestecnologia.relay.notify.AgentConnectedEvent;
import com.raizestecnologia.relay.notify.AgentDisconnectedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Registro dos agentes conectados (por CNPJ) e correlacao de requisicoes/respostas.
 */
@Component
public class AgentHub {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ApplicationEventPublisher publisher;
    private final com.raizestecnologia.relay.auth.UserEmpresaRepository vinculos;

    public AgentHub(ApplicationEventPublisher publisher, com.raizestecnologia.relay.auth.UserEmpresaRepository vinculos) {
        this.publisher = publisher;
        this.vinculos = vinculos;
    }

    // cnpj -> sessao do agente da loja
    private final Map<String, WebSocketSession> agents = new ConcurrentHashMap<>();
    // cnpj -> nome fantasia da loja
    private final Map<String, String> names = new ConcurrentHashMap<>();
    // requestId -> future aguardando a resposta do agente
    private final Map<String, CompletableFuture<Resposta>> pending = new ConcurrentHashMap<>();

    // Modo HTTPS: agente numa rede que derruba o WebSocket pergunta por aqui (POST /agent/poll) se tem
    // pedido. Enquanto ele estiver perguntando, os pedidos dessa loja vão pela fila (não pelo WebSocket).
    private final Map<String, java.util.concurrent.LinkedBlockingQueue<String>> filas = new ConcurrentHashMap<>();
    private final Map<String, Long> ultimoPoll = new ConcurrentHashMap<>();
    private static final long POLL_VIVO_MS = 15000;

    public record Empresa(String cnpj, String nome) {}
    public record Resposta(int status, String body) {}

    public void register(String cnpj, String nome, String revenda, WebSocketSession session) {
        agents.put(cnpj, session);
        names.put(cnpj, nome == null ? "" : nome);
        publisher.publishEvent(new AgentConnectedEvent(cnpj, names.get(cnpj),
                revenda == null || revenda.isBlank() ? null : revenda.trim().toUpperCase()));
    }

    public void remove(WebSocketSession session) {
        String cnpj = null;
        for (Map.Entry<String, WebSocketSession> e : agents.entrySet()) {
            if (e.getValue().getId().equals(session.getId())) {
                cnpj = e.getKey();
                break;
            }
        }
        if (cnpj != null) {
            agents.remove(cnpj);
            // seguindo pelo modo HTTPS: a loja continua online
            if (!pollVivo(cnpj)) publisher.publishEvent(new AgentDisconnectedEvent(cnpj, names.getOrDefault(cnpj, "")));
        }
    }

    public List<Empresa> empresas() {
        java.util.Set<String> todas = new java.util.LinkedHashSet<>(agents.keySet());
        for (String c : ultimoPoll.keySet()) if (pollVivo(c)) todas.add(c);
        return todas.stream().map(cnpj -> new Empresa(cnpj, names.getOrDefault(cnpj, ""))).toList();
    }

    public boolean online(String cnpj) {
        WebSocketSession s = agents.get(cnpj);
        return (s != null && s.isOpen()) || pollVivo(cnpj);
    }

    private boolean pollVivo(String cnpj) {
        Long t = ultimoPoll.get(cnpj);
        return t != null && System.currentTimeMillis() - t < POLL_VIVO_MS;
    }

    /**
     * Agente em modo HTTPS perguntando se tem pedido: espera até esperaMs pelo primeiro e devolve
     * todos os da fila (JSON de cada "req", igual ao do WebSocket).
     */
    public List<String> poll(String cnpj, String nome, String revenda, long esperaMs) throws InterruptedException {
        boolean estava = online(cnpj);
        ultimoPoll.put(cnpj, System.currentTimeMillis());
        if (nome != null && !nome.isBlank()) names.put(cnpj, nome);
        if (!estava) {
            publisher.publishEvent(new AgentConnectedEvent(cnpj, names.getOrDefault(cnpj, ""),
                    revenda == null || revenda.isBlank() ? null : revenda.trim().toUpperCase()));
        }
        var fila = filas.computeIfAbsent(cnpj, k -> new java.util.concurrent.LinkedBlockingQueue<>());
        List<String> out = new java.util.ArrayList<>();
        String primeiro = fila.poll(esperaMs, TimeUnit.MILLISECONDS);
        if (primeiro != null) {
            out.add(primeiro);
            fila.drainTo(out);
        }
        ultimoPoll.put(cnpj, System.currentTimeMillis());
        return out;
    }

    /** Loja que estava no modo HTTPS e parou de perguntar (e sem WebSocket): ficou offline. */
    @Scheduled(fixedDelay = 10000)
    public void limparPolls() {
        for (var e : ultimoPoll.entrySet()) {
            String cnpj = e.getKey();
            if (pollVivo(cnpj)) continue;
            ultimoPoll.remove(cnpj);
            filas.remove(cnpj);
            WebSocketSession s = agents.get(cnpj);
            if (s == null || !s.isOpen()) publisher.publishEvent(new AgentDisconnectedEvent(cnpj, names.getOrDefault(cnpj, "")));
        }
    }

    /** Recebe a resposta vinda do agente e completa o future correspondente. */
    public void completeResponse(String id, int status, String body) {
        CompletableFuture<Resposta> f = pending.remove(id);
        if (f != null) f.complete(new Resposta(status, body));
    }

    /** Usuario do sistema da loja ligado a quem pediu (painel da revenda > Usuarios), ou null. */
    private Long usuarioSistema(com.raizestecnologia.relay.auth.RelayPrincipal quem, String cnpj) {
        if (quem == null || quem.userId() == null) return null;
        try {
            return vinculos.findFirstByUserIdAndCnpj(Long.valueOf(quem.userId()), cnpj.replaceAll("\\D", ""))
                    .map(com.raizestecnologia.relay.auth.UserEmpresa::getUsuarioSistemaId).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Envia uma requisicao ao agente da loja e aguarda a resposta (com timeout). */
    public Resposta ask(String cnpj, String method, String path, String query, String body) {
        WebSocketSession s = agents.get(cnpj);
        boolean porFila = pollVivo(cnpj);
        if (!porFila && (s == null || !s.isOpen())) {
            return new Resposta(503, "{\"success\":false,\"message\":\"Loja offline (agente nao conectado)\"}");
        }
        String id = UUID.randomUUID().toString();
        CompletableFuture<Resposta> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            ObjectNode req = mapper.createObjectNode();
            req.put("type", "req");
            req.put("id", id);
            req.put("method", method);
            req.put("path", path);
            req.put("query", query == null ? "" : query);
            if (body != null) req.put("body", body);
            // quem pediu (aparece no log do agente: "fulano abriu Vendas")
            var quem = com.raizestecnologia.relay.auth.CurrentUser.get();
            if (quem != null && quem.email() != null) req.put("user", quem.email());
            // gravacao: o usuario do sistema da loja ligado a quem pediu (o agente grava no nome dele)
            if ("POST".equalsIgnoreCase(method)) {
                Long us = usuarioSistema(quem, cnpj);
                if (us != null) req.put("usuarioSistema", us);
            }
            if (porFila) {
                filas.computeIfAbsent(cnpj, k -> new java.util.concurrent.LinkedBlockingQueue<>()).offer(mapper.writeValueAsString(req));
            } else {
                synchronized (s) {
                    s.sendMessage(new TextMessage(mapper.writeValueAsString(req)));
                }
            }
            return future.get(12, TimeUnit.SECONDS);
        } catch (Exception e) {
            pending.remove(id);
            return new Resposta(504, "{\"success\":false,\"message\":\"Sem resposta da loja: " + e.getMessage() + "\"}");
        }
    }

    /**
     * Keepalive: envia um "ping" a cada agente a cada 8s. Sem isso, o proxy da nuvem (Render)
     * fecha a conexao ociosa quando a loja nao esta sendo consultada pelo app — e a loja "some".
     * Como o trafego parte do servidor para o agente, mantem a conexao viva nos dois sentidos.
     */
    @Scheduled(fixedDelay = 8000)
    public void keepAlive() {
        if (agents.isEmpty()) return;
        TextMessage ping = new TextMessage("{\"type\":\"ping\"}");
        for (WebSocketSession s : agents.values()) {
            try {
                if (s != null && s.isOpen()) {
                    synchronized (s) {
                        s.sendMessage(ping);
                    }
                }
            } catch (IOException ignore) {
                // conexao morta; sera limpa no afterConnectionClosed do handler
            }
        }
    }

    public JsonNode parse(String text) throws IOException {
        return mapper.readTree(text);
    }
}
