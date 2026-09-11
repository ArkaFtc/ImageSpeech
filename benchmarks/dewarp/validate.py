"""Independent host correctness checks; not phone performance measurements."""
from pathlib import Path
import json
import numpy as np
import cv2
from PIL import Image

root = Path(__file__).resolve().parent
def rgb(path):
    return np.asarray(Image.open(path).convert("RGB"))

checks = {}
a = rgb(root / "uvdoc-alone.png").astype(float)
b = rgb(root / "downloads/uvdoc-host-reference.png").astype(float)
checks["uvdoc_android_vs_host_pixels"] = {"mean_absolute_difference_0_255": float(abs(a-b).mean()), "max": float(abs(a-b).max())}
x = np.fromfile(root / "dewarp-cpu-alone-grid.bin", dtype="<f4")
y = np.fromfile(root / "dewarp-gpu-alone-grid.bin", dtype="<f4")
checks["dewarp_cpu_vs_gpu_grid"] = {"correlation": float(np.corrcoef(x,y)[0,1]), "mae": float(abs(x-y).mean()), "max": float(abs(x-y).max())}

# Match the published OpenCV blur/resize followed by align_corners=True
# bilinear sampling with zero padding. Use Android's decoded source image.
source = rgb(root / "baseline-chain.png")
h, w = source.shape[:2]
raw = y.reshape(2,128,128)
grid = np.stack([cv2.resize(cv2.blur(c, (3,3)), (w,h)) for c in raw], -1)
sx = (grid[...,0] + 1) * (w-1) / 2
sy = (grid[...,1] + 1) * (h-1) / 2
ix = np.floor(sx).astype(int); iy = np.floor(sy).astype(int)
fx = sx - ix; fy = sy - iy
reference = np.zeros((h,w,3),dtype=np.float64)
for dx,dy,weight in [(0,0,(1-fx)*(1-fy)),(1,0,fx*(1-fy)),(0,1,(1-fx)*fy),(1,1,fx*fy)]:
    xx=ix+dx; yy=iy+dy
    valid = (xx>=0)&(xx<w)&(yy>=0)&(yy<h)
    reference += source[yy.clip(0,h-1),xx.clip(0,w-1)] * (weight*valid)[...,None]
reference = reference.clip(0,255).astype(np.uint8)
actual = rgb(root / "dewarp-gpu-alone.png")
delta = abs(actual.astype(float)-reference.astype(float))
checks["dewarp_android_remap_vs_reference"] = {"mean_absolute_difference_0_255":float(delta.mean()),"max":float(delta.max()),"fraction_within_1":float((delta<=1).mean())}
assert checks["uvdoc_android_vs_host_pixels"]["max"] <= 2
assert checks["dewarp_cpu_vs_gpu_grid"]["correlation"] > .999
assert delta.mean() < .1
(root / "validation.json").write_text(json.dumps(checks,indent=2))
print(json.dumps(checks,indent=2))
