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

    public AdminController(AppUserRepository users, UserEmpresaRepository vinculos,
                           PasswordEncoder encoder, AgentHub hub) {
        this.users = users;
        this.vinculos = vinculos;
        this.encoder = encoder;
        this.hub = hub;
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
        List<Map<String, String>> lista = hub.empresas().stream()
                .map(e -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("cnpj", e.cnpj());
                    m.put("nome", e.nome());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(ApiEnvelope.ok(lista));
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
