"""Data update coordinator for the 华立电表 integration."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
import logging
import time
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

from .api import (
    HualiElecApi,
    HualiElecError,
    MeterData,
    NotBoundError,
    UsageRecord,
)
from .const import DOMAIN, HISTORY_TTL

_LOGGER = logging.getLogger(__name__)

# 校方服务器瞬时故障时的快速重试间隔 / 允许的连续失败次数
TRANSIENT_RETRY_INTERVAL = timedelta(minutes=5)
MAX_TRANSIENT_FAILURES = 3

# 校方服务器按北京时间结算（固定 UTC+8，无夏令时）
CST = timezone(timedelta(hours=8))


@dataclass(slots=True)
class HualiElecData:
    """Everything the sensors need for one refresh."""

    meter: MeterData
    history: list[UsageRecord] = field(default_factory=list)
    history_complete: bool = False

    # ------------------------------------------------------------------ money
    @property
    def balance(self) -> float | None:
        """剩余金额（元）。"""
        return self.meter.balance

    @property
    def remaining_energy(self) -> float | None:
        """把剩余金额按当前电价折算成电量（度）。"""
        price = self.meter.price
        if self.meter.balance is None or not price:
            return None
        return round(self.meter.balance / price, 2)

    @property
    def arrears(self) -> float | None:
        return self.meter.arrears

    @property
    def meter_reading(self) -> float | None:
        return self.meter.read_number

    # ------------------------------------------------------------------ usage
    @property
    def daily_usage(self) -> float | None:
        """最近一次结算日的用电量（度）。"""
        return self.history[0].usage if self.history else None

    @property
    def daily_usage_date(self) -> str | None:
        return self.history[0].date if self.history else None

    @property
    def usage_7d_average(self) -> float | None:
        values = [r.usage for r in self.history[:7] if r.usage is not None]
        return round(sum(values) / len(values), 2) if values else None

    @property
    def month_key(self) -> str:
        return datetime.now(CST).strftime("%Y-%m")

    @property
    def month_usage(self) -> float | None:
        """本月累计用电量（度）。"""
        values = [
            r.usage
            for r in self.history
            if r.usage is not None and r.date.startswith(self.month_key)
        ]
        if not values:
            return None
        return round(sum(values), 2)

    @property
    def month_cost(self) -> float | None:
        usage = self.month_usage
        price = self.meter.price
        if usage is None or not price:
            return None
        return round(usage * price, 2)

    # ------------------------------------------------------------------ run rate
    @property
    def daily_cost(self) -> float | None:
        """按近 7 天日均用电量折算的日均花费（元/天）。"""
        average = self.usage_7d_average
        price = self.meter.price
        if average is None or not price:
            return None
        return round(average * price, 2)

    @property
    def days_left(self) -> float | None:
        """余额按当前日均花费还能用多少天。"""
        cost = self.daily_cost
        if not cost or self.meter.balance is None:
            return None
        return round(self.meter.balance / cost, 1)


class HualiElecCoordinator(DataUpdateCoordinator[HualiElecData]):
    """Poll the campus meter service."""

    config_entry: ConfigEntry

    def __init__(
        self,
        hass: HomeAssistant,
        api: HualiElecApi,
        *,
        scan_interval_minutes: int,
    ) -> None:
        super().__init__(
            hass,
            _LOGGER,
            name=DOMAIN,
            update_interval=timedelta(minutes=scan_interval_minutes),
        )
        self.api = api
        self._history: list[UsageRecord] = []
        self._history_complete = False
        self._history_ts: float = 0.0
        self._normal_interval = timedelta(minutes=scan_interval_minutes)
        self._failures = 0

    async def _async_fetch_meter(self) -> MeterData:
        try:
            return await self.api.async_fetch_meter()
        except NotBoundError:
            _LOGGER.debug("openid 未绑定房间 %s，重新绑定", self.api.room)
            await self.api.async_bind()
            return await self.api.async_fetch_meter()

    async def _async_update_data(self) -> HualiElecData:
        try:
            meter = await self._async_fetch_meter()
        except HualiElecError as err:
            self._failures += 1
            # 校方服务器偶尔会短暂 DNS 超时/不可达，不要把实体直接标成不可用：
            # 先保留上一次的数据，并在 5 分钟后重试，连续失败多次才报错。
            if self.data is not None and self._failures < MAX_TRANSIENT_FAILURES:
                self.update_interval = TRANSIENT_RETRY_INTERVAL
                _LOGGER.warning(
                    "华立电表 %s 第 %s 次获取失败，保留上次数据，%s 秒后重试: %s",
                    self.api.room,
                    self._failures,
                    int(TRANSIENT_RETRY_INTERVAL.total_seconds()),
                    err,
                )
                return self.data
            raise UpdateFailed(str(err)) from err

        if self._failures:
            _LOGGER.info(
                "华立电表 %s 已恢复（此前连续失败 %s 次）", self.api.room, self._failures
            )
        self._failures = 0
        self.update_interval = self._normal_interval

        await self._async_refresh_history(meter)
        return HualiElecData(
            meter=meter,
            history=self._history,
            history_complete=self._history_complete,
        )

    async def _async_refresh_history(self, meter: MeterData) -> None:
        """Refresh the daily usage list; cached for ``HISTORY_TTL`` seconds."""
        if self._history and (time.monotonic() - self._history_ts) < HISTORY_TTL:
            return
        try:
            records = await self.api.async_fetch_usage(meter.meter_addr)
        except HualiElecError as err:
            # 用量属于附加信息，失败时保留旧数据
            _LOGGER.debug("获取用电历史失败: %s", err)
            return

        records.sort(key=lambda r: r.date, reverse=True)
        self._history = records
        self._history_ts = time.monotonic()
        self._history_complete = any(
            r.date and not r.date.startswith(datetime.now(CST).strftime("%Y-%m"))
            for r in records
        )
        _LOGGER.debug("用电历史已更新，共 %s 条", len(records))

    @property
    def history(self) -> list[dict[str, Any]]:
        """Daily usage as plain dicts (for diagnostics/attributes)."""
        return [
            {
                "date": r.date,
                "usage": r.usage,
                "reading": r.end_number,
            }
            for r in self._history
        ]
