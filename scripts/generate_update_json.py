import json
import sys

def main():
    if len(sys.argv) != 4:
        print("Usage: generate_update_json.py <version> <versionCode> <repo>")
        sys.exit(1)

    version = sys.argv[1].removeprefix("v")
    versionCode = int(sys.argv[2])
    repo = sys.argv[3]

    data = {
        "version": f"v{version}",
        "versionCode": versionCode,
        "zipUrl": f"https://github.com/{repo}/releases/download/v{version}/Vector-SR-v{version}-{versionCode}-Release.zip",
        "changelog": f"https://raw.githubusercontent.com/{repo}/master/zygisk/changelog.md"
    }

    with open("zygisk/update.json", "w", encoding="utf-8") as f:
        json.dump(data, f, indent=4)
        f.write("\n")

if __name__ == "__main__":
    main()
