"""HTTP client for the 华立智慧微平台 electricity meter service.

The campus system (https://dbwx.hlsywy.com) is a WeChat-only ASP.NET site:
the browser is identified by ``openid`` (normally injected by WeChat OAuth).
A room (``UserCode`` such as ``A1-105``) can be bound to any openid through
``/Web/MWeb/login_HN.aspx`` without a password, and multiple openids may be
bound to the same room, so Home Assistant can create its own private openid
instead of borrowing the student's WeChat identity.

Endpoints used:

* ``GET  /Web/MWeb/login_HN.aspx?memid&openid&operatorcode``  -> bind form
* ``POST /Web/MWeb/login_HN.aspx?...``                        -> bind room
* ``POST /Web/MWeb/payusereqlist.aspx?_r=show``               -> meter snapshot
* ``POST /Web/MWeb/amountlist.aspx?_r=show``                  -> daily usage
* ``POST /Web/MWeb/member.aspx?_r=delbind``                   -> unbind
"""

from __future__ import annotations

from dataclasses import dataclass
import json
import logging
import random
import re
import secrets
from typing import Any

import aiohttp

from .const import BASE_URL

_LOGGER = logging.getLogger(__name__)

TIMEOUT = aiohttp.ClientTimeout(total=30, connect=15)

USER_AGENT = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 "
    "MicroMessenger/8.0.44(0x18002c2b) NetType/WIFI Language/zh_CN"
)

_OPENID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

_HIDDEN_FIELDS = ("__VIEWSTATE", "__VIEWSTATEGENERATOR", "__EVENTVALIDATION")
_HIDDEN_RE = {
    name: re.compile(r'name="%s"[^>]*?value="([^"]*)"' % name) for name in _HIDDEN_FIELDS
}
_LAYER_MSG_RE = re.compile(r"layer\.(?:msg|alert)\(\s*['\"]([^'\"]{1,120})['\"]")

# 绑定接口返回的提示文案
MSG_BIND_OK = ("绑定成功", "已绑定", "不能重复绑定")
MSG_ROOM_UNKNOWN = "未查询到该用户"


class HualiElecError(Exception):
    """Base class for all integration errors."""


class CannotConnectError(HualiElecError):
    """Raised when the remote service cannot be reached."""


class RoomNotFoundError(HualiElecError):
    """Raised when the room number does not exist on the platform."""


class BindError(HualiElecError):
    """Raised when binding the room fails for another reason."""


class NotBoundError(HualiElecError):
    """Raised when the openid is not bound to a room (anymore)."""


class ApiError(HualiElecError):
    """Raised when the service answers with an error payload."""


@dataclass(slots=True)
class MeterData:
    """Latest snapshot of one meter."""

    user_code: str
    user_name: str
    meter_id: str | None
    meter_addr: str | None
    meter_type: str | None
    read_number: float | None
    read_date: str | None
    balance: float | None
    balance_date: str | None
    account_balance: float | None
    arrears: float | None
    price: float | None
    pay_type: str | None
    install_addr: str | None
    total_money: float | None


@dataclass(slots=True)
class UsageRecord:
    """One frozen daily reading."""

    date: str
    begin_date: str | None
    begin_number: float | None
    end_number: float | None
    usage: float | None


def generate_openid() -> str:
    """Return a WeChat-shaped openid that identifies this HA instance."""
    return "o" + "".join(secrets.choice(_OPENID_ALPHABET) for _ in range(27))


def _mask(value: str) -> str:
    return value[:4] + "***" + value[-3:] if len(value) > 8 else "***"


def _rand() -> str:
    return f"{random.random():.16f}"


def _to_float(value: Any) -> float | None:
    if value is None or value == "":
        return None
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        return None


def _to_int(value: Any) -> int | None:
    number = _to_float(value)
    return int(number) if number is not None else None


def _as_list(value: Any) -> list[dict[str, Any]]:
    """Normalise the double-encoded JSON strings the site sometimes returns."""
    if value is None:
        return []
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return []
        try:
            value = json.loads(text)
        except ValueError:
            return []
    if isinstance(value, dict):
        return [value]
    if isinstance(value, list):
        return [item for item in value if isinstance(item, dict)]
    return []


def _extract_hidden(name: str, html: str) -> str:
    match = _HIDDEN_RE[name].search(html)
    return match.group(1) if match else ""


def _extract_message(html: str) -> str | None:
    match = _LAYER_MSG_RE.search(html)
    return match.group(1) if match else None


class HualiElecApi:
    """Thin async wrapper around the campus meter endpoints."""

    def __init__(
        self,
        session: aiohttp.ClientSession,
        *,
        mem_id: str,
        operator_code: str,
        room: str,
        openid: str,
    ) -> None:
        self._session = session
        self._mem_id = mem_id
        self._operator_code = operator_code
        self._room = room
        self._openid = openid

    @property
    def room(self) -> str:
        return self._room

    @property
    def _query(self) -> str:
        return (
            f"memid={self._mem_id}&openid={self._openid}"
            f"&operatorcode={self._operator_code}"
        )

    # ------------------------------------------------------------------ http
    async def _async_get(self, path: str) -> str:
        url = f"{BASE_URL}{path}"
        try:
            async with self._session.get(
                url,
                timeout=TIMEOUT,
                headers={"User-Agent": USER_AGENT, "Referer": url},
            ) as resp:
                if resp.status >= 400:
                    raise CannotConnectError(f"HTTP {resp.status} for {path}")
                return await resp.text()
        except aiohttp.ClientError as err:
            raise CannotConnectError(str(err)) from err
        except TimeoutError as err:  # pragma: no cover - defensive
            raise CannotConnectError("timeout") from err

    async def _async_post(
        self, path: str, data: dict[str, str], *, allow_redirects: bool = True
    ) -> tuple[int, dict[str, str], str]:
        url = f"{BASE_URL}{path}"
        try:
            async with self._session.post(
                url,
                data=data,
                timeout=TIMEOUT,
                allow_redirects=allow_redirects,
                headers={
                    "User-Agent": USER_AGENT,
                    "Referer": f"{BASE_URL}/Web/MWeb/index.aspx?{self._query}",
                    "X-Requested-With": "XMLHttpRequest",
                },
            ) as resp:
                return resp.status, dict(resp.headers), await resp.text()
        except aiohttp.ClientError as err:
            raise CannotConnectError(str(err)) from err
        except TimeoutError as err:  # pragma: no cover - defensive
            raise CannotConnectError("timeout") from err

    async def _async_post_json(
        self, path: str, data: dict[str, Any], *, not_bound_is_soft: bool = True
    ) -> dict[str, Any]:
        payload_data = {key: str(value) for key, value in data.items()}
        status, _, text = await self._async_post(path, payload_data)
        body = text.strip()
        if not body.startswith("{"):
            # The platform answers with an HTML page while the openid has no
            # bound room (or the session expired).
            message = _extract_message(text) or "用户未绑定"
            if not_bound_is_soft:
                raise NotBoundError(message)
            raise ApiError(message)
        try:
            payload = json.loads(body)
        except ValueError as err:
            raise ApiError(f"HTTP {status}: 无法解析返回数据") from err
        if str(payload.get("RtnCode")) != "9999":
            raise ApiError(str(payload.get("RtnMsg") or "接口返回失败"))
        return payload

    # ------------------------------------------------------------------ bind
    async def async_bind(self) -> None:
        """Bind ``room`` to our openid (idempotent)."""
        path = f"/Web/MWeb/login_HN.aspx?{self._query}"
        html = await self._async_get(path)

        data = {
            "__VIEWSTATE": _extract_hidden("__VIEWSTATE", html),
            "__VIEWSTATEGENERATOR": _extract_hidden("__VIEWSTATEGENERATOR", html),
            "__EVENTVALIDATION": _extract_hidden("__EVENTVALIDATION", html),
            "citySel": self._room,
            "dept": "",
            "ddlRoom": self._room,
            "btnSubmit": "绑",
            "userid": "",
            "memid": self._mem_id,
            "openid": self._openid,
            "operatorcode": self._operator_code,
            "hidThirdUrl": "http://127.0.0.1:8051/api/Meters/",
            "hidUserCode": self._room,
        }
        status, headers, body = await self._async_post(path, data, allow_redirects=False)

        if any(msg in body for msg in MSG_BIND_OK) or "index.aspx" in headers.get(
            "Location", ""
        ):
            _LOGGER.debug("Bound room %s to openid %s", self._room, _mask(self._openid))
            return
        if MSG_ROOM_UNKNOWN in body:
            raise RoomNotFoundError(
                f"房间 {self._room} 不存在，请检查楼栋/房号（如 A1-105）"
            )
        raise BindError(_extract_message(body) or f"绑定失败 (HTTP {status})")

    async def async_unbind(self) -> None:
        """Remove our openid binding from the room (best effort cleanup)."""
        payload = await self._async_post_json(
            "/Web/MWeb/member.aspx?_r=delbind",
            {
                "usercode": self._room,
                "openid": self._openid,
                "memid": self._mem_id,
                "userid": "",
                "operatorcode": self._operator_code,
                "_m": _rand(),
            },
            not_bound_is_soft=False,
        )
        _LOGGER.debug("Unbound room %s (%s)", self._room, payload.get("RtnMsg"))

    # ------------------------------------------------------------------ data
    async def async_fetch_meter(self) -> MeterData:
        """Return the current meter snapshot for the bound room."""
        payload = await self._async_post_json(
            "/Web/MWeb/payusereqlist.aspx?_r=show",
            {
                "openid": self._openid,
                "memid": self._mem_id,
                "operatorcode": self._operator_code,
                "_m": _rand(),
            },
        )
        users = _as_list(payload.get("UserDataBody"))
        if not users:
            raise ApiError("未返回用户信息")

        wanted = self._room.strip().upper()
        user = next(
            (u for u in users if str(u.get("UserCode", "")).strip().upper() == wanted),
            users[0],
        )
        meters = _as_list(user.get("MeterDataBody"))
        if not meters:
            raise ApiError("该户号没有可查询的表具")

        # 电表优先（宿舍以电为主），其余取第一只表
        meter = next(
            (m for m in meters if "电" in str(m.get("MeterTypeName", ""))),
            meters[0],
        )

        user_code = str(user.get("UserCode") or self._room)
        meter_reserve = _to_float(meter.get("Reserve"))
        account_balance = (
            meter_reserve
            if meter_reserve
            else _to_float(user.get("UserReserve"))
        )
        meter_id = meter.get("MeterID")
        return MeterData(
            user_code=user_code,
            user_name=str(user.get("UserName") or user_code),
            meter_id=str(meter_id) if meter_id is not None else None,
            meter_addr=_str_or_none(meter.get("MeterAddr")),
            meter_type=_str_or_none(meter.get("MeterTypeName")),
            read_number=_to_float(meter.get("ReadNumber")),
            read_date=_str_or_none(meter.get("ReadDate")),
            balance=_to_float(meter.get("Remnant")),
            balance_date=_str_or_none(meter.get("RemnantDate")),
            account_balance=account_balance,
            arrears=_to_float(meter.get("FareMoney")),
            price=_to_float(meter.get("Price")),
            pay_type=_str_or_none(meter.get("PayType")),
            install_addr=_str_or_none(meter.get("InstallAddr")),
            total_money=_to_float(meter.get("TotalMoney")),
        )

    async def async_fetch_usage(
        self, meter_addr: str | None, *, max_pages: int = 4
    ) -> list[UsageRecord]:
        """Return daily usage records, newest first."""
        records: list[UsageRecord] = []
        for page in range(1, max_pages + 1):
            payload = await self._async_post_json(
                "/Web/MWeb/amountlist.aspx?_r=show",
                {
                    "openid": self._openid,
                    "memid": self._mem_id,
                    "operatorcode": self._operator_code,
                    "meteraddr": meter_addr or "",
                    "usercode": self._room,
                    "currentPage": page,
                    "_m": _rand(),
                },
            )
            rows = _as_list(payload.get("MonthDataBody"))
            if not rows:
                break
            records.extend(_parse_usage_row(row) for row in rows)
            total_pages = _to_int(payload.get("TotalPages"))
            if total_pages is not None and page >= total_pages:
                break
        return records


def _str_or_none(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _parse_usage_row(row: dict[str, Any]) -> UsageRecord:
    return UsageRecord(
        date=str(row.get("FreezeMonth") or ""),
        begin_date=_str_or_none(row.get("BeginMonth")),
        begin_number=_to_float(row.get("BeginNumber")),
        end_number=_to_float(row.get("EndNumber")),
        usage=_to_float(row.get("UseNumber")),
    )
