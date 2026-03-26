# Laser Mouse Controller

Turn your Android phone into a laser pointer tracking device. Use any laser pointer to control your computer's mouse cursor—perfect for presentations or as a light gun emulator for retro shooting games.

## Features

- Real-time laser pointer tracking using camera
- Wi-Fi communication with desktop server
- Configurable sensitivity and tracking area
- Low-latency mouse control

## Requirements

- Android 10+ (API 29+)
- A laser pointer

## Building

```bash
# Install dependencies
./gradlew assembleDebug

# Build release APK
./build_release.sh
```

The APK will be generated at `app/build/outputs/apk/release/`

## Related Project

This app works with the desktop server application:
- [Laser Mouse Server (PC)](https://github.com/cnhuye/laser-mouse-ctrl-pc)

## License

MIT License - see [LICENSE](LICENSE) file for details.
