package com.raizestecnologia.relay.saude;

import com.fasterxml.jackson.databind.JsonNode;
import com.raizestecnologia.relay.auth.AppUser;
import com.raizestecnologia.relay.auth.AppUserRepository;
import com.raizestecnologia.relay.loja.LojaService;
import com.raizestecnologia.relay.push.DeviceToken;
import com.raizestecnologia.relay.push.DeviceTokenRepository;
import com.raizestecnologia.relay.push.FcmSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Recebe os eventos de rede que os agentes mandam (saúde das lojas), guarda no histórico e avisa o master (DONO)
 * por push. O mesmo problema (mesma chave) só gera push de novo depois de 5 min; "voltou ao normal" sempre avisa.
 */
@Service
public class SaudeService {

    private static final Logger log = LoggerFactory.getLogger(SaudeService.class);
    private static final Duration SEM_REPETIR = Duration.ofMinutes(5);

    private final EventoLojaRepository repo;
    private final LojaService lojas;
    private final AppUserRepository users;
    private final DeviceTokenRepository tokens;
    private final FcmSender fcm;

    public SaudeService(EventoLojaRepository repo, LojaService lojas, AppUserRepository users, DeviceTokenRepository tokens, FcmSender fcm) {
        this.repo = repo;
        this.lojas = lojas;
        this.users = users;
        this.tokens = tokens;
        this.fcm = fcm;
    }

    private static String corta(String s, int n) {
        if (s == null) return null;
        s = s.trim();
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** Evento gerado pela própria central (ex.: computador da loja ficou offline). */
    public void registrarCentral(String loja, String tipo, String nivel, String titulo, String detalhe, String chave) {
        com.fasterxml.jackson.databind.node.ObjectNode n = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        n.put("tipo", tipo);
        n.put("nivel", nivel);
        n.put("titulo", titulo);
        n.put("detalhe", detalhe);
        n.put("chave", chave);
        registrar(loja, n);
    }

    public void registrar(String loja, JsonNode n) {
        try {
            String titulo = corta(n.path("titulo").asText(""), 200);
            if (loja == null || loja.isBlank() || titulo.isBlank()) return;
            EventoLoja e = new EventoLoja();
            e.setLoja(loja);
            e.setLojaNome(corta(lojas.conhecidas().getOrDefault(loja, ""), 120));
            e.setTipo(corta(n.path("tipo").asText("outro"), 30));
            String nivel = n.path("nivel").asText("aviso");
            e.setNivel(List.of("grave", "aviso", "ok").contains(nivel) ? nivel : "aviso");
            e.setTitulo(titulo);
            e.setDetalhe(corta(n.path("detalhe").asText(""), 1000));
            String chave = corta(n.path("chave").asText(e.getTipo() + ":" + titulo), 200);
            e.setChave(chave);
            boolean repetido = !"ok".equals(e.getNivel())
                    && repo.existsByLojaAndChaveAndPushTrueAndCriadoEmAfter(loja, chave, Instant.now().minus(SEM_REPETIR));
            e.setPush(!repetido);
            repo.save(e);
            if (!repetido) avisarMaster(e);
        } catch (Exception ex) {
            log.warn("[saude] evento da loja {} não registrado: {}", loja, ex.getMessage());
        }
    }

    private void avisarMaster(EventoLoja e) {
        if (!fcm.enabled()) return;
        List<Long> donos = users.findByRoleIgnoreCaseAndAtivoTrue("DONO").stream().map(AppUser::getId).toList();
        if (donos.isEmpty()) return;
        List<String> toks = tokens.findByUserIdIn(donos).stream().map(DeviceToken::getToken).toList();
        String loja = e.getLojaNome() == null || e.getLojaNome().isBlank() ? e.getLoja() : e.getLojaNome();
        String icone = "grave".equals(e.getNivel()) ? "🔴 " : "ok".equals(e.getNivel()) ? "🟢 " : "🟠 ";
        fcm.sendToTokens(toks, icone + loja + ": " + e.getTitulo(), e.getDetalhe() == null ? "" : e.getDetalhe(),
                Map.of("tipo", "saude", "loja", e.getLoja()));
    }
}
