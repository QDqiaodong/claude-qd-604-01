package com.bike.station.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bike.station.domain.InspectionVerifyLog;

public interface InspectionVerifyLogRepository extends JpaRepository<InspectionVerifyLog, Long> {

    List<InspectionVerifyLog> findByItemIdOrderByIdAsc(Long itemId);
}
