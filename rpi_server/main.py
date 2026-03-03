import time
import threading
from pathlib import Path
from collections import deque
from typing import Dict, List, Tuple, Optional

from fastapi import FastAPI, HTTPException, Request
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

import warnings
warnings.filterwarnings("ignore", category=DeprecationWarning)

from gpiozero import DigitalInputDevice, DigitalOutputDevice, Device

app = FastAPI()

# ====== FILE STORAGE ======
STORAGE_DIR = Path(__file__).resolve().parent / "storage"
app.mount("/files", StaticFiles(directory=str(STORAGE_DIR)), name="files")

# ====== НАСТРОЙКИ ======
OBJECT_ID = 3649
OBJECT_ADDRESS = "Лесной 2-й переулок, Бутырский Вал"
PULSE_SECONDS = 2.0            # 2 секунды
MIN_COMMAND_INTERVAL = 0.5     # антиспам команд
INPUT_BOUNCE = 0.03            # антидребезг входов

# Фильтр стабильности (влияет на currentPhase)
HISTORY_LEN = 7
STABLE_MIN_COUNT = 4

# ====== PINOUT (BCM) ======
# OUTPUT: HIGH = команда активна (через high-trigger MOSFET/SSR подаём +5V)
MANUAL_REQUEST_PIN = 21   # физ. pin 40

PHASE_OUTPUT_PINS: Dict[int, int] = {
    1: 20,  # pin 38
    2: 26,  # pin 37
    3: 16,  # pin 36
    4: 19,  # pin 35
    5: 13,  # pin 33
    6: 6,   # pin 31
    7: 12,  # pin 32
    8: 5,   # pin 29
}

# INPUT: активное состояние = HIGH
MANUAL_ALLOWED_PIN = 14   # физ. pin 8

PHASE_INPUT_PINS: Dict[int, int] = {
    1: 4,   # pin 7
    2: 18,  # pin 12
    3: 17,  # pin 11
    4: 27,  # pin 13
    5: 23,  # pin 16
    6: 22,  # pin 15
    7: 24,  # pin 18
    8: 25,  # pin 22
}

# ====== GPIO OBJECTS ======
phase_inputs: Dict[int, DigitalInputDevice] = {}
phase_outputs: Dict[int, DigitalOutputDevice] = {}
manual_allowed_in: Optional[DigitalInputDevice] = None
manual_request_out: Optional[DigitalOutputDevice] = None

# ====== STATE ======
command_lock = threading.Lock()
last_command_ts = 0.0
cancel_command_event = threading.Event()
active_command_phase: Optional[int] = None

phase_history = deque(maxlen=HISTORY_LEN)

# ====== MANUAL REQUEST SESSION ======
# 1) ручное включение РУ кнопкой: /api/manual/on с TTL
MANUAL_TTL = 4.0  # секунды; /api/manual/on должен приходить чаще этого
manual_lock = threading.Lock()
active_manual_devices: Dict[str, float] = {}

# 2) удержание РУ после активации фазы
MANUAL_HOLD_AFTER_ACTIVATE_SEC = 15 * 60.0  # 15 минут
manual_hold_until: float = 0.0              # epoch seconds, пока now < manual_hold_until => держим РУ HIGH

stop_event = threading.Event()

# ====== CONSOLE MONITOR STATE ======
last_inputs_snapshot: Dict[int, int] = {}
last_outputs_snapshot: Dict[int, int] = {}
last_manual_allowed: Optional[bool] = None
last_manual_request: Optional[bool] = None


class ActivateRequest(BaseModel):
    phase: int


class CancelActivateRequest(BaseModel):
    phase: Optional[int] = None


class ObjectInfoResponse(BaseModel):
    objectId: int
    address: str
    version: str
    images: List[str]


def pick_latest_version_dir(obj_dir: Path) -> Optional[Path]:
    versions = [p for p in obj_dir.iterdir() if p.is_dir()]
    if not versions:
        return None
    return sorted(versions, key=lambda p: p.name)[-1]


def list_images(version_dir: Path) -> List[Path]:
    allowed = {".jpg"}
    files = [p for p in version_dir.iterdir() if p.is_file() and p.suffix.lower() in allowed]

    def sort_key(p: Path):
        stem = p.stem
        return (0, int(stem)) if stem.isdigit() else (1, stem)

    return sorted(files, key=sort_key)


def base_url(req: Request) -> str:
    return str(req.base_url).rstrip("/")


def get_device_id(req: Request) -> str:
    return req.headers.get("X-Device-Id", "default")


def is_active_high(dev: DigitalInputDevice) -> bool:
    return bool(dev.value)


def read_phase_raw() -> Tuple[int, List[int]]:
    active = [p for p, d in phase_inputs.items() if is_active_high(d)]
    if len(active) == 1:
        return active[0], active
    if len(active) == 0:
        return 0, active
    return -1, active


def read_phase_stable() -> Tuple[int, List[int], int, List[int]]:
    raw_phase, raw_active = read_phase_raw()

    if raw_phase == -1:
        phase_history.clear()
        return -1, raw_active, raw_phase, raw_active

    if raw_phase == 0:
        phase_history.clear()
        return 0, raw_active, raw_phase, raw_active

    phase_history.append(raw_phase)

    counts: Dict[int, int] = {}
    for p in phase_history:
        counts[p] = counts.get(p, 0) + 1

    best = max(counts, key=counts.get)
    if counts[best] >= STABLE_MIN_COUNT:
        return best, [best], raw_phase, raw_active

    return raw_phase, raw_active, raw_phase, raw_active


def _manual_active_locked(now: float) -> bool:
    """
    True если надо держать MANUAL_REQUEST_PIN HIGH.
    Условия:
    - есть активные manual-устройства (TTL не истёк)
    - или действует удержание после активации фазы (now < manual_hold_until)
    """
    return bool(active_manual_devices) or (now < manual_hold_until)


def _recalc_manual_locked(now: float):
    """
    Пересчитываем список устройств по TTL и выставляем выход MANUAL_REQUEST_PIN.
    """
    global active_manual_devices, manual_request_out, manual_hold_until

    # prune devices by TTL
    cutoff = now - MANUAL_TTL
    active_manual_devices = {k: v for k, v in active_manual_devices.items() if v >= cutoff}

    if manual_request_out is None:
        return

    if _manual_active_locked(now):
        manual_request_out.on()
    else:
        manual_request_out.off()


def manual_touch_device(device_id: str):
    now = time.time()
    with manual_lock:
        active_manual_devices[device_id] = now
        _recalc_manual_locked(now)


def manual_drop_device(device_id: str, drop_all: bool = False, clear_hold: bool = True):
    """
    drop_all=True  -> очищаем весь список устройств
    clear_hold=True -> сбрасываем удержание после активации (чтобы "принудительно выключить РУ")
    """
    now = time.time()
    with manual_lock:
        if drop_all:
            active_manual_devices.clear()
        else:
            active_manual_devices.pop(device_id, None)

        if clear_hold:
            global manual_hold_until
            manual_hold_until = 0.0

        _recalc_manual_locked(now)


def extend_hold_after_activate_locked(now: float):
    """
    Продлеваем удержание РУ после активации на 15 минут от текущего момента.
    """
    global manual_hold_until
    manual_hold_until = max(manual_hold_until, now + MANUAL_HOLD_AFTER_ACTIVATE_SEC)


def watchdog_loop():
    while not stop_event.is_set():
        with manual_lock:
            _recalc_manual_locked(time.time())
        stop_event.wait(0.5)


def _snap_inputs() -> Dict[int, int]:
    return {p: int(d.value) for p, d in phase_inputs.items()}


def _snap_outputs() -> Dict[int, int]:
    return {p: int(d.value) for p, d in phase_outputs.items()}


def _fmt_secs_left(until_ts: float, now: float) -> str:
    if until_ts <= now:
        return "0s"
    left = int(until_ts - now)
    mm = left // 60
    ss = left % 60
    return f"{mm:02d}:{ss:02d}"


def print_gpio_state(prefix: str = ""):
    try:
        now = time.time()
        manual_allowed_raw = bool(manual_allowed_in.value) if manual_allowed_in else False
        manual_request = bool(manual_request_out.value) if manual_request_out else False

        in_state = _snap_inputs()
        out_state = _snap_outputs()

        stable_phase, stable_active, raw_phase, raw_active = read_phase_stable()

        with manual_lock:
            devices = list(active_manual_devices.keys())
            hold_left = _fmt_secs_left(manual_hold_until, now)
            manual_active = _manual_active_locked(now)

        print("\n" + "=" * 72)
        if prefix:
            print(f"[{prefix}]")

        print(f"ManualAllowed IN  GPIO{MANUAL_ALLOWED_PIN}: {manual_allowed_raw} (active-high)")
        print(f"ManualRequest OUT GPIO{MANUAL_REQUEST_PIN}: {manual_request} (HIGH=active)")
        print(f"Manual ACTIVE (devices|hold): {manual_active} | devices={devices} | hold_left={hold_left}")

        print(f"Phase stable: {stable_phase} | stable_active={stable_active} | raw={raw_phase} raw_active={raw_active}")

        print("\nPHASE INPUTS (GPIO -> value):")
        for p in sorted(in_state):
            gpio = PHASE_INPUT_PINS[p]
            print(f"  Phase {p}: GPIO{gpio} = {in_state[p]}")

        print("\nPHASE OUTPUTS (GPIO -> value):")
        for p in sorted(out_state):
            gpio = PHASE_OUTPUT_PINS[p]
            print(f"  Phase {p}: GPIO{gpio} = {out_state[p]}")

        print("=" * 72)

    except Exception as e:
        print(f"[PRINT ERROR] {e}")


def input_output_monitor_loop():
    global last_inputs_snapshot, last_outputs_snapshot, last_manual_allowed, last_manual_request

    while not stop_event.is_set():
        try:
            cur_inputs = _snap_inputs()
            cur_outputs = _snap_outputs()

            cur_manual_allowed = bool(manual_allowed_in.value) if manual_allowed_in else False
            cur_manual_request = bool(manual_request_out.value) if manual_request_out else False

            changed = (
                cur_inputs != last_inputs_snapshot
                or cur_outputs != last_outputs_snapshot
                or cur_manual_allowed != last_manual_allowed
                or cur_manual_request != last_manual_request
            )

            if changed:
                last_inputs_snapshot = cur_inputs
                last_outputs_snapshot = cur_outputs
                last_manual_allowed = cur_manual_allowed
                last_manual_request = cur_manual_request
                print_gpio_state("GPIO UPDATE")

        except Exception as e:
            print(f"[MONITOR ERROR] {e}")

        stop_event.wait(0.3)


@app.on_event("startup")
def startup():
    global phase_inputs, phase_outputs, manual_allowed_in, manual_request_out
    global last_inputs_snapshot, last_outputs_snapshot, last_manual_allowed, last_manual_request
    global manual_hold_until

    phase_history.clear()

    phase_inputs = {
        p: DigitalInputDevice(pin, pull_up=True, bounce_time=INPUT_BOUNCE)
        for p, pin in PHASE_INPUT_PINS.items()
    }
    manual_allowed_in = DigitalInputDevice(MANUAL_ALLOWED_PIN, pull_up=True, bounce_time=INPUT_BOUNCE)

    phase_outputs = {
        p: DigitalOutputDevice(pin, active_high=True, initial_value=False)
        for p, pin in PHASE_OUTPUT_PINS.items()
    }
    manual_request_out = DigitalOutputDevice(MANUAL_REQUEST_PIN, active_high=True, initial_value=False)
    manual_request_out.off()

    last_inputs_snapshot = {}
    last_outputs_snapshot = {}
    last_manual_allowed = None
    last_manual_request = None

    with manual_lock:
        active_manual_devices.clear()
        manual_hold_until = 0.0
        _recalc_manual_locked(time.time())

    stop_event.clear()
    threading.Thread(target=watchdog_loop, daemon=True).start()
    threading.Thread(target=input_output_monitor_loop, daemon=True).start()

    print_gpio_state("STARTUP")


@app.on_event("shutdown")
def shutdown():
    stop_event.set()

    try:
        print_gpio_state("SHUTDOWN")
    except Exception:
        pass

    for d in phase_outputs.values():
        try:
            d.off()
            d.close()
        except Exception:
            pass

    if manual_request_out is not None:
        try:
            manual_request_out.off()
            manual_request_out.close()
        except Exception:
            pass

    if manual_allowed_in is not None:
        try:
            manual_allowed_in.close()
        except Exception:
            pass

    for d in phase_inputs.values():
        try:
            d.close()
        except Exception:
            pass


# ================= API =================

@app.get("/api/status")
def status(req: Request):
    stable_phase, stable_active, raw_phase, raw_active = read_phase_stable()

    manual_allowed_raw = bool(manual_allowed_in.value) if manual_allowed_in else False
    manual_allowed = manual_allowed_raw  # active-high

    now = time.time()
    with manual_lock:
        manual_active = _manual_active_locked(now)
        hold_left = _fmt_secs_left(manual_hold_until, now)

    return {
        "serverTag": "GPIO_MAIN_V4_HOLD_AFTER_ACTIVATE",
        "ready": True,
        "objectId": OBJECT_ID,
        "address": OBJECT_ADDRESS,

        "currentPhase": stable_phase,
        "inputsActive": stable_active,

        "currentPhaseRaw": raw_phase,
        "inputsActiveRaw": raw_active,

        "manualAllowed": manual_allowed,
        "manualAllowedRaw": manual_allowed_raw,
        "manualRequestActive": manual_active,

        # полезно для отладки/индикации в UI
        "manualHoldLeft": hold_left,

        "ts": int(now),
        "pinFactory": type(Device.pin_factory).__name__ if Device.pin_factory else None,
    }


@app.get("/api/objects/{object_id}", response_model=ObjectInfoResponse)
def get_object(object_id: int, request: Request, version: Optional[str] = None):
    obj_dir = STORAGE_DIR / str(object_id)
    if not obj_dir.exists() or not obj_dir.is_dir():
        raise HTTPException(status_code=404, detail="Object not found")

    if version:
        version_dir = obj_dir / version
        if not version_dir.exists() or not version_dir.is_dir():
            raise HTTPException(status_code=404, detail="Version not found")
    else:
        version_dir = pick_latest_version_dir(obj_dir)
        if version_dir is None:
            raise HTTPException(status_code=404, detail="No versions for object")

    imgs = list_images(version_dir)
    if not imgs:
        raise HTTPException(status_code=404, detail="No images in version directory")

    b = base_url(request)
    ver = version_dir.name
    urls = [f"{b}/files/{object_id}/{ver}/{p.name}" for p in imgs]

    return ObjectInfoResponse(objectId=object_id, address=OBJECT_ADDRESS, version=ver, images=urls)


@app.get("/api/health")
def health():
    return {"ok": True}


@app.post("/api/manual/on")
def manual_on(req: Request):
    device = get_device_id(req)
    manual_touch_device(device)
    print_gpio_state(f"MANUAL ON ({device})")
    return {"ok": True, "manualRequestActive": True}


@app.post("/api/manual/off")
def manual_off(req: Request):
    """
    Принудительное отключение РУ из приложения:
    - убираем устройство из списка
    - СБРАСЫВАЕМ удержание после активации (manual_hold_until=0)
    """
    device = get_device_id(req)
    manual_drop_device(device_id=device, drop_all=False, clear_hold=True)
    print_gpio_state(f"MANUAL OFF ({device})")
    return {"ok": True, "manualRequestActive": False}


@app.post("/api/activate")
def activate(body: ActivateRequest, req: Request):
    global last_command_ts, active_command_phase

    if body.phase not in phase_outputs:
        raise HTTPException(400, "Unknown phase")

    # Разрешение от контроллера (active-high)
    if manual_allowed_in is not None and not bool(manual_allowed_in.value):
        print_gpio_state(f"ACTIVATE DENIED (phase={body.phase}) manualAllowed=False")
        raise HTTPException(403, "Manual control not allowed")

    # Требуем, чтобы РУ было активно (по устройствам/удержанию)
    now = time.time()
    with manual_lock:
        if not _manual_active_locked(now):
            print_gpio_state(f"ACTIVATE DENIED (phase={body.phase}) manualRequestActive=False")
            raise HTTPException(403, "Manual request not active")

    if now - last_command_ts < MIN_COMMAND_INTERVAL:
        raise HTTPException(429, "Too many commands")

    if not command_lock.acquire(blocking=False):
        raise HTTPException(409, "Command in progress")

    try:
        last_command_ts = now
        cancel_command_event.clear()
        active_command_phase = body.phase

        out = phase_outputs[body.phase]

        print(f"\n>>> ACTIVATE PHASE {body.phase} (pulse {PULSE_SECONDS:.1f}s)")
        print_gpio_state("BEFORE PULSE")

        out.on()
        pulse_deadline = time.time() + PULSE_SECONDS
        while time.time() < pulse_deadline:
            if cancel_command_event.is_set():
                break
            time.sleep(0.02)
        out.off()

        if cancel_command_event.is_set():
            print_gpio_state("ACTIVATION CANCELED")
            return {"accepted": False, "phase": body.phase, "cancelled": True}

        # === ВАЖНО: удержание РУ +15 минут после активации ===
        with manual_lock:
            extend_hold_after_activate_locked(time.time())
            _recalc_manual_locked(time.time())

        print_gpio_state("AFTER PULSE (HOLD EXTENDED)")

        return {"accepted": True, "phase": body.phase}
    finally:
        active_command_phase = None
        cancel_command_event.clear()
        command_lock.release()


@app.post("/api/activate/cancel")
def cancel_activate(body: Optional[CancelActivateRequest] = None):
    phase = body.phase if body is not None else None

    if phase is not None and phase not in phase_outputs:
        raise HTTPException(400, "Unknown phase")

    current_phase = active_command_phase
    if current_phase is None:
        return {"ok": True, "cancelled": False, "reason": "No active command"}

    if phase is not None and phase != current_phase:
        return {
            "ok": True,
            "cancelled": False,
            "reason": f"Different phase is active ({current_phase})",
            "activePhase": current_phase,
        }

    cancel_command_event.set()
    return {"ok": True, "cancelled": True, "phase": current_phase}


# Совместимость со старым названием
@app.post("/api/disconnect")
def disconnect(req: Request):
    device = get_device_id(req)
    manual_drop_device(device_id=device, drop_all=False, clear_hold=True)
    print_gpio_state(f"DISCONNECT ({device})")
    return {"ok": True}
