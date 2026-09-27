package com.bike.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bike.station.domain.FaultHandling;

import jakarta.persistence.LockModeType;

public interface FaultHandlingRepository extends JpaRepository<FaultHandling, Long> {

    Optional<FaultHandling> findByHandlingNo(String handlingNo);

    List<FaultHandling> findByBatchIdOrderByIdAsc(Long batchId);

    List<FaultHandling> findByPileIdOrderByIdDesc(Long pileId);

    /** 某桩位当前未完成（未复验通过）的处置单 —— 同一桩位最多一条 */
    List<FaultHandling> findByPileIdAndStatusIn(Long pileId, List<String> statuses);

    List<FaultHandling> findByStationIdAndStatusInOrderByIdDesc(Long stationId, List<String> statuses);

    /** 站点看板「待复验」计数用：全部等待复验的处置单 */
    List<FaultHandling> findByStatusIn(List<String> statuses);

    /**
     * 【悲观锁】锁处置单行：处置与复验相撞时，旧 rev 的一方在锁内被识别为冲突。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from FaultHandling h where h.id = :id")
    Optional<FaultHandling> findByIdForUpdate(@Param("id") Long id);
}
