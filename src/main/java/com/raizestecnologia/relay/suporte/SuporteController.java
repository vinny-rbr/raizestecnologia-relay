package com.raizestecnologia.relay.suporte;

import com.raizestecnologia.relay.auth.ApiEnvelope;
import com.raizestecnologia.relay.auth.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Suporte por WhatsApp:
 *  POST /api/suporte-wpp/mensagem  (conector, header X-Suporte-Token) — mensagem de um grupo SUP
 *  POST /api/suporte-wpp/ping      (conector) — "estou ligado"
 *  GET  /api/tv/suporte?k=CHAVE    (TV, sem login) — chamados em aberto + resumo do dia
 *  /api/admin/suporte/**           (master) — chamados, atender/finalizar, relatório, equipe, chaves
 */
@RestController
public class SuporteController {

    private final SuporteService svc;
    private final ChamadoRepository chamados;
    private final SuporteEquipeRepository equipe;
    private final com.raizestecnologia.relay.auth.AppUserRepository users;

    public SuporteController(SuporteService svc, ChamadoRepository chamados, SuporteEquipeRepository equipe,
                             com.raizestecnologia.relay.auth.AppUserRepository users) {
        this.svc = svc;
        this.chamados = chamados;
        this.equipe = equipe;
        this.users = users;
    }

    private static final ResponseEntity<Map<String, Object>> NEGADO =
            ResponseEntity.status(401).body(ApiEnvelope.fail("Chave inválida"));

    // ---------- conector ----------

    @PostMapping("/api/suporte-wpp/mensagem")
    public ResponseEntity<Map<String, Object>> mensagem(@RequestHeader(value = "X-Suporte-Token", required = false) String token,
                                                        @RequestBody Map<String, Object> b) {
        if (!svc.tokenOk(token)) return NEGADO;
        Object ts = b.get("ts");
        svc.receber(new SuporteService.Mensagem(s(b.get("grupoId")), s(b.get("grupoNome")), s(b.get("autorNumero")),
                s(b.get("autorNome")), Boolean.TRUE.equals(b.get("minha")), s(b.get("texto")),
                ts instanceof Number n ? n.longValue() : null));
        svc.salvarCfg(SuporteService.CFG_PING, Instant.now().toString());
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of()));
    }

    @PostMapping("/api/suporte-wpp/ping")
    public ResponseEntity<Map<String, Object>> ping(@RequestHeader(value = "X-Suporte-Token", required = false) String token) {
        if (!svc.tokenOk(token)) return NEGADO;
        svc.salvarCfg(SuporteService.CFG_PING, Instant.now().toString());
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("nomeDono", String.valueOf(svc.cfg(SuporteService.CFG_MEU_NOME)))));
    }

    private static String s(Object o) { return o == null ? null : o.toString(); }

    // ---------- TV ----------

    @GetMapping("/api/tv/suporte")
    public ResponseEntity<Map<String, Object>> tv(@RequestParam(required = false) String k) {
        if (!svc.chaveTvOk(k)) return NEGADO;
        LocalDate hoje = LocalDate.now(SuporteService.BRT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chamados", svc.ativos());
        out.put("hoje", svc.relatorio(hoje, hoje, false));
        out.put("conector", conectorOnline());
        out.put("agora", Instant.now().toString());
        // técnicos (pro ranking da TV mostrar todo mundo, inclusive quem ainda está com 0)
        List<String> tecnicos = new java.util.ArrayList<>();
        String dono = svc.cfg(SuporteService.CFG_MEU_NOME);
        if (dono != null && !dono.isBlank()) tecnicos.add(dono);
        equipe.findAll().stream().filter(SuporteEquipe::isTecnico).map(SuporteEquipe::getNome)
                .filter(n -> !tecnicos.contains(n)).forEach(tecnicos::add);
        out.put("tecnicos", tecnicos);
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    private boolean conectorOnline() {
        String p = svc.cfg(SuporteService.CFG_PING);
        try {
            return p != null && Instant.parse(p).isAfter(Instant.now().minusSeconds(180));
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- master ----------

    @GetMapping("/api/admin/suporte/chamados")
    public ResponseEntity<Map<String, Object>> ativos() {
        return ResponseEntity.ok(ApiEnvelope.ok(svc.ativos()));
    }

    /** POST .../atender {nome?} — sem nome, fica o nome de quem está logado no painel/app. */
    @PostMapping("/api/admin/suporte/chamados/{id}/atender")
    public ResponseEntity<Map<String, Object>> atender(@PathVariable Long id, @RequestBody(required = false) Map<String, String> b) {
        Chamado c = chamados.findById(id).orElse(null);
        if (c == null) return ResponseEntity.status(404).body(ApiEnvelope.fail("Chamado não encontrado"));
        svc.atender(c, quem(b), Instant.now());
        chamados.save(c);
        return ResponseEntity.ok(ApiEnvelope.ok(svc.json(c)));
    }

    @PostMapping("/api/admin/suporte/chamados/{id}/finalizar")
    public ResponseEntity<Map<String, Object>> finalizar(@PathVariable Long id, @RequestBody(required = false) Map<String, String> b) {
        Chamado c = chamados.findById(id).orElse(null);
        if (c == null) return ResponseEntity.status(404).body(ApiEnvelope.fail("Chamado não encontrado"));
        svc.finalizar(c, quem(b), Instant.now());
        chamados.save(c);
        return ResponseEntity.ok(ApiEnvelope.ok(svc.json(c)));
    }

    /** GET .../chamados/{id}/conversa — mensagens do chamado e o resumo (relatório: clicar no chamado). */
    @GetMapping("/api/admin/suporte/chamados/{id}/conversa")
    public ResponseEntity<Map<String, Object>> conversa(@PathVariable Long id) {
        Chamado c = chamados.findById(id).orElse(null);
        if (c == null) return ResponseEntity.status(404).body(ApiEnvelope.fail("Chamado não encontrado"));
        return ResponseEntity.ok(ApiEnvelope.ok(svc.conversa(c)));
    }

    /** GET .../resumo — texto pronto pra colar no grupo (app: aviso de chamado parado). */
    @GetMapping("/api/admin/suporte/resumo")
    public ResponseEntity<Map<String, Object>> resumo() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("texto", svc.resumoTexto());
        out.put("chamados", svc.ativos());
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    private String quem(Map<String, String> b) {
        String n = b == null ? null : b.get("nome");
        if (n != null && !n.isBlank()) return n.trim();
        var p = CurrentUser.get();
        try {
            var u = p == null ? null : users.findById(Long.valueOf(p.userId())).orElse(null);
            if (u != null && u.getNome() != null && !u.getNome().isBlank()) return u.getNome().trim().split("\\s+")[0];
        } catch (Exception ignored) {
        }
        return svc.cfg(SuporteService.CFG_MEU_NOME);
    }

    /** GET .../relatorio?de=2026-10-01&ate=2026-10-31 (padrão: últimos 30 dias) */
    @GetMapping("/api/admin/suporte/relatorio")
    public ResponseEntity<Map<String, Object>> relatorio(@RequestParam(required = false) String de,
                                                         @RequestParam(required = false) String ate) {
        LocalDate hoje = LocalDate.now(SuporteService.BRT);
        LocalDate a = ate == null || ate.isBlank() ? hoje : LocalDate.parse(ate);
        LocalDate d = de == null || de.isBlank() ? a.minusDays(29) : LocalDate.parse(de);
        if (d.isAfter(a)) d = a;
        return ResponseEntity.ok(ApiEnvelope.ok(svc.relatorio(d, a, true)));
    }

    @GetMapping("/api/admin/suporte/equipe")
    public ResponseEntity<Map<String, Object>> equipe() {
        List<Map<String, Object>> out = equipe.findAll().stream()
                .map(e -> Map.<String, Object>of("numero", e.getNumero(), "nome", e.getNome(), "funcao", e.getFuncao())).toList();
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    /** POST .../equipe {numero, nome, funcao: TECNICO|COMERCIAL} — número com DDD (o 55 do Brasil entra sozinho). */
    @PostMapping("/api/admin/suporte/equipe")
    public ResponseEntity<Map<String, Object>> salvarEquipe(@RequestBody Map<String, String> b) {
        String num = b.getOrDefault("numero", "").replaceAll("\\D", "");
        String nome = b.getOrDefault("nome", "").trim();
        String funcao = "COMERCIAL".equalsIgnoreCase(b.get("funcao")) ? "COMERCIAL" : "TECNICO";
        if (num.length() < 10 || nome.isEmpty()) return ResponseEntity.status(400).body(ApiEnvelope.fail("Informe nome e número com DDD"));
        if (num.length() <= 11) num = "55" + num; // número brasileiro sem o 55 (ids do WhatsApp mais longos ficam como vieram)
        equipe.save(new SuporteEquipe(num, nome, funcao));
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("numero", num, "nome", nome, "funcao", funcao)));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private SuporteAutorRepository autores;

    /** GET .../autores — quem escreveu nos grupos SUP e ainda não está na equipe (pra marcar técnico/comercial). */
    @GetMapping("/api/admin/suporte/autores")
    public ResponseEntity<Map<String, Object>> autores() {
        java.util.Set<String> naEquipe = new java.util.HashSet<>();
        equipe.findAll().forEach(e -> naEquipe.add(e.getNumero()));
        List<Map<String, Object>> out = autores.findTop300ByOrderByUltimaVezDesc().stream()
                .filter(a -> !naEquipe.contains(a.getNumero()))
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("numero", a.getNumero());
                    m.put("nome", a.getNome());
                    m.put("grupo", a.getUltimoGrupo());
                    m.put("ultimaVez", a.getUltimaVez() == null ? null : a.getUltimaVez().toString());
                    m.put("mensagens", a.getMensagens());
                    return m;
                }).toList();
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    @DeleteMapping("/api/admin/suporte/equipe/{numero}")
    public ResponseEntity<Map<String, Object>> removerEquipe(@PathVariable String numero) {
        equipe.deleteById(numero.replaceAll("\\D", ""));
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of()));
    }

    /** GET .../config — chaves (pro conector e o link da TV), nome do dono do número e se o conector está ligado. */
    @GetMapping("/api/admin/suporte/config")
    public ResponseEntity<Map<String, Object>> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tokenConector", svc.cfg(SuporteService.CFG_TOKEN));
        out.put("chaveTv", svc.cfg(SuporteService.CFG_TV));
        out.put("nomeDono", svc.cfg(SuporteService.CFG_MEU_NOME));
        out.put("conectorOnline", conectorOnline());
        out.put("ultimoSinal", svc.cfg(SuporteService.CFG_PING));
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    @PostMapping("/api/admin/suporte/config")
    public ResponseEntity<Map<String, Object>> salvarConfig(@RequestBody Map<String, String> b) {
        String n = b.get("nomeDono");
        if (n != null && !n.isBlank()) svc.salvarCfg(SuporteService.CFG_MEU_NOME, n.trim());
        return config();
    }
}
