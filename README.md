# Inkling

**Send/receive anything between your Supernote and other devices — with or without LocalSend.**

Inkling is an open-source Supernote plugin that lets you insert text, images, links, PDFs, clipboard content, and more into your Supernote from a PC or any other device. It runs completely offline, communicating only through your local network. This project has no commercial plans.

It integrates with [LocalSend](https://localsend.org), a free, open-source cross-platform file sharing app, but offers more than just that.

## Features

### Beyond LocalSend

- **Lasso Send** — Select text, images, screenshots, or links on your Supernote and send them to other devices via LocalSend
- **Doc-Screenshots-to-Note** — Very useful for Long Screenshot or multiple screenshots
- **Layer Management** — Use the pen as a temporary lasso to select elements without switching tools. Adjust layer order for the current layer or lasso-selected elements

### What you can receive via LocalSend

- **Doc Links** — Inserted as tappable links
- **Images** — Including doc screenshots
- **Text** — With paragraph mode (preserves line breaks) and no-space mode (strips whitespace, useful for CJK text)
- **Clipboard** — Basically what you've been selecting: writing, painting and textbox. It's 2026 and there's still no official clipboard on any e-ink device.

## About LocalSend

[LocalSend](https://localsend.org) is a free, open-source app for securely sharing files between nearby devices without needing the internet. Available on Windows, macOS, Linux, Android, and iOS.

I've been developing this for a while since it's been incredibly useful for my own workflow, but I wasn't planning to share it until it was more polished. Now I realize it's ready enough for a public release — and I'd love to invite developers to collaborate.

One area where I'd especially appreciate help: **image display filter algorithms**. Dithering is the current go-to, but it doesn't always look great on e-ink. If you have ideas for better pre-rendering or quantization approaches, I'm all ears.

## Future plans

- Local re-typesetting engine for epub, mobi, azw3, md, txt, and pdf with better e-ink rendering quality on Supernote
- Integration with CherryStudio / RikkaHub / ChatWise / SillyTavern directly on your Supernote

## Claude Skill

This repo also includes a Claude skill for AI-assisted Supernote plugin development, based on official documentation and changelogs (release 0.2.0).

### What is this?

A **skill** for Claude Code / Claude.ai / other platforms that supports a similar skill format. It provides:
- Supernote plugin architecture overview
- Core API quick reference (sync file paths, coordinates, gotchas)
- Type definitions (AIDL interfaces, callback enums)
- Build and debug workflow

### How to use it

This is a "skill" that teaches Claude (an AI assistant) about Supernote plugin development.

**Easiest way - Claude.ai (web/desktop):**
1. Go to [claude.ai](https://claude.ai) and sign up (free tier works)
2. Go to Settings → Capabilities and turn on "Code execution"[(1)](https://support.claude.com/en/articles/12512176-what-are-skills)
3. Download this repository as a ZIP file
4. Go to Customize → Skills, click "+", then "Upload a skill"[(1)](https://support.claude.com/en/articles/12512176-what-are-skills)
5. Upload the ZIP file
6. Start a chat and ask: "Help me create a Supernote plugin that..."

**For developers - Claude Code (terminal):**
1. Install Claude Code: `curl -fsSL https://claude.ai/install.sh | bash`[(2)](https://code.claude.com/docs/en/setup#advanced-installation-options)
2. Put `supernote-plugin-dev.md` in `~/.claude/skills/`[(3)](https://code.claude.com/docs/en/skills)
3. Run `claude` in your project folder
4. Ask about Supernote plugin development

Claude will automatically reference this documentation when helping you code.

## Disclaimer

This plugin is **not official**. It's a personal tool I built for my own Supernote plugin projects, shared in case others find it useful.

## License

MIT – do whatever you want with it.
