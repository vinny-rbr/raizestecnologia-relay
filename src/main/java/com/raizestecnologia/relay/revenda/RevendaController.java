package com.raizestecnologia.relay.revenda;

import com.raizestecnologia.relay.AgentHub;
import com.raizestecnologia.relay.auth.ApiEnvelope;
import com.raizestecnologia.relay.auth.AppUser;
import com.raizestecnologia.relay.auth.AppUserRepository;
import com.raizestecnologia.relay.auth.JwtService;
import com.raizestecnologia.relay.auth.Modulos;
import com.raizestecnologia.relay.auth.UserEmpresa;
import com.raizestecnologia.relay.auth.UserEmpresaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Set;
import com.raizestecnologia.relay.loja.Loja;
import com.raizestecnologia.relay.loja.LojaRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Painel da revenda: cadastro do revendedor, login (JWT proprio, role REVENDA) e a lista de
 * lojas DELE (as que ele instalou — vinculadas pelo codigo). Os endpoints /api/revenda/** sao
 * liberados no SecurityConfig e a autorizacao e feita aqui pelo token (role REVENDA).
 */
@RestController
@RequestMapping("/api/revenda")
public class RevendaController {

    private static final ZoneId BRT = ZoneId.of("America/Sao_Paulo");

    private final RevendaService revendas;
    private final LojaRepository lojas;
    private final AgentHub hub;
    private final JwtService jwt;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final com.raizestecnologia.relay.cobranca.CobrancaService cobrancas;
    private final com.raizestecnologia.relay.auth.LoginThrottle throttle;
    private final com.raizestecnologia.relay.notify.NotificationService notifier;
    private final UserEmpresaRepository vinculos;

    public RevendaController(RevendaService revendas, LojaRepository lojas, AgentHub hub, JwtService jwt,
                             AppUserRepository users, PasswordEncoder encoder,
                             com.raizestecnologia.relay.cobranca.CobrancaService cobrancas,
                             com.raizestecnologia.relay.auth.LoginThrottle throttle,
                             com.raizestecnologia.relay.notify.NotificationService notifier,
                             UserEmpresaRepository vinculos) {
        this.revendas = revendas;
        this.lojas = lojas;
        this.hub = hub;
        this.jwt = jwt;
        this.users = users;
        this.encoder = encoder;
        this.cobrancas = cobrancas;
        this.throttle = throttle;
        this.notifier = notifier;
        this.vinculos = vinculos;
    }

    /** POST /api/revenda/cadastro — cadastra um revendedor (CPF/CNPJ + dados) e ja loga. */
    @PostMapping("/cadastro")
    public ResponseEntity<Map<String, Object>> cadastro(@RequestBody Map<String, String> b) {
        try {
            Revenda r = revendas.cadastrar(
                    b.get("nome"), b.get("cpfCnpj"), b.get("email"), b.get("telefone"),
                    b.get("cidade"), b.get("uf"), b.get("senha"));
            // Avisa o master (push no celular + email) que entrou um revendedor novo.
            try {
                String cidadeUf = (b.getOrDefault("cidade", "") + "/" + b.getOrDefault("uf", "")).trim();
                notifier.notifyMaster("Nova revenda cadastrada",
                        b.getOrDefault("nome", "(sem nome)") + " — " + cidadeUf
                                + " · tel " + b.getOrDefault("telefone", "-")
                                + " · " + b.getOrDefault("email", "-"));
            } catch (Exception ignore) { /* notificacao nunca quebra o cadastro */ }
            return ResponseEntity.ok(ApiEnvelope.ok(sessao(r)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail(e.getMessage()));
        }
    }

    /** POST /api/revenda/login — {email, senha} -> token. Aceita revendedor OU o master (DONO). */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> b) {
        String email = b.get("email");
        String senha = b.get("senha");
        if (throttle.bloqueado(email)) {
            return ResponseEntity.status(429).body(ApiEnvelope.fail("Muitas tentativas. Tente de novo em alguns minutos."));
        }
        // 1) revendedor (conta principal da revenda)
        var rev = revendas.autenticar(email, senha);
        if (rev.isPresent()) { throttle.ok(email); return ResponseEntity.ok(ApiEnvelope.ok(sessao(rev.get()))); }
        // 2) usuario da conta (master DONO -> ve tudo; usuario-master de revenda -> ve so a revenda dele)
        if (email != null && senha != null) {
            AppUser u = users.findByEmailIgnoreCase(email.trim()).orElse(null);
            if (u != null && u.isAtivo() && encoder.matches(senha, u.getSenhaHash())) {
                if ("DONO".equals(u.getRole())) {
                    throttle.ok(email);
                    return ResponseEntity.ok(ApiEnvelope.ok(masterSessao(u)));
                }
                if ("REVENDA".equals(u.getRole()) && u.getRevendaId() != null) {
                    Revenda r = revendas.porId(u.getRevendaId()).filter(Revenda::isAtivo).orElse(null);
                    if (r != null) {
                        throttle.ok(email);
                        return ResponseEntity.ok(ApiEnvelope.ok(sessaoRevendaUser(u, r)));
                    }
                }
            }
        }
        throttle.falhou(email);
        return ResponseEntity.status(401).body(ApiEnvelope.fail("E-mail ou senha inválidos"));
    }

    /** GET /api/revenda/lojas — as lojas do revendedor logado. */
    @GetMapping("/lojas")
    public ResponseEntity<Map<String, Object>> minhasLojas(HttpServletRequest req) {
        Revenda r = autorizar(req);
        if (r == null) return ResponseEntity.status(401).body(ApiEnvelope.fail("Não autorizado"));
        List<Map<String, Object>> out = lojas.findByRevendaCodigoOrderByAtualizadoEmDesc(r.getCodigo())
                .stream().map(this::lojaJson).toList();
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    /** POST /api/revenda/lojas/{cnpj}/ativar — libera a loja (após pagar os R$30). */
    @PostMapping("/lojas/{cnpj}/ativar")
    public ResponseEntity<Map<String, Object>> ativar(HttpServletRequest req, @PathVariable String cnpj) {
        Revenda r = autorizar(req);
        if (r == null) return ResponseEntity.status(401).body(ApiEnvelope.fail("Não autorizado"));
        String c = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        Loja l = lojas.findById(c).orElse(null);
        if (l == null || !r.getCodigo().equals(l.getRevendaCodigo())) {
            return ResponseEntity.status(404).body(ApiEnvelope.fail("Loja não encontrada na sua revenda"));
        }
        l.setRevendaAtivada(true);
        l.setBloqueada(false);
        lojas.save(l);
        // monta o ciclo de R$30/mês que o revendedor paga ao dono (1º mês coberto pela ativação)
        cobrancas.ativarRevendaStore(c);
        return ResponseEntity.ok(ApiEnvelope.ok(lojaJson(lojas.findById(c).orElse(l))));
    }

    /** POST /api/revenda/lojas/{cnpj}/bloquear — o revendedor bloqueia o cliente dele (não pagou a ele). */
    @PostMapping("/lojas/{cnpj}/bloquear")
    public ResponseEntity<Map<String, Object>> bloquear(HttpServletRequest req, @PathVariable String cnpj,
                                                        @RequestBody(required = false) Map<String, String> body) {
        Loja l = lojaDoRevendedor(req, cnpj);
        if (l == null) return ResponseEntity.status(404).body(ApiEnvelope.fail("Loja não encontrada na sua revenda"));
        l.setBloqueada(true);
        String motivo = body == null ? null : body.get("motivo");
        l.setMotivoBloqueio(motivo == null || motivo.isBlank() ? "Bloqueado pela revenda" : motivo);
        lojas.save(l);
        return ResponseEntity.ok(ApiEnvelope.ok(lojaJson(l)));
    }

    /** POST /api/revenda/lojas/{cnpj}/desbloquear — o revendedor libera o cliente dele. */
    @PostMapping("/lojas/{cnpj}/desbloquear")
    public ResponseEntity<Map<String, Object>> desbloquear(HttpServletRequest req, @PathVariable String cnpj) {
        Loja l = lojaDoRevendedor(req, cnpj);
        if (l == null) return ResponseEntity.status(404).body(ApiEnvelope.fail("Loja não encontrada na sua revenda"));
        l.setBloqueada(false);
        l.setMotivoBloqueio(null);
        lojas.save(l);
        return ResponseEntity.ok(ApiEnvelope.ok(lojaJson(l)));
    }

    /** POST /api/revenda/lojas/{cnpj}/pago — o revendedor pagou os R$30 do mês desta loja ao dono. */
    @PostMapping("/lojas/{cnpj}/pago")
    public ResponseEntity<Map<String, Object>> pago(HttpServletRequest req, @PathVariable String cnpj) {
        Loja l = lojaDoRevendedor(req, cnpj);
        if (l == null) return ResponseEntity.status(404).body(ApiEnvelope.fail("Loja não encontrada na sua revenda"));
        cobrancas.revendaPagou(l.getCnpj());
        return ResponseEntity.ok(ApiEnvelope.ok(lojaJson(lojas.findById(l.getCnpj()).orElse(l))));
    }

    /** GET /api/revenda/lojas/{cnpj}/pagamentos — parcelas pagas (histórico) da loja. */
    @GetMapping("/lojas/{cnpj}/pagamentos")
    public ResponseEntity<Map<String, Object>> pagamentos(HttpServletRequest req, @PathVariable String cnpj) {
        Loja l = lojaDoRevendedor(req, cnpj);
        if (l == null) return ResponseEntity.status(404).body(ApiEnvelope.fail("Loja não encontrada na sua revenda"));
        return ResponseEntity.ok(ApiEnvelope.ok(cobrancas.historico(l.getCnpj())));
    }

    /** POST /api/revenda/pagar-lote {cnpjs:[...]} — um boleto/Pix só (R$30×N) pro revendedor pagar o dono. */
    @PostMapping("/pagar-lote")
    public ResponseEntity<Map<String, Object>> pagarLote(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        Revenda r = autorizar(req);
        if (r == null) return ResponseEntity.status(401).body(ApiEnvelope.fail("Não autorizado"));
        @SuppressWarnings("unchecked")
        List<String> pedidos = body != null && body.get("cnpjs") instanceof List ? (List<String>) body.get("cnpjs") : List.of();
        List<String> cnpjs = new java.util.ArrayList<>();
        for (String raw : pedidos) {
            String c = raw == null ? "" : raw.replaceAll("\\D", "");
            Loja l = lojas.findById(c).orElse(null);
            if (l != null && r.getCodigo().equals(l.getRevendaCodigo())) cnpjs.add(c);
        }
        if (cnpjs.isEmpty()) return ResponseEntity.status(400).body(ApiEnvelope.fail("Selecione ao menos uma loja"));
        try {
            var res = cobrancas.cobrarRevenda(r.getAsaasCustomerId(), r.getNome(), r.getCpfCnpj(), r.getEmail(), cnpjs);
            if (r.getAsaasCustomerId() == null || r.getAsaasCustomerId().isBlank()) {
                revendas.definirAsaasCustomer(r.getId(), res.custId());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("linkPagamento", res.linkPagamento() == null ? "" : res.linkPagamento());
            out.put("valor", res.valor());
            out.put("vencimento", res.vencimento());
            out.put("lojas", cnpjs.size());
            return ResponseEntity.ok(ApiEnvelope.ok(out));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(ApiEnvelope.fail("Falha ao gerar pagamento: " + e.getMessage()));
        }
    }

    /** Loja pelo cnpj, só se for do revendedor logado; null caso contrário. */
    private Loja lojaDoRevendedor(HttpServletRequest req, String cnpj) {
        Revenda r = autorizar(req);
        if (r == null) return null;
        String c = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        Loja l = lojas.findById(c).orElse(null);
        return (l != null && r.getCodigo().equals(l.getRevendaCodigo())) ? l : null;
    }

    /** POST /api/revenda/lojas/{cnpj}/grupo — organiza a loja num grupo (vazio = remove). Só as lojas do revendedor. */
    @PostMapping("/lojas/{cnpj}/grupo")
    public ResponseEntity<Map<String, Object>> definirGrupo(HttpServletRequest req, @PathVariable String cnpj,
                                                            @RequestBody(required = false) Map<String, String> body) {
        Revenda r = autorizar(req);
        if (r == null) return ResponseEntity.status(401).body(ApiEnvelope.fail("Não autorizado"));
        String c = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        Loja l = lojas.findById(c).orElse(null);
        if (l == null || !r.getCodigo().equals(l.getRevendaCodigo())) {
            return ResponseEntity.status(404).body(ApiEnvelope.fail("Loja não encontrada na sua revenda"));
        }
        l.setGrupo(body == null ? null : body.get("grupo"));
        lojas.save(l);
        return ResponseEntity.ok(ApiEnvelope.ok(lojaJson(l)));
    }

    /**
     * GET /api/revenda/instalador-base — o instalador-base LIMPO (zip), em streaming a
     * partir do release do GitHub (o GitHub nao libera CORS, entao o painel baixa por aqui:
     * o relay ja responde com CORS aberto). O navegador injeta o codigo do revendedor no
     * RaizesAgente.xml. Sem auth: o base e igual pra todos e nao tem segredo.
     */
    @GetMapping("/instalador-base")
    public ResponseEntity<InputStreamResource> instaladorBase() {
        String url = "https://github.com/vinny-rbr/raizestecnologia-agente/releases/download/instalador-base/instalador-base.zip";
        try {
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<java.io.InputStream> up = http.send(
                    HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "meugiro-relay").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (up.statusCode() != 200) {
                return ResponseEntity.status(502).build();
            }
            ResponseEntity.BodyBuilder b = ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"instalador-base.zip\"")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate");
            up.headers().firstValue("content-length").ifPresent(len -> b.header(HttpHeaders.CONTENT_LENGTH, len));
            return b.body(new InputStreamResource(up.body()));
        } catch (Exception e) {
            return ResponseEntity.status(502).build();
        }
    }

    // ---- Usuarios das lojas do revendedor -------------------------------
    // O revendedor cria/gerencia os usuarios (OPERADOR) das lojas DELE, definindo
    // a loja e as permissoes (so estoque / ve tudo / por tela). Nunca toca em DONO
    // nem em usuario de loja que nao e da revenda dele.

    /** GET /api/revenda/usuarios — usuarios das lojas do revendedor logado. */
    @GetMapping("/usuarios")
    public ResponseEntity<Map<String, Object>> usuarios(HttpServletRequest req) {
        Revenda r = autorizar(req);
        if (r == null) return ResponseEntity.status(401).body(ApiEnvelope.fail("Não autorizado"));
        Map<String, String> nomes = nomesDasLojas(r);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppUser u : users.findAll()) {
            List<UserEmpresa> vs = vinculos.findByUserId(u.getId());
            if ("OPERADOR".equals(u.getRole()) && vs.stream().anyMatch(v -> nomes.containsKey(v.getCnpj()))) {
                out.add(usuarioJson(u, vs, nomes));
            }
        }
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    /** POST /api/revenda/usuarios — cria um OPERADOR numa loja do revendedor. */
    @PostMapping("/usuarios")
    @Transactional
    public ResponseEntity<Map<String, Object>> criarUsuario(HttpServletRequest req, @RequestBody Map<String, Object> b) {
        Revenda r = autorizar(req);
        if (r == null) return ResponseEntity.status(401).body(ApiEnvelope.fail("Não autorizado"));
        Map<String, String> nomes = nomesDasLojas(r);
        String email = str(b.get("email"));
        String senha = str(b.get("senha"));
        if (email.isBlank()) return ResponseEntity.status(400).body(ApiEnvelope.fail("E-mail obrigatório"));
        if (senha.isBlank()) return ResponseEntity.status(400).body(ApiEnvelope.fail("Senha obrigatória"));
        if (users.findByEmailIgnoreCase(email).isPresent())
            return ResponseEntity.status(409).body(ApiEnvelope.fail("E-mail já cadastrado"));
        List<String> cnpjs = cnpjsPedidos(b);
        if (cnpjs.isEmpty()) return ResponseEntity.status(400).body(ApiEnvelope.fail("Escolha a loja do usuário"));
        for (String c : cnpjs)
            if (!nomes.containsKey(c)) return ResponseEntity.status(403).body(ApiEnvelope.fail("Loja não é da sua revenda"));
        AppUser u = new AppUser();
        u.setNome(str(b.get("nome")));
        u.setEmail(email);
        u.setSenhaHash(encoder.encode(senha));
        u.setRole("OPERADOR");
        u.setPermissoes(normalizarPermissoes(listaStr(b.get("permissoes"))));
        u.setAtivo(true);
        if (b.get("sessaoUnica") instanceof Boolean su) u.setSessaoUnica(su);
        u.setSenhaProvisoria(true); // 1o acesso: o usuario troca a senha
        for (String c : cnpjs) u.getEmpresas().add(new UserEmpresa(u, c));
        AppUser saved = users.save(u);
        return ResponseEntity.ok(ApiEnvelope.ok(usuarioJson(saved, vinculos.findByUserId(saved.getId()), nomes)));
    }

    /** POST /api/revenda/usuarios/{id} — edita nome/ativo/permissoes de um usuario da revenda. */
    @PostMapping("/usuarios/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> editarUsuario(HttpServletRequest req, @PathVariable Long id,
                                                             @RequestBody Map<String, Object> b) {
        Revenda r = autorizar(req);
        if (r == null) return ResponseEntity.status(401).body(ApiEnvelope.fail("Não autorizado"));
        Map<String, String> nomes = nomesDasLojas(r);
        AppUser u = users.findById(id).orElse(null);
        List<UserEmpresa> vs = u == null ? List.of() : vinculos.findByUserId(u.getId());
        if (u == null || !podeMexer(u, vs, nomes.keySet()))
            return ResponseEntity.status(404).body(ApiEnvelope.fail("Usuário não encontrado na sua revenda"));
        if (b.containsKey("nome")) u.setNome(str(b.get("nome")));
        if (b.get("ativo") instanceof Boolean bo) u.setAtivo(bo);
        if (b.get("sessaoUnica") instanceof Boolean su) u.setSessaoUnica(su);
        if (b.get("deviceLock") instanceof Boolean dl) u.setDeviceLock(dl);
        if (b.containsKey("permissoes")) u.setPermissoes(normalizarPermissoes(listaStr(b.get("permissoes"))));
        users.save(u);
        return ResponseEntity.ok(ApiEnvelope.ok(usuarioJson(u, vinculos.findByUserId(u.getId()), nomes)));
    }

    /** POST /api/revenda/usuarios/{id}/senha — define nova senha (provisoria) de um usuario da revenda. */
    @PostMapping("/usuarios/{id}/senha")
    @Transactional
    public ResponseEntity<Map<String, Object>> senhaUsuario(HttpServletRequest req, @PathVariable Long id,
                                                            @RequestBody Map<String, Object> b) {
        Revenda r = autorizar(req);
        if (r == null) return ResponseEntity.status(401).body(ApiEnvelope.fail("Não autorizado"));
        Map<String, String> nomes = nomesDasLojas(r);
        AppUser u = users.findById(id).orElse(null);
        List<UserEmpresa> vs = u == null ? List.of() : vinculos.findByUserId(u.getId());
        if (u == null || !podeMexer(u, vs, nomes.keySet()))
            return ResponseEntity.status(404).body(ApiEnvelope.fail("Usuário não encontrado na sua revenda"));
        String senha = str(b.get("senha"));
        if (senha.isBlank()) return ResponseEntity.status(400).body(ApiEnvelope.fail("Senha obrigatória"));
        u.setSenhaHash(encoder.encode(senha));
        u.setSenhaProvisoria(true);
        users.save(u);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("ok", true)));
    }

    /** POST /api/revenda/usuarios/{id}/liberar-aparelho — autoriza o aparelho novo (pendente). */
    @PostMapping("/usuarios/{id}/liberar-aparelho")
    @Transactional
    public ResponseEntity<Map<String, Object>> liberarAparelho(HttpServletRequest req, @PathVariable Long id) {
        Revenda r = autorizar(req);
        if (r == null) return ResponseEntity.status(401).body(ApiEnvelope.fail("Não autorizado"));
        Map<String, String> nomes = nomesDasLojas(r);
        AppUser u = users.findById(id).orElse(null);
        List<UserEmpresa> vs = u == null ? List.of() : vinculos.findByUserId(u.getId());
        if (u == null || !podeMexer(u, vs, nomes.keySet()))
            return ResponseEntity.status(404).body(ApiEnvelope.fail("Usuário não encontrado na sua revenda"));
        if (u.getDevicePendente() == null || u.getDevicePendente().isBlank())
            return ResponseEntity.status(400).body(ApiEnvelope.fail("Não há aparelho novo aguardando."));
        u.setDeviceAtual(u.getDevicePendente());
        u.setDeviceAtualNome(u.getDevicePendenteNome());
        u.setDevicePendente(null);
        u.setDevicePendenteNome(null);
        users.save(u);
        return ResponseEntity.ok(ApiEnvelope.ok(usuarioJson(u, vinculos.findByUserId(u.getId()), nomes)));
    }

    /** POST /api/revenda/usuarios/{id}/resetar-aparelho — zera o aparelho (proximo login re-vincula). */
    @PostMapping("/usuarios/{id}/resetar-aparelho")
    @Transactional
    public ResponseEntity<Map<String, Object>> resetarAparelho(HttpServletRequest req, @PathVariable Long id) {
        Revenda r = autorizar(req);
        if (r == null) return ResponseEntity.status(401).body(ApiEnvelope.fail("Não autorizado"));
        Map<String, String> nomes = nomesDasLojas(r);
        AppUser u = users.findById(id).orElse(null);
        List<UserEmpresa> vs = u == null ? List.of() : vinculos.findByUserId(u.getId());
        if (u == null || !podeMexer(u, vs, nomes.keySet()))
            return ResponseEntity.status(404).body(ApiEnvelope.fail("Usuário não encontrado na sua revenda"));
        u.setDeviceAtual(null);
        u.setDeviceAtualNome(null);
        u.setDevicePendente(null);
        u.setDevicePendenteNome(null);
        users.save(u);
        return ResponseEntity.ok(ApiEnvelope.ok(usuarioJson(u, vinculos.findByUserId(u.getId()), nomes)));
    }

    /** DELETE /api/revenda/usuarios/{id} — remove um usuario que é só das lojas da revenda. */
    @DeleteMapping("/usuarios/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> removerUsuario(HttpServletRequest req, @PathVariable Long id) {
        Revenda r = autorizar(req);
        if (r == null) return ResponseEntity.status(401).body(ApiEnvelope.fail("Não autorizado"));
        Set<String> meus = nomesDasLojas(r).keySet();
        AppUser u = users.findById(id).orElse(null);
        List<UserEmpresa> vs = u == null ? List.of() : vinculos.findByUserId(u.getId());
        if (u == null || !podeMexer(u, vs, meus))
            return ResponseEntity.status(404).body(ApiEnvelope.fail("Usuário não encontrado na sua revenda"));
        // só apaga de vez se TODAS as lojas dele são da revenda (senão afetaria outra revenda/o dono)
        if (!vs.stream().allMatch(v -> meus.contains(v.getCnpj())))
            return ResponseEntity.status(409).body(ApiEnvelope.fail("Esse usuário também está em lojas de outra conta; não dá pra excluir por aqui."));
        users.deleteById(id);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("ok", true)));
    }

    // ---- Usuarios-master da revenda -------------------------------------
    // Logins extras do painel da revenda (role REVENDA + revendaId). Cada um enxerga
    // SO os clientes desta revenda; quem ve todas as revendas e apenas o DONO (master do dono).

    /** GET /api/revenda/masters — os usuarios-master desta revenda. */
    @GetMapping("/masters")
    public ResponseEntity<Map<String, Object>> masters(HttpServletRequest req) {
        Revenda r = autorizar(req);
        if (r == null) return ResponseEntity.status(401).body(ApiEnvelope.fail("Não autorizado"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppUser u : users.findByRevendaId(r.getId())) out.add(masterJson(u));
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    /** POST /api/revenda/masters — cria um usuario-master desta revenda. */
    @PostMapping("/masters")
    @Transactional
    public ResponseEntity<Map<String, Object>> criarMaster(HttpServletRequest req, @RequestBody Map<String, Object> b) {
        Revenda r = autorizar(req);
        if (r == null) return ResponseEntity.status(401).body(ApiEnvelope.fail("Não autorizado"));
        String email = str(b.get("email"));
        String senha = str(b.get("senha"));
        if (email.isBlank()) return ResponseEntity.status(400).body(ApiEnvelope.fail("E-mail obrigatório"));
        if (senha.isBlank()) return ResponseEntity.status(400).body(ApiEnvelope.fail("Senha obrigatória"));
        if (users.findByEmailIgnoreCase(email).isPresent())
            return ResponseEntity.status(409).body(ApiEnvelope.fail("E-mail já cadastrado"));
        AppUser u = new AppUser();
        u.setNome(str(b.get("nome")));
        u.setEmail(email);
        u.setSenhaHash(encoder.encode(senha));
        u.setRole("REVENDA");
        u.setRevendaId(r.getId());
        u.setAtivo(true);
        AppUser saved = users.save(u);
        return ResponseEntity.ok(ApiEnvelope.ok(masterJson(saved)));
    }

    /** POST /api/revenda/masters/{id} — edita nome/ativo de um usuario-master desta revenda. */
    @PostMapping("/masters/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> editarMaster(HttpServletRequest req, @PathVariable Long id,
                                                            @RequestBody Map<String, Object> b) {
        Revenda r = autorizar(req);
        if (r == null) return ResponseEntity.status(401).body(ApiEnvelope.fail("Não autorizado"));
        AppUser u = masterDaRevenda(id, r);
        if (u == null) return ResponseEntity.status(404).body(ApiEnvelope.fail("Usuário não encontrado nesta revenda"));
        if (b.containsKey("nome")) u.setNome(str(b.get("nome")));
        if (b.get("ativo") instanceof Boolean bo) u.setAtivo(bo);
        users.save(u);
        return ResponseEntity.ok(ApiEnvelope.ok(masterJson(u)));
    }

    /** POST /api/revenda/masters/{id}/senha — troca a senha de um usuario-master desta revenda. */
    @PostMapping("/masters/{id}/senha")
    @Transactional
    public ResponseEntity<Map<String, Object>> senhaMaster(HttpServletRequest req, @PathVariable Long id,
                                                           @RequestBody Map<String, Object> b) {
        Revenda r = autorizar(req);
        if (r == null) return ResponseEntity.status(401).body(ApiEnvelope.fail("Não autorizado"));
        AppUser u = masterDaRevenda(id, r);
        if (u == null) return ResponseEntity.status(404).body(ApiEnvelope.fail("Usuário não encontrado nesta revenda"));
        String senha = str(b.get("senha"));
        if (senha.isBlank()) return ResponseEntity.status(400).body(ApiEnvelope.fail("Senha obrigatória"));
        u.setSenhaHash(encoder.encode(senha));
        users.save(u);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("ok", true)));
    }

    /** DELETE /api/revenda/masters/{id} — remove um usuario-master desta revenda. */
    @DeleteMapping("/masters/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> removerMaster(HttpServletRequest req, @PathVariable Long id) {
        Revenda r = autorizar(req);
        if (r == null) return ResponseEntity.status(401).body(ApiEnvelope.fail("Não autorizado"));
        AppUser u = masterDaRevenda(id, r);
        if (u == null) return ResponseEntity.status(404).body(ApiEnvelope.fail("Usuário não encontrado nesta revenda"));
        users.deleteById(id);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("ok", true)));
    }

    /** Usuario-master pelo id, so se pertence a esta revenda; null caso contrario. */
    private AppUser masterDaRevenda(Long id, Revenda r) {
        AppUser u = id == null ? null : users.findById(id).orElse(null);
        if (u == null || !"REVENDA".equals(u.getRole())) return null;
        return r.getId().equals(u.getRevendaId()) ? u : null;
    }

    private Map<String, Object> masterJson(AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("nome", u.getNome());
        m.put("email", u.getEmail());
        m.put("ativo", u.isAtivo());
        return m;
    }

    /** Loja(s) do revendedor: cnpj -> nome. */
    private Map<String, String> nomesDasLojas(Revenda r) {
        Map<String, String> nomes = new LinkedHashMap<>();
        for (Loja l : lojas.findByRevendaCodigoOrderByAtualizadoEmDesc(r.getCodigo())) {
            nomes.put(l.getCnpj(), l.getNome() == null ? "" : l.getNome());
        }
        return nomes;
    }

    /** true se o usuario é OPERADOR e tem ao menos uma loja da revenda (pode ser gerenciado). */
    private boolean podeMexer(AppUser u, List<UserEmpresa> vs, Set<String> meus) {
        return "OPERADOR".equals(u.getRole()) && vs.stream().anyMatch(v -> meus.contains(v.getCnpj()));
    }

    private Map<String, Object> usuarioJson(AppUser u, List<UserEmpresa> vs, Map<String, String> nomes) {
        List<Map<String, String>> empresas = new ArrayList<>();
        for (UserEmpresa v : vs) {
            if (!nomes.containsKey(v.getCnpj())) continue; // só as lojas da revenda
            Map<String, String> e = new LinkedHashMap<>();
            e.put("cnpj", v.getCnpj());
            e.put("nome", nomes.getOrDefault(v.getCnpj(), ""));
            empresas.add(e);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("nome", u.getNome());
        m.put("email", u.getEmail());
        m.put("ativo", u.isAtivo());
        m.put("sessaoUnica", u.isSessaoUnica());
        m.put("deviceLock", u.isDeviceLock());
        m.put("deviceAtualNome", u.getDeviceAtualNome() == null ? "" : u.getDeviceAtualNome());
        m.put("devicePendenteNome", u.getDevicePendenteNome() == null ? "" : u.getDevicePendenteNome());
        m.put("devicePendente", u.getDevicePendente() != null && !u.getDevicePendente().isBlank());
        m.put("permissoes", u.permissoesList());
        m.put("empresas", empresas);
        return m;
    }

    /** Filtra pros modulos conhecidos e junta em CSV; null/vazio = acesso total (vê tudo). */
    private static String normalizarPermissoes(List<String> perms) {
        if (perms == null || perms.isEmpty()) return null;
        List<String> ok = perms.stream()
                .filter(java.util.Objects::nonNull)
                .map(s -> s.trim().toLowerCase())
                .filter(Modulos.TODOS::contains)
                .distinct()
                .toList();
        return ok.isEmpty() ? null : String.join(",", ok);
    }

    /** CNPJs pedidos no body: aceita "cnpjs":[...] ou "cnpj":"..." (só dígitos). */
    private static List<String> cnpjsPedidos(Map<String, Object> b) {
        List<String> raw = new ArrayList<>();
        if (b.get("cnpjs") instanceof List<?> l) for (Object o : l) raw.add(String.valueOf(o));
        if (b.get("cnpj") != null) raw.add(String.valueOf(b.get("cnpj")));
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            String c = s == null ? "" : s.replaceAll("\\D", "");
            if (!c.isEmpty() && !out.contains(c)) out.add(c);
        }
        return out;
    }

    private static List<String> listaStr(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) if (x != null) out.add(String.valueOf(x));
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    // ---- helpers ----

    private Map<String, Object> sessao(Revenda r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo", "revenda");
        m.put("id", r.getId());
        m.put("nome", r.getNome());
        m.put("email", r.getEmail());
        m.put("codigo", r.getCodigo());
        m.put("token", jwt.generate(r.getId(), r.getEmail(), "REVENDA", null,
                Map.of("revId", r.getId())));
        return m;
    }

    /** Sessao de um usuario-master da revenda: token REVENDA com revId apontando pra revenda dele. */
    private Map<String, Object> sessaoRevendaUser(AppUser u, Revenda r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo", "revenda");
        m.put("id", u.getId());
        m.put("nome", u.getNome() == null || u.getNome().isBlank() ? r.getNome() : u.getNome());
        m.put("email", u.getEmail());
        m.put("codigo", r.getCodigo());
        // subject = id do AppUser; revId = id da revenda (o autorizar resolve a revenda por ele).
        m.put("token", jwt.generate(u.getId(), u.getEmail(), "REVENDA", null,
                Map.of("revId", r.getId())));
        return m;
    }

    /** Sessao do master (DONO): entra no painel com os poderes que ja tem no celular. */
    private Map<String, Object> masterSessao(AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo", "master");
        m.put("id", u.getId());
        m.put("nome", u.getNome() == null || u.getNome().isBlank() ? "Administrador" : u.getNome());
        m.put("email", u.getEmail());
        m.put("codigo", null);
        m.put("token", jwt.generate(u.getId(), u.getEmail(), u.getRole()));
        return m;
    }

    /** Lê o Bearer, valida (role REVENDA) e devolve a revenda; null se inválido. */
    private Revenda autorizar(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) return null;
        try {
            Claims c = jwt.parse(h.substring(7).trim());
            if (!"REVENDA".equals(String.valueOf(c.get("role")))) return null;
            // revId (usuarios-master e contas novas) manda; subject e fallback pros tokens antigos.
            Long revId = null;
            Object rv = c.get("revId");
            if (rv != null) { try { revId = Long.valueOf(String.valueOf(rv)); } catch (Exception ignore) {} }
            if (revId == null) { try { revId = Long.valueOf(c.getSubject()); } catch (Exception ignore) {} }
            if (revId == null) return null;
            return revendas.porId(revId).filter(Revenda::isAtivo).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> lojaJson(Loja l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cnpj", l.getCnpj());
        m.put("nome", l.getNome());
        m.put("online", hub.online(l.getCnpj()));
        m.put("ativadaEm", l.getAtivadaEm() == null ? null : l.getAtivadaEm().toString());
        m.put("diasUso", diasUso(l.getAtivadaEm()));
        m.put("diaVencimento", l.getDiaVencimento());
        m.put("vencimento", proximoVenc(l.getDiaVencimento()).toString());
        String status;
        if (l.getRevendaCodigo() != null && !l.isRevendaAtivada()) status = "aguardando";
        else if (l.isBloqueada()) status = "bloqueada";
        else status = "ativa";
        m.put("status", status);
        m.put("bloqueada", l.isBloqueada());
        m.put("grupo", l.getGrupo());
        // ciclo de R$30/mês que o revendedor paga ao dono
        m.put("mensalidade", com.raizestecnologia.relay.cobranca.CobrancaService.REVENDA_MENSALIDADE);
        LocalDate venc = proximoVenc(l.getDiaVencimento());
        boolean pago = l.getMensalidadePagaAte() != null && !l.getMensalidadePagaAte().isBefore(venc);
        m.put("pago", pago);
        m.put("motivo", l.isBloqueada() ? (l.getMotivoBloqueio() == null ? "" : l.getMotivoBloqueio()) : "");
        return m;
    }

    private int diasUso(Instant ativadaEm) {
        if (ativadaEm == null) return 0;
        long d = ChronoUnit.DAYS.between(ativadaEm.atZone(BRT).toLocalDate(), LocalDate.now(BRT));
        return (int) Math.max(0, d);
    }

    private LocalDate proximoVenc(int dia) {
        int d = Math.min(28, Math.max(1, dia));
        LocalDate hoje = LocalDate.now(BRT);
        LocalDate dt = hoje.withDayOfMonth(d);
        if (dt.isBefore(hoje)) dt = hoje.plusMonths(1).withDayOfMonth(d);
        return dt;
    }
}
