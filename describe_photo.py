import base64
import os
import sys
from pathlib import Path

from openai import OpenAI

client = OpenAI(
    api_key=os.environ["OPENROUTER_API_KEY"],
    base_url="https://openrouter.ai/api/v1"
)

MODEL = "openai/gpt-4o-mini"


def describe(image_path: str, prompt: str = "Describe this image in detail.") -> str:
    data = Path(image_path).read_bytes()
    b64 = base64.b64encode(data).decode("utf-8")

    ext = Path(image_path).suffix.lower().lstrip(".")
    mime = {"jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png", "gif": "image/gif", "webp": "image/webp"}.get(ext, "image/jpeg")

    response = client.chat.completions.create(
        model=MODEL,
        messages=[
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{b64}"}},
                ],
            }
        ],
    )
    return response.choices[0].message.content


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python describe_photo.py <image_file> [prompt]")
        print("Example: python describe_photo.py photo.jpg")
        print("Example: python describe_photo.py photo.jpg \"What objects are in this image?\"")
        sys.exit(1)

    path = sys.argv[1]
    prompt = sys.argv[2] if len(sys.argv) > 2 else "Describe this image in detail."

    print(f"Analyzing: {path}\n")
    result = describe(path, prompt)
    print("Description:")
    print(result)
