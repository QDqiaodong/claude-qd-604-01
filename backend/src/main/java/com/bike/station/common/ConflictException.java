package com.bike.station.common;

/**
 * 并发冲突（409）：两名运营人员几乎同时操作同一条记录，后一方拿到的是旧版本。
 *
 * 与普通业务拒绝（400）的区别：409 表示「你页面上的状态已经过期」，
 * latest 里带上数据库中最新的视图，前端据此提示冲突并用最新状态覆盖旧页面，
 * 已生效的车辆落点与巡检记录绝不允许被旧页面覆盖。
 */
public class ConflictException extends BizException {

    private final transient Object latest;

    public ConflictException(String message, Object latest) {
        super(message);
        this.latest = latest;
    }

    public Object getLatest() {
        return latest;
    }
}
