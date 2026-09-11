"""Pull benchmark outputs without shell text transcoding of PNG/grid bytes."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
ADB = r"C:\Users\aruns\AppData\Local\Android\Sdk\platform-tools\adb.exe"
for tag in sys.argv[1:]:
    for ext in (".json", ".png", "-grid.bin"):
        if ext == "-grid.bin" and not tag.startswith("dewarp-"):
            continue
        name = tag + ext
        result = subprocess.run([ADB, "-s", "R5GL6478N0N", "exec-out", "run-as",
            "com.example.dewarpbenchmark", "cat", "files/" + name], capture_output=True)
        if result.returncode == 0:
            (ROOT / name).write_bytes(result.stdout)
            print(f"Saved {name}: {len(result.stdout)} bytes")
        else:
            print(f"Missing {name}: {result.stderr.decode(errors='replace')}")
