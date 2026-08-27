package com.raizestecnologia.relay.push;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {
    Optional<DeviceToken> findByToken(String token);
    List<DeviceToken> findByUserIdIn(Collection<Long> userIds);
    void deleteByUserId(Long userId);

    /** Aparelhos por loja (via user_empresa), fora o dono. Linhas: [cnpj, app_version, atualizado_em]. */
    @org.springframework.data.jpa.repository.Query(value =
            "SELECT ue.cnpj AS cnpj, dt.app_version AS versao, dt.atualizado_em AS atualizado " +
            "FROM device_token dt " +
            "JOIN user_empresa ue ON ue.user_id = dt.user_id " +
            "JOIN app_user u ON u.id = dt.user_id " +
            "WHERE u.role <> 'DONO'", nativeQuery = true)
    List<Object[]> statsPorLoja();
}
