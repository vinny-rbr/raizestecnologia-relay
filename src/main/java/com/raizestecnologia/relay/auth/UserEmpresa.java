package com.raizestecnologia.relay.auth;

import jakarta.persistence.*;

/**
 * Vinculo entre um usuario e um CNPJ (loja) que ele pode enxergar.
 * Tabela: user_empresa  (UNIQUE user_id + cnpj)
 */
@Entity
@Table(
        name = "user_empresa",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_cnpj", columnNames = {"user_id", "cnpj"})
)
public class UserEmpresa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "cnpj", length = 14, nullable = false)
    private String cnpj;

    public UserEmpresa() {}

    public UserEmpresa(AppUser user, String cnpj) {
        this.user = user;
        this.cnpj = cnpj;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }

    public String getCnpj() { return cnpj; }
    public void setCnpj(String cnpj) { this.cnpj = cnpj; }
}
