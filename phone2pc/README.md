# Phone2PC Audio

Streams raw, uncompressed 48kHz/16-bit PCM mono audio from an Android phone
to a PC with minimal delay. No video, no compression, no lossy codec.

## How it works
- Android app captures mic via `AudioRecord` using `UNPROCESSED` source
  (bypasses AGC/noise suppression/echo cancellation for full fidelity)
  and pushes 20ms raw PCM chunks over a TCP socket with `TCP_NODELAY`.
- PC script receives the raw stream and plays it with `sounddevice` in
  low-latency mode, using a small ring buffer to absorb jitter without
  adding noticeable delay.

Typical end-to-end latency: roughly 40-80ms over USB, 60-150ms over
good WiFi (depends on your router/driver).

## 1. Build the Android app
1. Install [Android Studio](https://developer.android.com/studio).
2. Open the `android/` folder as a project.
3. Let Gradle sync, then Run on your phone (USB debugging enabled), or
   Build > Build APK and sideload it.
4. Grant the microphone permission when prompted.

## 2. Set up the PC receiver
```
pip install sounddevice numpy
python pc/receiver.py --list-devices     # optional: see output devices
python pc/receiver.py --port 6000
```

## 3. Connect — choose one

### USB (recommended — lowest, most stable latency)
1. Enable Developer Options + USB Debugging on the phone, plug in the cable.
2. On PC: `adb reverse tcp:6000 tcp:6000`
3. In the phone app, set IP to `127.0.0.1`, port `6000`, tap **Start Streaming**.

### WiFi (no cable needed)
1. Make sure phone and PC are on the same network.
2. Find your PC's LAN IP (`ipconfig` on Windows, `ifconfig`/`ip addr` on Mac/Linux).
3. In the phone app, enter that IP, port `6000`, tap **Start Streaming**.

## Optional: use it as a virtual microphone on PC
If you want other apps (Discord, a DAW, etc.) to pick up this audio as an
input device:
1. Install [VB-Audio Virtual Cable](https://vb-audio.com/Cable/) (free).
2. Run the receiver with `--device "CABLE Input"`.
3. In the other app, select "CABLE Output" as the microphone.

## Notes on "full quality"
- Audio is sent completely uncompressed (raw PCM), so there's no codec
  loss at all — this is the ceiling for phone-mic fidelity.
- If your phone's mic hardware supports 24-bit capture and you want to
  push past 16-bit, the sample format in both `MainActivity.kt`
  (`AudioFormat.ENCODING_PCM_16BIT`) and `receiver.py` (`DTYPE`) can be
  changed together — let me know if you want that variant.
