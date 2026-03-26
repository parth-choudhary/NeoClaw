---
name: android-device
description: Control the Android device — clipboard, GPS, battery, apps, sharing
---

# Android Device Control Skill

You can directly control the Android device using native tools.

## Available Tools

### Clipboard
```
get_clipboard()  → Read current clipboard
set_clipboard(text: "...")  → Copy to clipboard
```

### Device Info
```
get_device_info()  → Battery, storage, model, OS
```

### Location
```
get_location()  → GPS coordinates (requires permission)
```

### App Control
```
launch_app(package_name: "com.whatsapp")  → Launch any installed app
open_url(url: "https://example.com")  → Open URL in browser
share_file(path: "/root/Documents/file.pdf")  → Android share sheet
```

### Notifications
```
send_notification(title: "Done", body: "Your task is complete")
```

## Best Practices
- Always confirm with the user before accessing location or launching apps
- Use `get_device_info` when the user asks about their phone
- Prefer `share_file` over `open_url` for sharing local files
