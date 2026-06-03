#!/bin/bash
# Codex reset script - removes all configs and reinstalls clean

rm -rf "$HOME/.codex" "$HOME/.local/share/codex" 2>/dev/null || true
unset OPENAI_API_KEY OPENROUTER_API_KEY

npm uninstall -g @openai/codex 2>/dev/null || true
npm install -g @openai/codex

echo "Run: source $HOME/workspace/test/android-test/codex-reset.sh"
echo "Then: codex login"