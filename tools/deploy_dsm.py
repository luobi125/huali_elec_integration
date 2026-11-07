"""把华立电表集成部署到 192.168.16.4 上的 Home Assistant（DSM/File Station 路径）。

用法：
    python deploy_dsm.py --user luobi --password 'xxx'
    python deploy_dsm.py --user luobi --password 'xxx' --config-dir /volume1/docker/homeassistant/config

流程：DSM 登录 → 自动定位 HA 配置目录（找 configuration.yaml）→ 上传 zip → 解压到
custom_components/ → 调 HA API 重启 → 用配置流程添加集成 → 校验实体。
"""

from __future__ import annotations

import argparse
import io
import json
import os
import sys
import time
import zipfile

import requests

NAS = "http://192.168.16.4:5000"
HA = "http://192.168.16.4:8123"
REPO = r"C:\Users\luobi\huali_elec"
ZIP_NAME = "huali_elec_deploy.zip"


# --------------------------------------------------------------------------- DSM
class DSM:
    def __init__(self, user: str, password: str) -> None:
        self.s = requests.Session()
        self.base = NAS
        r = self.s.get(
            f"{NAS}/webapi/auth.cgi",
            params={
                "api": "SYNO.API.Auth",
                "version": "6",
                "method": "login",
                "account": user,
                "passwd": password,
                "session": "FileStation",
                "format": "sid",
            },
            timeout=20,
        )
        data = r.json()
        if not data.get("success"):
            raise SystemExit(f"DSM 登录失败: {data}")
        self.sid = data["data"]["sid"]
        print("DSM 登录成功")

    def _call(self, api: str, method: str, version: str = "2", **params):
        payload = {"api": api, "version": version, "method": method, "_sid": self.sid, **params}
        r = self.s.get(f"{self.base}/webapi/entry.cgi", params=payload, timeout=60)
        try:
            return r.json()
        except ValueError:
            raise SystemExit(f"{api}/{method} 返回非 JSON: {r.text[:300]}")

    def shares(self) -> list[str]:
        data = self._call("SYNO.FileStation.List", "list_share", additional='["real_path"]')
        if not data.get("success"):
            raise SystemExit(f"list_share 失败: {data}")
        return data["data"]["shares"]

    def find_config_dir(self, shares: list[str]) -> str | None:
        """在 docker 相关共享里找包含 configuration.yaml 的目录。"""
        for share in shares:
            path = share["path"]
            if not path.startswith("/volume"):
                continue
            data = self._call(
                "SYNO.FileStation.Search",
                "start",
                folder_path=path,
                pattern="configuration.yaml",
                recursive="true",
                version="2",
            )
            if not data.get("success"):
                continue
            taskid = data["data"]["taskid"]
            for _ in range(30):
                time.sleep(2)
                st = self._call("SYNO.FileStation.Search", "status", taskid=taskid)
                if not st.get("success"):
                    break
                d = st["data"]
                if d.get("finished"):
                    for f in d.get("files", []):
                        p = os.path.dirname(f["path"])
                        print("  找到配置目录:", p)
                        return p
                    break
        return None

    def upload(self, local_zip: str, remote_dir: str) -> None:
        with open(local_zip, "rb") as fh:
            r = self.s.post(
                f"{self.base}/webapi/entry.cgi",
                params={
                    "api": "SYNO.FileStation.Upload",
                    "version": "2",
                    "method": "upload",
                    "_sid": self.sid,
                    "path": remote_dir,
                    "create_parents": "true",
                    "overwrite": "true",
                },
                files={"file": (os.path.basename(local_zip), fh, "application/zip")},
                timeout=180,
            )
        data = r.json()
        if not data.get("success"):
            raise SystemExit(f"上传失败: {data}")
        print("zip 已上传到", remote_dir)

    def extract(self, remote_dir: str, name: str) -> None:
        data = self._call(
            "SYNO.FileStation.Extract",
            "start",
            file_path=f"{remote_dir}/{name}",
            dest_path=remote_dir,
            overwrite="true",
        )
        if not data.get("success"):
            raise SystemExit(f"解压失败: {data}")
        print("已解压")


# --------------------------------------------------------------------------- HA
def ha_token(user: str, password: str) -> str:
    client_id = f"{HA}/"
    s = requests.Session()
    flow = s.post(
        f"{HA}/auth/login_flow",
        json={"client_id": client_id, "handler": ["homeassistant", None], "redirect_uri": client_id, "type": "authorize"},
        timeout=20,
    ).json()
    step = s.post(
        f"{HA}/auth/login_flow/{flow['flow_id']}",
        json={"client_id": client_id, "username": user, "password": password},
        timeout=20,
    ).json()
    if step.get("type") != "create_entry":
        raise SystemExit(f"HA 登录失败: {step}")
    tok = s.post(
        f"{HA}/auth/token",
        data={"grant_type": "authorization_code", "code": step["result"], "client_id": client_id},
        timeout=20,
    ).json()
    return tok["access_token"]


def add_integration(token: str, campus: str, room: str) -> None:
    H = {"Authorization": f"Bearer {token}"}
    flow = requests.post(
        f"{HA}/api/config/config_entries/flow",
        headers=H,
        json={"handler": "huali_elec", "show_advanced_options": False},
        timeout=60,
    )
    print("配置流程:", flow.status_code, flow.text[:300])
    flow.raise_for_status()
    flow_id = flow.json()["flow_id"]
    step = requests.post(
        f"{HA}/api/config/config_entries/flow/{flow_id}",
        headers=H,
        json={"campus": campus, "room": room},
        timeout=120,
    )
    print("提交房间:", step.status_code, step.text[:400])


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--user", required=True, help="NAS 用户名")
    ap.add_argument("--password", required=True, help="NAS 密码")
    ap.add_argument("--config-dir", default=None, help="HA 配置目录（可选，默认自动搜索）")
    ap.add_argument("--ha-user", default="luobi")
    ap.add_argument("--ha-password", default="luobi#2006")
    ap.add_argument("--campus", default="zengcheng")
    ap.add_argument("--room", default=None, help="房号，例如 A1-105")
    ap.add_argument("--skip-ha", action="store_true")
    args = ap.parse_args()

    # 打 zip：custom_components/huali_elec/...
    zip_path = os.path.join(REPO, ZIP_NAME)
    src = os.path.join(REPO, "custom_components", "huali_elec")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, _dirs, files in os.walk(src):
            for name in files:
                if "__pycache__" in root:
                    continue
                full = os.path.join(root, name)
                rel = os.path.relpath(full, os.path.join(REPO, "custom_components"))
                zf.write(full, os.path.join("custom_components", rel))
    print("打包完成:", zip_path)

    dsm = DSM(args.user, args.password)
    config_dir = args.config_dir
    if not config_dir:
        shares = dsm.shares()
        print("共享文件夹:", [(s["path"], s["name"]) for s in shares])
        config_dir = dsm.find_config_dir(shares)
        if not config_dir:
            raise SystemExit("没找到 configuration.yaml，请用 --config-dir 指定 HA 配置目录")
    dsm.upload(zip_path, config_dir)
    dsm.extract(config_dir, ZIP_NAME)

    if args.skip_ha:
        return
    token = ha_token(args.ha_user, args.ha_password)
    io.open("ha_token.txt", "w", encoding="utf-8").write(token)
    H = {"Authorization": f"Bearer {token}"}
    print("重启 HA:", requests.post(f"{HA}/api/services/homeassistant/restart", headers=H, timeout=30).status_code)
    if args.room:
        print("等待 HA 回来…")
        for _ in range(60):
            time.sleep(5)
            try:
                if requests.get(f"{HA}/api/", headers={"Authorization": f"Bearer {ha_token(args.ha_user, args.ha_password)}"}, timeout=10).status_code == 200:
                    break
            except Exception:  # noqa: BLE001
                continue
        token = ha_token(args.ha_user, args.ha_password)
        add_integration(token, args.campus, args.room)


if __name__ == "__main__":
    main()
