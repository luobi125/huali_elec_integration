"""Sensors for the 华立电表 integration."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
import logging
from typing import Any

from homeassistant.components.sensor import (
    SensorDeviceClass,
    SensorEntity,
    SensorEntityDescription,
    SensorStateClass,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import UnitOfEnergy
from homeassistant.core import HomeAssistant
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import CAMPUSES, CONF_CAMPUS, CONF_ROOM, DOMAIN
from .coordinator import HualiElecCoordinator, HualiElecData

_LOGGER = logging.getLogger(__name__)

CURRENCY_CNY = "CNY"
ATTR_HISTORY_DAYS = 7


@dataclass(frozen=True, kw_only=True)
class HualiElecSensorEntityDescription(SensorEntityDescription):
    """Describes a 华立电表 sensor."""

    value_fn: Callable[[HualiElecData], float | None]


SENSORS: tuple[HualiElecSensorEntityDescription, ...] = (
    HualiElecSensorEntityDescription(
        key="balance",
        translation_key="balance",
        device_class=SensorDeviceClass.MONETARY,
        state_class=SensorStateClass.TOTAL,
        native_unit_of_measurement=CURRENCY_CNY,
        icon="mdi:cash",
        value_fn=lambda data: data.balance,
    ),
    HualiElecSensorEntityDescription(
        key="remaining_energy",
        translation_key="remaining_energy",
        device_class=SensorDeviceClass.ENERGY,
        state_class=SensorStateClass.TOTAL,
        native_unit_of_measurement=UnitOfEnergy.KILO_WATT_HOUR,
        icon="mdi:battery-heart-variant",
        value_fn=lambda data: data.remaining_energy,
    ),
    HualiElecSensorEntityDescription(
        key="meter_reading",
        translation_key="meter_reading",
        device_class=SensorDeviceClass.ENERGY,
        state_class=SensorStateClass.TOTAL_INCREASING,
        native_unit_of_measurement=UnitOfEnergy.KILO_WATT_HOUR,
        icon="mdi:meter-electric",
        value_fn=lambda data: data.meter_reading,
    ),
    HualiElecSensorEntityDescription(
        key="arrears",
        translation_key="arrears",
        device_class=SensorDeviceClass.MONETARY,
        state_class=SensorStateClass.TOTAL,
        native_unit_of_measurement=CURRENCY_CNY,
        icon="mdi:cash-alert",
        value_fn=lambda data: data.arrears,
    ),
    HualiElecSensorEntityDescription(
        key="daily_usage",
        translation_key="daily_usage",
        device_class=SensorDeviceClass.ENERGY,
        state_class=SensorStateClass.TOTAL_INCREASING,
        native_unit_of_measurement=UnitOfEnergy.KILO_WATT_HOUR,
        icon="mdi:chart-bar",
        value_fn=lambda data: data.daily_usage,
    ),
    HualiElecSensorEntityDescription(
        key="month_usage",
        translation_key="month_usage",
        device_class=SensorDeviceClass.ENERGY,
        state_class=SensorStateClass.TOTAL,
        native_unit_of_measurement=UnitOfEnergy.KILO_WATT_HOUR,
        icon="mdi:chart-line",
        value_fn=lambda data: data.month_usage,
    ),
    HualiElecSensorEntityDescription(
        key="month_cost",
        translation_key="month_cost",
        device_class=SensorDeviceClass.MONETARY,
        state_class=SensorStateClass.TOTAL,
        native_unit_of_measurement=CURRENCY_CNY,
        icon="mdi:cash-multiple",
        value_fn=lambda data: data.month_cost,
    ),
    HualiElecSensorEntityDescription(
        key="daily_cost",
        translation_key="daily_cost",
        device_class=SensorDeviceClass.MONETARY,
        native_unit_of_measurement=CURRENCY_CNY,
        icon="mdi:cash-clock",
        value_fn=lambda data: data.daily_cost,
    ),
    HualiElecSensorEntityDescription(
        key="days_left",
        translation_key="days_left",
        native_unit_of_measurement="天",
        icon="mdi:calendar-clock",
        value_fn=lambda data: data.days_left,
    ),
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Add the sensor entities for a config entry."""
    coordinator: HualiElecCoordinator = hass.data[DOMAIN][entry.entry_id]["coordinator"]
    async_add_entities(
        HualiElecSensor(coordinator, entry, description) for description in SENSORS
    )


class HualiElecSensor(CoordinatorEntity[HualiElecCoordinator], SensorEntity):
    """One value of a dormitory meter."""

    _attr_has_entity_name = True
    entity_description: HualiElecSensorEntityDescription

    def __init__(
        self,
        coordinator: HualiElecCoordinator,
        entry: ConfigEntry,
        description: HualiElecSensorEntityDescription,
    ) -> None:
        super().__init__(coordinator)
        self.entity_description = description
        self._entry = entry
        self._attr_unique_id = (
            f"{entry.data[CONF_CAMPUS]}_{entry.data[CONF_ROOM]}_{description.key}"
        )
        # 中文实体名无法生成好看的 entity_id，这里显式指定
        self._attr_suggested_object_id = f"{entry.data[CONF_ROOM]} {description.key}"

    @property
    def device_info(self) -> DeviceInfo:
        campus = CAMPUSES[self._entry.data[CONF_CAMPUS]]["name"]
        room = self._entry.data[CONF_ROOM]
        return DeviceInfo(
            identifiers={(DOMAIN, f"{self._entry.data[CONF_CAMPUS]}_{room}")},
            name=f"{room} 宿舍电表",
            manufacturer="华立数研中心",
            model=f"{campus} 宿舍电表",
            configuration_url="https://dbwx.hlsywy.com/Web/MWeb/index.aspx",
        )

    @property
    def native_value(self) -> float | None:
        if self.coordinator.data is None:
            return None
        value = self.entity_description.value_fn(self.coordinator.data)
        return round(value, 2) if value is not None else None

    @property
    def extra_state_attributes(self) -> dict[str, Any] | None:
        data = self.coordinator.data
        if data is None:
            return None
        meter = data.meter

        common: dict[str, Any] = {
            "room": meter.user_code,
            "meter_type": meter.meter_type,
            "price": meter.price,
        }

        match self.entity_description.key:
            case "balance" | "remaining_energy":
                common["balance"] = data.balance
                common["balance_date"] = meter.balance_date
                common["remaining_energy"] = data.remaining_energy
                return common
            case "meter_reading":
                common["read_date"] = meter.read_date
                common["meter_id"] = meter.meter_id
                common["meter_addr"] = meter.meter_addr
                common["install_addr"] = meter.install_addr
                common["pay_type"] = meter.pay_type
                return common
            case "arrears":
                common["account_balance"] = meter.account_balance
                return common
            case "daily_usage":
                common["date"] = data.daily_usage_date
                common["average_7d"] = data.usage_7d_average
                common["recent_days"] = [
                    {"date": record.date, "usage": record.usage}
                    for record in data.history[:ATTR_HISTORY_DAYS]
                ]
                return common
            case "month_usage" | "month_cost":
                common["month"] = data.month_key
                common["month_usage"] = data.month_usage
                common["month_cost"] = data.month_cost
                common["complete"] = data.history_complete
                return common
            case "daily_cost" | "days_left":
                common["average_7d_usage"] = data.usage_7d_average
                common["daily_cost"] = data.daily_cost
                common["days_left"] = data.days_left
                common["balance"] = data.balance
                return common
        return common
