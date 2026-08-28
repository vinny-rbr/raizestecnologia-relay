package com.raizestecnologia.relay.revenda;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Optional;

/**
 * Regras da revenda: cadastro (gera codigo unico + senha cifrada) e autenticacao.
 */
@Service
public class RevendaService {

    private static final String ALFA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // sem 0/O/1/I
    private static final SecureRandom RND = new SecureRandom();

    private final RevendaRepository repo;
    private final PasswordEncoder encoder;

    public RevendaService(RevendaRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    /** Cadastra um revendedor. Lança IllegalArgumentException se faltar dado ou e-mail repetido. */
    public Revenda cadastrar(String nome, String cpfCnpj, String email, String telefone,
                             String cidade, String uf, String senha) {
        String nm = nome == null ? "" : nome.trim();
        String em = email == null ? "" : email.trim().toLowerCase();
        if (nm.isBlank()) throw new IllegalArgumentException("Informe o nome");
        if (em.isBlank() || !em.contains("@")) throw new IllegalArgumentException("E-mail inválido");
        if (senha == null || senha.length() < 6) throw new IllegalArgumentException("Senha muito curta (mín. 6)");
        if (repo.existsByEmail(em)) throw new IllegalArgumentException("Já existe uma revenda com esse e-mail");

        Revenda r = new Revenda(nm,
                cpfCnpj == null ? null : cpfCnpj.replaceAll("\\D", ""),
                em, telefone, cidade,
                uf == null ? null : uf.trim().toUpperCase(),
                encoder.encode(senha), gerarCodigo());
        return repo.save(r);
    }

    /** Autentica por e-mail/senha. Vazio se inválido ou inativo. */
    public Optional<Revenda> autenticar(String email, String senha) {
        String em = email == null ? "" : email.trim().toLowerCase();
        return repo.findByEmail(em)
                .filter(Revenda::isAtivo)
                .filter(r -> senha != null && encoder.matches(senha, r.getSenhaHash()));
    }

    public Optional<Revenda> porId(Long id) { return id == null ? Optional.empty() : repo.findById(id); }
    public Optional<Revenda> porCodigo(String codigo) { return repo.findByCodigo(codigo); }

    @org.springframework.transaction.annotation.Transactional
    public void definirAsaasCustomer(Long id, String custId) {
        repo.findById(id).ifPresent(r -> { r.setAsaasCustomerId(custId); repo.save(r); });
    }

    private String gerarCodigo() {
        for (int tent = 0; tent < 20; tent++) {
            StringBuilder sb = new StringBuilder("REV");
            for (int i = 0; i < 6; i++) sb.append(ALFA.charAt(RND.nextInt(ALFA.length())));
            String cod = sb.toString();
            if (!repo.existsByCodigo(cod)) return cod;
        }
        throw new IllegalStateException("Não foi possível gerar código de revenda");
    }
}
