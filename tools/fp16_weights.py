"""Halve an ONNX model's size by storing its large weights as float16.

Each large float32 weight becomes a float16 initializer followed by a Cast back to float32.
ONNX Runtime folds those Casts when the session loads, so inference still runs in float32
and only the stored weights are rounded. On the project's test pages the OCR text and the
dewarped image were unchanged.

Usage: python fp16_weights.py original.onnx output.onnx
Needs: pip install onnx numpy
"""
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

MIN_ELEMENTS = 4096


def convert(src, dst):
    model = onnx.load(src)
    graph = model.graph
    graph_inputs = {i.name: i for i in graph.input}
    kept, added, casts = [], [], []
    for init in graph.initializer:
        w = numpy_helper.to_array(init)
        if w.dtype != np.float32 or w.ndim < 2 or w.size < MIN_ELEMENTS:
            kept.append(init)
            continue
        added.append(numpy_helper.from_array(w.astype(np.float16), init.name + "__fp16"))
        casts.append(helper.make_node("Cast", [init.name + "__fp16"], [init.name], to=TensorProto.FLOAT))
        if init.name in graph_inputs:
            graph.input.remove(graph_inputs[init.name])
    del graph.initializer[:]
    graph.initializer.extend(kept + added)
    nodes = casts + list(graph.node)
    del graph.node[:]
    graph.node.extend(nodes)
    onnx.checker.check_model(model)
    onnx.save(model, dst)


if __name__ == "__main__":
    convert(sys.argv[1], sys.argv[2])
