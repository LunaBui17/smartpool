"""Run with the ESP-IDF Python environment after idf.py build.

Checks Kconfig dependencies and compiles changed sources for mixed feature settings.
Does not change the active sdkconfig or build artifacts.
"""
import ctypes
import itertools
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

import kconfiglib

ROOT = Path(__file__).resolve().parents[1]
FEATURES = ["TEMPERATURE_ENABLED", "WATER_ENABLED", "RELAY_1_ENABLED",
            "RELAY_2_ENABLED", "MQTT_ENABLED", "HTTP_ENABLED", "WIFI_STA_ENABLED"]
os.environ.setdefault("IDF_TARGET", "esp32s3")
if "IDF_PATH" not in os.environ:
    raise SystemExit("Run in an ESP-IDF terminal (IDF_PATH is required)")


def config(values, initial="OFF", levels=(1, 1)):
    cfg = kconfiglib.Kconfig(str(ROOT / "main/Kconfig.projbuild"), warn=False)
    for name, enabled in zip(FEATURES, values):
        cfg.syms["POOL_" + name].set_value(2 if enabled else 0)
    for channel, level in enumerate(levels, 1):
        cfg.syms[f"RELAY_{channel}_ACTIVE_LEVEL"].set_value(str(level))
    cfg.syms["POOL_RELAY_INITIAL_" + initial].set_value(2)
    assert cfg.syms["POOL_SENSOR_IDLE_INTERVAL_MS"].str_value == "60000"
    assert cfg.syms["POOL_SENSOR_PUMP_INTERVAL_MS"].str_value == "5000"
    return cfg


for values in itertools.product((False, True), repeat=len(FEATURES)):
    for initial in ("OFF", "1_ON", "2_ON", "BOTH_ON"):
        cfg = config(values, initial)
        first = cfg.syms["POOL_RELAY_INITIAL_1_ON"].str_value == "y"
        second = cfg.syms["POOL_RELAY_INITIAL_2_ON"].str_value == "y"
        both = cfg.syms["POOL_RELAY_INITIAL_BOTH_ON"].str_value == "y"
        first = first or both
        second = second or both
        assert not first or values[2]
        assert not second or values[3]
        expected = initial == "OFF" or (values[2] and values[3] if initial == "BOTH_ON" else values[2 if initial == "1_ON" else 3])
        assert cfg.syms["POOL_RELAY_INITIAL_" + initial].str_value == ("y" if expected else "n")
        for name, enabled in zip(FEATURES, values):
            assert cfg.syms["POOL_" + name].str_value == ("y" if enabled else "n")
print("PASS: 512 Kconfig combinations, simultaneous startup and disabled-channel dependencies")

entries = json.loads((ROOT / "build/compile_commands.json").read_text())
header = (ROOT / "build/config/sdkconfig.h").read_text()
feature_pattern = r"^#define CONFIG_POOL_(?:" + "|".join(FEATURES + [
    "RELAY_INITIAL_OFF", "RELAY_INITIAL_1_ON", "RELAY_INITIAL_2_ON", "RELAY_INITIAL_BOTH_ON"]) + r") .*\n"
header = re.sub(feature_pattern, "", header, flags=re.M)
header = re.sub(r"^#define CONFIG_POOL_NTP_(?:SERVER|BACKUP_SERVER) .*\n", "", header, flags=re.M)
header = re.sub(r"^#define CONFIG_POOL_SENSOR_(?:IDLE|PUMP)_INTERVAL_MS .*\n", "", header, flags=re.M)
header = re.sub(r"^#define CONFIG_RELAY_[12]_ACTIVE_LEVEL .*\n", "", header, flags=re.M)
cases = [
    ((True,) * 7, "OFF"),
    ((True,) * 7, "BOTH_ON"),
    ((True,) * 7, "BOTH_ON", (0, 0)),
    ((True,) * 7, "1_ON", (0, 1)),
    ((True,) * 7, "2_ON", (1, 0)),
    ((False,) * 7, "OFF"),
    ((False, True, False, True, True, False, False), "2_ON"),
    ((True, False, True, False, False, True, True), "1_ON"),
    ((True, True, True, True, False, False, False), "2_ON"),
    ((False, False, True, True, True, True, True), "1_ON"),
]
parser = ctypes.windll.shell32.CommandLineToArgvW
parser.argtypes = [ctypes.c_wchar_p, ctypes.POINTER(ctypes.c_int)]
parser.restype = ctypes.POINTER(ctypes.c_wchar_p)

with tempfile.TemporaryDirectory(prefix="feature-check-", dir=ROOT / "build") as folder:
    directory = Path(folder)
    for case in cases:
        values, initial = case[:2]
        levels = case[2] if len(case) > 2 else (1, 1)
        cfg = config(values, initial, levels)
        flags = "".join("#define CONFIG_" + name + " 1\n"
                        for name, symbol in cfg.syms.items()
                        if name.startswith("POOL_") and name in {"POOL_" + key for key in FEATURES + [
                            "RELAY_INITIAL_OFF", "RELAY_INITIAL_1_ON", "RELAY_INITIAL_2_ON", "RELAY_INITIAL_BOTH_ON"]}
                        and symbol.str_value == "y")
        for channel, level in enumerate(levels, 1):
            symbol = cfg.syms[f"RELAY_{channel}_ACTIVE_LEVEL"]
            assert symbol.str_value == str(level)
            flags += f"#define CONFIG_RELAY_{channel}_ACTIVE_LEVEL {symbol.str_value}\n"
        for name in ("POOL_SENSOR_IDLE_INTERVAL_MS", "POOL_SENSOR_PUMP_INTERVAL_MS"):
            flags += f"#define CONFIG_{name} {cfg.syms[name].str_value}\n"
        for name in ("POOL_NTP_SERVER", "POOL_NTP_BACKUP_SERVER"):
            flags += f"#define CONFIG_{name} {json.dumps(cfg.syms[name].str_value)}\n"
        (directory / "sdkconfig.h").write_text(header + flags)
        for entry in entries:
            source = Path(entry["file"])
            if source.parent != ROOT / "main" or source.name not in {
                "main.c", "relay.c", "water_level.c", "pool_network.c", "wifi_setup.c", "pump_auto.c", "pool_mqtt_protocol.c"
            }:
                continue
            count = ctypes.c_int()
            pointer = parser(entry["command"], ctypes.byref(count))
            args = [pointer[i] for i in range(count.value)]
            ctypes.windll.kernel32.LocalFree(ctypes.cast(pointer, ctypes.c_void_p))
            output_index = args.index("-o")
            del args[output_index:output_index + 2]
            args.remove("-c")
            args.insert(1, "-I" + directory.as_posix())
            args.insert(1, "-fsyntax-only")
            subprocess.run(args, cwd=entry["directory"], check=True)
print("PASS: target compiler checks for 10 mixed configurations (including all four relay polarities) across 7 source files")
