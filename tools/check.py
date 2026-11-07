#!/usr/bin/env python3
"""Standalone checker for the 华立电表 (Huali dorm meter) API.

Useful to verify a campus/room combination outside of Home Assistant.

    pip install aiohttp
    python tools/check.py --campus zengcheng --room A1-105

The script binds a temporary identifier, prints the meter snapshot plus the
last days of usage, then unbinds again.
"""

from __future__ import annotations

import argparse
import asyncio
from dataclasses import asdict
import json
import pathlib
import sys
import types

# Load the integration modules without importing the package __init__ (which
# requires Home Assistant itself to be installed).
_PKG_DIR = pathlib.Path(__file__).resolve().parents[1] / "custom_components" / "huali_elec"
_PKG = types.ModuleType("huali_elec")
_PKG.__path__ = [str(_PKG_DIR)]
sys.modules["huali_elec"] = _PKG

import aiohttp  # noqa: E402

from huali_elec import api as _api  # noqa: E402
from huali_elec import const as _const  # noqa: E402


async def main() -> int:
    parser = argparse.ArgumentParser(description="华立电表 API 自检工具")
    parser.add_argument(
        "--campus",
        default="zengcheng",
        choices=sorted(_const.CAMPUSES),
        help="校区（默认 zengcheng 增城校区）",
    )
    parser.add_argument("--room", required=True, help="房间号，例如 A1-105")
    parser.add_argument(
        "--keep-binding",
        action="store_true",
        help="保留临时绑定（默认查询完成后解绑）",
    )
    args = parser.parse_args()

    campus = _const.CAMPUSES[args.campus]
    room = args.room.strip().upper()
    openid = _api.generate_openid()
    print(f"校区: {campus['name']}  memid={campus['memid']}  operatorcode={campus['operator_code']}")
    print(f"房间: {room}  临时 openid: {openid}")

    async with aiohttp.ClientSession(
        cookie_jar=aiohttp.CookieJar(unsafe=True),
        headers={"User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) MicroMessenger/8.0.44"},
    ) as session:
        api = _api.HualiElecApi(
            session,
            mem_id=campus["memid"],
            operator_code=campus["operator_code"],
            room=room,
            openid=openid,
        )
        try:
            await api.async_bind()
            meter = await api.async_fetch_meter()
            usage = await api.async_fetch_usage(meter.meter_addr, max_pages=2)
        except _api.HualiElecError as err:
            print(f"失败: {err}", file=sys.stderr)
            return 1

        print(json.dumps(asdict(meter), ensure_ascii=False, indent=2))
        print(f"\n最近 {len(usage)} 天用电:")
        for record in usage:
            print(f"  {record.date}  {record.usage} 度  (读数 {record.end_number})")

        if usage:
            latest = usage[0]
            month = latest.date[:7]
            month_total = sum(r.usage or 0 for r in usage if r.date.startswith(month))
            print(f"\n{month} 已获取的用电合计: {month_total:.2f} 度")

        if not args.keep_binding:
            try:
                await api.async_unbind()
                print("\n已解绑临时 openid")
            except _api.HualiElecError as err:
                print(f"解绑失败（可忽略，绑定只影响本次临时 openid）: {err}", file=sys.stderr)

    print(f"\n平台入口: {_const.BASE_URL}/Web/MWeb/index.aspx")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
