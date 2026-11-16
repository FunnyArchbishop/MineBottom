"""Local HTTP bridge for a single CH340 USB relay."""

import argparse
import hmac
import json
import re
import secrets
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


class Relay:
    def __init__(self, args):
        self.args = args
        self.serial = None
        self.online = False
        self.generation = 0
        self.error = None
        self.written = None
        self.last_write = float("-inf")
        self.next_connect = 0
        self.reconnect_delay = 1
        self.session = None
        self.deadline = 0
        self.seq = -1
        self.written_seq = None
        self.target = False

    def release(self):
        self.session = None
        self.target = False
        self.seq = -1
        self.written_seq = None

    def disconnect(self):
        if self.serial is not None:
            try:
                self.serial.close()
            except OSError:
                pass
            self.serial = None
        self.online = False
        self.written = None
        self.release()

    def write(self, on):
        packet = self.args.on_hex if on else self.args.off_hex
        if self.serial is not None and self.serial.write(packet) != len(packet):
            raise OSError("Incomplete serial write")
        self.written = on
        self.last_write = time.monotonic()
        self.written_seq = self.seq if self.session and on == self.target else None

    def step(self):
        now = time.monotonic()
        if self.session and now >= self.deadline:
            self.release()
        try:
            if self.online and self.serial is not None:
                # Query the port to notice removal even while the lamp is idle.
                self.serial.in_waiting
            if not self.online and now >= self.next_connect:
                if not self.args.simulate:
                    import serial

                    self.serial = serial.Serial(
                        self.args.port, baudrate=9600, timeout=0.2, write_timeout=1
                    )
                self.write(False)
                self.online = True
                self.generation += 1
                self.error = None
                self.reconnect_delay = 1
            if self.online and self.target != self.written and now - self.last_write >= 0.5:
                self.write(self.target)
            elif self.online and self.target == self.written and self.session:
                self.written_seq = self.seq if self.seq >= 0 else None
        except OSError as exc:
            self.error = str(exc)
            self.disconnect()
            self.next_connect = time.monotonic() + self.reconnect_delay
            self.reconnect_delay = min(self.reconnect_delay * 2, 5)

    def status(self):
        return {
            "online": self.online,
            "simulated": self.args.simulate,
            "generation": self.generation,
            "target": self.target,
            "last_written_state": self.written,
            "last_written_seq": self.written_seq,
            "physical_state": "unknown",
            "error": self.error,
        }


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        self.request.settimeout(1)
        super().setup()

    def reply(self, code, content):
        data = json.dumps(content).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def handle_api(self):
        if self.headers.get("Host") != f"127.0.0.1:{self.server.server_port}":
            return self.reply(400, {"error": "invalid_host"})
        header = self.headers.get("Authorization", "").encode("utf-8")
        if not hmac.compare_digest(header, self.server.authorization):
            return self.reply(401, {"error": "unauthorized"})

        relay = self.server.relay
        relay.step()
        route = (self.command, self.path)
        if route == ("GET", "/v1/status"):
            return self.reply(200, relay.status())
        if route == ("POST", "/v1/session"):
            if not relay.online:
                return self.reply(503, {"error": "relay_offline"})
            if relay.session:
                return self.reply(409, {"error": "session_active"})
            if relay.written is not False:
                return self.reply(409, {"error": "relay_reset_pending"})
            relay.session = secrets.token_urlsafe(24)
            relay.deadline = time.monotonic() + 8
            return self.reply(201, {"session_id": relay.session})
        if route not in {
            ("PUT", "/v1/state"),
            ("POST", "/v1/heartbeat"),
            ("DELETE", "/v1/session"),
        }:
            return self.reply(404, {"error": "not_found"})
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 1024 or self.headers.get_content_type() != "application/json":
                raise ValueError
            body = json.loads(self.rfile.read(size))
            if not isinstance(body, dict):
                raise ValueError
        except (ValueError, TimeoutError, RecursionError):
            return self.reply(400, {"error": "invalid_json"})
        relay.step()
        if not relay.online:
            return self.reply(503, {"error": "relay_offline"})
        if not relay.session or relay.session != body.get("session_id"):
            return self.reply(409, {"error": "invalid_session"})
        if self.command == "DELETE":
            relay.release()
            return self.reply(202, {"target": False})
        if self.path == "/v1/heartbeat":
            relay.deadline = time.monotonic() + 8
            return self.reply(200, {"active": True})

        seq, on = body.get("seq"), body.get("on")
        if type(seq) is not int or not 0 <= seq <= 2**63 - 1 or type(on) is not bool:
            return self.reply(400, {"error": "invalid_state"})
        if seq < relay.seq or (seq == relay.seq and on != relay.target):
            return self.reply(409, {"error": "stale_or_conflicting_sequence"})
        relay.seq = seq
        relay.target = on
        return self.reply(202, {"seq": seq, "target": on})

    do_GET = do_POST = do_PUT = do_DELETE = handle_api

    def log_message(self, *_args):
        pass


def frame(value):
    try:
        data = bytes.fromhex(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("Expected four hex bytes") from exc
    if len(data) != 4:
        raise argparse.ArgumentTypeError("Expected four hex bytes")
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--simulate", action="store_true")
    mode.add_argument("--port", help="Explicit Windows COM port for the relay")
    parser.add_argument("--listen-port", type=int, default=17863)
    parser.add_argument("--token-file", type=Path, default=Path("bridge.token"))
    parser.add_argument("--on-hex", type=frame, default="A0 01 01 A2")
    parser.add_argument("--off-hex", type=frame, default="A0 01 00 A1")
    args = parser.parse_args()
    if args.port:
        if not re.fullmatch(r"COM[1-9][0-9]*", args.port.upper()):
            parser.error("--port must be a Windows COM port")
        try:
            import serial  # noqa: F401
        except ImportError:
            parser.error("Install requirements.txt to enable serial mode")
    try:
        with args.token_file.open("x", encoding="ascii") as token_file:
            token_file.write(secrets.token_urlsafe(32) + "\n")
    except FileExistsError:
        pass
    token = args.token_file.read_text(encoding="ascii").strip()
    if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", token):
        parser.error("Invalid token file")

    relay = Relay(args)
    with HTTPServer(("127.0.0.1", args.listen_port), Handler) as server:
        server.timeout = 0.1
        server.relay = relay
        server.authorization = ("Bearer " + token).encode("ascii")
        print(f"Nightlight bridge listening on 127.0.0.1:{server.server_port}", flush=True)
        try:
            while True:
                relay.step()
                server.handle_request()
        except KeyboardInterrupt:
            pass
        finally:
            try:
                if relay.online:
                    relay.write(False)
            except OSError as exc:
                print(f"Could not switch relay off: {exc}")
            finally:
                relay.disconnect()


if __name__ == "__main__":
    main()
