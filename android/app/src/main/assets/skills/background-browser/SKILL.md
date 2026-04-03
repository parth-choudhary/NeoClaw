---
name: background-browser
description: Browse the web silently in the background using headless WebView
---

# Background Browser Skill

You can browse the web in the **background** without disrupting the user's current phone activity. Pages load in a headless WebView that the user never sees.

## ⚠️ Setup Required
The user must enable **Browser Mode** in Settings → Agent Interaction Mode → Browser (Background web).
The user should log into websites via **Manage Login Sessions** so you inherit their cookies.

## Available Tools

### Navigate
```
browser_open(url: "https://web.telegram.org")    // Open a page
browser_get_url()                                 // Check current URL
```

### Read Page Content
```
browser_read()   // Returns structured content with clickable elements
```
Output format:
- `[CLICKABLE: Send]` — A button or clickable element. Use `browser_click(selector_or_text: "Send")` to click it.
- `[LINK: Profile]` — A hyperlink. Click it or follow the href.
- `[INPUT(text): Search...]` — A text input field. Use `browser_type` to fill it.
- Regular text, headings, and lists represent page content.

### Interact
```
browser_click(selector_or_text: "Send")           // Click by visible text
browser_click(selector_or_text: ".btn-primary")   // Click by CSS selector
browser_type(selector_or_text: "Message", text: "Hello!")  // Type in a field
browser_press_enter()                             // Submit the message!
browser_scroll(direction: "down")                 // Load more content
```

### Advanced
```
browser_execute_js(script: "document.title")   // Run custom JavaScript
```

## ⚡ CRITICAL: How to Send Messages in Chat Apps

Chat web apps (Telegram, WhatsApp, Slack) use `contenteditable` divs, not regular inputs. **This works correctly.** Follow this exact pattern:

### Send a Telegram message:
```
1. browser_open(url: "https://web.telegram.org/a/")
2. browser_read()                                    // See contacts list
3. browser_click(selector_or_text: "Contact Name")  // Open the chat
4. browser_read()                                    // Verify chat opened
5. browser_type(selector_or_text: "Message", text: "Hey, what's up?")  // Type message
6. browser_press_enter()                             // SEND IT! Or click Send button
```

### Search on a website:
```
1. browser_open(url: "https://google.com")
2. browser_type(selector_or_text: "Search", text: "latest news")
3. browser_press_enter()                             // Submit search
4. browser_read()                                    // Read results
```

### Fill a form:
```
1. browser_open(url: "https://example.com/form")
2. browser_type(selector_or_text: "Email", text: "user@example.com")
3. browser_type(selector_or_text: "Password", text: "secret")
4. browser_click(selector_or_text: "Submit")
```

## Best Practices

1. **Always `browser_read()` after navigation** — This shows you what's on the page and what you can interact with.
2. **Use `browser_press_enter()` to send messages** — This is how chat apps submit messages (Enter key).
3. **Use visible text for clicking** — `browser_click(selector_or_text: "Send")` works better than CSS selectors.
4. **Batch predictable actions** — If you know the UI, batch `browser_type` + `browser_press_enter` in one turn.
5. **Fallback to CSS selectors** — If text matching fails, use class names or IDs visible in `browser_read()` output.
6. **You CAN interact with any web page** — typing, clicking, scrolling all work. Don't give up and resort to clipboard.
7. **Never say you can't interact** — If `browser_click` or `browser_type` fails, try alternative selectors or `browser_execute_js`.
