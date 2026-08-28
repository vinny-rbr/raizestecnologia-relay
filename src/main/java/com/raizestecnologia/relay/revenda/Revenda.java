package com.raizestecnologia.relay.revenda;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Revendedor autorizado do Meu Giro. Cada revenda tem um {@code codigo} unico; o instalador
 * do agente carrega esse codigo, de modo que toda loja que o revendedor instalar cai
 * automaticamente no painel dele (Loja.revendaCodigo). Tabela: revenda.
 */
@Entity
@Table(name = "revenda")
public class Revenda {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nome;

    /** CPF ou CNPJ do revendedor (so digitos). */
    @Column(name = "cpf_cnpj", length = 20)
    private String cpfCnpj;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(length = 20)
    private String telefone;

    private String cidade;

    @Column(length = 2)
    private String uf;

    @Column(name = "senha_hash", nullable = false)
    private String senhaHash;

    /** Codigo unico da revenda (vai no instalador; vincula as lojas). Ex.: "REV7KQ2M9". */
    @Column(nullable = false, unique = true, length = 20)
    private String codigo;

    @Column(nullable = false, columnDefinition = "boolean not null default true")
    private boolean ativo = true;

    @Column(name = "criado_em")
    private Instant criadoEm = Instant.now();

    /** Id do cliente no Asaas (cus_...) — o revendedor, pagador dos R$30/loja ao dono. */
    @Column(name = "asaas_customer_id", length = 40)
    private String asaasCustomerId;

    protected Revenda() {}

    public String getAsaasCustomerId() { return asaasCustomerId; }
    public void setAsaasCustomerId(String v) { this.asaasCustomerId = v; }

    public Revenda(String nome, String cpfCnpj, String email, String telefone,
                   String cidade, String uf, String senhaHash, String codigo) {
        this.nome = nome;
        this.cpfCnpj = cpfCnpj;
        this.email = email;
        this.telefone = telefone;
        this.cidade = cidade;
        this.uf = uf;
        this.senhaHash = senhaHash;
        this.codigo = codigo;
    }

    public Long getId() { return id; }
    public String getNome() { return nome; }
    public String getCpfCnpj() { return cpfCnpj; }
    public String getEmail() { return email; }
    public String getTelefone() { return telefone; }
    public String getCidade() { return cidade; }
    public String getUf() { return uf; }
    public String getSenhaHash() { return senhaHash; }
    public void setSenhaHash(String h) { this.senhaHash = h; }
    public String getCodigo() { return codigo; }
    public boolean isAtivo() { return ativo; }
    public void setAtivo(boolean a) { this.ativo = a; }
    public Instant getCriadoEm() { return criadoEm; }
}
