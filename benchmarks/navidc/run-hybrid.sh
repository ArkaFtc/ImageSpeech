#!/system/bin/sh
cd /data/local/tmp/navidc-bench || exit 1
export LD_LIBRARY_PATH=gpu
start=$(date +%s)
timeout 180 gpu/llama-mtmd-cli -m NaviDC-OCR-Q4_K_M.gguf --mmproj NaviDC-OCR-mmproj-q8_0.gguf --image test.png -p "Please output the text content from the image." -c 1024 -n 64 -t 4 --temp 0 --no-warmup -ngl 99 --no-mmproj-offload -lv 3 --image-max-tokens 256 > hybrid-t4.log 2>&1 &
runner=$!
while kill -0 "$runner" 2>/dev/null; do
  for pid in $(pidof llama-mtmd-cli); do
    grep -E 'VmRSS|VmHWM' /proc/$pid/status
  done
  sleep 1
done > hybrid-t4-memory.log
wait "$runner"
code=$?
end=$(date +%s)
echo "exit_code=$code elapsed_seconds=$((end-start))"
cat hybrid-t4.log