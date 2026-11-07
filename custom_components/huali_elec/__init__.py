"""Set up the 华立电表 integration."""

from __future__ import annotations

import logging
from typing import Any

import aiohttp
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_create_clientsession

from .api import HualiElecApi
from .const import (
    CAMPUSES,
    CONF_CAMPUS,
    CONF_OPENID,
    CONF_ROOM,
    CONF_SCAN_INTERVAL,
    DEFAULT_SCAN_INTERVAL,
    DOMAIN,
)
from .coordinator import HualiElecCoordinator

_LOGGER = logging.getLogger(__name__)

PLATFORMS: list[Platform] = [Platform.SENSOR]


def _build_api(
    hass: HomeAssistant, entry: ConfigEntry
) -> tuple[HualiElecApi, aiohttp.ClientSession]:
    campus = CAMPUSES[entry.data[CONF_CAMPUS]]
    # 每个配置项使用独立的 cookie jar，避免多个房间的 ASP.NET 会话互相覆盖
    session = async_create_clientsession(
        hass, cookie_jar=aiohttp.CookieJar(unsafe=True), auto_cleanup=False
    )
    api = HualiElecApi(
        session,
        mem_id=campus["memid"],
        operator_code=campus["operator_code"],
        room=entry.data[CONF_ROOM],
        openid=entry.data[CONF_OPENID],
    )
    return api, session


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up a room from a config entry."""
    api, session = _build_api(hass, entry)
    coordinator = HualiElecCoordinator(
        hass,
        api,
        scan_interval_minutes=entry.options.get(
            CONF_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL
        ),
    )
    await coordinator.async_config_entry_first_refresh()

    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = {
        "coordinator": coordinator,
        "session": session,
    }
    entry.async_on_unload(entry.add_update_listener(_async_update_listener))

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""
    unload_ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unload_ok:
        runtime: dict[str, Any] | None = hass.data.get(DOMAIN, {}).pop(
            entry.entry_id, None
        )
        if runtime and not runtime["session"].closed:
            await runtime["session"].close()
    return unload_ok


async def async_remove_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Called when the user deletes the entry: drop our openid binding."""
    api, session = _build_api(hass, entry)
    try:
        await api.async_unbind()
    except Exception as err:  # noqa: BLE001 - cleanup must never raise
        _LOGGER.warning("解绑房间 %s 失败: %s", entry.data.get(CONF_ROOM), err)
    finally:
        if not session.closed:
            await session.close()


async def _async_update_listener(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Reload the entry when options change."""
    await hass.config_entries.async_reload(entry.entry_id)
