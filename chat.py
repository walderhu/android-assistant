import os

from openai import OpenAI

client = OpenAI(
    api_key=os.environ["OPENROUTER_API_KEY"],
    base_url="https://openrouter.ai/api/v1"
)

MODEL = "openai/gpt-4o-mini"

messages = []

print(f"Chat with {MODEL}. Type 'exit' to quit.\n")

while True:
    user_input = input("You: ").strip()

    if not user_input:
        continue

    if user_input.lower() == "exit":
        break

    messages.append({"role": "user", "content": user_input})

    response = client.chat.completions.create(
        model=MODEL,
        messages=messages,
    )

    reply = response.choices[0].message.content

    print(f"\nAI: {reply}\n")

    messages.append({"role": "assistant", "content": reply})
