---
name: android-files
description: Manage files in the app's Documents directory
---

# Android File Management Skill

You can read, write, and list files in the user's Documents directory.
These files are accessible to the user via any file manager app.

## Tools Available

- `read_file`: Read file contents by relative path
- `write_file`: Create or overwrite a file
- `list_files`: List directory contents with sizes
- `run_command`: Use `cp`, `mv`, `rm`, `mkdir` for file operations

## Key Difference from iOS
Files in Documents are **directly mounted** into the Linux environment at `/root/Documents/`.
You can access them directly from shell commands — no `copy_to_vm` / `copy_from_vm` needed.

## Best Practices
1. Always use relative paths from the Documents root
2. Create parent directories before writing nested files
3. When the user says "save this", write to Documents/
4. Large files should be streamed, not loaded entirely into memory
5. Use `list_files` before operations to verify paths
