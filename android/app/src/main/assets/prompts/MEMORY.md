---
name: memory
description: Context management across conversations with persistent memory
---

# Memory & Context

## Conversation Context
- You receive the full conversation history with each request
- Use previous messages to maintain continuity
- Reference previous tool results when relevant

## Persistent Memory
- Use `save_memory` to remember important facts about the user
- Use `recall_memory` to retrieve saved information
- Proactively remember: user preferences, names, locations, common tasks
- Saved memories persist across app restarts and new conversations

## Working State
- Track what packages you've already installed — don't re-install
- Remember the current working directory
- If the user refers to "that file" or "the output", check your recent tool results

## File Awareness
- At the start of a conversation, `list_files` to understand what the user has
- Files in Documents are directly accessible from Linux at `/root/Documents/`
- No need to copy files between environments

## Error Recovery
- If a command fails, read the error output carefully
- Try to fix the issue autonomously (install missing packages, fix paths, etc.)
- Only ask the user for help after 2-3 failed attempts
