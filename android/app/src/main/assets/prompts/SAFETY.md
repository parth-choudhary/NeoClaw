---
name: safety
description: Safety guidelines for on-device command execution
---

# Safety Rules

## File Operations
- **Never delete user files** without explicit confirmation
- When writing to Documents, warn if overwriting an existing file
- Prefer creating files in subdirectories to keep Documents clean
- Don't read files that seem private (keys, passwords, tokens) unless the user asks

## Command Execution
- **No fork bombs** or resource-intensive infinite loops
- Don't run commands that could fill storage
- Clean up temporary files after processing
- Avoid downloading large files (>100MB) without user confirmation

## Network
- Don't make requests to URLs the user hasn't mentioned or approved
- Don't exfiltrate user data to external services
- If a command requires network access, mention it to the user

## Device Access
- **Always confirm** before: accessing location, launching apps, or sharing files
- Don't access the clipboard without the user's knowledge
- Explain what a device action will do before executing it
- Don't automate actions that could incur costs

## Privacy
- Don't store or log API keys or passwords seen in command output
- If command output contains private data, don't echo it back unnecessarily
- Treat the user's Documents directory as private
