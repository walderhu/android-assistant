import os
import sys

from openai import OpenAI

client = OpenAI(
    api_key=os.environ["OPENROUTER_API_KEY"],
    base_url="https://openrouter.ai/api/v1"
)

MODEL = "openai/whisper-large-v3"  # alternative: "openai/gpt-4o-mini-transcribe"


def transcribe(audio_path: str) -> str:
    with open(audio_path, "rb") as f:
        result = client.audio.transcriptions.create(
            model=MODEL,
            file=f,
        )
    return result.text


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python transcribe.py <audio_file>")
        print("Example: python transcribe.py audio.mp3")
        sys.exit(1)

    path = sys.argv[1]
    print(f"Transcribing: {path}\n")
    text = transcribe(path)
    print("Result:")
    print(text)
