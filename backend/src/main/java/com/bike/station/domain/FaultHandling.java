package com.bike.station.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 桩位故障处置单。
 *
 * 由逐桩检查发现故障自动开立；复验是唯一的出口。
 * 数据库 active_pile_id 生成列 + 唯一索引硬保证「同一桩位不能同时存在两条
 * 尚未完成的故障处置」，服务层在持锁状态下先查一次给出人话提示。
 *
 * rev 是乐观锁：处置与复验相撞、或两名运营人员几乎同时复验同一单时，
 * 旧 rev 提交得到 409 并返回最新状态，已生效的处置/复验结论不被旧页面覆盖。
 */
@Entity
@Table(name = "fault_handling")
public class FaultHandling {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "handling_no", nullable = false, length = 32)
    private String handlingNo;

    @Column(name = "pile_id", nullable = false)
    private Long pileId;

    @Column(name = "station_id", nullable = false)
    private Long stationId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "bike_id")
    private Long bikeId;

    @Column(name = "fault_level", nullable = false, length = 16)
    private String faultLevel;

    @Column(name = "fault_desc", length = 255)
    private String faultDesc;

    /** FH_PENDING / FH_HANDLED / FH_RECHECK_PASS / FH_RECHECK_REJECTED */
    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "handler_name", length = 32)
    private String handlerName;

    @Column(name = "handle_note", length = 255)
    private String handleNote;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    @Column(name = "rechecker_name", length = 32)
    private String recheckerName;

    @Column(name = "recheck_pass")
    private Boolean recheckPass;

    @Column(name = "recheck_note", length = 255)
    private String recheckNote;

    @Column(name = "rechecked_at")
    private LocalDateTime recheckedAt;

    @Column(name = "rev", nullable = false)
    private Integer rev;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getHandlingNo() {
        return handlingNo;
    }

    public void setHandlingNo(String handlingNo) {
        this.handlingNo = handlingNo;
    }

    public Long getPileId() {
        return pileId;
    }

    public void setPileId(Long pileId) {
        this.pileId = pileId;
    }

    public Long getStationId() {
        return stationId;
    }

    public void setStationId(Long stationId) {
        this.stationId = stationId;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public Long getBikeId() {
        return bikeId;
    }

    public void setBikeId(Long bikeId) {
        this.bikeId = bikeId;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getHandlerName() {
        return handlerName;
    }

    public void setHandlerName(String handlerName) {
        this.handlerName = handlerName;
    }

    public String getHandleNote() {
        return handleNote;
    }

    public void setHandleNote(String handleNote) {
        this.handleNote = handleNote;
    }

    public LocalDateTime getHandledAt() {
        return handledAt;
    }

    public void setHandledAt(LocalDateTime handledAt) {
        this.handledAt = handledAt;
    }

    public String getRecheckerName() {
        return recheckerName;
    }

    public void setRecheckerName(String recheckerName) {
        this.recheckerName = recheckerName;
    }

    public Boolean getRecheckPass() {
        return recheckPass;
    }

    public void setRecheckPass(Boolean recheckPass) {
        this.recheckPass = recheckPass;
    }

    public String getRecheckNote() {
        return recheckNote;
    }

    public void setRecheckNote(String recheckNote) {
        this.recheckNote = recheckNote;
    }

    public LocalDateTime getRecheckedAt() {
        return recheckedAt;
    }

    public void setRecheckedAt(LocalDateTime recheckedAt) {
        this.recheckedAt = recheckedAt;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
