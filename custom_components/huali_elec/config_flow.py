"""Config flow for the 华立电表 integration."""

from __future__ import annotations

import logging
from typing import Any

import aiohttp
import voluptuous as vol
from homeassistant.config_entries import (
    ConfigEntry,
    ConfigFlow,
    ConfigFlowResult,
    OptionsFlow,
)
from homeassistant.core import callback
from homeassistant.helpers import selector
from homeassistant.helpers.aiohttp_client import async_create_clientsession

from .api import (
    CannotConnectError,
    HualiElecApi,
    HualiElecError,
    NotBoundError,
    RoomNotFoundError,
    generate_openid,
)
from .const import (
    CAMPUSES,
    CONF_CAMPUS,
    CONF_OPENID,
    CONF_ROOM,
    CONF_SCAN_INTERVAL,
    DEFAULT_SCAN_INTERVAL,
    DOMAIN,
    MAX_SCAN_INTERVAL,
    MIN_SCAN_INTERVAL,
)

_LOGGER = logging.getLogger(__name__)

CAMPUS_OPTIONS = [
    selector.SelectOptionDict(value=key, label=campus["name"])
    for key, campus in CAMPUSES.items()
]

PLATFORM_URL = "https://dbwx.hlsywy.com/Web/MWeb/index.aspx"


def _user_schema(defaults: dict[str, Any] | None = None) -> vol.Schema:
    defaults = defaults or {}
    return vol.Schema(
        {
            vol.Required(
                CONF_CAMPUS, default=defaults.get(CONF_CAMPUS, next(iter(CAMPUSES)))
            ): selector.SelectSelector(
                selector.SelectSelectorConfig(
                    options=CAMPUS_OPTIONS,
                    mode=selector.SelectSelectorMode.DROPDOWN,
                )
            ),
            vol.Required(
                CONF_ROOM, default=defaults.get(CONF_ROOM, "")
            ): selector.TextSelector(
                selector.TextSelectorConfig(type=selector.TextSelectorType.TEXT)
            ),
        }
    )


class HualiElecConfigFlow(ConfigFlow, domain=DOMAIN):
    """Ask for campus + room and verify them against the platform."""

    VERSION = 1

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        errors: dict[str, str] = {}

        if user_input is not None:
            campus_key = user_input[CONF_CAMPUS]
            room = user_input[CONF_ROOM].strip().upper()
            campus = CAMPUSES[campus_key]

            await self.async_set_unique_id(f"{campus['operator_code']}_{room}")
            self._abort_if_unique_id_configured()

            openid = generate_openid()
            # 临时会话：自己管理生命周期，避免关闭 HA 托管的 session
            session = async_create_clientsession(
                self.hass,
                cookie_jar=aiohttp.CookieJar(unsafe=True),
                auto_cleanup=False,
            )
            api = HualiElecApi(
                session,
                mem_id=campus["memid"],
                operator_code=campus["operator_code"],
                room=room,
                openid=openid,
            )
            bound = False
            try:
                await api.async_bind()
                bound = True
                await api.async_fetch_meter()
            except RoomNotFoundError:
                errors["base"] = "invalid_room"
            except CannotConnectError:
                errors["base"] = "cannot_connect"
            except NotBoundError:
                errors["base"] = "bind_failed"
            except HualiElecError:
                errors["base"] = "bind_failed"
            except Exception:  # noqa: BLE001
                _LOGGER.exception("Unexpected error while validating room %s", room)
                errors["base"] = "unknown"
            else:
                return self.async_create_entry(
                    title=f"{campus['name']} {room}",
                    data={
                        CONF_CAMPUS: campus_key,
                        CONF_ROOM: room,
                        CONF_OPENID: openid,
                    },
                )
            finally:
                if bound and errors:
                    # 校验失败时不要把临时绑定留在平台上
                    try:
                        await api.async_unbind()
                    except HualiElecError:
                        pass
                if not session.closed:
                    await session.close()

            user_input = {**user_input, CONF_ROOM: room}

        return self.async_show_form(
            step_id="user",
            data_schema=_user_schema(user_input),
            errors=errors,
            description_placeholders={"example": "A1-105", "url": PLATFORM_URL},
        )

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: ConfigEntry) -> HualiElecOptionsFlow:
        return HualiElecOptionsFlow()


class HualiElecOptionsFlow(OptionsFlow):
    """Options: polling interval."""

    async def async_step_init(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        if user_input is not None:
            return self.async_create_entry(data=user_input)

        current = self.config_entry.options.get(
            CONF_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL
        )
        return self.async_show_form(
            step_id="init",
            data_schema=vol.Schema(
                {
                    vol.Required(
                        CONF_SCAN_INTERVAL, default=int(current)
                    ): vol.All(
                        selector.NumberSelector(
                            selector.NumberSelectorConfig(
                                min=MIN_SCAN_INTERVAL,
                                max=MAX_SCAN_INTERVAL,
                                step=5,
                                mode=selector.NumberSelectorMode.BOX,
                                unit_of_measurement="min",
                            )
                        ),
                        vol.Coerce(int),
                    )
                }
            ),
        )
