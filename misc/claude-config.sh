#!/bin/bash

export OPENROUTER_API_KEY=""
export ANTHROPIC_BASE_URL="https://openrouter.ai/api"
export ANTHROPIC_AUTH_TOKEN="$OPENROUTER_API_KEY"
export ANTHROPIC_API_KEY=""

# low
# export ANTHROPIC_DEFAULT_HAIKU_MODEL="deepseek/deepseek-v4-flash"
export ANTHROPIC_DEFAULT_HAIKU_MODEL="openrouter/free"

# middle 
# export ANTHROPIC_DEFAULT_SONNET_MODEL="moonshotai/kimi-k2.5"
export ANTHROPIC_DEFAULT_SONNET_MODEL="qwen/qwen3-coder-flash"

# high
export ANTHROPIC_DEFAULT_OPUS_MODEL="openai/gpt-5-mini"

claude
