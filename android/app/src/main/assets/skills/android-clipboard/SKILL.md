---
name: android-clipboard
description: Read from and write to the Android clipboard
---

# Android Clipboard Skill

You can interact with the Android clipboard using native tools.

## Copy to Clipboard
```
set_clipboard(text: "text to copy")
```

## Read from Clipboard
```
get_clipboard()
```

## Use Cases
- Copy generated code/text to clipboard for the user
- Read clipboard contents shared by the user
- Transform clipboard contents (e.g., format JSON, translate text)
