# Reference copy: personal account credentials removed.
# -*- coding: utf-8 -*-

import json
import base64
from datetime import datetime, timezone, timedelta
from urllib.parse import (
    urlparse,
    parse_qs,
    urljoin,
    quote,
)

import requests
from bs4 import BeautifulSoup

from Crypto.Cipher import AES, DES
from Crypto.Util.Padding import pad


# ============================================================
#                     配置区域
# ============================================================

# 福大统一认证账号
USERNAME = ""

# 福大统一认证密码
PASSWORD = ""


# -------------------- 签到位置 --------------------

# 纬度
LATITUDE = 26.05

# 经度
LONGITUDE = 119.19

# 地址。(不知道干啥的，AI写的，放空应该就ok)
ADDRESS = ""


# -------------------- 其他配置 --------------------

BASE_URL = "https://yzsxg.fzu.edu.cn"

AUTHORIZE_URL = (
    "https://yzsxg.fzu.edu.cn/"
    "livecloud/project/fzu/attn/oauth2/authorize.action"
)

# 网页没有 FCaIsNo 参数时默认就是 0
CA_IS_NO = "0"

REQUEST_TIMEOUT = (8, 25)

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/140.0.0.0 Safari/537.36"
)


# 晚点名接口 AES
ATTN_AES_KEY = b"apexinfoapexinfo"
ATTN_AES_IV = b"apexinfoapexinfo"

# 中国时区
CHINA_TZ = timezone(timedelta(hours=8))


# ============================================================
#                  一些通用辅助函数
# ============================================================

def get_token_from_url(url):
    """
    从 URL 中读取：
        ?token=xxxxx
    """

    if not url:
        return None

    try:
        query = parse_qs(
            urlparse(url).query
        )

        return query.get(
            "token",
            [None]
        )[0]

    except Exception:
        return None


def find_token_in_response(response):
    """
    同时从 Response 当前 URL 和 Location 中寻找 token。
    """

    token = get_token_from_url(
        response.url
    )

    if token:
        return token

    location = response.headers.get(
        "Location"
    )

    if location:
        target = urljoin(
            response.url,
            location
        )

        token = get_token_from_url(
            target
        )

        if token:
            return token

    return None


def get_element_value(soup, element_id):
    """
    HTML 中某个元素可能是：

        <input id="..." value="...">

    也可能是：

        <p id="...">...</p>

    两种都支持。
    """

    element = soup.find(
        id=element_id
    )

    if element is None:
        return None

    if element.name == "input":
        return element.get("value")

    return element.get_text(
        strip=True
    )


# ============================================================
#                 SSO 密码加密
# ============================================================

def encrypt_sso_password(
    password,
    croypto
):
    """
    croypto 是 SSO 页面动态下发的 Base64 密钥。

    当前这类 SSO 部署主要存在：

        AES-ECB
        DES-ECB

    根据解码后的密钥长度选择。
    """

    try:
        key = base64.b64decode(
            croypto
        )

    except Exception as e:
        raise RuntimeError(
            f"无法解析 croypto：{e}"
        )

    raw = password.encode(
        "utf-8"
    )

    print(
        "[SSO] 动态密钥长度：",
        len(key),
        "bytes"
    )

    # --------------------------------------------------------
    # AES
    # --------------------------------------------------------

    if len(key) in (
        16,
        24,
        32
    ):

        print(
            "[SSO] 使用 AES-ECB"
        )

        cipher = AES.new(
            key,
            AES.MODE_ECB
        )

        encrypted = cipher.encrypt(
            pad(
                raw,
                AES.block_size
            )
        )

    # --------------------------------------------------------
    # DES
    # --------------------------------------------------------

    elif len(key) == 8:

        print(
            "[SSO] 使用 DES-ECB"
        )

        cipher = DES.new(
            key,
            DES.MODE_ECB
        )

        encrypted = cipher.encrypt(
            pad(
                raw,
                DES.block_size
            )
        )

    else:

        raise RuntimeError(
            "无法识别 SSO 密钥长度："
            + str(len(key))
        )

    return base64.b64encode(
        encrypted
    ).decode("utf-8")


# ============================================================
#                  解析 SSO 登录页面
# ============================================================

def parse_sso_page(html):
    soup = BeautifulSoup(
        html,
        "html.parser"
    )

    # --------------------------------------------------------
    # croypto
    # --------------------------------------------------------

    croypto = get_element_value(
        soup,
        "login-croypto"
    )

    if not croypto:

        element = soup.find(
            "input",
            attrs={
                "name": "croypto"
            }
        )

        if element:
            croypto = element.get(
                "value"
            )

    # --------------------------------------------------------
    # execution
    # --------------------------------------------------------

    execution = None

    element = soup.find(
        "input",
        attrs={
            "name": "execution"
        }
    )

    if element:
        execution = element.get(
            "value"
        )

    if not execution:
        execution = get_element_value(
            soup,
            "execution"
        )

    if not execution:
        execution = get_element_value(
            soup,
            "login-page-flowkey"
        )

    # --------------------------------------------------------
    # 登录类型
    # --------------------------------------------------------

    login_type = get_element_value(
        soup,
        "current-login-type"
    )

    if not login_type:
        login_type = "UsernamePassword"

    # --------------------------------------------------------
    # 是否要求验证码
    # --------------------------------------------------------

    captcha_url = get_element_value(
        soup,
        "captcha-url"
    )

    # --------------------------------------------------------
    # 找登录 form
    # --------------------------------------------------------

    form = None

    username_input = soup.find(
        "input",
        attrs={
            "name": "username"
        }
    )

    if username_input:
        form = username_input.find_parent(
            "form"
        )

    if form is None:

        execution_input = soup.find(
            "input",
            attrs={
                "name": "execution"
            }
        )

        if execution_input:
            form = execution_input.find_parent(
                "form"
            )

    if form is None:
        form = soup.find("form")

    return {
        "soup": soup,
        "form": form,
        "croypto": croypto,
        "execution": execution,
        "login_type": login_type,
        "captcha_url": captcha_url,
    }


# ============================================================
#                     自动登录 SSO
# ============================================================

def login():
    """
    返回：

        session
        token
    """

    if not USERNAME.strip():
        raise RuntimeError(
            "请在代码顶部填写 USERNAME"
        )

    if not PASSWORD:
        raise RuntimeError(
            "请在代码顶部填写 PASSWORD"
        )

    session = requests.Session()

    session.headers.update({
        "User-Agent": USER_AGENT,
        "Accept-Language":
            "zh-CN,zh;q=0.9,en;q=0.8",
    })

    # ========================================================
    # STEP 1
    # 从晚点名授权入口开始。
    #
    # 不直接 GET sso.fzu.edu.cn/login，
    # 因为我们需要保留晚点名系统对应的 service/callback。
    # ========================================================

    print()
    print(
        "========================================"
    )
    print("1. 获取 SSO 登录页面")
    print(
        "========================================"
    )

    response = session.get(
        AUTHORIZE_URL,
        allow_redirects=True,
        timeout=REQUEST_TIMEOUT
    )

    print(
        "[SSO] 当前地址：",
        response.url
    )

    # --------------------------------------------------------
    # 如果 session 本身已经获得 token
    # --------------------------------------------------------

    chain = list(
        response.history
    ) + [response]

    for item in chain:

        token = find_token_in_response(
            item
        )

        if token:

            print(
                "[SSO] 已直接获得 Token"
            )

            return session, token

    # --------------------------------------------------------
    # 应当进入 SSO
    # --------------------------------------------------------

    if (
        "sso.fzu.edu.cn"
        not in urlparse(response.url).netloc
    ):

        raise RuntimeError(
            "没有进入福大统一身份认证页面：\n"
            + response.url
        )

    # ========================================================
    # STEP 2
    # 解析动态参数
    # ========================================================

    print()
    print(
        "========================================"
    )
    print("2. 解析 SSO 动态参数")
    print(
        "========================================"
    )

    page_info = parse_sso_page(
        response.text
    )

    croypto = page_info[
        "croypto"
    ]

    execution = page_info[
        "execution"
    ]

    login_type = page_info[
        "login_type"
    ]

    captcha_url = page_info[
        "captcha_url"
    ]

    form = page_info[
        "form"
    ]

    if not croypto:

        with open(
            "sso_page.html",
            "w",
            encoding="utf-8"
        ) as f:
            f.write(
                response.text
            )

        raise RuntimeError(
            "没有找到 croypto。\n"
            "登录页面已保存为 sso_page.html"
        )

    if not execution:

        with open(
            "sso_page.html",
            "w",
            encoding="utf-8"
        ) as f:
            f.write(
                response.text
            )

        raise RuntimeError(
            "没有找到 execution。\n"
            "登录页面已保存为 sso_page.html"
        )

    print(
        "[SSO] croypto 获取成功"
    )

    print(
        "[SSO] execution 获取成功"
    )

    print(
        "[SSO] 登录类型：",
        login_type
    )

    # --------------------------------------------------------
    # 验证码
    # --------------------------------------------------------

    if (
        captcha_url
        and captcha_url.strip()
    ):

        raise RuntimeError(
            "当前 SSO 登录流程要求验证码。\n"
            "为了避免绕过验证码，程序停止自动登录。"
        )

    # ========================================================
    # STEP 3
    # 加密密码
    # ========================================================

    encrypted_password = (
        encrypt_sso_password(
            PASSWORD,
            croypto
        )
    )

    print(
        "[SSO] 密码加密完成"
    )

    # ========================================================
    # STEP 4
    # 自动复制登录页面的隐藏字段
    # ========================================================

    login_data = {}

    if form:

        for inp in form.find_all(
            "input"
        ):

            name = inp.get(
                "name"
            )

            if not name:
                continue

            value = inp.get(
                "value"
            )

            login_data[
                name
            ] = value or ""

    # --------------------------------------------------------
    # 覆盖关键字段
    # --------------------------------------------------------

    login_data[
        "username"
    ] = USERNAME

    login_data[
        "password"
    ] = encrypted_password

    login_data[
        "type"
    ] = login_type

    login_data[
        "_eventId"
    ] = "submit"

    login_data[
        "execution"
    ] = execution

    login_data[
        "croypto"
    ] = croypto

    login_data[
        "geolocation"
    ] = ""

    # 某些版本会包含这些字段
    if "captcha_code" in login_data:
        login_data[
            "captcha_code"
        ] = ""

    if "captcha_payload" in login_data:
        login_data[
            "captcha_payload"
        ] = ""

    # ========================================================
    # STEP 5
    # 确定 POST 地址
    # ========================================================

    page_url = response.url

    if (
        form
        and form.get("action")
    ):

        login_url = urljoin(
            page_url,
            form.get("action")
        )

        # ----------------------------------------------------
        # 如果 form action 只是 /login，
        # 而当前 URL 中有 service 等参数，
        # 保留当前 query。
        # ----------------------------------------------------

        old = urlparse(
            page_url
        )

        new = urlparse(
            login_url
        )

        if (
            old.path == new.path
            and old.query
            and not new.query
        ):

            login_url = (
                login_url
                + "?"
                + old.query
            )

    else:

        login_url = page_url

    parsed_login = urlparse(
        login_url
    )

    origin = (
        parsed_login.scheme
        + "://"
        + parsed_login.netloc
    )

    # ========================================================
    # STEP 6
    # POST 登录
    # ========================================================

    print()
    print(
        "========================================"
    )
    print("3. 提交统一认证")
    print(
        "========================================"
    )

    login_response = session.post(
        login_url,
        data=login_data,
        headers={
            "User-Agent":
                USER_AGENT,

            "Referer":
                page_url,

            "Origin":
                origin,

            "Content-Type":
                "application/x-www-form-urlencoded",

            "Accept":
                (
                    "text/html,"
                    "application/xhtml+xml,"
                    "application/xml;q=0.9,"
                    "*/*;q=0.8"
                ),
        },
        allow_redirects=False,
        timeout=REQUEST_TIMEOUT
    )

    print(
        "[SSO] 登录 HTTP 状态：",
        login_response.status_code
    )

    # --------------------------------------------------------
    # 先看看这个 response 自己有没有 token
    # --------------------------------------------------------

    token = find_token_in_response(
        login_response
    )

    if token:

        print(
            "[SSO] 登录成功，获得 Token"
        )

        return session, token

    # --------------------------------------------------------
    # 成功一般是 30x
    # --------------------------------------------------------

    if login_response.status_code not in (
        301,
        302,
        303,
        307,
        308,
    ):

        # 保存错误页方便分析
        with open(
            "sso_login_failed.html",
            "w",
            encoding="utf-8"
        ) as f:

            f.write(
                login_response.text
            )

        soup = BeautifulSoup(
            login_response.text,
            "html.parser"
        )

        error_text = ""

        # 尝试提取错误提示
        for error_id in (
            "login-error-msg",
            "login-error-message",
            "error-message",
            "errorMsg",
        ):

            element = soup.find(
                id=error_id
            )

            if element:

                error_text = (
                    element.get_text(
                        " ",
                        strip=True
                    )
                )

                break

        if not error_text:

            text = soup.get_text(
                " ",
                strip=True
            )

            if "验证码" in text:
                error_text = (
                    "登录页面要求验证码"
                )

            elif "密码" in text:
                error_text = (
                    "可能是账号或密码错误"
                )

        raise RuntimeError(
            "统一认证登录失败。\n"
            f"HTTP={login_response.status_code}\n"
            f"{error_text}\n"
            "服务器页面已保存为 "
            "sso_login_failed.html"
        )

    # ========================================================
    # STEP 7
    # 手动跟踪重定向
    #
    # Token 可能出现在某一层 Location 中。
    # ========================================================

    print()
    print(
        "========================================"
    )
    print("4. 处理 SSO 回调")
    print(
        "========================================"
    )

    current = login_response

    token = None

    for i in range(20):

        # ----------------------------------------------------
        # 当前响应
        # ----------------------------------------------------

        found = find_token_in_response(
            current
        )

        if found:
            token = found

        location = (
            current.headers.get(
                "Location"
            )
        )

        if not location:
            break

        next_url = urljoin(
            current.url,
            location
        )

        # ----------------------------------------------------
        # 先检查即将跳转的 URL
        # ----------------------------------------------------

        found = get_token_from_url(
            next_url
        )

        if found:
            token = found

        parsed = urlparse(
            next_url
        )

        print(
            f"[SSO] redirect {i + 1}:",
            parsed.netloc,
            parsed.path
        )

        current = session.get(
            next_url,
            headers={
                "User-Agent":
                    USER_AGENT,

                "Referer":
                    current.url,
            },
            allow_redirects=False,
            timeout=REQUEST_TIMEOUT
        )

    # --------------------------------------------------------
    # 最后再检查一次
    # --------------------------------------------------------

    found = find_token_in_response(
        current
    )

    if found:
        token = found

    if not token:

        raise RuntimeError(
            "统一认证似乎已经完成，"
            "但回调过程中没有找到晚点名 Token。"
        )

    print()
    print(
        "[SSO] 登录成功"
    )
    print(
        "[SSO] 已获得当天动态 Token"
    )

    # 不打印 token

    return session, token


# ============================================================
#                     晚点名签到
# ============================================================

class FZUCheckin:

    def __init__(
        self,
        session,
        token
    ):
        self.session = session
        self.token = token

        self.session.headers.update({
            "token": token,

            "Accept":
                "application/json, text/plain, */*",

            "User-Agent":
                USER_AGENT,

            "Referer": (
                BASE_URL
                + "/plug-in/livecloud/project/"
                "fzu/attn/index.action"
            ),
        })

    # ========================================================
    # 晚点名参数 AES
    #
    # AES-CBC
    # key = apexinfoapexinfo
    # iv  = apexinfoapexinfo
    # PKCS7
    # Base64
    # ========================================================

    @staticmethod
    def encrypt(data):

        text = json.dumps(
            data,
            ensure_ascii=False,
            separators=(
                ",",
                ":"
            )
        )

        cipher = AES.new(
            ATTN_AES_KEY,
            AES.MODE_CBC,
            ATTN_AES_IV
        )

        encrypted = cipher.encrypt(
            pad(
                text.encode("utf-8"),
                AES.block_size
            )
        )

        return base64.b64encode(
            encrypted
        ).decode("utf-8")

    # ========================================================
    # 解析统一 API 返回
    # ========================================================

    @staticmethod
    def parse_api_response(
        response,
        name
    ):

        print(
            f"[API] {name}: "
            f"HTTP {response.status_code}"
        )

        try:

            obj = response.json()

        except Exception:

            raise RuntimeError(
                f"{name} 没有返回 JSON：\n"
                + response.text[:500]
            )

        if (
            obj.get("success") is not True
            or obj.get("code") != 1
        ):

            raise RuntimeError(
                f"{name} 请求失败："
                + str(
                    obj.get("msg")
                    or obj
                )
            )

        return obj.get(
            "data"
        )

    # ========================================================
    # init.action
    # ========================================================

    def init(self):

        response = self.session.post(
            BASE_URL
            + "/livecloud/project/fzu/"
            "attn/init.action",

            json={},

            timeout=REQUEST_TIMEOUT
        )

        return self.parse_api_response(
            response,
            "init.action"
        )

    # ========================================================
    # currentTimestamp.action
    #
    # 网页这里传的是 {}，
    # 因此没有 param。
    # ========================================================

    def get_server_timestamp(self):

        response = self.session.get(
            BASE_URL
            + "/livecloud/project/fzu/"
            "attn/currentTimestamp.action",

            timeout=REQUEST_TIMEOUT
        )

        data = self.parse_api_response(
            response,
            "currentTimestamp.action"
        )

        if not data:
            return None

        return data.get(
            "timestamp"
        )

    # ========================================================
    # 网页式加密 GET
    #
    # JS:
    #
    # param:
    #     encodeURIComponent(
    #         Encrypt(payload)
    #     )
    #
    # 随后 axios 又会将 params 编码到 URL。
    # ========================================================

    def encrypted_get(
        self,
        path,
        payload
    ):

        encrypted = self.encrypt(
            payload
        )

        # 第一次编码：
        # 对应 JS encodeURIComponent
        encoded = quote(
            encrypted,
            safe=""
        )

        response = self.session.get(
            BASE_URL + path,

            params={
                "param": encoded
            },

            timeout=REQUEST_TIMEOUT
        )

        return self.parse_api_response(
            response,
            path
        )

    # ========================================================
    # 合并 schoolData 中所有签到范围
    # ========================================================

    @staticmethod
    def build_school_position(
        init_data
    ):

        result = {
            "range": []
        }

        for school in (
            init_data.get(
                "schoolData"
            )
            or []
        ):

            raw = school.get(
                "FPosition"
            )

            if not raw:
                continue

            try:

                position = json.loads(
                    raw
                )

            except Exception:

                continue

            result[
                "range"
            ].extend(
                position.get(
                    "range"
                )
                or []
            )

        return result

    # ========================================================
    # 根据 campus ID 找校区名称
    # ========================================================

    @staticmethod
    def get_campus_name(
        init_data,
        campus_id
    ):

        for school in (
            init_data.get(
                "schoolData"
            )
            or []
        ):

            if (
                str(
                    school.get("ID")
                )
                ==
                str(campus_id)
            ):

                return (
                    school.get(
                        "FName"
                    )
                    or ""
                )

        return ""

    # ========================================================
    # check.action
    # ========================================================

    def check_location(
        self,
        init_data
    ):

        print()
        print(
            "========================================"
        )
        print("6. 校验签到位置")
        print(
            "========================================"
        )

        position = (
            self.build_school_position(
                init_data
            )
        )

        payload = {
            "mapType":
                "amap",

            "longitude":
                LONGITUDE,

            "latitude":
                LATITUDE,

            "range":
                json.dumps(
                    position,
                    ensure_ascii=False,
                    separators=(
                        ",",
                        ":"
                    )
                ),
        }

        result = self.encrypted_get(
            "/livecloud/project/fzu/"
            "attn/check.action",

            payload
        )

        if not isinstance(
            result,
            list
        ):

            return None

        if not result:
            return None

        return result[0]

    # ========================================================
    # 将服务器 timestamp 转成北京时间
    # ========================================================

    @staticmethod
    def timestamp_to_datetime(
        timestamp
    ):

        if timestamp is None:
            return datetime.now(
                CHINA_TZ
            )

        try:

            timestamp = float(
                timestamp
            )

            # JS 的 Date(timestamp)
            # 一般为毫秒时间戳
            if timestamp > 10_000_000_000:

                timestamp /= 1000

            return datetime.fromtimestamp(
                timestamp,
                tz=CHINA_TZ
            )

        except Exception:

            return datetime.now(
                CHINA_TZ
            )

    # ========================================================
    # 判断是否在签到时间
    # ========================================================

    @staticmethod
    def is_in_time_range(
        now,
        start,
        end
    ):

        if not start or not end:
            return False

        try:

            sh, sm = map(
                int,
                start.split(":")[:2]
            )

            eh, em = map(
                int,
                end.split(":")[:2]
            )

        except Exception:

            return False

        current = (
            now.hour * 60
            + now.minute
        )

        start_minutes = (
            sh * 60
            + sm
        )

        end_minutes = (
            eh * 60
            + em
        )

        # 普通情况
        if (
            start_minutes
            <= end_minutes
        ):

            return (
                start_minutes
                <= current
                <= end_minutes
            )

        # 跨午夜，例如 23:00 - 00:30
        return (
            current >= start_minutes
            or current <= end_minutes
        )

    # ========================================================
    # 是否迟到
    # ========================================================

    @staticmethod
    def calc_is_late(
        now,
        late_time
    ):

        if not late_time:
            return 0

        try:

            hour, minute = map(
                int,
                late_time.split(":")[:2]
            )

        except Exception:

            return 0

        current = (
            now.hour * 60
            + now.minute
        )

        late = (
            hour * 60
            + minute
        )

        return (
            1
            if current > late
            else 0
        )

    # ========================================================
    # 主签到流程
    # ========================================================

    def run(self):

        if (
            LATITUDE == 0
            or LONGITUDE == 0
        ):

            raise RuntimeError(
                "请填写 LATITUDE "
                "和 LONGITUDE"
            )

        print()
        print(
            "========================================"
        )
        print("5. 获取晚点名计划")
        print(
            "========================================"
        )

        data = self.init()

        if not data:

            raise RuntimeError(
                "init.action 没有返回数据"
            )

        user = (
            data.get(
                "userData"
            )
            or {}
        )

        params = (
            data.get(
                "paramsData"
            )
            or {}
        )

        status = (
            data.get(
                "statusData"
            )
            or {}
        )

        # ----------------------------------------------------
        # 信息
        # ----------------------------------------------------

        print(
            "姓名：",
            user.get(
                "FName",
                ""
            )
        )

        print(
            "班级：",
            user.get(
                "FClass",
                ""
            )
        )

        print(
            "学院：",
            user.get(
                "FCollUnit",
                ""
            )
        )

        print(
            "签到计划：",
            user.get(
                "FPlanID",
                ""
            )
        )

        print(
            "签到方式：",
            user.get(
                "FWay"
            )
        )

        print(
            "签到时间：",
            params.get(
                "FStartTime"
            ),
            "-",
            params.get(
                "FEndTime"
            )
        )

        # ----------------------------------------------------
        # 已经签到
        # ----------------------------------------------------

        current_status = (
            status.get(
                "status"
            )
        )

        if current_status in (
            "SUCCESS",
            "PROCESSING",
        ):

            print()
            print(
                "[√] 今天已经签到。"
            )

            return

        # ----------------------------------------------------
        # 没有计划
        # ----------------------------------------------------

        plan_id = user.get(
            "FPlanID"
        )

        if not plan_id:

            print()
            print(
                "[i] 当前没有签到计划。"
            )

            return

        # ----------------------------------------------------
        # FWay
        #
        # 1 = School
        # 2 = Outside
        # 3 = NeedNot
        # ----------------------------------------------------

        way = user.get(
            "FWay"
        )

        try:
            way = int(
                way
            )
        except Exception:
            pass

        if way == 3:

            print()
            print(
                "[i] 当前无需签到。"
            )

            return

        # ====================================================
        # 获取服务器时间
        # ====================================================

        timestamp = (
            self.get_server_timestamp()
        )

        now = (
            self.timestamp_to_datetime(
                timestamp
            )
        )

        print()
        print(
            "服务器时间：",
            now.strftime(
                "%Y-%m-%d %H:%M:%S"
            )
        )

        start_time = params.get(
            "FStartTime"
        )

        end_time = params.get(
            "FEndTime"
        )

        # ----------------------------------------------------
        # 时间检查
        # ----------------------------------------------------

        if not self.is_in_time_range(
            now,
            start_time,
            end_time
        ):

            print()
            print(
                "[i] 当前不在签到时间范围内："
            )

            print(
                start_time,
                "-",
                end_time
            )

            return

        # ====================================================
        # 确定校区
        # ====================================================

        matched_campus = None

        campus_id = ""

        campus_title = ""

        # ----------------------------------------------------
        # 校内
        # ----------------------------------------------------

        if way == 1:

            matched_campus = (
                self.check_location(
                    data
                )
            )

            if not matched_campus:

                raise RuntimeError(
                    "服务器判断当前位置"
                    "不在允许签到的范围内"
                )

            campus_id = str(
                matched_campus.get(
                    "id"
                )
                or ""
            )

            campus_title = (
                matched_campus.get(
                    "title"
                )
                or
                matched_campus.get(
                    "name"
                )
                or ""
            )

            print(
                "[√] 位置校验成功"
            )

            print(
                "匹配区域：",
                campus_title
            )

        # ----------------------------------------------------
        # 校外
        # ----------------------------------------------------

        elif way == 2:

            campus_id = str(
                user.get(
                    "FDefaultCampus"
                )
                or ""
            )

            campus_title = (
                self.get_campus_name(
                    data,
                    campus_id
                )
            )

            if not ADDRESS:

                raise RuntimeError(
                    "当前是校外签到模式，"
                    "请填写 ADDRESS"
                )

        else:

            raise RuntimeError(
                "未知签到方式："
                + str(way)
            )

        # ====================================================
        # actualLocation
        #
        # 网页相当于：
        #
        # [campus.title, address]
        #     .filter(Boolean)
        #     .join(" ")
        # ====================================================

        actual_location = " ".join(
            value

            for value in (
                campus_title,
                ADDRESS,
            )

            if value
        )

        # ----------------------------------------------------
        # 迟到判断
        # ----------------------------------------------------

        is_late = (
            self.calc_is_late(
                now,
                params.get(
                    "FLateTime"
                )
            )
        )

        # ====================================================
        # 最终 clockIn 参数
        # ====================================================

        clock_data = {

            "campus":
                campus_id,

            "lon":
                LONGITUDE,

            "lat":
                LATITUDE,

            "startTime":
                start_time,

            "endTime":
                end_time,

            "actualLocation":
                actual_location,

            "caIsNo":
                CA_IS_NO,

            "collUnit":
                user.get(
                    "FCollUnit",
                    ""
                ),

            "way":
                way,

            "isLate":
                is_late,
        }

        print()
        print(
            "========================================"
        )
        print("7. 签到参数")
        print(
            "========================================"
        )

        print(
            json.dumps(
                clock_data,
                ensure_ascii=False,
                indent=2
            )
        )

        # ====================================================
        # 提交
        # ====================================================

        print()
        print(
            "========================================"
        )
        print("8. 提交签到")
        print(
            "========================================"
        )

        result = self.encrypted_get(
            "/livecloud/project/fzu/"
            "attn/clockIn.action",

            clock_data
        )

        if result is None:

            raise RuntimeError(
                "clockIn.action "
                "没有返回签到信息"
            )

        print()
        print(
            "服务器返回："
        )

        print(
            json.dumps(
                result,
                ensure_ascii=False,
                indent=2
            )
        )

        # ----------------------------------------------------
        # 前端返回结构：
        #
        # {
        #     "attn": {
        #         ...
        #     }
        # }
        # ----------------------------------------------------

        if (
            isinstance(
                result,
                dict
            )
            and isinstance(
                result.get(
                    "attn"
                ),
                dict
            )
        ):

            attn = result[
                "attn"
            ]

            print()
            print(
                "========================================"
            )
            print(
                "          签到成功"
            )
            print(
                "========================================"
            )

            print(
                "结果：",
                attn.get(
                    "msg",
                    "签到成功"
                )
            )

            print(
                "时间：",
                attn.get(
                    "checkInTime",
                    ""
                )
            )

            print(
                "地点：",
                attn.get(
                    "actualLocation",
                    ""
                )
            )

            print(
                "========================================"
            )

        else:

            print()
            print(
                "[√] 请求成功。"
            )


# ============================================================
#                       MAIN
# ============================================================

def main():

    print()
    print(
        "========================================"
    )
    print(
        "         智慧福大自动签到"
    )
    print(
        "========================================"
    )

    # --------------------------------------------------------
    # 自动账号密码登录
    # --------------------------------------------------------

    session, token = login()

    # --------------------------------------------------------
    # 自动签到
    # --------------------------------------------------------

    client = FZUCheckin(
        session,
        token
    )

    client.run()


if __name__ == "__main__":

    try:

        main()

    except KeyboardInterrupt:

        print()
        print(
            "[!] 用户终止程序"
        )

    except requests.RequestException as e:

        print()
        print(
            "========================================"
        )
        print(
            "[!] 网络请求失败"
        )
        print(
            str(e)
        )
        print(
            "========================================"
        )

    except Exception as e:

        print()
        print(
            "========================================"
        )
        print(
            "[!] 程序运行失败"
        )
        print(
            str(e)
        )
        print(
            "========================================"
        )