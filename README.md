# Inkling

A Claude skill for AI-assisted Supernote plugin development, based on official documentation and changelogs (release 0.2.0).

## What is this?

A **skill** for Claude Code / Claude.ai / other platforms that supports a similar skill format. It provides:
- Supernote plugin architecture overview
- Core API quick reference (sync file paths, coordinates, gotchas)
- Type definitions (AIDL interfaces, callback enums)
- Build and debug workflow

## How to use it

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

This skill is **not official**. It's a personal tool I built for my own Supernote plugin projects, shared in case others find it useful.

## License

MIT – do whatever you want with it.