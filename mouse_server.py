#!/usr/bin/env python3
"""
Cross-Platform Mouse & Presentation Server (Windows, macOS, Linux)
Works over BOTH:
  1. Wi-Fi LAN (1-tap "Scan Wi-Fi" in the Android app — no ADB needed!)
  2. USB Cable (run: adb reverse tcp:8080 tcp:8080, connect to 127.0.0.1)

Dependency:
  pip3 install pynput
"""

import platform
import socket
import sys
import threading

# ── Windows High-DPI Awareness (prevents scaling jumps at 125% / 150% / 200%) ──
if sys.platform == "win32":
    try:
        import ctypes
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)  # Per-Monitor DPI Aware v2
        except Exception:
            ctypes.windll.user32.SetProcessDPIAware()
    except Exception:
        pass

try:
    from pynput.mouse import Button, Controller as MouseController
    from pynput.keyboard import Controller as KeyboardController, Key
except ImportError:
    print("ERROR: 'pynput' is not installed.")
    print("Install it with:  pip3 install pynput")
    sys.exit(1)

BIND_HOST = "0.0.0.0"
TCP_PORT = 8080
DISCOVERY_PORT = 8081
DISCOVER_MAGIC = "LASER_DISCOVER"

mouse = MouseController()
keyboard = KeyboardController()
input_lock = threading.Lock()


def get_os_label() -> str:
    if sys.platform == "darwin":
        return "macOS"
    if sys.platform == "win32":
        return "Windows"
    return platform.system() or "Linux"


def check_macos_accessibility() -> bool:
    """Checks macOS Accessibility permission without spawning any GUI window."""
    if sys.platform != "darwin":
        return True
    try:
        import ApplicationServices  # Provided by pyobjc (installed with pynput on macOS)
        return bool(ApplicationServices.AXIsProcessTrusted())
    except Exception:
        try:
            import ctypes
            import ctypes.util
            lib_path = ctypes.util.find_library("ApplicationServices")
            if lib_path:
                lib = ctypes.cdll.LoadLibrary(lib_path)
                lib.AXIsProcessTrusted.restype = ctypes.c_bool
                return bool(lib.AXIsProcessTrusted())
        except Exception:
            pass
    return True


def get_screen_center() -> tuple[int, int]:
    """Gets primary display center natively (no Tkinter Dock bounce on macOS!)."""
    if sys.platform == "darwin":
        try:
            import Quartz  # Installed automatically with pynput on macOS
            main_id = Quartz.CGMainDisplayID()
            bounds = Quartz.CGDisplayBounds(main_id)
            return int(bounds.size.width // 2), int(bounds.size.height // 2)
        except Exception:
            pass
    elif sys.platform == "win32":
        try:
            import ctypes
            user32 = ctypes.windll.user32
            return int(user32.GetSystemMetrics(0) // 2), int(user32.GetSystemMetrics(1) // 2)
        except Exception:
            pass

    try:
        import tkinter as tk
        root = tk.Tk()
        root.withdraw()
        w, h = root.winfo_screenwidth(), root.winfo_screenheight()
        root.destroy()
        return w // 2, h // 2
    except Exception:
        return 960, 540


def get_lan_ips() -> list[str]:
    ips: list[str] = []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            primary = s.getsockname()[0]
            if primary and not primary.startswith("127."):
                ips.append(primary)
    except Exception:
        pass

    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = info[4][0]
            if ip and not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except Exception:
        pass
    return ips


def run_udp_discovery_responder(host_name: str, os_label: str) -> None:
    """Responds to 1-tap 'Scan Wi-Fi' broadcasts from the Android app."""
    reply = f"LASER_HERE:{host_name}:{os_label}:{TCP_PORT}".encode("utf-8")
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
            udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            udp.bind((BIND_HOST, DISCOVERY_PORT))
            while True:
                try:
                    data, addr = udp.recvfrom(256)
                    if data.decode("utf-8", errors="ignore").strip() == DISCOVER_MAGIC:
                        udp.sendto(reply, addr)
                except Exception:
                    pass
    except Exception as e:
        print(f"[Note] Wi-Fi auto-discovery port {DISCOVERY_PORT} unavailable ({e}). Manual IP still works.")


def tap_key(key) -> None:
    keyboard.press(key)
    keyboard.release(key)


def handle_client(conn: socket.socket, addr, host_name: str, os_label: str) -> None:
    print(f"[+] Connected: {addr[0]}:{addr[1]}")
    left_held = False
    right_held = False

    try:
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        conn.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        greeting = f"HELLO:{host_name}:{os_label}\n".encode("utf-8")
        conn.sendall(greeting)

        buffer = ""
        while True:
            chunk = conn.recv(2048)
            if not chunk:
                break

            buffer += chunk.decode("utf-8", errors="ignore")
            if "\n" not in buffer:
                continue

            lines = buffer.split("\n")
            buffer = lines[-1]

            # Coalesce consecutive dx,dy movements within this network packet
            # so macOS Quartz WindowServer never lags under high sensor rates!
            acc_dx = 0
            acc_dy = 0

            def flush_move():
                nonlocal acc_dx, acc_dy
                if acc_dx != 0 or acc_dy != 0:
                    mouse.move(acc_dx, acc_dy)
                    acc_dx = 0
                    acc_dy = 0

            with input_lock:
                for raw_line in lines[:-1]:
                    line = raw_line.strip()
                    if not line:
                        continue

                    if "," in line and not line[0].isalpha():
                        try:
                            dx_str, dy_str = line.split(",", 1)
                            acc_dx += int(dx_str)
                            acc_dy += int(dy_str)
                        except ValueError:
                            pass
                        continue

                    # Flush any pending cursor movement BEFORE firing clicks, scrolls, or keys!
                    flush_move()

                    if line.startswith("SCROLL "):
                        try:
                            dy = int(line.split(" ", 1)[1])
                            if dy != 0:
                                mouse.scroll(0, dy)
                        except ValueError:
                            pass
                    elif line == "LDOWN":
                        if not left_held:
                            mouse.press(Button.left)
                            left_held = True
                    elif line == "LUP":
                        if left_held:
                            mouse.release(Button.left)
                            left_held = False
                    elif line == "RDOWN":
                        if not right_held:
                            mouse.press(Button.right)
                            right_held = True
                    elif line == "RUP":
                        if right_held:
                            mouse.release(Button.right)
                            right_held = False
                    elif line == "LCLICK":
                        mouse.click(Button.left)
                    elif line == "RCLICK":
                        mouse.click(Button.right)
                    elif line == "NEXT":
                        tap_key(Key.right)
                    elif line == "PREV":
                        tap_key(Key.left)
                    elif line == "PGDN":
                        tap_key(Key.page_down)
                    elif line == "PGUP":
                        tap_key(Key.page_up)
                    elif line == "BLACKOUT":
                        tap_key("b")
                    elif line == "ESC":
                        tap_key(Key.esc)
                    elif line == "CENTER":
                        cx, cy = get_screen_center()
                        mouse.position = (cx, cy)

                flush_move()

    except Exception as e:
        print(f"[!] Client error ({addr[0]}): {e}")
    finally:
        # Ensure buttons are never stuck down if phone disconnects mid-drag
        with input_lock:
            if left_held:
                try:
                    mouse.release(Button.left)
                except Exception:
                    pass
            if right_held:
                try:
                    mouse.release(Button.right)
                except Exception:
                    pass
        try:
            conn.close()
        except Exception:
            pass
        print(f"[-] Disconnected: {addr[0]}:{addr[1]}")


def start_server() -> None:
    os_label = get_os_label()
    host_name = socket.gethostname().split(".")[0] or "Computer"
    lan_ips = get_lan_ips()

    print("=" * 60)
    print(f"  Laser Pointer Pro – Companion Server ({os_label})")
    print("=" * 60)

    if sys.platform == "darwin":
        if check_macos_accessibility():
            print("  [OK] macOS Accessibility permission is granted.")
        else:
            print("  [ACTION REQUIRED] macOS Accessibility permission needed!")
            print("  -> Open System Settings > Privacy & Security > Accessibility")
            print("  -> Enable your Terminal / IDE app, then restart this script.")
        print()

    print("  Connect via Wi-Fi (no cable needed):")
    if lan_ips:
        for ip in lan_ips:
            print(f"    • Tap 'Scan Wi-Fi' in app, or enter IP:  {ip}")
    else:
        print("    • Tap 'Scan Wi-Fi' in app, or enter this computer's Wi-Fi IP")
    print()
    print("  Or connect via USB cable:")
    print(f"    • Run:  adb reverse tcp:{TCP_PORT} tcp:{TCP_PORT}")
    print("    • Set Host in app to:  127.0.0.1")
    print("=" * 60)
    print(f"Listening on TCP port {TCP_PORT} (Press Ctrl+C to stop)...\n")

    threading.Thread(
        target=run_udp_discovery_responder,
        args=(host_name, os_label),
        daemon=True
    ).start()

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((BIND_HOST, TCP_PORT))
        server.listen(5)

        try:
            while True:
                conn, addr = server.accept()
                threading.Thread(
                    target=handle_client,
                    args=(conn, addr, host_name, os_label),
                    daemon=True
                ).start()
        except KeyboardInterrupt:
            print("\nShutting down server.")


if __name__ == "__main__":
    start_server()
