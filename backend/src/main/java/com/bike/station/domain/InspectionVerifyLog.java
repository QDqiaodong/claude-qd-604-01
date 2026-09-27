package com.bike.station.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 巡检复验记录：每一次复验动作都留一行痕。
 * 复验不通过的原因保留在 note 里，同一条故障处置可以反复「处置→复验不通过→再处置」。
 */
@Entity
@Table(name = "inspection_verify_log")
public class InspectionVerifyLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "pile_id", nullable = false)
    private Long pileId;

    @Column(name = "result", nullable = false, length = 16)
    private String result;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "operator_id", nullable = false)
    private Long operatorId;

    /** 复验通过后桩位恢复成的状态：IDLE 空闲 / OCCUPIED 占用（按真实占车决定） */
    @Column(name = "pile_restored", length = 24)
    private String pileRestored;

    @Column(name = "restored_bike_code", length = 32)
    private String restoredBikeCode;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
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

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getPileRestored() {
        return pileRestored;
    }

    public void setPileRestored(String pileRestored) {
        this.pileRestored = pileRestored;
    }

    public String getRestoredBikeCode() {
        return restoredBikeCode;
    }

    public void setRestoredBikeCode(String restoredBikeCode) {
        this.restoredBikeCode = restoredBikeCode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
