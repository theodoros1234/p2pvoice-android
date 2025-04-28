# P2PVoice for Android

Peer to peer video and audio communication between two Android devices using Wi-Fi Direct, without the need for any mobile infrastructure.

For Opus audio encoding/decoding, it uses the [theeasiestway/android-opus-codec](https://github.com/theeasiestway/android-opus-codec) library, which is a wrapper around [libopus](https://opus-codec.org/release/stable/2019/04/12/libopus-1_3_1.html).

**WARNING:** This app is in early development, and may never be fully developed. ***DO NOT USE for anything important!***

## Information

P2PVoice uses **Wi-Fi Direct** for communication between two phones. It uses the **H.264** video codec for transmitting video from the devices’ cameras, and the **Opus** audio codec for transmitting audio from the devices’ microphones. The Opus codec is very efficient, and can achieve good audio quality, even with a very low bitrate. This should allow the app to maintain a high audio quality even with low data rates, especially if the camera is disabled. While in a video call, the app **automatically adjusts the video bitrate** based on network statistics, decreasing it when the connection can’t keep up, and increasing it when the connection is strong enough to handle it (max 6000 Kbps, min 400 Kbps).


## Usage:

1. Open the app on two phones.
2. Tap “Start” on the top right corner to start scanning for nearby Wi-Fi Direct devices. Do this for both of your devices.
3. When one of your devices detects the other, tap on its name in the list to start the connection.
4. On the other device, Android might ask for confirmation before connecting. Tap “Accept” to allow the connection.
5. When the two devices connect to each other, the video call will start. You will need to accept permissions for using the camera and microphone before the call starts.
6. On the call screen, you will see the following:
    - the video feed from the other phone’s camera
    - a small preview in the corner for your camera
    - the current video bitrate
    - the following buttons for controlling the call:
        - Mute/Unmute
        - Earpiece/Speaker toggle
        - Switch camera, to switch between the front and back cameras
        - Toggle camera, to enable/disable your camera
        - End call button
7. After ending the call, the devices will disconnect from each other, and you will return to the scan screen, where you can search for another device to connect to.
8. If you force-close the app or it crashes, make sure to disconnect from Wi-Fi Direct from your devices’ Wi-Fi settings, to prevent it from draining your device’s battery.
