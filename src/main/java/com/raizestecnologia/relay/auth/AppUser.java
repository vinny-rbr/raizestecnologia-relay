package com.raizestecnologia.relay.auth;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Usuario real do sistema (login por email/senha).
 * Tabela: app_user
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nome")
    private String nome;

    @Column(name = "email", unique = true, nullable = false)
    private String email;

    // Nome de usuario pra entrar no app (alternativa ao e-mail), ex. "preco bentevi".
    // Unico, guardado em minusculas. Quem so tem usuario ganha um e-mail interno (EMAIL_INTERNO).
    @Column(name = "login", unique = true, length = 80)
    private String login;

    /**
     * Quando role=REVENDA e este e um usuario-master de uma revenda, o id da revenda
     * a que ele pertence. Ve so os clientes daquela revenda. null = revenda principal / nao-revenda.
     */
    @Column(name = "revenda_id")
    private Long revendaId;

    @Column(name = "senha_hash", nullable = false)
    private String senhaHash;

    @Column(name = "role", nullable = false)
    private String role = "OPERADOR";

    /**
     * Modulos que este usuario pode acessar, em CSV (ex.: "produtos,contagem").
     * null ou vazio = acesso total (legado / sem restricao). DONO ignora (ve tudo).
     */
    @Column(name = "permissoes", length = 1000)
    private String permissoes;

    @Column(name = "ativo", nullable = false)
    private boolean ativo = true;

    /** true = senha definida pelo admin (provisoria); no 1o acesso o usuario deve trocar. */
    // columnDefinition com default: o ddl-auto consegue adicionar a coluna numa tabela
    // que ja tem linhas (Postgres preenche as existentes com false).
    @Column(name = "senha_provisoria", nullable = false, columnDefinition = "boolean not null default false")
    private boolean senhaProvisoria = false;

    /** true = sessao unica: logar em outro aparelho desloga o anterior (uma sessao por vez). */
    @Column(name = "sessao_unica", nullable = false, columnDefinition = "boolean not null default false")
    private boolean sessaoUnica = false;

    /** true = usuario SO de consulta de preco: ao logar, o app abre direto na camera (scanner). */
    @Column(name = "consulta_preco", nullable = false, columnDefinition = "boolean not null default false")
    private boolean consultaPreco = false;

    /** id da sessao ativa (quando sessaoUnica): so o token com este sid vale. */
    @Column(name = "sessao_id", length = 64)
    private String sessaoId;

    /** true = trava por aparelho: so o aparelho autorizado loga; novo aparelho precisa liberacao. */
    @Column(name = "device_lock", nullable = false, columnDefinition = "boolean not null default false")
    private boolean deviceLock = false;

    /** aparelho autorizado (id gerado pelo app) + nome amigavel (modelo). */
    @Column(name = "device_atual", length = 80)
    private String deviceAtual;
    @Column(name = "device_atual_nome", length = 120)
    private String deviceAtualNome;

    /** aparelho novo que tentou logar e aguarda liberacao no painel. */
    @Column(name = "device_pendente", length = 80)
    private String devicePendente;
    @Column(name = "device_pendente_nome", length = 120)
    private String devicePendenteNome;

    // "Esqueci a senha": a senha nova que o usuario pediu no app (hash), aguardando o
    // revendedor (ou o DONO) aprovar no painel. null = sem pedido.
    @Column(name = "senha_pendente_hash", length = 100)
    private String senhaPendenteHash;
    @Column(name = "senha_pendente_em")
    private Instant senhaPendenteEm;

    @Column(name = "criado_em")
    private Instant criadoEm = Instant.now();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<UserEmpresa> empresas = new ArrayList<>();

    public AppUser() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    /** Sufixo do e-mail gerado pra quem entra so pelo nome de usuario (nao e e-mail real). */
    public static final String EMAIL_INTERNO = "@usuario.meugiro";

    public String getLogin() { return login; }
    public void setLogin(String login) { this.login = normalizarLogin(login); }

    /** E-mail real (vazio se for o interno gerado pro usuario sem e-mail). */
    public String getEmailReal() {
        return email == null || email.endsWith(EMAIL_INTERNO) ? "" : email;
    }

    /** Como a pessoa entra: o usuario se tiver, senao o e-mail. */
    public String getAcesso() {
        return login != null && !login.isBlank() ? login : getEmailReal();
    }

    public static String normalizarLogin(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase().replaceAll("\\s+", " ");
        return t.isEmpty() ? null : t;
    }

    public Long getRevendaId() { return revendaId; }
    public void setRevendaId(Long revendaId) { this.revendaId = revendaId; }

    public String getSenhaHash() { return senhaHash; }
    public void setSenhaHash(String senhaHash) { this.senhaHash = senhaHash; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getPermissoes() { return permissoes; }
    public void setPermissoes(String permissoes) { this.permissoes = permissoes; }

    /** Modulos como lista (vazia se null/em branco = acesso total). */
    @jakarta.persistence.Transient
    public java.util.List<String> permissoesList() {
        if (permissoes == null || permissoes.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(permissoes.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    public boolean isAtivo() { return ativo; }
    public void setAtivo(boolean ativo) { this.ativo = ativo; }

    public boolean isSenhaProvisoria() { return senhaProvisoria; }
    public void setSenhaProvisoria(boolean senhaProvisoria) { this.senhaProvisoria = senhaProvisoria; }

    public boolean isSessaoUnica() { return sessaoUnica; }
    public void setSessaoUnica(boolean sessaoUnica) { this.sessaoUnica = sessaoUnica; }

    public String getSenhaPendenteHash() { return senhaPendenteHash; }
    public void setSenhaPendenteHash(String senhaPendenteHash) { this.senhaPendenteHash = senhaPendenteHash; }
    public Instant getSenhaPendenteEm() { return senhaPendenteEm; }
    public void setSenhaPendenteEm(Instant senhaPendenteEm) { this.senhaPendenteEm = senhaPendenteEm; }

    public boolean isConsultaPreco() { return consultaPreco; }
    public void setConsultaPreco(boolean consultaPreco) { this.consultaPreco = consultaPreco; }

    public String getSessaoId() { return sessaoId; }
    public void setSessaoId(String sessaoId) { this.sessaoId = sessaoId; }

    public boolean isDeviceLock() { return deviceLock; }
    public void setDeviceLock(boolean deviceLock) { this.deviceLock = deviceLock; }

    public String getDeviceAtual() { return deviceAtual; }
    public void setDeviceAtual(String deviceAtual) { this.deviceAtual = deviceAtual; }

    public String getDeviceAtualNome() { return deviceAtualNome; }
    public void setDeviceAtualNome(String deviceAtualNome) { this.deviceAtualNome = deviceAtualNome; }

    public String getDevicePendente() { return devicePendente; }
    public void setDevicePendente(String devicePendente) { this.devicePendente = devicePendente; }

    public String getDevicePendenteNome() { return devicePendenteNome; }
    public void setDevicePendenteNome(String devicePendenteNome) { this.devicePendenteNome = devicePendenteNome; }

    public Instant getCriadoEm() { return criadoEm; }
    public void setCriadoEm(Instant criadoEm) { this.criadoEm = criadoEm; }

    public List<UserEmpresa> getEmpresas() { return empresas; }
    public void setEmpresas(List<UserEmpresa> empresas) { this.empresas = empresas; }
}
