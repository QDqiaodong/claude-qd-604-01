package com.bike.station.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 巡检批次逐桩明细：一桩一行。
 *
 * snapshotOccupied/snapshotBikeId/snapshotBikeCode 是建单时的占车快照，
 * 之后借还/调拨只改 bike.pile_id，不改写这里 —— 页面始终能看到「建单当时」的事实。
 *
 * rev 是乐观锁：两名运营人员几乎同时登记同一个桩，或检查提交与车辆离桩、复验相撞时，
 * 拿着旧 rev 的提交会得到 409 冲突，必须按最新状态重来。
 */
@Entity
@Table(name = "inspection_item")
public class InspectionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "pile_id", nullable = false)
    private Long pileId;

    @Column(name = "pile_no", nullable = false, length = 16)
    private String pileNo;

    @Column(name = "snapshot_occupied", nullable = false)
    private Boolean snapshotOccupied;

    @Column(name = "snapshot_bike_id")
    private Long snapshotBikeId;

    @Column(name = "snapshot_bike_code", length = 32)
    private String snapshotBikeCode;

    /** PENDING 待检查 / OK 正常 / FAULT 故障 */
    @Column(name = "result", nullable = false, length = 16)
    private String result;

    @Column(name = "lock_status", nullable = false, length = 16)
    private String lockStatus;

    @Column(name = "power_status", nullable = false, length = 16)
    private String powerStatus;

    @Column(name = "comm_status", nullable = false, length = 16)
    private String commStatus;

    @Column(name = "fault_level", length = 16)
    private String faultLevel;

    @Column(name = "fault_desc", length = 255)
    private String faultDesc;

    /** ISOLATED 已隔离 / PENDING_ISOLATED 待隔离（仅故障有值） */
    @Column(name = "isolated_mode", length = 24)
    private String isolatedMode;

    @Column(name = "checker_name", length = 32)
    private String checkerName;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    @Column(name = "rev", nullable = false)
    private Integer rev;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getPileId() {
        return pileId;
    }

    public void setPileId(Long pileId) {
        this.pileId = pileId;
    }

    public String getPileNo() {
        return pileNo;
    }

    public void setPileNo(String pileNo) {
        this.pileNo = pileNo;
    }

    public Boolean getSnapshotOccupied() {
        return snapshotOccupied;
    }

    public void setSnapshotOccupied(Boolean snapshotOccupied) {
        this.snapshotOccupied = snapshotOccupied;
    }

    public Long getSnapshotBikeId() {
        return snapshotBikeId;
    }

    public void setSnapshotBikeId(Long snapshotBikeId) {
        this.snapshotBikeId = snapshotBikeId;
    }

    public String getSnapshotBikeCode() {
        return snapshotBikeCode;
    }

    public void setSnapshotBikeCode(String snapshotBikeCode) {
        this.snapshotBikeCode = snapshotBikeCode;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getLockStatus() {
        return lockStatus;
    }

    public void setLockStatus(String lockStatus) {
        this.lockStatus = lockStatus;
    }

    public String getPowerStatus() {
        return powerStatus;
    }

    public void setPowerStatus(String powerStatus) {
        this.powerStatus = powerStatus;
    }

    public String getCommStatus() {
        return commStatus;
    }

    public void setCommStatus(String commStatus) {
        this.commStatus = commStatus;
    }

    public String getFaultLevel() {
        return faultLevel;
    }

    public void setFaultLevel(String faultLevel) {
        this.faultLevel = faultLevel;
    }

    public String getFaultDesc() {
        return faultDesc;
    }

    public void setFaultDesc(String faultDesc) {
        this.faultDesc = faultDesc;
    }

    public String getIsolatedMode() {
        return isolatedMode;
    }

    public void setIsolatedMode(String isolatedMode) {
        this.isolatedMode = isolatedMode;
    }

    public String getCheckerName() {
        return checkerName;
    }

    public void setCheckerName(String checkerName) {
        this.checkerName = checkerName;
    }

    public LocalDateTime getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(LocalDateTime checkedAt) {
        this.checkedAt = checkedAt;
    }

    public Integer getRev() {
        return rev;
    }

    public void setRev(Integer rev) {
        this.rev = rev;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
