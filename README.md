# Better Internet Tiles (Plexle Mod)

<img alt="GitHub" src="https://img.shields.io/github/license/plex-le/Better-Internet-Tiles"> <img alt="GitHub last commit" src="https://img.shields.io/github/last-commit/plex-le/Better-Internet-Tiles"> <img alt="GitHub release (latest by date)" src="https://img.shields.io/github/v/release/plex-le/Better-Internet-Tiles">

## About

This application aims to create a unified internet quick-settings tile, which is actually useful (I'm looking at you, Android 12+). Next to this, **separate Wi-Fi and mobile data tiles are also available** if you just want to go back to the behaviour of Android 11 or lower.

Tapping the unified internet tile will simply toggle between Wi-Fi and mobile data, reducing the amount of taps needed from 3 to just 1 quick tap. Long pressing any tile will redirect you to the relevant system settings page.

## Key Features & Plexle Mod Enhancements

- **Fast Multi-Tier SSID Sniffing**: Instant SSID updates without display lag.
- **Tasker Fallback Support**: When Shizuku or Root shell access is not granted, the Wi-Fi tile can seamlessly delegate toggle actions to Tasker.
- **Coexist Installation**: Custom Application ID (`be.plexle.unifiedinternetqs`) allows installing side-by-side with original or official builds.
- **Battery & Performance Optimized**: Fixed QS tile listening lifecycle to eliminate background loop re-binds.
- **Unified & Separate Tiles**: Unified Internet tile, separate Wi-Fi tile, Mobile Data tile, NFC tile, Airplane Mode tile, and Bluetooth tile.
- **Lock Screen Access Configurable**: Ability to configure access to the tiles while the device is locked.

## Shell Access / Fallback

- **Root / Shizuku**: Shell access can be granted using regular Root or the [Shizuku](https://shizuku.rikka.app/) application for direct execution.
- **Tasker Fallback**: If you don't use Shizuku or Root, you can configure Tasker with external access enabled to handle Wi-Fi toggling.

## How to Install

1. Download the signed APK (`BetterInternetTiles-v...apk`) from the [Latest GitHub Release](https://github.com/plex-le/Better-Internet-Tiles/releases/latest).
2. Install the APK on your Android device (Android 12+).
3. Open the app and grant Shizuku/Root access, or configure Tasker integration.
4. Edit your Quick Settings panel, add the desired tiles to your active list.

## Credits

This project is a fork based on the excellent work by [Casper Verswijvelt](https://github.com/CasperVerswijvelt):
* **Original Repository**: [CasperVerswijvelt/Unified-Internet-QS](https://github.com/CasperVerswijvelt/Unified-Internet-QS)
* Sincere thanks to Casper Verswijvelt and all contributors for the original implementation!

## License

This project is licensed under the GPL-3.0 License. See the [LICENSE](LICENSE) file for details.
