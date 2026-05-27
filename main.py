import os

from openai import OpenAI

client = OpenAI(
    api_key=os.environ["OPENROUTER_API_KEY"],
    base_url="https://openrouter.ai/api/v1"
)

messages = []

print("Chat started! Type 'exit' to quit.\n")

while True:
    user_input = input("You: ")

    if user_input.lower() == "exit":
        break

    messages.append({
        "role": "user",
        "content": user_input
    })

    response = client.chat.completions.create(
        model="openai/gpt-oss-120b:free",
        messages=messages
    )

    assistant_reply = response.choices[0].message.content

    print(f"\nAI: {assistant_reply}\n")

    messages.append({
        "role": "assistant",
        "content": assistant_reply
    })
