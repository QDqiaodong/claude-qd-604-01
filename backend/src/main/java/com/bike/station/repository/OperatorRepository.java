package com.bike.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bike.station.domain.Operator;

import jakarta.persistence.LockModeType;

public interface OperatorRepository extends JpaRepository<Operator, Long> {

    List<Operator> findAllByOrderByIdAsc();

    boolean existsByPhone(String phone);

    /**
     * 【悲观锁】按主键锁 operator 表的这一行。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Operator o where o.id = :id")
    Optional<Operator> findByIdForUpdate(@Param("id") Long id);
}
