package com.raizestecnologia.relay.push;

import jakarta.persistence.*;

import java.time.Instant;

/** Token de push (FCM) de um aparelho, associado ao usuario logado. Tabela: device_token */
@Entity
@Table(name = "device_token")
public class DeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token", nullable = false, unique = true, length = 512)
    private String token;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "platform")
    private String platform;

    @Column(name = "atualizado_em")
    private Instant atualizadoEm = Instant.now();

    public DeviceToken() {}

    public DeviceToken(String token, Long userId, String platform) {
        this.token = token;
        this.userId = userId;
        this.platform = platform;
    }

    public Long getId() { return id; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public Instant getAtualizadoEm() { return atualizadoEm; }
    public void setAtualizadoEm(Instant atualizadoEm) { this.atualizadoEm = atualizadoEm; }
}
