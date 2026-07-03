package com.raizestecnologia.relay.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserEmpresaRepository extends JpaRepository<UserEmpresa, Long> {
    List<UserEmpresa> findByUserId(Long userId);
    boolean existsByUserIdAndCnpj(Long userId, String cnpj);
    void deleteByUserIdAndCnpj(Long userId, String cnpj);
}
