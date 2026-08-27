package com.raizestecnologia.relay.revenda;

import com.raizestecnologia.relay.AgentHub;
import com.raizestecnologia.relay.auth.ApiEnvelope;
import com.raizestecnologia.relay.auth.AppUser;
import com.raizestecnologia.relay.auth.AppUserRepository;
import com.raizestecnologia.relay.auth.JwtService;
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
@CrossOrigin(origins = "*")
public class RevendaController {

    private static final ZoneId BRT = ZoneId.of("America/Sao_Paulo");

    private final RevendaService revendas;
    private final LojaRepository lojas;
    private final AgentHub hub;
    private final JwtService jwt;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;

    public RevendaController(RevendaService revendas, LojaRepository lojas, AgentHub hub, JwtService jwt,
                             AppUserRepository users, PasswordEncoder encoder) {
        this.revendas = revendas;
        this.lojas = lojas;
        this.hub = hub;
        this.jwt = jwt;
        this.users = users;
        this.encoder = encoder;
    }

    /** POST /api/revenda/cadastro — cadastra um revendedor (CPF/CNPJ + dados) e ja loga. */
    @PostMapping("/cadastro")
    public ResponseEntity<Map<String, Object>> cadastro(@RequestBody Map<String, String> b) {
        try {
            Revenda r = revendas.cadastrar(
                    b.get("nome"), b.get("cpfCnpj"), b.get("email"), b.get("telefone"),
                    b.get("cidade"), b.get("uf"), b.get("senha"));
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
        // 1) revendedor
        var rev = revendas.autenticar(email, senha);
        if (rev.isPresent()) return ResponseEntity.ok(ApiEnvelope.ok(sessao(rev.get())));
        // 2) master (mesmo login do celular; so DONO acessa o painel como master)
        if (email != null && senha != null) {
            AppUser u = users.findByEmailIgnoreCase(email.trim()).orElse(null);
            if (u != null && u.isAtivo() && "DONO".equals(u.getRole()) && encoder.matches(senha, u.getSenhaHash())) {
                return ResponseEntity.ok(ApiEnvelope.ok(masterSessao(u)));
            }
        }
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
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600");
            up.headers().firstValue("content-length").ifPresent(len -> b.header(HttpHeaders.CONTENT_LENGTH, len));
            return b.body(new InputStreamResource(up.body()));
        } catch (Exception e) {
            return ResponseEntity.status(502).build();
        }
    }

    // ---- helpers ----

    private Map<String, Object> sessao(Revenda r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo", "revenda");
        m.put("id", r.getId());
        m.put("nome", r.getNome());
        m.put("email", r.getEmail());
        m.put("codigo", r.getCodigo());
        m.put("token", jwt.generate(r.getId(), r.getEmail(), "REVENDA"));
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
            return revendas.porId(Long.valueOf(c.getSubject())).filter(Revenda::isAtivo).orElse(null);
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
