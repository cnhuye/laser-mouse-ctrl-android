# Laser Mouse Controller

[**Official Website**](https://laser.scieford.com/) | [**Download**](https://laser.scieford.com/download)

Turn your Android phone into a laser pointer tracking device. Use any laser pointer to control your computer's mouse cursor—perfect for presentations or as a light gun emulator for retro shooting games.

## Features

- Real-time laser pointer tracking using camera
- Wi-Fi communication with desktop server
- Configurable sensitivity and tracking area
- Low-latency mouse control

## Requirements

- Android 10+ (API 29+)
- A laser pointer
- [Laser Mouse Server](https://github.com/cnhuye/laser-mouse-ctrl-pc) running on your computer

## Usage

This app must be used together with the desktop server:

### Step 1: Run PC Server
1. **Run as Administrator** - Right-click the PC server application and select "Run as administrator"
2. **Start Service** - Click the "Start" button in the PC server to enable the service

### Step 2: Setup Phone Camera
1. Place your phone horizontally and point the camera at the screen (use a phone stand for stability)
2. Ensure the camera can see the entire screen area
3. Adjust the camera angle so the screen fills most of the frame

### Step 3: Connect to PC
1. Make sure your phone and computer are connected to the same LAN (local area network)
2. Open this app and tap the "Connect" button on the right side
3. Wait about 1 second for the PC to be discovered, then tap "Connect"

### Step 4: Screen Detection
1. The PC will display a full-screen background image
2. The phone will enter screen detection mode
3. After a few seconds, detection completes and you'll enter laser tracking mode

### Step 5: Start Using
Point your laser pointer at the screen to control the mouse cursor. The cursor will follow the laser dot.

## Tips

- **Laser Pointer** - Use a laser pointer with sufficient brightness
- **Screen Type** - Works best with projector screens. LED/LCD TVs may reflect the laser dot, making detection difficult at screen edges
- **TV Distance** - If using a TV screen, keep the phone 2-3 times the screen width away

## Building

Open the project in Android Studio and build:

1. **Debug APK**: Build → Build Bundle(s) / APK(s) → Build APK(s)
2. **Release APK**: Build → Generate Signed Bundle / APK → APK

The APK will be generated at `app/build/outputs/apk/`

## Related Project

This app works with the desktop server application:
- [Laser Mouse Server (PC)](https://github.com/cnhuye/laser-mouse-ctrl-pc)

## License

MIT License - see [LICENSE](LICENSE) file for details.
