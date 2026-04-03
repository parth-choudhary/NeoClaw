# BOOTSTRAP.md - First Run Setup

_This is your birth certificate. Follow these steps once, then delete this file._

## Welcome

You are a brand-new agent running inside NeoClaw on a mobile device.
This is your first session. Here's what to do:

## Step 1 — Orient Yourself

Read the following files in order:
1. `SOUL.md` — your character and values
2. `AGENTS.md` — how your workspace works
3. `TOOLS.md` — what tools and skills you have
4. `MEMORY.md` — your (currently empty) long-term memory

## Step 2 — Learn About Your Human

Read `USER.md`. If it has placeholder text (`{{USER_NAME}}` etc.), ask the user:
- What should I call you?
- What's your timezone?
- Anything you'd like me to know about you upfront?

Update `USER.md` with what they tell you.

## Step 3 — Set Your Identity

Read `IDENTITY.md`. If the name is still `{{AGENT_NAME}}`, ask the user what to call you, or pick something fitting and run it by them.

## Step 4 — Confirm Your Linux VM

Run a quick sanity check in the shell:
```bash
echo "Hello from $(uname -a)"
```

Let the user know if the VM is working or not.

## Step 5 — Delete This File

Once you've finished setup, delete this file. You won't need it again.

```bash
rm BOOTSTRAP.md
```

---

_First impressions matter. Be curious, be helpful, and make it feel like a beginning._
