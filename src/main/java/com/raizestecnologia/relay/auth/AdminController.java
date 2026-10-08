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
    private final com.raizestecnologia.relay.revenda.RevendaService revendas;

    public AdminController(AppUserRepository users, UserEmpresaRepository vinculos,
                           PasswordEncoder encoder, AgentHub hub,
                           com.raizestecnologia.relay.audit.AuditoriaRepository auditoria,
                           com.raizestecnologia.relay.loja.LojaService lojas,
                           com.raizestecnologia.relay.cobranca.CobrancaService cobrancas,
                           com.raizestecnologia.relay.push.DeviceTokenRepository devices,
                           com.raizestecnologia.relay.revenda.RevendaService revendas) {
        this.users = users;
        this.vinculos = vinculos;
        this.encoder = encoder;
        this.hub = hub;
        this.auditoria = auditoria;
        this.lojas = lojas;
        this.cobrancas = cobrancas;
        this.devices = devices;
        this.revendas = revendas;
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

    // ---- Solicitacoes de troca de senha (DONO ve todas) ----

    @GetMapping("/solicitacoes")
    public ResponseEntity<Map<String, Object>> solicitacoes() {
        Map<String, String> nomes = nomesPorCnpj();
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppUser u : users.findAll()) {
            if (!SenhaResetController.temPedido(u)) continue;
            List<String> ls = new ArrayList<>();
            for (UserEmpresa v : vinculos.findByUserId(u.getId())) ls.add(nomes.getOrDefault(v.getCnpj(), v.getCnpj()));
            out.add(SenhaResetController.json(u, ls));
        }
        out.sort((a, b) -> String.valueOf(b.get("pedidoEm")).compareTo(String.valueOf(a.get("pedidoEm"))));
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    @PostMapping("/solicitacoes/{id}/{acao}")
    @Transactional
    public ResponseEntity<Map<String, Object>> decidirSolicitacao(@PathVariable Long id, @PathVariable String acao) {
        AppUser u = users.findById(id).orElse(null);
        if (u == null || !SenhaResetController.temPedido(u))
            return ResponseEntity.status(404).body(ApiEnvelope.fail("Solicitação não encontrada"));
        if ("aprovar".equals(acao)) SenhaResetController.aprovar(u);
        else if ("recusar".equals(acao)) SenhaResetController.recusar(u);
        else return ResponseEntity.status(400).body(ApiEnvelope.fail("Ação inválida"));
        users.save(u);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("ok", true)));
    }

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

        Map<String, com.raizestecnologia.relay.revenda.Revenda> revPorCodigo = revendasPorCodigo();

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
            m.put("sistema", l == null ? null : l.getSistema());
            m.put("cortesia", l != null && l.isCortesia());
            m.put("situacaoRevenda", cobrancas.situacaoRevenda(l));
            String revCod = l == null ? null : l.getRevendaCodigo();
            var rev = revCod == null ? null : revPorCodigo.get(revCod.toUpperCase());
            m.put("revendaCodigo", revCod);
            m.put("revendaNome", rev == null ? null : rev.getNome());
            m.put("revendaPendente", l == null ? null : l.getRevendaPendente());
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
        registrarAcao(c, "loja_revenda", codigo == null || codigo.isBlank() ? "Desvinculada" : "Revenda " + codigo);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("cnpj", c, "revenda", codigo == null ? "" : codigo)));
    }

    // ---- Transferencia de loja entre revendas (DONO autoriza) -------------
    // Quando uma revenda instala o agente numa loja que ja e de outra revenda, a relay nao troca
    // sozinha: grava o pedido na loja e o master decide aqui.

    /** GET /api/admin/transferencias — pedidos pendentes, com os dados das duas revendas. */
    @GetMapping("/transferencias")
    public ResponseEntity<Map<String, Object>> transferencias() {
        Map<String, com.raizestecnologia.relay.revenda.Revenda> porCodigo = revendasPorCodigo();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var l : lojas.transferenciasPendentes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cnpj", l.getCnpj());
            m.put("nome", l.getNome());
            m.put("online", hub.online(l.getCnpj()));
            m.put("pedidoEm", l.getRevendaPendenteEm() == null ? null : l.getRevendaPendenteEm().toString());
            m.put("atual", revendaResumo(l.getRevendaCodigo(), porCodigo));
            m.put("nova", revendaResumo(l.getRevendaPendente(), porCodigo));
            out.add(m);
        }
        out.sort((a, b) -> String.valueOf(b.get("pedidoEm")).compareTo(String.valueOf(a.get("pedidoEm"))));
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    /** POST /api/admin/transferencias/{cnpj}/aprovar|recusar */
    @PostMapping("/transferencias/{cnpj}/{acao}")
    @Transactional
    public ResponseEntity<Map<String, Object>> decidirTransferencia(@PathVariable String cnpj, @PathVariable String acao) {
        String c = normalizeCnpj(cnpj);
        var loja = c == null ? null : lojas.obter(c).orElse(null);
        if (loja == null || loja.getRevendaPendente() == null)
            return ResponseEntity.status(404).body(ApiEnvelope.fail("Pedido não encontrado"));
        String de = loja.getRevendaCodigo(), para = loja.getRevendaPendente();
        if ("aprovar".equals(acao)) {
            lojas.vincularRevenda(c, para);
            registrarAcao(c, "loja_revenda", "Transferida de " + de + " para " + para);
        } else if ("recusar".equals(acao)) {
            lojas.recusarTransferencia(c);
            registrarAcao(c, "loja_revenda", "Transferência para " + para + " recusada (fica com " + de + ")");
        } else {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("Ação inválida"));
        }
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("ok", true)));
    }

    private Map<String, com.raizestecnologia.relay.revenda.Revenda> revendasPorCodigo() {
        Map<String, com.raizestecnologia.relay.revenda.Revenda> m = new java.util.HashMap<>();
        for (var r : revendas.listarTodas()) if (r.getCodigo() != null) m.put(r.getCodigo().toUpperCase(), r);
        return m;
    }

    private static Map<String, Object> revendaResumo(String codigo,
                                                     Map<String, com.raizestecnologia.relay.revenda.Revenda> porCodigo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codigo", codigo);
        var r = codigo == null ? null : porCodigo.get(codigo.toUpperCase());
        m.put("encontrada", r != null);
        if (r != null) {
            m.put("id", r.getId());
            m.put("nome", r.getNome());
            m.put("cpfCnpj", r.getCpfCnpj());
            m.put("email", r.getEmail());
            m.put("telefone", r.getTelefone());
            m.put("cidade", r.getCidade());
            m.put("uf", r.getUf());
            m.put("ativo", r.isAtivo());
        }
        return m;
    }

    // ---- Revendas e seus usuarios-master (DONO) --------------------------
    // O DONO pode listar as revendas e criar/gerir os logins-master de cada uma
    // (role REVENDA + revenda_id): esses usuarios veem SO os clientes daquela revenda.

    /** GET /api/admin/revendas — todas as revendas (para o painel do DONO escolher). */
    @GetMapping("/revendas")
    public ResponseEntity<Map<String, Object>> listarRevendas() {
        // clientes (lojas) de cada revenda, numa query so
        Map<String, List<Map<String, Object>>> clientes = new java.util.HashMap<>();
        for (var l : lojas.todas()) {
            if (l.getRevendaCodigo() == null) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("cnpj", l.getCnpj());
            c.put("nome", l.getNome());
            c.put("online", hub.online(l.getCnpj()));
            c.put("bloqueada", l.isBloqueada());
            clientes.computeIfAbsent(l.getRevendaCodigo().toUpperCase(), k -> new ArrayList<>()).add(c);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : revendas.listarTodas()) {
            Map<String, Object> m = new LinkedHashMap<>();
            var cl = r.getCodigo() == null ? List.<Map<String, Object>>of() : clientes.getOrDefault(r.getCodigo().toUpperCase(), List.of());
            m.put("qtdClientes", cl.size());
            m.put("clientes", cl);
            m.put("id", r.getId());
            m.put("nome", r.getNome());
            m.put("cpfCnpj", r.getCpfCnpj());
            m.put("codigo", r.getCodigo());
            m.put("ativo", r.isAtivo());
            m.put("qtdMasters", users.findByRevendaId(r.getId()).size());
            out.add(m);
        }
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    /** GET /api/admin/revendas/{id}/masters — os usuarios-master de uma revenda. */
    @GetMapping("/revendas/{id}/masters")
    public ResponseEntity<Map<String, Object>> revendaMasters(@PathVariable Long id) {
        if (revendas.porId(id).isEmpty()) return ResponseEntity.status(404).body(ApiEnvelope.fail("Revenda não encontrada"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppUser u : users.findByRevendaId(id)) out.add(masterJson(u));
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    /**
     * POST /api/admin/revendas/{id}/masters {nome,email,senha,vincular} — cria um master dessa revenda.
     * Se o e-mail ja existir: sem "vincular" devolve 409; com "vincular"=true transforma o usuario
     * existente em master desta revenda (role REVENDA + revenda_id); a senha em branco mantem a atual.
     */
    @PostMapping("/revendas/{id}/masters")
    @Transactional
    public ResponseEntity<Map<String, Object>> criarRevendaMaster(@PathVariable Long id, @RequestBody Map<String, Object> b) {
        if (revendas.porId(id).isEmpty()) return ResponseEntity.status(404).body(ApiEnvelope.fail("Revenda não encontrada"));
        String email = str(b.get("email"));
        if (email.isBlank()) return ResponseEntity.status(400).body(ApiEnvelope.fail("E-mail obrigatório"));
        String senha = str(b.get("senha"));
        boolean vincular = Boolean.parseBoolean(String.valueOf(b.get("vincular")));
        var existente = users.findByEmailIgnoreCase(email);
        if (existente.isPresent()) {
            if (!vincular) return ResponseEntity.status(409).body(ApiEnvelope.fail("E-mail já cadastrado"));
            AppUser u = existente.get();
            u.setRole("REVENDA");
            u.setRevendaId(id);
            u.setAtivo(true);
            if (!str(b.get("nome")).isBlank()) u.setNome(str(b.get("nome")));
            if (!senha.isBlank()) u.setSenhaHash(encoder.encode(senha));
            users.save(u);
            return ResponseEntity.ok(ApiEnvelope.ok(masterJson(u)));
        }
        if (senha.isBlank()) return ResponseEntity.status(400).body(ApiEnvelope.fail("Senha obrigatória"));
        AppUser u = new AppUser();
        u.setNome(str(b.get("nome")));
        u.setEmail(email.trim());
        u.setSenhaHash(encoder.encode(senha));
        u.setRole("REVENDA");
        u.setRevendaId(id);
        u.setAtivo(true);
        AppUser saved = users.save(u);
        return ResponseEntity.ok(ApiEnvelope.ok(masterJson(saved)));
    }

    /** POST /api/admin/revendas/{id}/masters/{uid} — edita nome/ativo de um master da revenda. */
    @PostMapping("/revendas/{id}/masters/{uid}")
    @Transactional
    public ResponseEntity<Map<String, Object>> editarRevendaMaster(@PathVariable Long id, @PathVariable Long uid,
                                                                   @RequestBody Map<String, Object> b) {
        AppUser u = users.findById(uid).orElse(null);
        if (u == null || !id.equals(u.getRevendaId())) return ResponseEntity.status(404).body(ApiEnvelope.fail("Usuário não encontrado"));
        if (b.get("nome") != null) u.setNome(str(b.get("nome")));
        if (b.get("ativo") != null) u.setAtivo(Boolean.parseBoolean(String.valueOf(b.get("ativo"))));
        users.save(u);
        return ResponseEntity.ok(ApiEnvelope.ok(masterJson(u)));
    }

    /** POST /api/admin/revendas/{id}/masters/{uid}/senha {senha} — redefine a senha. */
    @PostMapping("/revendas/{id}/masters/{uid}/senha")
    @Transactional
    public ResponseEntity<Map<String, Object>> senhaRevendaMaster(@PathVariable Long id, @PathVariable Long uid,
                                                                  @RequestBody Map<String, Object> b) {
        AppUser u = users.findById(uid).orElse(null);
        if (u == null || !id.equals(u.getRevendaId())) return ResponseEntity.status(404).body(ApiEnvelope.fail("Usuário não encontrado"));
        String senha = str(b.get("senha"));
        if (senha.isBlank()) return ResponseEntity.status(400).body(ApiEnvelope.fail("Senha obrigatória"));
        u.setSenhaHash(encoder.encode(senha));
        users.save(u);
        return ResponseEntity.ok(ApiEnvelope.ok(masterJson(u)));
    }

    /** DELETE /api/admin/revendas/{id}/masters/{uid} — remove um master da revenda. */
    @DeleteMapping("/revendas/{id}/masters/{uid}")
    @Transactional
    public ResponseEntity<Map<String, Object>> excluirRevendaMaster(@PathVariable Long id, @PathVariable Long uid) {
        AppUser u = users.findById(uid).orElse(null);
        if (u == null || !id.equals(u.getRevendaId())) return ResponseEntity.status(404).body(ApiEnvelope.fail("Usuário não encontrado"));
        users.delete(u);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("id", uid)));
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    private Map<String, Object> masterJson(AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("nome", u.getNome());
        m.put("email", u.getEmail());
        m.put("ativo", u.isAtivo());
        return m;
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
