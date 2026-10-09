package com.raizestecnologia.relay.painel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raizestecnologia.relay.AgentHub;
import com.raizestecnologia.relay.auth.ApiEnvelope;
import com.raizestecnologia.relay.auth.CurrentUser;
import com.raizestecnologia.relay.auth.RelayPrincipal;
import com.raizestecnologia.relay.loja.LojaService;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Painel de pedidos do Link (salao): a TV mostra PREPARANDO (atendimentos abertos, por ordem) e
 * PRONTOS (marcados no app, com o nome da 1a linha da observacao + numero do atendimento).
 *
 *  App (logado, cabecalho X-Empresa):
 *   GET  /api/painel/pedidos                    preparando + prontos
 *   POST /api/painel/pedidos/{id}/pronto        marca pronto
 *   POST /api/painel/pedidos/{id}/voltar        desfaz (volta pra preparando)
 *   POST /api/painel/pedidos/{id}/entregue      tira da TV
 *   POST /api/painel/tv/conectar {codigo}       liga a TV que mostra esse codigo
 *   GET  /api/painel/tv  /  POST /api/painel/tv/{token}/desligar
 *  TV (sem login, so o token que fica no navegador dela):
 *   POST /api/tv/nova                           {token, codigo}
 *   GET  /api/tv/{token}                        aguardando codigo, ou o painel da loja
 */
@RestController
@CrossOrigin(origins = "*")
public class PainelController {

    /** Quanto tempo o pronto fica na TV. */
    static final Duration PRONTO_NA_TELA = Duration.ofMinutes(10);
    /** A TV pergunta a cada 3s; o agente so e consultado no maximo a cada 2,5s por loja. */
    private static final long CACHE_MS = 2500;

    private final AgentHub hub;
    private final LojaService lojas;
    private final PainelProntoRepository prontos;
    private final PainelTvRepository tvs;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, Abertos> cache = new ConcurrentHashMap<>();

    private record Abertos(long em, boolean online, List<Map<String, Object>> lista) {}

    public PainelController(AgentHub hub, LojaService lojas, PainelProntoRepository prontos, PainelTvRepository tvs) {
        this.hub = hub;
        this.lojas = lojas;
        this.prontos = prontos;
        this.tvs = tvs;
    }

    // ---------------------------------------------------------------- app

    @GetMapping("/api/painel/pedidos")
    public ResponseEntity<Map<String, Object>> pedidos(@RequestHeader(value = "X-Empresa", required = false) String empresa) {
        String cnpj = loja(empresa);
        if (cnpj == null) return negado();
        return ResponseEntity.ok(ApiEnvelope.ok(montar(cnpj, true)));
    }

    @PostMapping("/api/painel/pedidos/{id}/pronto")
    public ResponseEntity<Map<String, Object>> pronto(@RequestHeader(value = "X-Empresa", required = false) String empresa,
                                                      @PathVariable long id,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        String cnpj = loja(empresa);
        if (cnpj == null) return negado();
        PainelPronto p = prontos.findByCnpjAndIdAtendimento(cnpj, id).orElseGet(() -> new PainelPronto(cnpj, id));
        // nome/numero: do que o app mandou, senao do ultimo retrato do agente
        Map<String, Object> at = abertoPorId(cnpj, id);
        Object num = body != null && body.get("numero") != null ? body.get("numero") : at == null ? null : at.get("numero");
        Object nome = body != null && body.get("nome") != null ? body.get("nome") : at == null ? null : at.get("nome");
        p.numero = num == null ? null : toLong(num);
        p.nome = nome == null ? null : cut(String.valueOf(nome).trim(), 80);
        p.prontoEm = Instant.now();
        p.entregueEm = null;
        prontos.save(p);
        return ResponseEntity.ok(ApiEnvelope.ok(montar(cnpj, false)));
    }

    @PostMapping("/api/painel/pedidos/{id}/voltar")
    public ResponseEntity<Map<String, Object>> voltar(@RequestHeader(value = "X-Empresa", required = false) String empresa,
                                                      @PathVariable long id) {
        String cnpj = loja(empresa);
        if (cnpj == null) return negado();
        prontos.findByCnpjAndIdAtendimento(cnpj, id).ifPresent(prontos::delete);
        return ResponseEntity.ok(ApiEnvelope.ok(montar(cnpj, false)));
    }

    @PostMapping("/api/painel/pedidos/{id}/entregue")
    public ResponseEntity<Map<String, Object>> entregue(@RequestHeader(value = "X-Empresa", required = false) String empresa,
                                                        @PathVariable long id) {
        String cnpj = loja(empresa);
        if (cnpj == null) return negado();
        prontos.findByCnpjAndIdAtendimento(cnpj, id).ifPresent(p -> {
            p.entregueEm = Instant.now();
            prontos.save(p);
        });
        return ResponseEntity.ok(ApiEnvelope.ok(montar(cnpj, false)));
    }

    @PostMapping("/api/painel/tv/conectar")
    public ResponseEntity<Map<String, Object>> conectar(@RequestHeader(value = "X-Empresa", required = false) String empresa,
                                                        @RequestBody(required = false) Map<String, Object> body) {
        String cnpj = loja(empresa);
        if (cnpj == null) return negado();
        String codigo = body == null || body.get("codigo") == null ? "" : String.valueOf(body.get("codigo")).replaceAll("\\D", "");
        PainelTv tv = codigo.length() == 6 ? tvs.findFirstByCodigoAndCnpjIsNull(codigo).orElse(null) : null;
        if (tv == null) {
            return ResponseEntity.status(404).body(ApiEnvelope.fail("Código não encontrado. Confira o número que aparece na TV."));
        }
        tv.cnpj = cnpj;
        tv.codigo = null;
        tvs.save(tv);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("conectada", true)));
    }

    @GetMapping("/api/painel/tv")
    public ResponseEntity<Map<String, Object>> listarTvs(@RequestHeader(value = "X-Empresa", required = false) String empresa) {
        String cnpj = loja(empresa);
        if (cnpj == null) return negado();
        List<Map<String, Object>> out = new ArrayList<>();
        for (PainelTv t : tvs.findByCnpjOrderByCriadoEm(cnpj)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("token", t.token);
            m.put("criadoEm", t.criadoEm.toString());
            m.put("vistoEm", t.vistoEm == null ? null : t.vistoEm.toString());
            m.put("online", t.vistoEm != null && t.vistoEm.isAfter(Instant.now().minusSeconds(30)));
            out.add(m);
        }
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    @PostMapping("/api/painel/tv/{token}/desligar")
    public ResponseEntity<Map<String, Object>> desligar(@RequestHeader(value = "X-Empresa", required = false) String empresa,
                                                        @PathVariable String token) {
        String cnpj = loja(empresa);
        if (cnpj == null) return negado();
        tvs.findById(token).filter(t -> cnpj.equals(t.cnpj)).ifPresent(tvs::delete);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("desligada", true)));
    }

    // ---------------------------------------------------------------- TV (publico)

    @PostMapping("/api/tv/nova")
    public ResponseEntity<Map<String, Object>> novaTv() {
        String token = hex(20);
        String codigo;
        do {
            codigo = String.format("%06d", rnd.nextInt(1_000_000));
        } while (tvs.findFirstByCodigoAndCnpjIsNull(codigo).isPresent());
        tvs.save(new PainelTv(token, codigo));
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("token", token, "codigo", codigo)));
    }

    @GetMapping("/api/tv/{token}")
    public ResponseEntity<Map<String, Object>> tv(@PathVariable String token) {
        PainelTv tv = tvs.findById(token).orElse(null);
        if (tv == null) return ResponseEntity.status(404).body(ApiEnvelope.fail("TV desligada"));
        Instant agora = Instant.now();
        if (tv.vistoEm == null || tv.vistoEm.isBefore(agora.minusSeconds(20))) {
            tv.vistoEm = agora;
            tvs.save(tv);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (tv.cnpj == null) {
            out.put("ligada", false);
            out.put("codigo", tv.codigo);
            return ResponseEntity.ok(ApiEnvelope.ok(out));
        }
        out.put("ligada", true);
        if (lojas.estaBloqueada(tv.cnpj)) {
            out.put("bloqueada", true);
            return ResponseEntity.ok(ApiEnvelope.ok(out));
        }
        out.putAll(montar(tv.cnpj, true));
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    // ---------------------------------------------------------------- montagem

    private Map<String, Object> montar(String cnpj, boolean podeCache) {
        Abertos ab = abertos(cnpj, podeCache);
        Instant agora = Instant.now();
        List<PainelPronto> recentes = prontos.findByCnpjAndProntoEmAfterOrderByProntoEmDesc(cnpj, agora.minus(PRONTO_NA_TELA));
        // qualquer marcacao (mesmo entregue) tira o atendimento do "preparando"
        Set<Long> marcados = new HashSet<>();
        for (PainelPronto p : recentes) marcados.add(p.idAtendimento);
        if (!ab.lista.isEmpty()) {
            for (Map<String, Object> a : ab.lista) {
                Long id = toLong(a.get("id"));
                if (!marcados.contains(id)) {
                    prontos.findByCnpjAndIdAtendimento(cnpj, id).ifPresent(p -> marcados.add(p.idAtendimento));
                }
            }
        }
        List<Map<String, Object>> preparando = new ArrayList<>();
        for (Map<String, Object> a : ab.lista) {
            if (!marcados.contains(toLong(a.get("id")))) preparando.add(a);
        }
        List<Map<String, Object>> prontosOut = new ArrayList<>();
        for (PainelPronto p : recentes) {
            if (p.entregueEm != null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.idAtendimento);
            m.put("numero", p.numero);
            m.put("nome", p.nome == null ? "" : p.nome);
            m.put("prontoEm", p.prontoEm.toString());
            m.put("segundos", Duration.between(p.prontoEm, agora).getSeconds());
            prontosOut.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("loja", lojas.conhecidas().getOrDefault(cnpj, ""));
        out.put("online", ab.online);
        out.put("preparando", preparando);
        out.put("prontos", prontosOut);
        out.put("agora", agora.toString());
        return out;
    }

    /** Atendimentos abertos (agente: GET /api/salao/painel), com cache curto por loja. */
    private Abertos abertos(String cnpj, boolean podeCache) {
        Abertos c = cache.get(cnpj);
        long agora = System.currentTimeMillis();
        if (podeCache && c != null && agora - c.em < CACHE_MS) return c;
        List<Map<String, Object>> lista = c == null ? List.of() : c.lista;
        boolean online = false;
        AgentHub.Resposta r = hub.ask(cnpj, "GET", "/api/salao/painel", "", null);
        if (r.status() == 200) {
            try {
                JsonNode data = mapper.readTree(r.body()).path("data").path("lista");
                List<Map<String, Object>> nova = new ArrayList<>();
                for (JsonNode n : data) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", n.path("id").asLong());
                    m.put("numero", n.path("numero").isNull() ? null : n.path("numero").asLong());
                    m.put("nome", n.path("nome").asText(""));
                    m.put("mesa", n.path("mesa").isNull() || n.path("mesa").isMissingNode() ? null : n.path("mesa").asText());
                    m.put("abertura", n.path("abertura").asText(""));
                    m.put("minutos", n.path("minutos").asInt());
                    nova.add(m);
                }
                lista = nova;
                online = true;
            } catch (Exception ignore) {
                // resposta estranha: fica com a ultima lista
            }
        }
        Abertos novo = new Abertos(agora, online, lista);
        cache.put(cnpj, novo);
        return novo;
    }

    private Map<String, Object> abertoPorId(String cnpj, long id) {
        Abertos c = cache.get(cnpj);
        if (c == null) c = abertos(cnpj, false);
        for (Map<String, Object> a : c.lista) if (toLong(a.get("id")) == id) return a;
        return null;
    }

    /** Limpa prontos velhos e TVs que nunca foram ligadas. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void limpar() {
        prontos.limpar(Instant.now().minus(Duration.ofDays(2)));
        tvs.limparNaoLigadas(Instant.now().minus(Duration.ofDays(1)));
    }

    // ---------------------------------------------------------------- apoio

    /** Loja do X-Empresa se o usuario logado pode mexer nela (e ela nao esta bloqueada); senao null. */
    private String loja(String empresa) {
        String cnpj = empresa == null ? "" : empresa.replaceAll("\\D", "");
        if (cnpj.isBlank()) return null;
        RelayPrincipal p = CurrentUser.get();
        if (p == null) return null;
        if (p.isDono()) return cnpj;
        boolean pode = p.cnpjs() != null && p.cnpjs().contains(cnpj);
        if (!pode && p.isRevenda()) pode = lojas.cnpjsDaRevenda(p.revendaCodigo()).contains(cnpj);
        if (!pode || lojas.estaBloqueada(cnpj)) return null;
        return cnpj;
    }

    private static ResponseEntity<Map<String, Object>> negado() {
        return ResponseEntity.status(403).body(ApiEnvelope.fail("Sem permissão para esta empresa"));
    }

    private String hex(int bytes) {
        byte[] b = new byte[bytes];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static Long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        try {
            return o == null ? null : Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String cut(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }
}
