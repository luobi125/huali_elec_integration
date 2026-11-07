"""Diagnostics support for the 华立电表 integration."""

from __future__ import annotations

from dataclasses import asdict
from typing import Any

from homeassistant.components.diagnostics import async_redact_data
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant

from .const import CONF_OPENID, DOMAIN
from .coordinator import HualiElecCoordinator

TO_REDACT = {CONF_OPENID}


async def async_get_config_entry_diagnostics(
    hass: HomeAssistant, entry: ConfigEntry
) -> dict[str, Any]:
    """Return diagnostics for a config entry."""
    coordinator: HualiElecCoordinator = hass.data[DOMAIN][entry.entry_id]["coordinator"]
    data = coordinator.data
    return {
        "entry": {
            "data": async_redact_data(dict(entry.data), TO_REDACT),
            "options": dict(entry.options),
        },
        "meter": asdict(data.meter) if data else None,
        "history": coordinator.history if coordinator else None,
        "summary": {
            "month_usage": data.month_usage if data else None,
            "month_cost": data.month_cost if data else None,
            "usage_7d_average": data.usage_7d_average if data else None,
        },
    }
