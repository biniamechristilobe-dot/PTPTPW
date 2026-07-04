"""
Phone2PC Audio Receiver
-----------------------
Listens on a TCP port for a raw, uncompressed 48kHz/16-bit PCM mono
audio stream (matching the Android sender) and plays it back with
minimal latency using the sounddevice library.

Usage:
    pip install sounddevice numpy
    python receiver.py --port 6000

Connection modes:
    USB (recommended, lowest latency, no WiFi involved):
        adb reverse tcp:6000 tcp:6000
        (phone app connects to 127.0.0.1:6000, tunneled over the cable)

    WiFi:
        python receiver.py --host 0.0.0.0 --port 6000
        (phone app connects to this PC's LAN IP)

Optional: to make this audio usable as a "microphone" input in other
apps (Discord, OBS, etc.), install VB-Audio Virtual Cable, then pass
--device "CABLE Input" (or list devices with --list-devices).
"""

import argparse
import queue
import socket
import sys
import threading

import numpy as np
import sounddevice as sd

SAMPLE_RATE = 48000
CHANNELS = 1
DTYPE = "int16"
BLOCK_FRAMES = 960          # 20ms at 48kHz
BLOCK_BYTES = BLOCK_FRAMES * 2 * CHANNELS  # int16 = 2 bytes/sample


def list_devices():
    print(sd.query_devices())


def recv_exact(sock: socket.socket, n: int) -> bytes:
    """Read exactly n bytes from the socket, or return b'' on disconnect."""
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return b""
        buf.extend(chunk)
    return bytes(buf)


def network_thread(conn: socket.socket, audio_q: "queue.Queue[np.ndarray]", stop_evt: threading.Event):
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    try:
        while not stop_evt.is_set():
            raw = recv_exact(conn, BLOCK_BYTES)
            if not raw:
                print("[receiver] phone disconnected")
                break
            samples = np.frombuffer(raw, dtype=np.int16)
            try:
                audio_q.put_nowait(samples)
            except queue.Full:
                # Drop oldest to keep latency from creeping up under sustained overload
                try:
                    audio_q.get_nowait()
                except queue.Empty:
                    pass
                audio_q.put_nowait(samples)
    finally:
        stop_evt.set()
        conn.close()


def main():
    parser = argparse.ArgumentParser(description="Phone2PC low-latency audio receiver")
    parser.add_argument("--host", default="0.0.0.0", help="Host/IP to bind (0.0.0.0 = all interfaces)")
    parser.add_argument("--port", type=int, default=6000)
    parser.add_argument("--device", default=None, help="Output device name or index (see --list-devices)")
    parser.add_argument("--list-devices", action="store_true")
    args = parser.parse_args()

    if args.list_devices:
        list_devices()
        return

    audio_q: "queue.Queue[np.ndarray]" = queue.Queue(maxsize=10)  # ~200ms max backlog
    silence = np.zeros(BLOCK_FRAMES, dtype=np.int16)

    def callback(outdata, frames, time_info, status):
        if status:
            print(status, file=sys.stderr)
        try:
            data = audio_q.get_nowait()
        except queue.Empty:
            data = silence
        outdata[:, 0] = data

    stream = sd.OutputStream(
        samplerate=SAMPLE_RATE,
        channels=CHANNELS,
        dtype=DTYPE,
        blocksize=BLOCK_FRAMES,
        latency="low",
        device=args.device,
        callback=callback,
    )

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(1)
    print(f"[receiver] listening on {args.host}:{args.port}")
    print("[receiver] for USB: run `adb reverse tcp:%d tcp:%d` then connect phone app to 127.0.0.1:%d"
          % (args.port, args.port, args.port))

    with stream:
        while True:
            print("[receiver] waiting for phone to connect...")
            conn, addr = srv.accept()
            print(f"[receiver] connected: {addr}")
            stop_evt = threading.Event()
            t = threading.Thread(target=network_thread, args=(conn, audio_q, stop_evt), daemon=True)
            t.start()
            t.join()  # block here until this connection drops, then accept again


if __name__ == "__main__":
    main()
