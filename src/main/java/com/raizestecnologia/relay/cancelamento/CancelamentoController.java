package com.raizestecnologia.relay.cancelamento;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raizestecnologia.relay.AgentHub;
import com.raizestecnologia.relay.auth.ApiEnvelope;
import com.raizestecnologia.relay.auth.AppUser;
import com.raizestecnologia.relay.auth.AppUserRepository;
import com.raizestecnologia.relay.auth.CurrentUser;
import com.raizestecnologia.relay.auth.RelayPrincipal;
import com.raizestecnologia.relay.auth.UserEmpresa;
import com.raizestecnologia.relay.auth.UserEmpresaRepository;
import com.raizestecnologia.relay.loja.LojaService;
import com.raizestecnologia.relay.push.DeviceToken;
import com.raizestecnologia.relay.push.DeviceTokenRepository;
import com.raizestecnologia.relay.push.FcmSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cancelamento aprovado pelo celular: o garçom sem permissão pede (POST /api/cancelamentos), o relay manda push
 * pra quem pode aprovar na loja (vínculo com usuário do LinkPro Administrador ou "permitir cancelamentos"), quem
 * aprova toca em Aprovar (POST /{id}/decidir) e o relay manda o agente cancelar NO NOME de quem aprovou (o agente
 * confere a permissão de novo). O pedido vence em 10 min e só pode ser decidido uma vez.
 */
@RestController
@RequestMapping("/api/cancelamentos")
public class CancelamentoController {

    private static final Logger log = LoggerFactory.getLogger(CancelamentoController.class);
    private static final Duration VALIDADE = Duration.ofMinutes(10);

    private final SolicitacaoCancelamentoRepository repo;
    private final UserEmpresaRepository vinculos;
    private final AppUserRepository users;
    private final DeviceTokenRepository tokens;
    private final FcmSender fcm;
    private final AgentHub hub;
    private final LojaService lojas;
    private final ObjectMapper mapper = new ObjectMapper();

    public CancelamentoController(SolicitacaoCancelamentoRepository repo, UserEmpresaRepository vinculos, AppUserRepository users,
                                  DeviceTokenRepository tokens, FcmSender fcm, AgentHub hub, LojaService lojas) {
        this.repo = repo;
        this.vinculos = vinculos;
        this.users = users;
        this.tokens = tokens;
        this.fcm = fcm;
        this.hub = hub;
        this.lojas = lojas;
    }

    private static String digitos(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    private static boolean acessa(RelayPrincipal p, String loja) {
        return p != null && (p.isDono() || (p.cnpjs() != null && p.cnpjs().contains(loja)));
    }

    private String nomeDe(RelayPrincipal p) {
        try {
            AppUser u = users.findById(Long.valueOf(p.userId())).orElse(null);
            if (u != null && u.getNome() != null && !u.getNome().isBlank()) return u.getNome().trim();
        } catch (Exception ignored) {
        }
        return p.email();
    }

    /** Vínculos (usuário x loja) que podem aprovar: ligados a um usuário do LinkPro com permissão de cancelar. */
    private List<UserEmpresa> aprovadores(String loja) {
        Set<Long> autorizados = new HashSet<>();
        try {
            AgentHub.Resposta r = hub.ask(loja, "GET", "/api/salao/garcom/autorizadores", null, null);
            if (r.status() == 200) for (JsonNode n : mapper.readTree(r.body()).path("data")) autorizados.add(n.asLong());
        } catch (Exception e) {
            log.warn("[cancelamento] não consegui a lista de quem autoriza na loja {}: {}", loja, e.getMessage());
        }
        List<UserEmpresa> out = new ArrayList<>();
        for (UserEmpresa v : vinculos.findByCnpj(loja)) {
            if (v.getUsuarioSistemaId() == null || v.getUser() == null || !v.getUser().isAtivo()) continue;
            if (!autorizados.isEmpty() && !autorizados.contains(v.getUsuarioSistemaId())) continue;
            out.add(v);
        }
        return out;
    }

    private Map<String, Object> json(SolicitacaoCancelamento s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("loja", s.getLoja());
        m.put("lojaNome", s.getLojaNome());
        m.put("solicitante", s.getSolicitanteNome());
        m.put("descricao", s.getDescricao());
        m.put("status", s.getStatus());
        m.put("aprovador", s.getAprovadorNome());
        m.put("resultado", s.getResultado());
        m.put("criadoEm", s.getCriadoEm().toString());
        m.put("venceEm", s.getCriadoEm().plus(VALIDADE).toString());
        return m;
    }

    private void expirar(SolicitacaoCancelamento s) {
        if (SolicitacaoCancelamento.PENDENTE.equals(s.getStatus()) && s.getCriadoEm().plus(VALIDADE).isBefore(Instant.now())) {
            s.setStatus(SolicitacaoCancelamento.EXPIRADO);
            s.setDecididoEm(Instant.now());
            repo.save(s);
        }
    }

    /** POST /api/cancelamentos — o garçom pede (X-Empresa = loja). body: {descricao, acao:{...}} */
    @PostMapping
    public ResponseEntity<Map<String, Object>> pedir(@RequestHeader(value = "X-Empresa", required = false) String empresa,
                                                     @RequestBody Map<String, Object> body) throws Exception {
        RelayPrincipal p = CurrentUser.get();
        String loja = digitos(empresa);
        if (p == null) return ResponseEntity.status(401).body(ApiEnvelope.fail("Nao autenticado"));
        if (loja.isBlank() || !acessa(p, loja)) return ResponseEntity.status(403).body(ApiEnvelope.fail("Sem permissao para esta empresa"));
        String descricao = String.valueOf(body.getOrDefault("descricao", "")).trim();
        Object acao = body.get("acao");
        if (descricao.isBlank() || !(acao instanceof Map)) return ResponseEntity.badRequest().body(ApiEnvelope.fail("Pedido incompleto."));
        List<UserEmpresa> quem = aprovadores(loja);
        quem.removeIf(v -> String.valueOf(v.getUser().getId()).equals(p.userId()));
        if (quem.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiEnvelope.fail(
                    "Ninguém pra aprovar: ligue no painel um usuário do app a um usuário do LinkPro com permissão de cancelar."));
        }
        SolicitacaoCancelamento s = new SolicitacaoCancelamento();
        s.setId(UUID.randomUUID().toString());
        s.setLoja(loja);
        s.setLojaNome(lojas.conhecidas().getOrDefault(loja, ""));
        s.setSolicitanteId(Long.valueOf(p.userId()));
        s.setSolicitanteNome(nomeDe(p));
        s.setDescricao(descricao.length() > 480 ? descricao.substring(0, 480) : descricao);
        s.setAcao(mapper.writeValueAsString(acao));
        repo.save(s);
        // push pra quem pode aprovar
        List<Long> ids = quem.stream().map(v -> v.getUser().getId()).distinct().toList();
        List<String> toks = tokens.findByUserIdIn(ids).stream().map(DeviceToken::getToken).toList();
        fcm.sendToTokens(toks, "Pedido de cancelamento" + (s.getLojaNome().isBlank() ? "" : " · " + s.getLojaNome()),
                s.getSolicitanteNome() + " pediu: " + s.getDescricao(), Map.of("tipo", "cancelamento", "id", s.getId()));
        log.info("[cancelamento] {} pediu na loja {}: {} (push pra {} aparelho(s))", s.getSolicitanteNome(), loja, s.getDescricao(), toks.size());
        Map<String, Object> out = json(s);
        out.put("aprovadores", quem.stream().map(v -> v.getUser().getNome()).distinct().toList());
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    /** GET /api/cancelamentos/{id} — o garçom acompanha (aprovado/recusado). */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> ver(@PathVariable String id) {
        RelayPrincipal p = CurrentUser.get();
        SolicitacaoCancelamento s = repo.findById(id).orElse(null);
        if (s == null || !acessa(p, s.getLoja())) return ResponseEntity.status(404).body(ApiEnvelope.fail("Pedido não encontrado"));
        expirar(s);
        return ResponseEntity.ok(ApiEnvelope.ok(json(s)));
    }

    /** Lojas em que este usuário pode aprovar (vínculo ligado a usuário do LinkPro). */
    private Set<String> lojasQueAprova(RelayPrincipal p) {
        Set<String> out = new HashSet<>();
        if (p == null) return out;
        for (UserEmpresa v : vinculos.findByUserId(Long.valueOf(p.userId())))
            if (v.getUsuarioSistemaId() != null) out.add(v.getCnpj());
        return out;
    }

    /** GET /api/cancelamentos/pendentes — pedidos esperando a aprovação deste usuário (celular/Mesas). */
    @GetMapping("/pendentes")
    public ResponseEntity<Map<String, Object>> pendentes() {
        RelayPrincipal p = CurrentUser.get();
        Set<String> ls = lojasQueAprova(p);
        List<Map<String, Object>> out = new ArrayList<>();
        if (!ls.isEmpty()) {
            for (SolicitacaoCancelamento s : repo.findByLojaInAndStatusAndCriadoEmAfterOrderByCriadoEmDesc(ls,
                    SolicitacaoCancelamento.PENDENTE, Instant.now().minus(VALIDADE))) {
                if (String.valueOf(s.getSolicitanteId()).equals(p.userId())) continue;
                out.add(json(s));
            }
        }
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    /** GET /api/cancelamentos/historico — últimos pedidos das lojas que este usuário aprova. */
    @GetMapping("/historico")
    public ResponseEntity<Map<String, Object>> historico() {
        RelayPrincipal p = CurrentUser.get();
        Set<String> ls = lojasQueAprova(p);
        List<Map<String, Object>> out = new ArrayList<>();
        if (!ls.isEmpty()) for (SolicitacaoCancelamento s : repo.findTop30ByLojaInOrderByCriadoEmDesc(ls)) {
            expirar(s);
            out.add(json(s));
        }
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    /** POST /api/cancelamentos/{id}/decidir {aprovar:true|false} — quem pode aprova ou recusa. */
    @PostMapping("/{id}/decidir")
    public synchronized ResponseEntity<Map<String, Object>> decidir(@PathVariable String id, @RequestBody Map<String, Object> body) throws Exception {
        RelayPrincipal p = CurrentUser.get();
        SolicitacaoCancelamento s = repo.findById(id).orElse(null);
        if (s == null || p == null) return ResponseEntity.status(404).body(ApiEnvelope.fail("Pedido não encontrado"));
        if (!lojasQueAprova(p).contains(s.getLoja()))
            return ResponseEntity.status(403).body(ApiEnvelope.fail("Seu usuário não aprova cancelamentos nesta loja."));
        expirar(s);
        if (!SolicitacaoCancelamento.PENDENTE.equals(s.getStatus()))
            return ResponseEntity.status(409).body(ApiEnvelope.fail("Esse pedido já foi " + s.getStatus().toLowerCase() + "."));
        s.setAprovadorId(Long.valueOf(p.userId()));
        s.setAprovadorNome(nomeDe(p));
        s.setDecididoEm(Instant.now());
        if (!Boolean.TRUE.equals(body.get("aprovar"))) {
            s.setStatus(SolicitacaoCancelamento.RECUSADO);
            repo.save(s);
            return ResponseEntity.ok(ApiEnvelope.ok(json(s)));
        }
        // aprova: o agente faz no nome de quem aprovou (e confere a permissão dele no LinkPro)
        JsonNode acao = mapper.readTree(s.getAcao());
        AgentHub.Resposta r;
        if ("item".equals(acao.path("tipo").asText())) {
            Map<String, Object> corpo = new LinkedHashMap<>();
            if (acao.hasNonNull("qtd")) corpo.put("qtd", acao.get("qtd").decimalValue());
            r = hub.ask(s.getLoja(), "POST", "/api/salao/garcom/atendimento/" + acao.path("idAtendimento").asLong()
                    + "/item/" + acao.path("idItem").asLong() + "/cancelar", null, mapper.writeValueAsString(corpo));
        } else {
            r = hub.ask(s.getLoja(), "POST", "/api/salao/garcom/autorizar", null, "{}");
        }
        String msg = "";
        try { msg = mapper.readTree(r.body()).path("message").asText(""); } catch (Exception ignored) { }
        if (r.status() == 200) {
            s.setStatus(SolicitacaoCancelamento.APROVADO);
            s.setResultado(msg);
        } else {
            s.setStatus(SolicitacaoCancelamento.ERRO);
            s.setResultado(r.status() == 403 ? "Seu usuário do LinkPro não tem permissão de cancelar." : msg.isBlank() ? "Falhou (" + r.status() + ")" : msg);
        }
        repo.save(s);
        log.info("[cancelamento] {} {} o pedido {} da loja {}: {}", s.getAprovadorNome(), s.getStatus(), s.getId(), s.getLoja(), s.getResultado());
        if (SolicitacaoCancelamento.ERRO.equals(s.getStatus()))
            return ResponseEntity.status(422).body(ApiEnvelope.fail(s.getResultado()));
        return ResponseEntity.ok(ApiEnvelope.ok(json(s)));
    }
}
