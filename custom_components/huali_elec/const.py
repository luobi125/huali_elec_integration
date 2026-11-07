"""Constants for the 华立电表 integration."""

from __future__ import annotations

DOMAIN = "huali_elec"

# 华立数研智慧微平台 (微信端电表充值/查询系统)
BASE_URL = "https://dbwx.hlsywy.com"

DEFAULT_SCAN_INTERVAL = 30  # minutes
MIN_SCAN_INTERVAL = 5
MAX_SCAN_INTERVAL = 720

# 历史用电（日用量）缓存时间，秒
HISTORY_TTL = 3600

# 校区 -> (memid, operatorcode)
# 来源：https://db-naviga.zyxinlab.com/ 宿舍入口 -> dbwx.hlsywy.com/Web/MWeb/jump.html
CAMPUSES: dict[str, dict[str, str]] = {
    "zengcheng": {
        "name": "增城校区",
        "memid": "208",
        "operator_code": "ZCSSDB",
    },
    "yunfu": {
        "name": "云浮校区",
        "memid": "250",
        "operator_code": "YFSSDB",
    },
    "jiangmen_bachelor": {
        "name": "江门本科校区",
        "memid": "208",
        "operator_code": "JMBKSSDB",
    },
    "jiangmen_technician": {
        "name": "江门技师校区",
        "memid": "179",
        "operator_code": "JMJSSSDB",
    },
}

CONF_CAMPUS = "campus"
CONF_ROOM = "room"
CONF_OPENID = "openid"
CONF_SCAN_INTERVAL = "scan_interval"
