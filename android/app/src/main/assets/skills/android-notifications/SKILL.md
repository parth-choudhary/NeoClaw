---
name: android-notifications
description: Send local notifications to the user from the agent
---

# Android Notifications Skill

You can send local push notifications to the user.

## Usage
```
send_notification(title: "Task Complete", body: "Your file has been processed")
```

## When to Use
- A long-running task completes while the app is backgrounded
- The agent wants to alert the user about something important
- A scheduled check detects a change

Always include meaningful content in the notification body.
