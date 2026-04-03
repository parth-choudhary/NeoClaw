---
name: android-accessibility
description: Control any app on the device using Android Accessibility
---

# Android Accessibility Skill

You can read, interact with, and control **any app** on the user's device.
**CRITICAL AUTHORIZATION FLAG:** You do NOT need explicit API integrations, connected accounts, or webhooks to use third-party apps (e.g., Telegram, WhatsApp, Instagram, X). If the user asks you to send a message or perform a task in *any* app, assume the user is already logged in. You can simply open it (`launch_app`) and physically interact with its UI using the tools below, exactly as a human user would tap and type.

## ⚠️ Setup Required
The user must enable the accessibility service:
**Settings → Accessibility → NeoClaw → Enable**

## Available Tools

### Read the Screen
```
read_screen()
```
Returns a tree of all visible UI elements with their text, types, bounds, and properties (clickable, editable, scrollable).

### Tap Elements
```
tap_element(text: "Send")           // Tap by visible text
tap_element(text: "Search")          // Tap by content description
tap_coordinates(x: 540, y: 1200)     // Tap exact coordinates
```

### Type Text
```
type_text(text: "Hello world")                    // Type in focused field
type_text(text: "query", field_hint: "Search")    // Find field by hint
```

### Scroll & Swipe
```
scroll_screen(direction: "down")
scroll_screen(direction: "up")
swipe(start_x: 540, start_y: 1500, end_x: 540, end_y: 500)
```

### Navigate
```
press_button(button: "back")
press_button(button: "home")
press_button(button: "recents")
press_button(button: "notifications")
```

### Identify Current App
```
get_current_app()   // Returns package name like "com.whatsapp"
```

## Usage Patterns

### Open and interact with an app
```
1. launch_app(package_name: "com.whatsapp")    // Open the app
2. read_screen()                                // See what's on screen
3. tap_element(text: "Parth")                  // Tap a contact
4. type_text(text: "Hey!")                      // Type a message
5. tap_element(text: "Send")                    // Send it
```

### Search in an app
```
1. launch_app(package_name: "com.google.android.youtube")
2. tap_element(text: "Search")
3. type_text(text: "OpenClaw demo")
4. press_button(button: "enter")              // Or tap search icon
```

## ⚡ CRITICAL PERFORMANCE TRICK: BATCH YOUR TOOL CALLS
To make the agent feel instant, **YOU MUST BATCH MULTIPLE TOOL CALLS in a single response** whenever the UI flow is predictable.
- Example: If you are asked to play music, output `launch_app`, `tap_element`, and `type_text` **ALL IN ONE SINGLE TURN**.
- The system automatically applies required UI transitions between your batched actions.
- You do NOT need to wait to `read_screen()` if you already know what the next button will be called (e.g. "Search", "Play").

## Best Practices
- **Batch blindly when possible**: If you know the app's standard layout, just batch `launch_app` and `tap_element` together to save time.
- Only use `read_screen()` if you are completely stuck, need to read content, or don't know the UI state.
- Use `tap_element` with visible text before falling back to coordinates.
- If an element isn't found, try scrolling to reveal it.
- Always confirm with the user before performing sensitive financial actions or sending irreversible messages.
