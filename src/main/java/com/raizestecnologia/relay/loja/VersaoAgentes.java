package com.raizestecnologia.relay.loja;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raizestecnologia.relay.AgentHub;
import com.raizestecnologia.relay.notify.AgentConnectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Versão do agente de cada loja (painel master): pergunta /api/version ao agente quando ele conecta e a cada
 * 30 min, e guarda na loja. A versão mais nova é a "latest" do GitHub (cache de 10 min). Funciona com os
 * agentes antigos (o /api/version existe há muito tempo).
 */
@Component
public class VersaoAgentes {

    private static final Logger log = LoggerFactory.getLogger(VersaoAgentes.class);
    private static final String LATEST = "https://api.github.com/repos/vinny-rbr/raizestecnologia-agente/releases/latest";

    private final AgentHub hub;
    private final LojaService lojas;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile Integer ultima;
    private volatile long ultimaEm = 0;

    public VersaoAgentes(AgentHub hub, LojaService lojas) {
        this.hub = hub;
        this.lojas = lojas;
    }

    @EventListener
    public void onConnect(AgentConnectedEvent ev) {
        String cnpj = ev.cnpj();
        CompletableFuture.runAsync(() -> perguntar(cnpj), CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS));
    }

    @Scheduled(fixedDelay = 30 * 60_000L, initialDelay = 5 * 60_000L)
    public void atualizarTodas() {
        for (AgentHub.Empresa e : hub.empresas()) perguntar(e.cnpj());
    }

    private void perguntar(String cnpj) {
        try {
            AgentHub.Resposta r = hub.ask(cnpj, "GET", "/api/version", null, null);
            if (r.status() != 200) return;
            JsonNode d = json.readTree(r.body()).path("data");
            int v = d.path("agente").asInt(0);
            if (v > 0) lojas.definirVersaoAgente(cnpj, v);
        } catch (Exception e) {
            log.debug("[versao] loja {}: {}", cnpj, e.getMessage());
        }
    }

    /** Número da última versão publicada (tag vNN da release "latest"), ou null se não deu pra saber. */
    public Integer ultima() {
        if (ultima != null && System.currentTimeMillis() - ultimaEm < 10 * 60_000L) return ultima;
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(LATEST)).timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/vnd.github+json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                String tag = json.readTree(r.body()).path("tag_name").asText("");
                String n = tag.replaceAll("\\D", "");
                if (!n.isEmpty()) {
                    ultima = Integer.valueOf(n);
                    ultimaEm = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            log.debug("[versao] latest do GitHub: {}", e.getMessage());
        }
        return ultima;
    }
}
