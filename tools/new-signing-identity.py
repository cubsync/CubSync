#!/usr/bin/env python3
"""生成 / 校验一个应用的 release 签名身份（密钥库 + keystore.properties）。

设计前提，四条：

1. **密钥库不可重造。** 所以它在生成前会被检查是否已存在，绝不静默覆盖。
2. **密码不经过命令行参数。** keytool 的 `-storepass:env` 走环境变量传递，
   因此密码不进 shell 历史、不出现在进程列表里；本脚本自身用 getpass 静默读取，
   不落盘、不回显、不写日志。校验时反过来从 keystore.properties 读，同样不回显。
3. **同一个脚本服务所有项目。** 不写死任何项目名 —— 用法见 --help。
4. **只能站在仓库根目录。** keystore.properties 必须落在仓库根，Gradle 从那里读它；
   写在别处 = 文件看着在、构建永远读不到。所以位置不对时直接报错，不猜。

生成（在目标仓库根目录下，无需任何参数）：

    py <脚本路径>/new-signing-identity.py

应用名取自当前目录名，仓库名取自 `git remote origin`，别名与证书主题由应用名派生。
任何一项都可以用参数覆盖。加 --dry-run 只打印将要填写的信息、不生成文件。

校验（一条命令查完，不需要输密码）：

    py <脚本路径>/new-signing-identity.py --verify
    py <脚本路径>/new-signing-identity.py --verify --build   # 再加构建与 APK 实测

查：文件在不在、四个值全不全、密码能不能打开库、别名在不在、证书主题对不对、
有效期够不够、有没有被 git 跟踪、构建接线通不通、打出来的包是不是同一把钥匙。

产出：
    <slug>-release.jks      密钥库（.gitignore 已覆盖 *.jks）
    keystore.properties     四个值，真文件不入库

相关文档：docs/SIGNING.md
"""

from __future__ import annotations

import argparse
import getpass
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

DEFAULT_VALIDITY_DAYS = 10950  # 30 年：Google 要求有效期覆盖应用在商店的预期寿命
MIN_PASSWORD_LENGTH = 8
ENV_STORE_PASSWORD = "CUBSYNC_NEW_STORE_PASSWORD"
ENV_KEY_PASSWORD = "CUBSYNC_NEW_KEY_PASSWORD"

ANDROID_MODULE = ":app:android"
APK_RELATIVE = Path("app/android/build/outputs/apk/release")

# 校验用的四行摘要前缀：✓ 通过 · ✗ 失败 · ⚠ 可疑（不一定是错，但要看一眼）
OK, BAD, WARN = "  [✓]", "  [✗]", "  [⚠]"


def decode(raw: bytes) -> str:
    """keytool 在 Windows 上按本地代码页输出，utf-8 会失败。"""
    for encoding in ("utf-8", "gbk", "cp1252"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def find_keytool() -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        for name in ("keytool.exe", "keytool"):
            candidate = Path(java_home) / "bin" / name
            if candidate.is_file():
                return str(candidate)
    found = shutil.which("keytool")
    if found:
        return found
    sys.exit(
        "找不到 keytool。请设置 JAVA_HOME 指向一个 JDK（keytool 随 JDK 分发，"
        "JRE 里没有），或把它加进 PATH。"
    )


def find_apksigner() -> str | None:
    """取 Android SDK 里最新的 apksigner；v2/v3 签名的包只能用它校验。"""
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(var)
        if not root:
            continue
        build_tools = Path(root) / "build-tools"
        if not build_tools.is_dir():
            continue
        for name in ("apksigner.bat", "apksigner"):
            candidates = sorted(build_tools.glob(f"*/{name}"), reverse=True)
            if candidates:
                return str(candidates[0])
    return shutil.which("apksigner")


def slugify(name: str) -> str:
    """把应用名压成文件名安全的形式；没有 ASCII 字母数字时返回空串。"""
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


def repo_root(path: Path) -> Path | None:
    """所在 git 仓库的根目录；不在仓库里返回 None。"""
    try:
        result = subprocess.run(
            ["git", "-C", str(path), "rev-parse", "--show-toplevel"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
    except (FileNotFoundError, OSError):
        return None
    if result.returncode != 0:
        return None
    return Path(decode(result.stdout).strip())


def detect_repo(start: Path) -> str | None:
    """从 git remote origin 推断 owner/name，失败返回 None。"""
    try:
        result = subprocess.run(
            ["git", "-C", str(start), "remote", "get-url", "origin"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
    except (FileNotFoundError, OSError):
        return None
    if result.returncode != 0:
        return None
    url = decode(result.stdout).strip()
    # git@github.com:owner/name.git  或  https://github.com/owner/name.git
    match = re.search(r"[:/]([^/:@]+/[^/:@]+?)(?:\.git)?$", url)
    return match.group(1) if match else None


def ask_password(prompt: str) -> str:
    while True:
        first = getpass.getpass(prompt)
        if len(first) < MIN_PASSWORD_LENGTH:
            print(f"  密码至少 {MIN_PASSWORD_LENGTH} 个字符，请重来。")
            continue
        second = getpass.getpass("  再输一次确认: ")
        if first != second:
            print("  两次不一致，请重来。")
            continue
        return first


def run_keytool(keytool: str, args: list[str], env: dict[str, str]) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        [keytool, *args],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )


def read_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def normalize_fingerprint(text: str) -> str:
    """把 SHA-256 指纹压成可比形式：去掉冒号、统一大写。"""
    return re.sub(r"[^0-9A-F]", "", text.upper())


def is_tracked(repo: Path, target: Path) -> bool:
    try:
        result = subprocess.run(
            ["git", "-C", str(repo), "ls-files", "--error-unmatch", str(target)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except (FileNotFoundError, OSError):
        return False
    return result.returncode == 0


def is_ignored(repo: Path, target: Path) -> bool:
    try:
        result = subprocess.run(
            ["git", "-C", str(repo), "check-ignore", "-q", str(target)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except (FileNotFoundError, OSError):
        return False
    return result.returncode == 0


def keystore_type(keystore: Path) -> str:
    """靠文件头判断类型：PKCS12 是 DER SEQUENCE，JKS 是 FEEDFEED。"""
    with keystore.open("rb") as handle:
        head = handle.read(4)
    if head[:2] == b"\xfe\xed":
        return "JKS"
    if head[:1] == b"\x30":
        return "PKCS12"
    return "未知"


def parse_jdk_date(text: str) -> datetime | None:
    """解析 keytool 打的 "Sun Oct 08 22:37:26 CST 2056"。

    刻意不用 strptime 的 %Z：时区缩写（CST 之类）在 C locale 下基本匹配不上，
    整串会直接失败 —— 那会让有效期检查静默失效。
    """
    match = re.match(r"\w{3}\s+(\w{3})\s+(\d{1,2})\s+[\d:]+\s+\S+\s+(\d{4})", text)
    if not match:
        return None
    month_name, day, year = match.group(1), int(match.group(2)), int(match.group(3))
    try:
        return datetime(year, datetime.strptime(month_name, "%b").month, day)
    except ValueError:
        return None


def gradle_command(root: Path) -> str | None:
    for name in ("gradlew.bat", "gradlew"):
        candidate = root / name
        if candidate.is_file():
            return str(candidate)
    return None


# --------------------------------------------------------------------------- 生成


def generate(options: argparse.Namespace) -> int:
    out_dir = Path(options.dir).resolve()

    root = repo_root(out_dir)
    if root is None:
        print(
            f"⚠ {out_dir} 不在 git 仓库内。\n"
            "  keystore.properties 必须放在仓库根目录（Gradle 从根目录读它），\n"
            "  确认你 cd 到了目标仓库再跑。\n"
        )
    elif root != out_dir:
        sys.exit(
            f"这里不是仓库根目录。仓库根是 {root}\n"
            f"keystore.properties 必须落在仓库根（Gradle 从根目录读它），\n"
            f"否则生成出来的东西构建永远看不见。请 cd 到 {root} 再跑，\n"
            f"或者用 --dir \"{root}\"。"
        )

    if options.name is None:
        derived = out_dir.name
        if not slugify(derived):
            sys.exit(
                f"当前目录名「{derived}」派生不出可用的应用名（需要至少一个 ASCII 字母或数字）。\n"
                "请 cd 到仓库根目录再跑，或用 --name 指定，例如 --name CubSync。"
            )
        name = derived
    else:
        name = options.name
        if not slugify(name):
            sys.exit(f"--name「{name}」派生不出可用的文件名，请至少包含一个 ASCII 字母或数字。")

    slug = slugify(name)
    alias = options.alias or slug
    keystore = out_dir / f"{slug}-release.jks"
    properties = out_dir / "keystore.properties"
    dn = options.dn or f"CN={name}, O={name}, C=CN"
    repo = options.repo or detect_repo(out_dir)

    print(f"应用      {name}")
    print(f"产出目录  {out_dir}")
    print(f"密钥库    {keystore}")
    print(f"别名      {alias}")
    print(f"主题      {dn}")
    print(f"有效期    {options.validity} 天（{options.validity / 365:.1f} 年）")
    print(f"仓库      {repo or '（未推断出，上传 secrets 时用 --repo 指定）'}")
    print()

    if options.dry_run:
        print("--dry-run：以上即为将要写入的值，未生成任何文件。")
        print("去掉 --dry-run 即真正生成；那时才会问你两个密码。")
        return 0

    if keystore.exists() and not options.force:
        sys.exit(
            f"{keystore} 已存在。\n"
            "密钥库不可重造：覆盖它等于换掉签名身份，所有已安装的用户都必须卸载重装。\n"
            "如果确实要换，先备份旧文件，再显式加 --force。"
        )
    if properties.exists() and not options.force:
        sys.exit(f"{properties} 已存在。确认要重新生成时加 --force。")

    keytool = find_keytool()
    print(f"keytool   {keytool}")
    print()
    print("密码输入不会显示，也不会进入 shell 历史或进程列表。")
    print("两个密码建议用同一个：它们存在一起，分开只多一处会丢。")
    print()
    store_password = ask_password("密钥库密码: ")
    key_password = ask_password("密钥密码: ")
    print()

    env = dict(os.environ)
    env[ENV_STORE_PASSWORD] = store_password
    env[ENV_KEY_PASSWORD] = key_password

    result = run_keytool(
        keytool,
        [
            "-genkeypair",
            "-keystore", str(keystore),
            "-alias", alias,
            "-keyalg", "RSA",
            "-keysize", "4096",
            "-validity", str(options.validity),
            "-dname", dn,
            "-storetype", "PKCS12",
            "-storepass:env", ENV_STORE_PASSWORD,
            "-keypass:env", ENV_KEY_PASSWORD,
        ],
        env,
    )
    if result.returncode != 0:
        print(decode(result.stdout), file=sys.stderr)
        sys.exit(f"keytool 失败（退出码 {result.returncode}），未写出任何文件。")

    print(decode(result.stdout).strip())
    print()

    properties.write_text(
        "# 本地签名身份。真文件不入库 —— 入库的只有 keystore.properties.example。\n"
        "# storeFile 相对仓库根目录解析，绝对路径也可以。\n"
        f"storeFile={keystore.name}\n"
        f"storePassword={store_password}\n"
        f"keyAlias={alias}\n"
        f"keyPassword={key_password}\n",
        encoding="utf-8",
    )
    print(f"已写入 {properties}（含明文密码，已在 .gitignore 内）")

    print()
    print("接下来两件事")
    print()
    print("1. 校验（一条命令，不需要再输密码）")
    print(f"     py {Path(__file__).name} --verify")
    print()
    print("2. 备份（两份，缺一不可）")
    print(f"     - 密码管理器：把 {keystore.name} 作为附件挂到一条安全笔记上，")
    print("       备注里写 storePassword / keyAlias / keyPassword 和指纹。")
    print("     - 离线归档：同一个文件 + 一个已签名的 release APK。")
    print("     只留文件不留密码，等于两个都丢。")
    print()
    print("注意：不要把密钥库提交到任何仓库，包括私仓 —— clone 权限就等于泄露。")
    return 0


# --------------------------------------------------------------------------- 校验


def verify(options: argparse.Namespace) -> int:
    out_dir = Path(options.dir).resolve()
    properties = out_dir / "keystore.properties"
    failures: list[str] = []
    warnings: list[str] = []

    def bad(message: str) -> None:
        failures.append(message)

    def warn(message: str) -> None:
        warnings.append(message)

    print(f"校验目标  {out_dir}")
    print()

    # 1. 位置
    root = repo_root(out_dir)
    if root is None:
        print(f"{WARN} 不在 git 仓库内，无法确认这里就是仓库根")
        warn("目录不在 git 仓库内")
    elif root != out_dir:
        print(f"{BAD} 这里不是仓库根（仓库根：{root}）")
        bad(f"不在仓库根目录，keystore.properties 放在这里构建读不到")
    else:
        print(f"{OK} 位于仓库根  {root}")

    # 2. keystore.properties
    if not properties.is_file():
        print(f"{BAD} 找不到 {properties.name}")
        bad("keystore.properties 不存在 —— 还没生成，或在别处生成过")
        return report(failures, warnings)

    values = read_properties(properties)
    missing = [k for k in ("storeFile", "storePassword", "keyAlias", "keyPassword") if not values.get(k)]
    if missing:
        print(f"{BAD} keystore.properties 缺项：{', '.join(missing)}")
        bad("keystore.properties 有键为空")
        return report(failures, warnings)
    print(f"{OK} keystore.properties 四个值齐全")

    # 3. 密钥库文件
    keystore = (out_dir / values["storeFile"]).resolve()
    if not keystore.is_file():
        print(f"{BAD} 密钥库不存在：{keystore}")
        bad("storeFile 指向的文件不存在")
        return report(failures, warnings)
    print(f"{OK} 密钥库存在  {keystore.name}（{keystore.stat().st_size} 字节，{keystore_type(keystore)}）")

    keytool = find_keytool()

    # 3b. 手动输入一遍密码来核对 —— 用于验证「我记在密码管理器里的那串」到底对不对。
    # 输入用 getpass 静默读取、不落 shell 历史；对照是与文件里的值比，不比出明文。
    if options.check_password:
        typed = getpass.getpass("  请输入你要核对的密码（不显示）: ")
        if not typed:
            print(f"{WARN} 没有输入，跳过密码核对")
        else:
            probe_env = dict(os.environ)
            probe_env[ENV_STORE_PASSWORD] = typed
            probe = run_keytool(
                keytool,
                ["-list", "-keystore", str(keystore), "-storepass:env", ENV_STORE_PASSWORD],
                probe_env,
            )
            if probe.returncode != 0:
                print(f"{BAD} 你输入的密码打不开这个密钥库")
                bad("你输入的密码不对（或这个库不是这一份）")
            elif typed == values["storePassword"]:
                print(f"{OK} 你输入的密码正确，且与 keystore.properties 里记的完全一致")
            else:
                print(f"{WARN} 你输入的密码能打开库，但与 keystore.properties 里记的不一样")
                warn("keystore.properties 里记的密码是错的 —— 以你输入的这串为准，去改它")

    # 4. 密码能不能打开库 —— 这是「设置错了」最可能的地方
    env = dict(os.environ)
    env[ENV_STORE_PASSWORD] = values["storePassword"]
    listing = run_keytool(
        keytool,
        [
            "-list", "-v",
            "-J-Duser.language=en", "-J-Duser.country=US",
            "-keystore", str(keystore),
            "-storepass:env", ENV_STORE_PASSWORD,
        ],
        env,
    )
    listing_text = decode(listing.stdout)
    if listing.returncode != 0 or "Alias name" not in listing_text:
        print(f"{BAD} keystore.properties 里的密码打不开这个密钥库")
        bad("storePassword 与密钥库不匹配（或文件损坏）")
        return report(failures, warnings)
    print(f"{OK} storePassword 能打开密钥库")

    # 5. 别名
    alias = values["keyAlias"]
    if f"Alias name: {alias}" not in listing_text:
        found = re.findall(r"Alias name:\s*(\S+)", listing_text)
        print(f"{BAD} 库里没有别名 {alias}（实际有：{', '.join(found) or '无'}）")
        bad("keyAlias 与密钥库里的别名不一致")
        return report(failures, warnings)
    print(f"{OK} 别名存在  {alias}")

    # 6. 证书主题 / 有效期 / 指纹
    entry = listing_text.split(f"Alias name: {alias}", 1)[1] if f"Alias name: {alias}" in listing_text else listing_text
    owner = re.search(r"Owner:\s*(.+)", entry)
    owner_text = owner.group(1).strip() if owner else ""
    if owner_text and any(ord(ch) > 127 for ch in owner_text):
        print(f"{WARN} 证书主题含非 ASCII：{owner_text}")
        warn("证书主题含非 ASCII，多半是从中文目录名派生出来的")
    elif "CN=app," in owner_text or owner_text.startswith("CN=app"):
        print(f"{WARN} 证书主题像是兜底值：{owner_text}")
        warn("证书主题是兜底出来的 app")
    elif owner_text:
        print(f"{OK} 证书主题  {owner_text}")

    until = re.search(r"until:\s*(.+)", entry)
    if until:
        text = until.group(1).strip()
        expiry = parse_jdk_date(text)
        if expiry is not None:
            days = (expiry - datetime.now()).days
            if days < 365 * 25:
                print(f"{WARN} 有效期只剩 {days} 天（{days / 365:.1f} 年）—— Google 要求覆盖应用预期寿命")
                warn(f"有效期只剩 {days} 天")
            else:
                print(f"{OK} 有效期  {days} 天（{days / 365:.1f} 年）")
        else:
            print(f"  [i] 有效期（未能解析，原样）：{text}")

    sha_match = re.search(r"SHA256:\s*([0-9A-F:]{60,})", listing_text)
    fingerprint = sha_match.group(1) if sha_match else ""
    if fingerprint:
        print(f"      SHA-256  {fingerprint}")
    else:
        print(f"{WARN} 读不出 SHA-256 指纹")
        warn("读不出指纹")

    # 7. 别被 git 带走
    if root is not None:
        for target, label in ((keystore, "密钥库"), (properties, "keystore.properties")):
            if is_tracked(root, target):
                print(f"{BAD} {label} 已被 git 跟踪 —— 立刻从索引里移除")
                bad(f"{label} 被 git 跟踪")
            elif is_ignored(root, target):
                print(f"{OK} {label} 未被跟踪且已被 .gitignore 覆盖")
            else:
                print(f"{WARN} {label} 未被跟踪，但 .gitignore 里没有明确规则")
                warn(f"{label} 没有被 .gitignore 明确覆盖")

    # 8. 构建接线（可选，最慢也最权威）
    if options.build:
        print()
        print("构建接线（--build）")
        gradle = gradle_command(out_dir)
        if gradle is None:
            print(f"{BAD} 找不到 gradlew")
            bad("找不到 gradlew，无法验证构建接线")
        elif not (out_dir / "app/android/build.gradle.kts").is_file():
            print(f"{BAD} 没有 app/android 模块，--build 不适用")
            bad("没有安卓模块")
        else:
            report_result = subprocess.run(
                [gradle, "--offline", "--no-daemon", f"{ANDROID_MODULE}:signingReport"],
                cwd=str(out_dir),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            report_text = decode(report_result.stdout)
            release_block = ""
            match = re.search(r"Variant: release(.*?)(?:\n\s*\n|Variant: |\Z)", report_text, re.S)
            if match:
                release_block = match.group(1)
            if re.search(r"Config:\s*null", release_block):
                print(f"{BAD} signingReport 显示 release 没有签名配置 —— 构建读不到 keystore.properties")
                bad("Gradle 读不到签名配置（keystore.properties 或路径不对）")
            else:
                store = re.search(r"Store:\s*(.+)", release_block)
                if store:
                    print(f"{OK} Gradle 读到了密钥库  {store.group(1).strip()}")
                if alias in release_block:
                    print(f"{OK} Gradle 读到了别名  {alias}")
                else:
                    print(f"{BAD} Gradle 读到的别名不是 {alias}")
                    bad("Gradle 侧别名与文件不一致")
                gradle_sha = re.search(r"SHA-?256:\s*([0-9A-F:]{60,})", release_block, re.I)
                if gradle_sha and fingerprint:
                    if normalize_fingerprint(gradle_sha.group(1)) == normalize_fingerprint(fingerprint):
                        print(f"{OK} Gradle 侧指纹与密钥库一致")
                    else:
                        print(f"{BAD} Gradle 侧指纹与密钥库不一致")
                        bad("Gradle 读到的不是这个密钥库")

            print("  打包 release APK 并核对签名…")
            build_result = subprocess.run(
                [gradle, "--offline", "--no-daemon", f"{ANDROID_MODULE}:assembleRelease"],
                cwd=str(out_dir),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            if build_result.returncode != 0:
                print(f"{BAD} assembleRelease 失败")
                print(decode(build_result.stdout)[-1500:])
                bad("assembleRelease 失败")
            else:
                apks = sorted((out_dir / APK_RELATIVE).glob("*.apk"))
                signed = [apk for apk in apks if "unsigned" not in apk.name]
                if not signed:
                    print(f"{WARN} 没有签名产物，只有：{', '.join(apk.name for apk in apks) or '无'}")
                    warn("assembleRelease 没有产出签名 APK")
                else:
                    apk = signed[0]
                    print(f"{OK} 产出 {apk.name}")
                    apksigner = find_apksigner()
                    if apksigner is None:
                        print(f"{WARN} 找不到 apksigner（设 ANDROID_HOME），跳过 APK 指纹核对")
                        warn("找不到 apksigner")
                    else:
                        # v2/v3 签名的包只能用它校验；keytool -printcert 读不到且不报错。
                        check = subprocess.run(
                            [apksigner, "verify", "--print-certs", str(apk)],
                            stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT,
                        )
                        check_text = decode(check.stdout)
                        apk_sha = re.search(r"SHA-256 digest:\s*([0-9a-fA-F]{64})", check_text)
                        if apk_sha is None:
                            print(f"{BAD} apksigner 没打出指纹")
                            print(check_text[-800:])
                            bad("APK 签名核对失败")
                        elif fingerprint and normalize_fingerprint(apk_sha.group(1)) == normalize_fingerprint(fingerprint):
                            print(f"{OK} APK 签名指纹与密钥库一致 —— 这一版就是这把钥匙签的")
                        else:
                            print(f"{BAD} APK 签名指纹与密钥库不一致")
                            bad("APK 不是这把钥匙签的")
                        dn_match = re.search(r"Signer #1 certificate DN:\s*(.+)", check_text)
                        if dn_match:
                            print(f"      APK 证书主题  {dn_match.group(1).strip()}")

    return report(failures, warnings)


def report(failures: list[str], warnings: list[str]) -> int:
    print()
    if failures:
        print(f"结论：不通过（{len(failures)} 项问题）")
        for item in failures:
            print(f"  - {item}")
        return 1
    if warnings:
        print(f"结论：通过，但有 {len(warnings)} 处要你自己看一眼")
        for item in warnings:
            print(f"  - {item}")
        return 0
    print("结论：全部通过")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="生成或校验一个应用的 release 签名身份（密钥库 + keystore.properties）。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--verify", action="store_true", help="校验现有签名身份（不需要输密码）")
    parser.add_argument(
        "--check-password",
        action="store_true",
        help="配合 --verify：再让你手输一遍密码核对（用于验证密码管理器里记的那串对不对）",
    )
    parser.add_argument("--build", action="store_true", help="配合 --verify：再跑 Gradle 与 apksigner 做端到端核对（慢）")
    parser.add_argument("--name", default=None, help="应用名，用来派生文件名与别名，例如 CubSync；默认取当前目录名")
    parser.add_argument("--repo", default=None, help="GitHub 仓库 owner/name，用于打印 gh secret 命令；默认从 git remote 推断")
    parser.add_argument("--alias", default=None, help="密钥别名，默认是应用名的小写形式")
    parser.add_argument("--dir", default=".", help="产出目录，默认当前目录（应在目标仓库根目录下运行）")
    parser.add_argument("--validity", type=int, default=DEFAULT_VALIDITY_DAYS, help=f"有效天数，默认 {DEFAULT_VALIDITY_DAYS}")
    parser.add_argument("--dn", default=None, help='证书主题，默认 "CN=<应用名>, O=<应用名>, C=CN"')
    parser.add_argument("--dry-run", action="store_true", help="只打印将要填写的值，不生成任何文件")
    parser.add_argument(
        "--force",
        action="store_true",
        help="允许覆盖已存在的密钥库（危险：已发布的包将再也无法被升级覆盖）",
    )
    options = parser.parse_args()

    if options.verify or options.build or options.check_password:
        return verify(options)
    return generate(options)


if __name__ == "__main__":
    sys.exit(main())
