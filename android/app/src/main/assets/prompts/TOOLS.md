---
name: tools
description: Detailed usage instructions for all available tools
---

# Tool Reference

## `run_command`
Execute a shell command in the Alpine Linux environment.

```
run_command(command: "ls -la /root/")
```

**Key points:**
- Commands run as `root` in the Linux environment
- Working directory is `/root/` by default
- The user's Documents folder is mounted at `/root/Documents/`
- Install missing tools: `apk add --no-cache <package>`
- Maximum execution time is ~60 seconds per command

**Common packages:**
| Package | Provides |
|---------|----------|
| `git` | git |
| `ffmpeg` | ffmpeg, ffprobe |
| `python3` | python3, pip3 |
| `nodejs` | node, npm |
| `gcc` | gcc, g++ |
| `imagemagick` | convert, identify |
| `curl` | curl |
| `jq` | jq |

---

## `read_file`
Read a text file from the Documents directory.
```
read_file(path: "notes/todo.txt")
```

## `write_file`
Write content to a file in the Documents directory.
```
write_file(path: "output/result.txt", content: "Hello World")
```

## `list_files`
List contents of the Documents directory.
```
list_files(path: "photos/")
```

## `install_package`
Install a Linux package.
```
install_package(package: "ffmpeg")
```

---

## Device Tools

### `get_clipboard` / `set_clipboard`
Read from or write to the Android clipboard.
```
get_clipboard()
set_clipboard(text: "Copied text")
```

### `get_device_info`
Get device info: battery, storage, model, OS version.

### `get_location`
Get current GPS coordinates (requires location permission).

### `open_url`
Open a URL in the default browser.
```
open_url(url: "https://example.com")
```

### `launch_app`
Launch an installed app by package name. Even if you are not "connected" to an app (like Telegram or WhatsApp), you can still launch it and control it visually using the Android Accessibility Skill.
```
launch_app(package_name: "com.whatsapp")
```

### `share_file`
Share a file via the Android share sheet.
```
share_file(path: "/root/Documents/output.pdf")
```

### `send_notification`
Send a local notification to the user.
```
send_notification(title: "Task Complete", body: "Your file is ready")
```

---

## Memory Tools

### `save_memory`
Save a fact to persistent memory. Survives across conversations.
```
save_memory(key: "user_name", value: "Parth")
```

### `recall_memory`
Recall a previously saved memory. Use key `*` to list all.
```
recall_memory(key: "user_name")
recall_memory(key: "*")
```

---

## Scheduling Tools

### `schedule_task`
Schedule a recurring task with notification delivery.
```
schedule_task(
  prompt: "Check the weather in Bangalore",
  schedule: { "type": "daily", "hour": 8, "minute": 0 }
)
```

| Type | Parameters |
|------|-----------|
| `daily` | hour, minute |
| `weekly` | weekday (1=Sun..7=Sat), hour, minute |
| `every_n_hours` | n |
| `every_n_minutes` | n |
| `one_time` | delay_minutes |

### `list_tasks` / `cancel_task`
```
list_tasks()
cancel_task(task_id: "a1b2c3d4")
```

---

## Tool Chaining Patterns

### Process user files
```
run_command("apk add ffmpeg")
run_command("ffmpeg -i /root/Documents/video.mov -crf 28 /root/Documents/compressed.mp4")
```

### Create and save
```
run_command("python3 -c 'print(\"hello\")' > /root/Documents/output.txt")
```

### Install + run
```
install_package("git") → run_command("git clone ...")
```
