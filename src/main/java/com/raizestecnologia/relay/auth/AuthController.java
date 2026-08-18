package com.raizestecnologia.relay.auth;

import com.raizestecnologia.relay.auth.dto.LoginRequest;
import com.raizestecnologia.relay.auth.dto.SenhaRequest;
import jakarta.transaction.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Autenticacao: login (emite JWT) e /me (dados do token).
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final com.raizestecnologia.relay.audit.AuditoriaService auditoria;
    private final com.raizestecnologia.relay.push.DeviceTokenRepository deviceTokens;

    public AuthController(AppUserRepository users, PasswordEncoder encoder, JwtService jwt,
                          com.raizestecnologia.relay.audit.AuditoriaService auditoria,
                          com.raizestecnologia.relay.push.DeviceTokenRepository deviceTokens) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.auditoria = auditoria;
        this.deviceTokens = deviceTokens;
    }

    /** POST /api/auth/login -> {id,name,email,role,store,token}. 401 se invalido/inativo. */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) LoginRequest req) {
        String email = req == null ? null : req.email();
        String senha = req == null ? null : req.senhaEfetiva();
        if (email == null || email.isBlank() || senha == null || senha.isBlank()) {
            return ResponseEntity.status(401).body(ApiEnvelope.fail("Credenciais invalidas"));
        }

        AppUser user = users.findByEmailIgnoreCase(email.trim()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).body(ApiEnvelope.fail("Usuário não encontrado"));
        }
        if (!user.isAtivo()) {
            return ResponseEntity.status(403).body(ApiEnvelope.fail("Usuário inativo. Fale com o administrador."));
        }
        if (!encoder.matches(senha, user.getSenhaHash())) {
            return ResponseEntity.status(401).body(ApiEnvelope.fail("Senha inválida"));
        }

        String token = jwt.generate(user.getId(), user.getEmail(), user.getRole());
        auditoria.registrar(user.getId(), user.getEmail(), user.getNome(), null, "login", "login efetuado");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.getId());
        data.put("name", user.getNome() == null ? "" : user.getNome());
        data.put("email", user.getEmail());
        data.put("role", user.getRole());
        data.put("store", "");
        data.put("permissoes", user.permissoesList());
        data.put("senhaProvisoria", user.isSenhaProvisoria());
        data.put("token", token);
        return ResponseEntity.ok(ApiEnvelope.ok(data));
    }

    /** GET /api/auth/me -> {id,name,email,role} (do token/banco). */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        RelayPrincipal p = CurrentUser.get();
        if (p == null) {
            return ResponseEntity.status(401).body(ApiEnvelope.fail("Nao autenticado"));
        }
        AppUser user = users.findById(Long.valueOf(p.userId())).orElse(null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", Long.valueOf(p.userId()));
        data.put("name", user != null && user.getNome() != null ? user.getNome() : "");
        data.put("email", p.email());
        data.put("role", p.role());
        data.put("permissoes", user != null ? user.permissoesList() : java.util.List.of());
        return ResponseEntity.ok(ApiEnvelope.ok(data));
    }

    /**
     * POST /api/auth/senha — o proprio usuario autenticado define/troca a senha dele
     * (usado no 1o acesso, quando a senha veio provisoria do admin). Limpa o flag provisorio.
     */
    @PostMapping("/senha")
    @Transactional
    public ResponseEntity<Map<String, Object>> trocarMinhaSenha(@RequestBody SenhaRequest req) {
        RelayPrincipal p = CurrentUser.get();
        if (p == null) {
            return ResponseEntity.status(401).body(ApiEnvelope.fail("Nao autenticado"));
        }
        String nova = req == null ? null : req.senha();
        if (nova == null || nova.trim().length() < 4) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("A senha precisa de ao menos 4 caracteres"));
        }
        AppUser user = users.findById(Long.valueOf(p.userId())).orElse(null);
        if (user == null) {
            return ResponseEntity.status(404).body(ApiEnvelope.fail("Usuario nao encontrado"));
        }
        user.setSenhaHash(encoder.encode(nova));
        user.setSenhaProvisoria(false);
        users.save(user);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("ok", true)));
    }

    /**
     * POST /api/auth/excluir-conta — o proprio usuario autenticado exclui a conta dele
     * e os dados associados (vinculos de loja + tokens de push). Exigido pela Apple
     * (5.1.1(v)) para apps que permitem criar conta.
     */
    @PostMapping("/excluir-conta")
    @Transactional
    public ResponseEntity<Map<String, Object>> excluirConta() {
        RelayPrincipal p = CurrentUser.get();
        if (p == null) {
            return ResponseEntity.status(401).body(ApiEnvelope.fail("Nao autenticado"));
        }
        Long id;
        try { id = Long.valueOf(p.userId()); }
        catch (Exception e) { return ResponseEntity.status(400).body(ApiEnvelope.fail("Usuario invalido")); }

        AppUser user = users.findById(id).orElse(null);
        if (user == null) {
            return ResponseEntity.status(404).body(ApiEnvelope.fail("Usuario nao encontrado"));
        }
        try { deviceTokens.deleteByUserId(id); } catch (Exception ignore) {}
        auditoria.registrar(id, user.getEmail(), user.getNome(), null, "conta_excluida",
                "conta excluida pelo proprio usuario");
        users.delete(user); // remove tambem os vinculos de empresa (orphanRemoval)
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("ok", true)));
    }
}
