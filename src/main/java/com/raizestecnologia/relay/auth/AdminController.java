package com.raizestecnologia.relay.auth;

import com.raizestecnologia.relay.AgentHub;
import com.raizestecnologia.relay.auth.dto.CreateUserRequest;
import com.raizestecnologia.relay.auth.dto.EmpresaRequest;
import com.raizestecnologia.relay.auth.dto.SenhaRequest;
import com.raizestecnologia.relay.auth.dto.UpdateUserRequest;
import jakarta.transaction.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Area de administracao (somente DONO). CRUD de usuarios, vinculo de empresas
 * (CNPJs) e listagem das lojas conhecidas (via AgentHub).
 */
@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class AdminController {

    private final AppUserRepository users;
    private final UserEmpresaRepository vinculos;
    private final PasswordEncoder encoder;
    private final AgentHub hub;
    private final com.raizestecnologia.relay.audit.AuditoriaRepository auditoria;
    private final com.raizestecnologia.relay.loja.LojaService lojas;
    private final com.raizestecnologia.relay.cobranca.CobrancaService cobrancas;
    private final com.raizestecnologia.relay.push.DeviceTokenRepository devices;

    public AdminController(AppUserRepository users, UserEmpresaRepository vinculos,
                           PasswordEncoder encoder, AgentHub hub,
                           com.raizestecnologia.relay.audit.AuditoriaRepository auditoria,
                           com.raizestecnologia.relay.loja.LojaService lojas,
                           com.raizestecnologia.relay.cobranca.CobrancaService cobrancas,
                           com.raizestecnologia.relay.push.DeviceTokenRepository devices) {
        this.users = users;
        this.vinculos = vinculos;
        this.encoder = encoder;
        this.hub = hub;
        this.auditoria = auditoria;
        this.lojas = lojas;
        this.cobrancas = cobrancas;
        this.devices = devices;
    }

    // ---- Auditoria (DONO) ------------------------------------------------

    /** GET /api/admin/auditoria?cnpj=... — ultimos 200 eventos (opcionalmente por loja). */
    @GetMapping("/auditoria")
    public ResponseEntity<Map<String, Object>> auditoria(@RequestParam(required = false) String cnpj) {
        List<com.raizestecnologia.relay.audit.Auditoria> lista =
                (cnpj == null || cnpj.isBlank())
                        ? auditoria.findTop200ByOrderByTsDesc()
                        : auditoria.findTop200ByCnpjOrderByTsDesc(normalizeCnpj(cnpj));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var a : lista) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", a.getTs() == null ? null : a.getTs().toString());
            m.put("email", a.getEmail());
            m.put("nome", a.getNome());
            m.put("cnpj", a.getCnpj());
            m.put("acao", a.getAcao());
            m.put("detalhe", a.getDetalhe());
            out.add(m);
        }
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    // ---- Usuarios --------------------------------------------------------

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> listUsers() {
        Map<String, String> nomes = nomesPorCnpj();
        List<Map<String, Object>> lista = new ArrayList<>();
        for (AppUser u : users.findAll()) {
            lista.add(toDto(u, nomes));
        }
        return ResponseEntity.ok(ApiEnvelope.ok(lista));
    }

    @PostMapping("/users")
    @Transactional
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody CreateUserRequest req) {
        if (req == null || req.email() == null || req.email().isBlank()) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("email obrigatorio"));
        }
        if (req.senha() == null || req.senha().isBlank()) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("senha obrigatoria"));
        }
        if (users.findByEmailIgnoreCase(req.email().trim()).isPresent()) {
            return ResponseEntity.status(409).body(ApiEnvelope.fail("email ja cadastrado"));
        }

        AppUser u = new AppUser();
        u.setNome(req.nome());
        u.setEmail(req.email().trim());
        u.setSenhaHash(encoder.encode(req.senha()));
        u.setRole(normalizeRole(req.role()));
        u.setPermissoes(normalizePermissoes(req.permissoes()));
        u.setAtivo(true);
        u.setSenhaProvisoria(true); // 1o acesso: o usuario troca por uma senha propria
        if (req.cnpjs() != null) {
            for (String raw : req.cnpjs()) {
                String cnpj = normalizeCnpj(raw);
                if (cnpj != null && u.getEmpresas().stream().noneMatch(e -> e.getCnpj().equals(cnpj))) {
                    u.getEmpresas().add(new UserEmpresa(u, cnpj));
                }
            }
        }
        AppUser saved = users.save(u);
        return ResponseEntity.ok(ApiEnvelope.ok(toDto(saved, nomesPorCnpj())));
    }

    @PutMapping("/users/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateUser(@PathVariable Long id,
                                                           @RequestBody UpdateUserRequest req) {
        AppUser u = users.findById(id).orElse(null);
        if (u == null) return notFound();
        if (req != null) {
            if (req.nome() != null) u.setNome(req.nome());
            if (req.role() != null && !req.role().isBlank()) u.setRole(normalizeRole(req.role()));
            if (req.ativo() != null) u.setAtivo(req.ativo());
            if (req.permissoes() != null) u.setPermissoes(normalizePermissoes(req.permissoes()));
        }
        AppUser saved = users.save(u);
        return ResponseEntity.ok(ApiEnvelope.ok(toDto(saved, nomesPorCnpj())));
    }

    @PostMapping("/users/{id}/senha")
    @Transactional
    public ResponseEntity<Map<String, Object>> setSenha(@PathVariable Long id,
                                                        @RequestBody SenhaRequest req) {
        AppUser u = users.findById(id).orElse(null);
        if (u == null) return notFound();
        if (req == null || req.senha() == null || req.senha().isBlank()) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("senha obrigatoria"));
        }
        u.setSenhaHash(encoder.encode(req.senha()));
        u.setSenhaProvisoria(true); // senha definida pelo admin: usuario troca no proximo acesso
        users.save(u);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("ok", true)));
    }

    @DeleteMapping("/users/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable Long id) {
        if (!users.existsById(id)) return notFound();
        users.deleteById(id);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("ok", true)));
    }

    // ---- Empresas (vinculos de CNPJ) ------------------------------------

    @PostMapping("/users/{id}/empresas")
    @Transactional
    public ResponseEntity<Map<String, Object>> addEmpresa(@PathVariable Long id,
                                                          @RequestBody EmpresaRequest req) {
        AppUser u = users.findById(id).orElse(null);
        if (u == null) return notFound();
        String cnpj = req == null ? null : normalizeCnpj(req.cnpj());
        if (cnpj == null) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("cnpj invalido"));
        }
        if (u.getEmpresas().stream().noneMatch(e -> e.getCnpj().equals(cnpj))) {
            u.getEmpresas().add(new UserEmpresa(u, cnpj));
            users.save(u);
        }
        return ResponseEntity.ok(ApiEnvelope.ok(toDto(u, nomesPorCnpj())));
    }

    @DeleteMapping("/users/{id}/empresas/{cnpj}")
    @Transactional
    public ResponseEntity<Map<String, Object>> removeEmpresa(@PathVariable Long id,
                                                             @PathVariable String cnpj) {
        AppUser u = users.findById(id).orElse(null);
        if (u == null) return notFound();
        String norm = normalizeCnpj(cnpj);
        u.getEmpresas().removeIf(e -> e.getCnpj().equals(norm));
        users.save(u);
        return ResponseEntity.ok(ApiEnvelope.ok(toDto(u, nomesPorCnpj())));
    }

    // ---- Empresas disponiveis (lojas conectadas) ------------------------

    @GetMapping("/empresas")
    public ResponseEntity<Map<String, Object>> empresas() {
        // 1 query só: carrega todas as lojas e usa a entidade em memória (evita N+1 consultas).
        java.util.Map<String, com.raizestecnologia.relay.loja.Loja> byId = new java.util.HashMap<>();
        Map<String, String> conhecidas = new LinkedHashMap<>();
        for (com.raizestecnologia.relay.loja.Loja l : lojas.todas()) {
            byId.put(l.getCnpj(), l);
            conhecidas.put(l.getCnpj(), l.getNome());
        }
        for (AgentHub.Empresa e : hub.empresas()) {
            conhecidas.putIfAbsent(e.cnpj().replaceAll("\\D", ""), e.nome());
        }
        // aparelhos (celulares) e versao do app por loja
        java.util.Map<String, Integer> devCount = new java.util.HashMap<>();
        java.util.Map<String, java.util.TreeSet<String>> devVers = new java.util.HashMap<>();
        for (Object[] r : devices.statsPorLoja()) {
            String c = r[0] == null ? "" : r[0].toString().replaceAll("\\D", "");
            if (c.isBlank()) continue;
            devCount.merge(c, 1, Integer::sum);
            String v = r[1] == null ? null : r[1].toString().trim();
            if (v != null && !v.isEmpty()) devVers.computeIfAbsent(c, k -> new java.util.TreeSet<>()).add(v);
        }

        List<Map<String, Object>> lista = new ArrayList<>();
        for (var en : conhecidas.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            com.raizestecnologia.relay.loja.Loja l = byId.get(en.getKey());
            m.put("cnpj", en.getKey());
            m.put("nome", en.getValue());
            m.put("online", hub.online(en.getKey()));
            m.put("bloqueada", l != null && l.isBloqueada());
            m.put("motivo", l == null ? null : l.getMotivoBloqueio());
            java.time.Instant ativ = l == null ? null : l.getAtivadaEm();
            Double mensStored = l == null ? null : l.getMensalidade();
            double mens = mensStored != null ? mensStored : MENSALIDADE_PADRAO; // R$30 padrão
            m.put("ativadaEm", ativ == null ? null : ativ.toString());
            m.put("diasUso", diasUso(ativ));
            m.put("mensalidade", mens);
            m.put("implantacao", IMPLANTACAO);
            m.put("grupo", l == null ? null : l.getGrupo());
            java.time.LocalDate implVenc = l == null ? null : l.getImplantacaoVence();
            m.put("implantacaoVence", implVenc == null ? null : implVenc.toString());
            m.put("dispositivos", devCount.getOrDefault(en.getKey(), 0));
            var vs = devVers.get(en.getKey());
            m.put("appVersion", vs == null || vs.isEmpty() ? null : String.join(", ", vs));
            m.put("diaVencimento", l == null ? 5 : l.getDiaVencimento());
            // Estado de cobrança (implantação -> 1ª proporcional -> mensalidade).
            if (l != null) {
                var est = cobrancas.estado(l);
                m.put("implantacaoPaga", l.isImplantacaoPaga());
                m.put("fase", est.fase());
                m.put("itemCobranca", est.item());
                m.put("valorAtual", est.valor());
                m.put("vencimentoAtual", est.vencimento());
                m.put("pagavel", est.pagavel());
                m.put("primeiraMensalidade", est.primeiraMensalidade());
                // compatibilidade com a UI atual
                m.put("proximaCobranca", est.vencimento());
                m.put("valorProximaCobranca", est.valor());
                m.put("primeiraCobranca", est.primeiraMensalidade());
            } else {
                m.put("implantacaoPaga", false);
                m.put("fase", "implantacao");
                m.put("itemCobranca", "Implantação");
                m.put("valorAtual", IMPLANTACAO);
                m.put("vencimentoAtual", null);
                m.put("pagavel", true);
                m.put("primeiraMensalidade", false);
                m.put("proximaCobranca", null);
                m.put("valorProximaCobranca", null);
                m.put("primeiraCobranca", false);
            }
            lista.add(m);
        }
        return ResponseEntity.ok(ApiEnvelope.ok(lista));
    }

    // ---- Bloqueio de loja por pagamento (somente DONO/master) ------------

    /** POST /api/admin/lojas/{cnpj}/bloquear  body opcional: {"motivo":"..."} */
    @PostMapping("/lojas/{cnpj}/bloquear")
    @Transactional
    public ResponseEntity<Map<String, Object>> bloquearLoja(@PathVariable String cnpj,
                                                            @RequestBody(required = false) Map<String, String> body) {
        String c = normalizeCnpj(cnpj);
        if (c == null) return ResponseEntity.status(400).body(ApiEnvelope.fail("cnpj invalido"));
        String motivo = body == null ? null : body.get("motivo");
        lojas.bloquear(c, motivo);
        registrarAcao(c, "loja_bloqueada", motivo == null || motivo.isBlank() ? "Pagamento pendente" : motivo);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("cnpj", c, "bloqueada", true)));
    }

    /** POST /api/admin/lojas/{cnpj}/desbloquear */
    @PostMapping("/lojas/{cnpj}/desbloquear")
    @Transactional
    public ResponseEntity<Map<String, Object>> desbloquearLoja(@PathVariable String cnpj) {
        String c = normalizeCnpj(cnpj);
        if (c == null) return ResponseEntity.status(400).body(ApiEnvelope.fail("cnpj invalido"));
        lojas.desbloquear(c);
        registrarAcao(c, "loja_desbloqueada", "Acesso reativado");
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("cnpj", c, "bloqueada", false)));
    }

    /** POST /api/admin/lojas/{cnpj}/ativacao  body: {"data":"YYYY-MM-DD"} — define o dia que o cliente começou (base da cobrança). */
    @PostMapping("/lojas/{cnpj}/ativacao")
    @Transactional
    public ResponseEntity<Map<String, Object>> definirAtivacao(@PathVariable String cnpj,
                                                               @RequestBody Map<String, String> body) {
        String c = normalizeCnpj(cnpj);
        if (c == null) return ResponseEntity.status(400).body(ApiEnvelope.fail("cnpj invalido"));
        String data = body == null ? null : body.get("data");
        if (data == null || data.isBlank()) return ResponseEntity.status(400).body(ApiEnvelope.fail("data obrigatoria (YYYY-MM-DD)"));
        try {
            // interpreta a data no fuso BRT, ao meio-dia (evita virar o dia por causa do UTC)
            java.time.Instant quando = java.time.LocalDate.parse(data.trim())
                    .atTime(12, 0).atZone(BRT).toInstant();
            lojas.definirAtivacao(c, quando);
            registrarAcao(c, "loja_ativacao", "Cliente desde " + data.trim());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cnpj", c);
            out.put("ativadaEm", quando.toString());
            out.put("diasUso", diasUso(quando));
            Double ms = lojas.mensalidade(c);
            out.putAll(cobranca(quando, ms != null ? ms : MENSALIDADE_PADRAO));
            return ResponseEntity.ok(ApiEnvelope.ok(out));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("data invalida (use YYYY-MM-DD)"));
        }
    }

    /** POST /api/admin/lojas/{cnpj}/mensalidade  body: {"valor": 89.90} — define o valor mensal do cliente. */
    @PostMapping("/lojas/{cnpj}/mensalidade")
    @Transactional
    public ResponseEntity<Map<String, Object>> definirMensalidade(@PathVariable String cnpj,
                                                                  @RequestBody Map<String, Object> body) {
        String c = normalizeCnpj(cnpj);
        if (c == null) return ResponseEntity.status(400).body(ApiEnvelope.fail("cnpj invalido"));
        Object v = body == null ? null : body.get("valor");
        Double valor;
        try {
            valor = v == null ? null : Double.valueOf(v.toString().replace(",", "."));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("valor invalido"));
        }
        lojas.definirMensalidade(c, valor);
        registrarAcao(c, "loja_mensalidade", "Mensalidade R$ " + valor);
        java.time.Instant ativ = lojas.ativadaEm(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cnpj", c);
        out.put("mensalidade", valor);
        out.putAll(cobranca(ativ, valor));
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    /** POST /api/admin/lojas/{cnpj}/dia-vencimento  body: {"dia": 15} — muda o dia de vencimento (1..28). */
    @PostMapping("/lojas/{cnpj}/dia-vencimento")
    @Transactional
    public ResponseEntity<Map<String, Object>> definirDiaVencimento(@PathVariable String cnpj,
                                                                    @RequestBody Map<String, Object> body) {
        String c = normalizeCnpj(cnpj);
        if (c == null) return ResponseEntity.status(400).body(ApiEnvelope.fail("cnpj invalido"));
        Object v = body == null ? null : body.get("dia");
        int dia;
        try {
            dia = v == null ? 5 : Integer.parseInt(v.toString().trim());
        } catch (Exception e) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("dia invalido"));
        }
        if (dia < 1 || dia > 28) return ResponseEntity.status(400).body(ApiEnvelope.fail("dia deve ser entre 1 e 28"));
        lojas.definirDiaVencimento(c, dia);
        registrarAcao(c, "loja_dia_vencimento", "Vencimento dia " + dia);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("cnpj", c, "diaVencimento", dia)));
    }

    /** POST /api/admin/lojas/{cnpj}/grupo  body: {"grupo": "..."} — organiza a loja num grupo (vazio = remove). */
    @PostMapping("/lojas/{cnpj}/grupo")
    @Transactional
    public ResponseEntity<Map<String, Object>> definirGrupo(@PathVariable String cnpj,
                                                            @RequestBody(required = false) Map<String, String> body) {
        String c = normalizeCnpj(cnpj);
        if (c == null) return ResponseEntity.status(400).body(ApiEnvelope.fail("cnpj invalido"));
        String grupo = body == null ? null : body.get("grupo");
        lojas.definirGrupo(c, grupo);
        registrarAcao(c, "loja_grupo", grupo == null || grupo.isBlank() ? "Sem grupo" : "Grupo: " + grupo);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("cnpj", c, "grupo", grupo == null ? "" : grupo)));
    }

    /** POST /api/admin/lojas/{cnpj}/revenda {codigo} — vincula a loja a um revendedor (vazio = desvincula). */
    @PostMapping("/lojas/{cnpj}/revenda")
    @Transactional
    public ResponseEntity<Map<String, Object>> vincularRevenda(@PathVariable String cnpj,
                                                               @RequestBody(required = false) Map<String, String> body) {
        String c = normalizeCnpj(cnpj);
        if (c == null) return ResponseEntity.status(400).body(ApiEnvelope.fail("cnpj invalido"));
        String codigo = body == null ? null : body.get("codigo");
        lojas.vincularRevenda(c, codigo);
        if (codigo != null && !codigo.isBlank()) cobrancas.ativarRevendaStore(c);
        registrarAcao(c, "loja_revenda", codigo == null || codigo.isBlank() ? "Desvinculada" : "Revenda " + codigo);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("cnpj", c, "revenda", codigo == null ? "" : codigo)));
    }

    /** POST /api/admin/cobranca/verificar-inadimplentes — roda agora a checagem de bloqueio (5 dias após venc). */
    @PostMapping("/cobranca/verificar-inadimplentes")
    public ResponseEntity<Map<String, Object>> verificarInadimplentes() {
        int n = cobrancas.bloquearInadimplentes();
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("bloqueadas", n)));
    }

    /** POST /api/admin/lojas/{cnpj}/pago-ate {data} — corrige o "mensalidade paga até" (vazio = nada pago). */
    @PostMapping("/lojas/{cnpj}/pago-ate")
    @Transactional
    public ResponseEntity<Map<String, Object>> definirPagoAte(@PathVariable String cnpj,
                                                              @RequestBody(required = false) Map<String, String> body) {
        String c = normalizeCnpj(cnpj);
        if (c == null) return ResponseEntity.status(400).body(ApiEnvelope.fail("cnpj invalido"));
        String data = body == null ? null : body.get("data");
        try {
            java.time.LocalDate d = (data == null || data.isBlank()) ? null : java.time.LocalDate.parse(data.trim());
            lojas.definirMensalidadePagaAte(c, d);
            registrarAcao(c, "loja_pago_ate", d == null ? "Nada pago" : "Pago até " + d);
            return ResponseEntity.ok(ApiEnvelope.ok(Map.of("cnpj", c, "pagoAte", d == null ? "" : d.toString())));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("data invalida (use YYYY-MM-DD)"));
        }
    }

    /** GET /api/admin/lojas/{cnpj}/pagamentos — parcelas pagas (histórico) da loja. */
    @GetMapping("/lojas/{cnpj}/pagamentos")
    public ResponseEntity<Map<String, Object>> pagamentosLoja(@PathVariable String cnpj) {
        String c = normalizeCnpj(cnpj);
        if (c == null) return ResponseEntity.status(400).body(ApiEnvelope.fail("cnpj invalido"));
        return ResponseEntity.ok(ApiEnvelope.ok(cobrancas.historico(c)));
    }

    /** POST /api/admin/lojas/{cnpj}/implantacao-vencimento  body: {"data":"YYYY-MM-DD"} (vazio = padrão de 3 dias). */
    @PostMapping("/lojas/{cnpj}/implantacao-vencimento")
    @Transactional
    public ResponseEntity<Map<String, Object>> definirImplantacaoVenc(@PathVariable String cnpj,
                                                                      @RequestBody(required = false) Map<String, String> body) {
        String c = normalizeCnpj(cnpj);
        if (c == null) return ResponseEntity.status(400).body(ApiEnvelope.fail("cnpj invalido"));
        String data = body == null ? null : body.get("data");
        try {
            java.time.LocalDate d = (data == null || data.isBlank()) ? null : java.time.LocalDate.parse(data.trim());
            lojas.definirImplantacaoVence(c, d);
            registrarAcao(c, "loja_implantacao_venc", d == null ? "Implantação: vencimento padrão" : "Implantação vence " + d);
            return ResponseEntity.ok(ApiEnvelope.ok(Map.of("cnpj", c, "implantacaoVence", d == null ? "" : d.toString())));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("data invalida (use YYYY-MM-DD)"));
        }
    }

    private static final java.time.ZoneId BRT = java.time.ZoneId.of("America/Sao_Paulo");

    /** Dias que o cliente já usa (desde a ativação). null → 0. */
    private static int diasUso(java.time.Instant ativadaEm) {
        if (ativadaEm == null) return 0;
        java.time.LocalDate ini = ativadaEm.atZone(BRT).toLocalDate();
        long d = java.time.temporal.ChronoUnit.DAYS.between(ini, java.time.LocalDate.now(BRT));
        return (int) Math.max(0, d);
    }

    /** Regra de cobrança: mensalidade vence todo dia 5; implantação (uma vez) na 1a cobrança;
     *  a 1a cobrança é proporcional (do dia da instalação até o dia 5). */
    static final double IMPLANTACAO = 50.0;
    static final double MENSALIDADE_PADRAO = 30.0; // padrão quando o master não define outro valor
    private static final int DIA_COBRANCA = 5;

    /** Monta os campos de cobrança (proximaCobranca, primeiraCobranca, valorProximaCobranca). */
    private static Map<String, Object> cobranca(java.time.Instant ativadaEm, Double mensalidade) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (ativadaEm == null) {
            r.put("proximaCobranca", null);
            r.put("primeiraCobranca", false);
            r.put("valorProximaCobranca", null);
            return r;
        }
        java.time.LocalDate ativ = ativadaEm.atZone(BRT).toLocalDate();
        java.time.LocalDate hoje = java.time.LocalDate.now(BRT);
        // 1a cobrança = primeiro dia 5 DEPOIS da instalação (instalou antes do dia 5 -> dia 5 do mesmo mês).
        java.time.LocalDate primeira = ativ.withDayOfMonth(DIA_COBRANCA);
        if (!ativ.isBefore(primeira)) primeira = primeira.plusMonths(1);
        boolean ehPrimeira = !hoje.isAfter(primeira); // a 1a cobrança ainda não passou
        java.time.LocalDate proxima = ehPrimeira ? primeira : proximoDia5(hoje);
        r.put("proximaCobranca", proxima.toString());
        r.put("primeiraCobranca", ehPrimeira);
        if (mensalidade == null) {
            r.put("valorProximaCobranca", null);
            return r;
        }
        double valor;
        if (ehPrimeira) {
            long dias = java.time.temporal.ChronoUnit.DAYS.between(ativ, primeira);
            double proporcional = round2(mensalidade * dias / 30.0); // proporcional ao uso ate o dia 5
            valor = round2(IMPLANTACAO + proporcional);
        } else {
            valor = round2(mensalidade);
        }
        r.put("valorProximaCobranca", valor);
        return r;
    }

    /** Próximo dia 5 >= [from]. */
    private static java.time.LocalDate proximoDia5(java.time.LocalDate from) {
        java.time.LocalDate c = from.withDayOfMonth(DIA_COBRANCA);
        if (c.isBefore(from)) c = from.plusMonths(1).withDayOfMonth(DIA_COBRANCA);
        return c;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** DELETE /api/admin/lojas/{cnpj} — remove a loja do registro (loja desativada/errada). */
    @DeleteMapping("/lojas/{cnpj}")
    @Transactional
    public ResponseEntity<Map<String, Object>> removerLoja(@PathVariable String cnpj) {
        String c = normalizeCnpj(cnpj);
        if (c == null) return ResponseEntity.status(400).body(ApiEnvelope.fail("cnpj invalido"));
        lojas.remover(c);
        registrarAcao(c, "loja_removida", "Loja removida do registro");
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("cnpj", c, "removida", true)));
    }

    private void registrarAcao(String cnpj, String acao, String detalhe) {
        RelayPrincipal p = CurrentUser.get();
        Long uid = null;
        try { if (p != null && p.userId() != null) uid = Long.valueOf(p.userId()); } catch (Exception ignore) {}
        try {
            auditoria.save(new com.raizestecnologia.relay.audit.Auditoria(
                    uid, p == null ? null : p.email(), null, cnpj, acao, detalhe));
        } catch (Exception ignore) {}
    }

    // ---- Helpers ---------------------------------------------------------

    private Map<String, Object> toDto(AppUser u, Map<String, String> nomes) {
        List<Map<String, String>> empresas = new ArrayList<>();
        for (UserEmpresa v : vinculos.findByUserId(u.getId())) {
            Map<String, String> e = new LinkedHashMap<>();
            e.put("cnpj", v.getCnpj());
            e.put("nome", nomes.getOrDefault(v.getCnpj(), ""));
            empresas.add(e);
        }
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", u.getId());
        dto.put("nome", u.getNome());
        dto.put("email", u.getEmail());
        dto.put("role", u.getRole());
        dto.put("ativo", u.isAtivo());
        dto.put("permissoes", u.permissoesList());
        dto.put("empresas", empresas);
        return dto;
    }

    /** Filtra a lista pros modulos conhecidos e junta em CSV; null/vazio = acesso total. */
    private static String normalizePermissoes(List<String> perms) {
        if (perms == null || perms.isEmpty()) return null;
        List<String> ok = perms.stream()
                .filter(java.util.Objects::nonNull)
                .map(s -> s.trim().toLowerCase())
                .filter(Modulos.TODOS::contains)
                .distinct()
                .toList();
        return ok.isEmpty() ? null : String.join(",", ok);
    }

    private Map<String, String> nomesPorCnpj() {
        Map<String, String> nomes = new LinkedHashMap<>();
        for (AgentHub.Empresa e : hub.empresas()) {
            nomes.put(e.cnpj(), e.nome());
        }
        return nomes;
    }

    private static String normalizeRole(String role) {
        if (role == null) return "OPERADOR";
        String r = role.trim().toUpperCase();
        return r.equals("DONO") ? "DONO" : "OPERADOR";
    }

    private static String normalizeCnpj(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }

    private ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404).body(ApiEnvelope.fail("usuario nao encontrado"));
    }
}
