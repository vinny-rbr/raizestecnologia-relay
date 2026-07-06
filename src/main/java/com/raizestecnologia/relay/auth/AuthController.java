package com.raizestecnologia.relay.auth;

import com.raizestecnologia.relay.auth.dto.LoginRequest;
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

    public AuthController(AppUserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
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
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.getId());
        data.put("name", user.getNome() == null ? "" : user.getNome());
        data.put("email", user.getEmail());
        data.put("role", user.getRole());
        data.put("store", "");
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
        return ResponseEntity.ok(ApiEnvelope.ok(data));
    }
}
