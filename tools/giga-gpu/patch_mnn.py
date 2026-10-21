#!/usr/bin/env python3
"""Bounded MNN3.6.1 fix: clear shape-specific auxiliary convolution kernels.

When onResize downgrades GEMM convolution to the direct path, upstream retains
old transpose pre/post kernels with buffers from the preceding shape. onExecute
then unconditionally enqueues those kernels. Clear them before selecting the
new shape's path; the GEMM path recreates and rebinds them when selected.
"""
from pathlib import Path
import hashlib
import subprocess

root = Path(__file__).resolve().parents[2] / ".tools/mnn/MNN-3.6.1"
expected_commit = "d407447ed56c4121a11ccbd266dc184ca1ead0c2"
assert subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip() == expected_commit
relative = "source/backend/opencl/execution/buffer/ConvBufExecution.cpp"
source = root / relative
original = subprocess.check_output(["git", "-C", str(root), "show", f"{expected_commit}:{relative}"])
text = source.read_text()
marker = "lacan_clear_stale_conv_aux_kernels"
anchor = '''ErrorCode ConvBufExecution::onResize(const std::vector<Tensor *> &inputs, const std::vector<Tensor *> &outputs) {
#ifdef LOG_VERBOSE
    MNN_PRINT("Start ConvExecution onResize !\\n");
#endif
'''
patch = '''    // lacan_clear_stale_conv_aux_kernels: prior GEMM shape may now use direct convolution.
    mPreKernel.reset();
    mPostKernel.reset();
'''
assert original.decode().count(anchor) == 1
expected = original.decode().replace(anchor, anchor + patch, 1)
if marker in text:
    assert text == expected, "Unexpected additional MNN convolution modifications"
else:
    assert source.read_bytes() == original, "Refusing to patch modified MNN convolution source"
    source.write_text(expected)
print("Pinned MNN stale convolution kernels patch ready; original_sha256=" + hashlib.sha256(original).hexdigest())
