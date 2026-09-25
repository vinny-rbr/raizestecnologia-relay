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
