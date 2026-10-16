package com.raizestecnologia.relay.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserEmpresaRepository extends JpaRepository<UserEmpresa, Long> {
    List<UserEmpresa> findByUserId(Long userId);
    List<UserEmpresa> findByCnpj(String cnpj);

    /** [userId, usuarioSistemaId, nome, ativo] dos vínculos da loja (sem carregar o usuário à parte). */
    @org.springframework.data.jpa.repository.Query(
            "select v.user.id, v.usuarioSistemaId, v.user.nome, v.user.ativo from UserEmpresa v where v.cnpj = :cnpj")
    List<Object[]> resumoPorCnpj(@org.springframework.data.repository.query.Param("cnpj") String cnpj);
    boolean existsByUserIdAndCnpj(Long userId, String cnpj);
    java.util.Optional<UserEmpresa> findFirstByUserIdAndCnpj(Long userId, String cnpj);
    void deleteByUserIdAndCnpj(Long userId, String cnpj);
}
