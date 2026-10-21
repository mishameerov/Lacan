#!/usr/bin/env python3
"""Invoke pinned MNN native converter without Python CLI telemetry imports."""
import sys
import _tools
if len(sys.argv) != 3:
    raise SystemExit("usage: convert.py SOURCE.onnx OUTPUT.mnn")
_tools.mnnconvert(["mnnconvert", "-f", "ONNX", "--modelFile", sys.argv[1],
                   "--MNNModel", sys.argv[2], "--bizCode", "Lacan",
                   "--useGeluApproximation", "0"])
